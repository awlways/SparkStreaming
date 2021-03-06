package com.gh.yun

import java.util

import com.gh.bean.alert.KeyValue
import com.gh.utils._
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010._
import org.codehaus.jackson.JsonNode

import scala.collection.mutable.ArrayBuffer

/**
  * Created by GH-GAN on 2016/11/24.
  */
object AppNginxCap {
  def main(args: Array[String]) {
   /* if (args.length < 4) {
      System.err.println("Usage: Kafka <zkQuorum> <group> <topics> <numThreads>")
      System.exit(1)
    }*/
    Logger.getRootLogger.setLevel(Level.WARN)

    val sparkConf = new SparkConf().setAppName("capability-nginx").setMaster(ConfigUtil.sparkmaster)
    val ssc = new StreamingContext(sparkConf, Seconds(ConfigUtil.capStreamtime))
    ssc.checkpoint(ConfigUtil.nginxCapcheckpoint)

    var brokers = ConfigUtil.brokers
    val _topics = "capability-nginx".split(",").toSet
    val group = "capability-nginx-js"

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> brokers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> group,
      "auto.offset.reset" -> "latest",          //当前偏移不在服务器上时,按最新开始
      "heartbeat.interval.ms" -> "6000",
      "session.timeout.ms" -> "20000",
      "max.partition.fetch.bytes" -> "1048576000",
      "max.poll.records" -> "5000000",                 // message.max.bytes
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )

    val stream = KafkaUtils.createDirectStream[String, String](
      ssc,
      PreferConsistent,
      Subscribe[String, String](_topics, kafkaParams)
    )

    val datas = parseLog(stream)
    val mysql_group = datas.groupByKey()
    compute(mysql_group)

    ssc.start()
    ssc.awaitTermination()
  }

  //一条包含多条记录
  def parseLog(stream : InputDStream[ConsumerRecord[String, String]]) : DStream[(String,(String,Double,Double))] ={
    val datas = stream.map(line => {
      var node : JsonNode = JsonUtil.getJsonNode(line.value())
      node
    }).filter(x => (x != null && x.get("type") != null))
      .filter(x => {
        val _type = x.get("type").asText().trim
        var environment_id = ""
        var container_uuid = ""
        try{
          val data = x.get("data")
          environment_id = data.get("environment_id").asText().trim
          container_uuid = data.get("container_uuid").asText().trim
        }catch { case ex : Exception => ex.printStackTrace() }

        !"".equals(_type) && !"".equals(environment_id) && !"".equals(container_uuid) && null != environment_id && null != container_uuid
      })
      .flatMap(node => {
      val _type = node.get("type").asText()
      val environment_id = node.get("data").get("environment_id").asText()
      val container_uuid = node.get("data").get("container_uuid").asText()

      var container_name = ""
      var namespace = ""
      try {
        container_name = node.get("data").get("container_name").asText()
        namespace = node.get("data").get("namespace").asText()
      }catch {
        case ex : Exception => {
          println("--------> container_name/namespace is null")
          ex.printStackTrace()
        }
      }

      val _stats = node.get("data").get("stats")
      val arr = ArrayBuffer[(String,(String,Double,Double))]()
      for (i <- 0 to (_stats.size() - 1) ){
        val stats = node.get("data").get("stats").get(i)
        val timestamp = stats.get("timestamp").asText()
        val accepts = stats.get("accepts").asDouble()
        val requests = stats.get("requests").asDouble()
        arr.+=((environment_id+"#"+container_uuid+"#"+_type + "#" + container_name + "#" + namespace, (timestamp,accepts,requests)))
      }
      arr
    })
    datas
  }

  def compute(mysql_group : DStream[(String,Iterable[(String,Double,Double)])]): Unit ={
      val warn = mysql_group.map(x => {
          val count = x._2.size

          val start_log = x._2.head
          val end_log = x._2.last
          val start_time = start_log._1
          val end_time = end_log._1

          // accepts
          val accepts_sum = x._2.map(_._2).reduce(_+_)
          val accepts_avg = accepts_sum / count

          // requests
          val requests_sum = x._2.map(_._3).reduce(_+_)
          val requests_avg = requests_sum / count

          val accept = accepts_avg / requests_avg

          (x._1,start_time,end_time,accept)
      }).map(x => aAlert(x))      // 阈值
        .filter(_._4.size() > 0)  // 是否有告警信息

      warn.foreachRDD( record => {
        val alerts = record.map(line => {
            AlertInfoUtil.toWarnBean(AlertInfoUtil.SUCCESS,AlertInfoUtil.ALERT_TYPE_M,AlertInfoUtil.ALERT_DIM_A,line._1, line._2,line._3,line._4,line._5)
        })
        if (!alerts.isEmpty()){
          val collect = alerts.collect()
          if (collect.size > 0) new HttpUtil().alerts(collect)  //告警
        }
      })

  }

  def aAlert(line : (String,String,String,Double)): (String,String,String,util.ArrayList[KeyValue],String) ={
    val list = new util.ArrayList[KeyValue]()

    val gz = HttpUtil.gz_map.get("app_nginx_accept")
    gz.getCondition match {
      case "GTE"  =>   if(line._4 >= gz.getValue)  list.add(new KeyValue("accept",line._4.toString))
      case "GT"   =>   if(line._4 > gz.getValue)   list.add(new KeyValue("accept",line._4.toString))
      case "LTE"  =>   if(line._4 <= gz.getValue)  list.add(new KeyValue("accept",line._4.toString))
      case "LT"   =>   if(line._4 < gz.getValue)   list.add(new KeyValue("accept",line._4.toString))
      case "EQ"   =>   if(line._4 == gz.getValue)  list.add(new KeyValue("accept",line._4.toString))
    }

    (line._1,line._2,line._3,list,"")
  }

}
