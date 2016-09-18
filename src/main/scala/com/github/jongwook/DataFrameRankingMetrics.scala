package com.github.jongwook

import org.apache.spark.ml.param.{Param, ParamMap, Params}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{DoubleType, IntegerType}
import org.apache.spark.sql._
import org.slf4j.LoggerFactory


trait DataFrameRankingParams extends Params {
  override val uid: String = Identifiable.randomUID("datasetRankingParams")

  val userCol = new Param[String](this, "userCol", "column name for user ids. Ids must be within the integer value range.")
  val itemCol = new Param[String](this, "itemCol", "column name for item ids. Ids must be within the integer value range.")
  val ratingCol = new Param[String](this, "ratingCol", "column name for ratings")
  val predictionCol = new Param[String](this, "predictionCol", "prediction column name")

  setDefault(userCol, "user")
  setDefault(itemCol, "item")
  setDefault(ratingCol, "rating")
  setDefault(predictionCol, "prediction")

  def setUserCol(value: String): this.type = set(userCol, value)
  def setItemCol(value: String): this.type = set(itemCol, value)
  def setRatingCol(value: String): this.type = set(ratingCol, value)
  def setPredictionCol(value: String): this.type = set(predictionCol, value)
}


class DataFrameRankingMetrics(predicted: DataFrame, groundTruth: DataFrame, relevanceThreshold: Double = 0) extends DataFrameRankingParams with Serializable {

  lazy val log = LoggerFactory.getLogger(getClass)
  lazy val sqlContext = groundTruth.sqlContext

  def predictionAndLabels: RDD[(Array[(Int, Double)], Array[(Int, Double)])] = {
    import sqlContext.implicits._
    import org.apache.spark.sql.functions._

    val p = predicted
    val g = groundTruth

    val user = col($(userCol)).cast(IntegerType).as("user")
    val item = col($(itemCol)).cast(IntegerType).as("item")
    val prediction = col($(predictionCol)).cast(DoubleType).as("prediction")
    val rating = col($(ratingCol)).cast(DoubleType).as("rating")

    val left = p.select(user, item, prediction).map {
      case Row(user: Int, item: Int, prediction: Double) => (user, (item, prediction))
    }
    val right = g.select(user, item, rating).flatMap {
      case Row(user: Int, item: Int, rating: Double) if rating >= relevanceThreshold => Some((user, (item, rating)))
      case _ => None
    }
    (left.rdd cogroup right.rdd).values.map {
      case (predictedItems, groundTruthItems) =>
        val prediction = predictedItems.toArray.sortBy(-_._2)
        val labels = groundTruthItems.toArray.sortBy(-_._2)
        (prediction, labels)
    }.cache()
  }

  def precisionAt(k: Int): Double = {
    require(k > 0, "ranking position k should be positive")
    predictionAndLabels.map { case (pred, lab) =>
      val labSet = lab.map(_._1).toSet

      if (labSet.nonEmpty) {
        val n = math.min(pred.length, k)
        var i = 0
        var cnt = 0
        while (i < n) {
          if (labSet.contains(pred(i)._1)) {
            cnt += 1
          }
          i += 1
        }
        cnt.toDouble / k
      } else {
        log.warn("Empty ground truth set, check input data")
        0.0
      }
    }.mean()
  }

  lazy val meanAveragePrecision: Double = {
    predictionAndLabels.map { case (pred, lab) =>
      val labSet = lab.map(_._1).toSet

      if (labSet.nonEmpty) {
        var i = 0
        var cnt = 0
        var precSum = 0.0
        val n = pred.length
        while (i < n) {
          if (labSet.contains(pred(i)._1)) {
            cnt += 1
            precSum += cnt.toDouble / (i + 1)
          }
          i += 1
        }
        precSum / labSet.size
      } else {
        log.warn("Empty ground truth set, check input data")
        0.0
      }
    }.mean()
  }

  def ndcgAt(k: Int): Double = {
    require(k > 0, "ranking position k should be positive")
    predictionAndLabels.map { case (pred, label) =>
      val labelMap = label.toMap
      if (labelMap.nonEmpty) {
        val labSetSize = labelMap.size
        val n = math.min(math.max(pred.length, labSetSize), k)
        var idealDcg = 0.0
        var dcg = 0.0
        var i = 0

        while (i < n) {
          var gain = 0.0
          var ideal = 0.0

          if (i < pred.length) {
            gain = labelMap.get(pred(i)._1).map(rel => (math.pow(2, rel) - 1) / math.log(i + 2)).getOrElse(0.0)
            dcg += gain
          }

          if (i < label.length) {
            ideal = (math.pow(2, label(i)._2) - 1) / math.log(i + 2)
            idealDcg += ideal
          }

          i += 1
        }
        dcg / idealDcg
      } else {
        log.warn("Empty ground truth set, check input data")
        0.0
      }
    }.mean()
  }

  override def copy(extra: ParamMap): Params = {
    val copied = new DataFrameRankingMetrics(predicted, groundTruth)
    copyValues(copied, extra)
    copied
  }

}


object DataFrameRankingMetrics {
  def apply[P: Encoder, G: Encoder](predicted: Dataset[P], groundTruth: Dataset[G], relevanceThreshold: Double = 0) = {
    new DataFrameRankingMetrics(predicted.toDF, groundTruth.toDF, relevanceThreshold)
  }

  def apply(predicted: DataFrame, groundTruth: DataFrame) = {
    new DataFrameRankingMetrics(predicted, groundTruth)
  }

  def apply(predicted: DataFrame, groundTruth: DataFrame, relevanceThreshold: Double) = {
    new DataFrameRankingMetrics(predicted, groundTruth, relevanceThreshold)
  }
}
