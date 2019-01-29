package com.ibeifeng.sparkproject.spark.page;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;

import com.alibaba.fastjson.JSONObject;
import com.ibeifeng.sparkproject.constant.Constants;
import com.ibeifeng.sparkproject.dao.IPageSplitConvertRateDAO;
import com.ibeifeng.sparkproject.dao.ITaskDAO;
import com.ibeifeng.sparkproject.dao.factory.DAOFactory;
import com.ibeifeng.sparkproject.domain.PageSplitConvertRate;
import com.ibeifeng.sparkproject.domain.Task;
import com.ibeifeng.sparkproject.util.DateUtils;
import com.ibeifeng.sparkproject.util.NumberUtils;
import com.ibeifeng.sparkproject.util.ParamUtils;
import com.ibeifeng.sparkproject.util.SparkUtils;

import scala.Tuple2;

public class MyPageConvertRateSparkTest {
	public static void main(String[] args) {
		// 1、构造Spark上下文
		SparkConf conf = new SparkConf().setAppName(Constants.SPARK_APP_NAME_PAGE);
		SparkUtils.setMaster(conf);

		JavaSparkContext sc = new JavaSparkContext(conf);
		SQLContext sqlContext = SparkUtils.getSQLContext(sc.sc());

		// 2、生成模拟数据
		SparkUtils.mockData(sc, sqlContext);

		// 3、查询任务，获取任务的参数
		//long taskid = ParamUtils.getTaskIdFromArgs(args, Constants.SPARK_LOCAL_TASKID_PAGE);
		long taskid = 2L;
		
		ITaskDAO taskDAO = DAOFactory.getTaskDAO();
		Task task = taskDAO.findById(taskid);
		if (task == null) {
			System.out.println(new Date() + ": cannot find this task with id [" + taskid + "].");
			return;
		} else {
			System.out.println("Found Task " + task.getTaskid() + " " + task.getTaskName() + "  " + task.getTaskParam());
		}

		JSONObject taskParam = JSONObject.parseObject(task.getTaskParam());

		// 4、查询指定日期范围内的用户访问行为数据
		JavaRDD<Row> actionRDD = SparkUtils.getActionRDDByDateRange(sqlContext, taskParam);

		// sessionId, RowInfo
		JavaPairRDD<String, Row> sessionid2actionRDD = getSessionid2actionRDD(actionRDD);
		sessionid2actionRDD = sessionid2actionRDD.cache();

		// => sessionId(unique), Iterable<Row>
		JavaPairRDD<String, Iterable<Row>> sessionid2actionsRDD = sessionid2actionRDD.groupByKey();

		JavaPairRDD<String, Integer> pageSplitRDD = generateAndMatchPageSplit(sc, sessionid2actionsRDD, taskParam);
		Map<String, Object> pageSplitPvMap = pageSplitRDD.countByKey();

		long startPagePv = getStartPagePv(taskParam, sessionid2actionsRDD);
		
		//Got a map like   3_5 0.67
		Map<String, Double> convertRateMap = computePageSplitConvertRate(taskParam, pageSplitPvMap, startPagePv);
		
		persistConvertRate(taskid, convertRateMap);

		sc.close();
	}

	/**
	 * 
	 * @param taskid
	 * @param convertRateMap
	 */
	private static void persistConvertRate(long taskid,
			Map<String, Double> convertRateMap) {
		StringBuffer buffer = new StringBuffer("");
		
		for(Map.Entry<String, Double> convertRateEntry : convertRateMap.entrySet()) {
			String pageSplit = convertRateEntry.getKey();
			double convertRate = convertRateEntry.getValue();
			buffer.append(pageSplit + "=" + convertRate + "|");
		}
		
		String convertRate = buffer.toString();
		convertRate = convertRate.substring(0, convertRate.length() - 1);
		
		PageSplitConvertRate pageSplitConvertRate = new PageSplitConvertRate();
		pageSplitConvertRate.setTaskid(taskid); 
		pageSplitConvertRate.setConvertRate(convertRate); 
		
		IPageSplitConvertRateDAO pageSplitConvertRateDAO = DAOFactory.getPageSplitConvertRateDAO();
		pageSplitConvertRateDAO.insert(pageSplitConvertRate); 
	}
	/**
	 * 
	 * @param taskParam
	 * @param pageSplitPvMap
	 * @param startPagePv
	 * @return
	 */
	private static Map<String, Double> computePageSplitConvertRate(JSONObject taskParam,
			Map<String, Object> pageSplitPvMap, long startPagePv) {
		Map<String, Double> convertRateMap = new HashMap<String, Double>();
		String[] targetPages = ParamUtils.getParam(taskParam, Constants.PARAM_TARGET_PAGE_FLOW).split(",");
		long lastPageSplitPv = 0L;

		// 3,5,2,4,6
		// 3_5 rate = 3_5 pv / 3 pv
		// 5_2 rate = 5_2 pv / 3_5 pv
		for(int i = 1; i < targetPages.length; i++) {
			String targetPageSplit = targetPages[i - 1] + "_" + targetPages[i];
			long targetPageSplitPv = Long.valueOf(String.valueOf(
					pageSplitPvMap.get(targetPageSplit)));  
			
			double convertRate = 0.0;
			
			if(i == 1) {
				convertRate = NumberUtils.formatDouble(
						(double)targetPageSplitPv / (double)startPagePv, 2);  
			} else {
				convertRate = NumberUtils.formatDouble(
						(double)targetPageSplitPv / (double)lastPageSplitPv, 2);
			}
			convertRateMap.put(targetPageSplit, convertRate);
			lastPageSplitPv = targetPageSplitPv;
		}
		return convertRateMap;
	}

