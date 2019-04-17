package org.insightedge.geodemo.processing

import com.spatial4j.core.distance.DistanceUtils

import org.apache.log4j.{Level, Logger}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.SparkConf

import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.kafka010._
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent

import org.insightedge.geodemo.common.gridModel.{NewOrder, OrderRequest, ProcessedOrder}
import org.insightedge.geodemo.common.kafkaMessages.{OrderEvent, PickupEvent}
import org.insightedge.spark.context.InsightEdgeConfig
import org.insightedge.spark.implicits.all._
import org.openspaces.spatial.ShapeFactory._

import play.api.libs.json.Json

/**
  * To run from IDE:
  *  - set 'runFromIde' to 'true' in build.sbt
  *  - pass local[*] as a master in program arguments
  *
  *
  *   See: https://spark.apache.org/docs/2.3.2/streaming-kafka-0-10-integration.html
  */

object DynamicPriceProcessor {
  ///home/xap/alex/demos/Tal


  implicit val orderEventReads = Json.reads[OrderEvent]
  implicit val pickupEventReads = Json.reads[PickupEvent]

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      throw new IllegalArgumentException("Not enough args. Expected args: spark-master-url")
    }

    val Array(master) = args

    val ieConfig = InsightEdgeConfig("demo", Some("xap-14.0.1"), Some("127.0.0.1"))
    val scConfig = new SparkConf().setAppName("GeospatialDemo").setMaster(master).setInsightEdgeConfig(ieConfig)
    val ssc = new StreamingContext(scConfig, Seconds(1))
    ssc.checkpoint("checkpoint")
    val sc = ssc.sparkContext

    val rootLogger = Logger.getRootLogger
    rootLogger.setLevel(Level.ERROR)

    val ordersStream = initKafkaStream(ssc, "orders")
    val pickupsStream = initKafkaStream(ssc, "pickups")

    ordersStream
      .map(record => Json.parse(record.value.toString()).as[OrderEvent])
      .transform { rdd =>
        val query = "location spatial:within ? AND status = ?"
        val radius = 0.5 * DistanceUtils.KM_TO_DEG
        val queryParamsConstructor = (e: OrderEvent) => Seq(circle(point(e.longitude, e.latitude), radius), NewOrder)
        val projections = Some(Seq("id"))
        rdd.zipWithGridSql[OrderRequest](query, queryParamsConstructor, projections)
      }
      .map { case (e: OrderEvent, nearOrders: Seq[OrderRequest]) =>
        val location = point(e.longitude, e.latitude)
        val nearOrderIds = nearOrders.map(_.id)
        val priceFactor = if (nearOrderIds.length > 3) {
          1.0 + (nearOrderIds.length - 3) * 0.1
        } else {
          1.0
        }
        OrderRequest(e.id, e.time, location, priceFactor, nearOrderIds, NewOrder)
      }
      .transform { rdd =>
        rdd.foreach(println)
        rdd
      }
      .saveToGrid()


    pickupsStream
      .transform { rdd =>
        rdd.foreach(println)
        rdd
      }
      .map(record => Json.parse(record.value.toString()).as[PickupEvent])
      .transform { rdd =>
        val query = "id = ?"
        val queryParamsConstructor = (e: PickupEvent) => Seq(e.orderId)
        rdd.zipWithGridSql[OrderRequest](query, queryParamsConstructor, None)
      }
      .flatMap { case (e: PickupEvent, orders: Seq[OrderRequest]) =>
        // there should be only 1 order unless we receive incorrect data
        orders.map(_.copy(status = ProcessedOrder))
      }
      .saveToGrid()

    ssc.start()
    ssc.awaitTermination()
  }

  /**
    * Creates kafka stream with brokers and topic
    */
  private def initKafkaStream(ssc: StreamingContext, topic: String):
      InputDStream[ConsumerRecord[String, String]] = {

    //KafkaUtils.createStream(ssc, "127.0.0.1:2181", "geo-demo", Map(topic -> 1)).map(_._2)

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> "localhost:9092",
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "dynamicpriceprocessor_group_id",
      "auto.offset.reset" -> "earliest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )


    val topics = Array(topic)

    KafkaUtils.createDirectStream[String, String](
      ssc,
      PreferConsistent,
      Subscribe[String, String](topics, kafkaParams)
    )
  }

}
