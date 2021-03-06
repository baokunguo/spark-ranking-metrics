package com.github.jongwook

import org.apache.spark.SparkConf
import org.apache.spark.mllib.recommendation.Rating
import org.apache.spark.sql.SparkSession
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.{FlatSpec, Matchers}

/** Tests the equality of metrics from our implementation and Rival's */
class TestEqualityWithRival extends FlatSpec with Matchers {
  import TestFixture._
  implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(eps)

  val ourResults: Map[Metric, Seq[Double]] = {
    val spark = SparkSession.builder().master(new SparkConf().get("spark.master", "local[8]")).getOrCreate()
    import spark.implicits._

    val predictionDF = spark.createDataset(prediction)
    val groundTruthDF = spark.createDataset(groundTruth)

    val metrics = new SparkRankingMetrics(predictionDF, groundTruthDF, itemCol = "product", predictionCol = "rating")

    Map(
      NDCG -> metrics.ndcgAt(ats),
      MAP -> metrics.mapAt(ats),
      Precision -> metrics.precisionAt(ats),
      Recall -> metrics.recallAt(ats)
    )
  }

  val rivalResults: Map[Metric, Seq[Double]] = {
    import net.recommenders.rival.core.DataModel
    import net.recommenders.rival.evaluation.metric.{ranking => rival}

    val predictionModel = new DataModel[Int, Int]()
    val groundTruthModel = new DataModel[Int, Int]()

    prediction.foreach {
      case Rating(user, item, score) => predictionModel.addPreference(user, item, score)
    }
    groundTruth.foreach {
      case Rating(user, item, score) => groundTruthModel.addPreference(user, item, score)
    }

    val ndcg = new rival.NDCG(predictionModel, groundTruthModel, eps, ats, rival.NDCG.TYPE.EXP)
    val map = new rival.MAP(predictionModel, groundTruthModel, eps, ats)
    val precision = new rival.Precision(predictionModel, groundTruthModel, eps, ats)
    val recall = new rival.Recall(predictionModel, groundTruthModel, eps, ats)

    Seq(ndcg, map, precision, recall).foreach(_.compute())

    Map(
      NDCG -> ats.map(ndcg.getValueAt),
      MAP -> ats.map(map.getValueAt),
      Precision -> ats.map(precision.getValueAt),
      Recall -> ats.map(recall.getValueAt)
    )
  }

  for (metric <- Seq(NDCG, /* MAP,*/ Precision, Recall)) {  // temporarily skipping MAP until we're sure about Rival's implementation
    s"Our $metric implementation" should "produce the same numbers as Rival" in {
      for ((ours, rivals) <- ourResults(metric) zip rivalResults(metric)) {
        ours should equal (rivals)
      }
    }
  }
}