	/**
	 * 
	 * @param taskParam
	 * @param sessionid2actionsRDD
	 * @return
	 */
	private static long getStartPagePv(JSONObject taskParam, JavaPairRDD<String, Iterable<Row>> sessionid2actionsRDD) {
		String targetPageFlow = ParamUtils.getParam(taskParam, Constants.PARAM_TARGET_PAGE_FLOW);
		final long startPageId = Long.valueOf(targetPageFlow.split(",")[0]);

		JavaRDD<Long> startPageRDD = sessionid2actionsRDD
				.flatMap(new FlatMapFunction<Tuple2<String, Iterable<Row>>, Long>() {
					@Override
					public Iterable<Long> call(Tuple2<String, Iterable<Row>> tuple) throws Exception {
						List<Long> list = new ArrayList<Long>();
						Iterator<Row> iterator = tuple._2.iterator();
						while (iterator.hasNext()) {
							Row row = iterator.next();
							long pageid = row.getLong(3);
							if (pageid == startPageId) {
								list.add(pageid);
							}
						}
						return list;
					}
				});

		return startPageRDD.count();
	}

	/**
	 * 
	 * @param sc
	 * @param sessionid2actionsRDD
	 * @param taskParam
	 * @return
	 */
	private static JavaPairRDD<String, Integer> generateAndMatchPageSplit(JavaSparkContext sc,
			JavaPairRDD<String, Iterable<Row>> sessionid2actionsRDD, JSONObject taskParam) {

		String targetPageFlow = ParamUtils.getParam(taskParam, Constants.PARAM_TARGET_PAGE_FLOW);
		final Broadcast<String> targetPageFlowBroadcast = sc.broadcast(targetPageFlow);

		return sessionid2actionsRDD
				.flatMapToPair(new PairFlatMapFunction<Tuple2<String, Iterable<Row>>, String, Integer>() {

					@Override
					public Iterable<Tuple2<String, Integer>> call(Tuple2<String, Iterable<Row>> tuple)
							throws Exception {
						List<Tuple2<String, Integer>> list = new ArrayList<Tuple2<String, Integer>>();
						Iterator<Row> iterator = tuple._2.iterator();
						String[] targetPages = targetPageFlowBroadcast.value().split(",");

						List<Row> rows = new ArrayList<Row>();
						while (iterator.hasNext()) {
							rows.add(iterator.next());
						}

						Collections.sort(rows, new Comparator<Row>() {
							@Override
							public int compare(Row o1, Row o2) {
								String actionTime1 = o1.getString(4);
								String actionTime2 = o2.getString(4);
								Date date1 = DateUtils.parseTime(actionTime1);
								Date date2 = DateUtils.parseTime(actionTime2);
								return (int) (date1.getTime() - date2.getTime());
							}
						});

						Long lastPageId = null;
						for (Row row : rows) {
							long pageid = row.getLong(3);
							if (lastPageId == null) {
								lastPageId = pageid;
								continue;
							}
							// 生成一个页面切片
							// 3,5,2,1,8,9
							// lastPageId=3
							// 5，切片，3_5
							String pageSplit = lastPageId + "_" + pageid;

							// 对这个切片判断一下，是否在用户指定的页面流中
							for (int i = 1; i < targetPages.length; i++) {
								// 比如说，用户指定的页面流是3,2,5,8,1
								// 遍历的时候，从索引1开始，就是从第二个页面开始
								// 3_2
								String targetPageSplit = targetPages[i - 1] + "_" + targetPages[i];

								if (pageSplit.equals(targetPageSplit)) {
									list.add(new Tuple2<String, Integer>(pageSplit, 1));
									break;
								}
							}

							lastPageId = pageid;
						}
						return list;
					}
				});
	}

	private static JavaPairRDD<String, Row> getSessionid2actionRDD(JavaRDD<Row> actionRDD) {
		return actionRDD.mapToPair(new PairFunction<Row, String, Row>() {

			private static final long serialVersionUID = 1L;

			@Override
			public Tuple2<String, Row> call(Row row) throws Exception {
				String sessionid = row.getString(2);
				return new Tuple2<String, Row>(sessionid, row);
			}

		});
	}

}
