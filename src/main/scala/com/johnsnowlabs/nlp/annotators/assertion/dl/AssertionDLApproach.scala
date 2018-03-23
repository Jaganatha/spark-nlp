package com.johnsnowlabs.nlp.annotators.assertion.dl

import com.johnsnowlabs.ml.tensorflow.{AssertionDatasetEncoder, DatasetEncoderParams, TensorflowAssertion, TensorflowWrapper}
import com.johnsnowlabs.nlp.AnnotatorType._
import com.johnsnowlabs.nlp.annotators.datasets.AssertionAnnotationWithLabel
import com.johnsnowlabs.nlp.annotators.ner.Verbose
import com.johnsnowlabs.nlp.embeddings.ApproachWithWordEmbeddings
import org.apache.commons.io.IOUtils
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.param.{FloatParam, IntParam, Param}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions._
import org.tensorflow.{Graph, Session}

import scala.collection.mutable

/**
  * Created by jose on 14/03/18.
  */
class AssertionDLApproach(override val uid: String)
  extends ApproachWithWordEmbeddings[AssertionDLApproach, AssertionDLModel]{

  override val requiredAnnotatorTypes = Array(DOCUMENT)
  override val description: String = "Deep Learning based Assertion Status"

  // example of possible values, 'Negated', 'Affirmed', 'Historical'
  val label = new Param[String](this, "label", "Column with one label per document")
  // TODO: remove target everywhere, use start and end instead
  val target = new Param[String](this, "target", "Column with the target to analyze")

  val startParam = new Param[String](this, "start", "Column with token number for first target token")
  val endParam = new Param[String](this, "end", "Column with token number for last target token")

  val batchSize = new IntParam(this, "batchSize", "Size for each batch in the optimization process")
  val epochsNumber = new IntParam(this, "epochs", "Number of epochs for the optimization process")
  val learningRate = new FloatParam(this, "learningRate", "Learning rate for the optimization process")
  val dropout = new FloatParam(this, "dropout", "Dropout at the output of each layer")


  def setLabelCol(label: String): this.type = set(label, label)
  def setTargetCol(target: String): this.type = set(target, target)

  def setStart(start: String): this.type = set(startParam, start)
  def setEnd(end: String): this.type = set(endParam, end)

  def setBatchSize(size: Int): this.type = set(batchSize, size)
  def setEpochs(number: Int): this.type = set(epochsNumber, number)
  def setLearningRate(rate: Float): this.type = set(learningRate, rate)
  def setDropout(factor: Float): this.type = set(dropout, factor)


  // defaults
  setDefault(label -> "label",
    target -> "target",
    startParam -> "start",
    endParam -> "end",
    batchSize -> 64,
    epochsNumber -> 5,
    learningRate -> 0.0012f,
    dropout -> 0.05f)

  override def train(dataset: Dataset[_], recursivePipeline: Option[PipelineModel]): AssertionDLModel = {

    /* TODO: what performs better, multiple collects or only one? */
    val sentences = dataset.
      withColumn("text", extractTextUdf(col(getInputCols.head))).
      select("text").
      collect().
      map(row => row.getAs[String]("text").split(" "))

    val annotations = dataset.
      select(col(getOrDefault(label)), col(getOrDefault(startParam)), col(getOrDefault(endParam))).
      collect().
      map(row => AssertionAnnotationWithLabel(
        row.getAs[String](getOrDefault(label)),
        row.getAs[Int](getOrDefault(startParam)),
        row.getAs[Int](getOrDefault(endParam))
      ))

    val labelCol = getOrDefault(label)
    /* infer labels and assign a number to each */
    val labelMappings = annotations.map(_.label).distinct.toList

    val graph = new Graph()
    val session = new Session(graph)

    val graphStream = getClass.getResourceAsStream("/assertion_dl/blstm_34_32_30_200.pb")
    val graphBytesDef = IOUtils.toByteArray(graphStream)
    graph.importGraphDef(graphBytesDef)

    val tf = new TensorflowWrapper(session, graph)
    val params = DatasetEncoderParams(labelMappings, List.empty)
    val encoder = new AssertionDatasetEncoder(embeddings.get.getEmbeddings, params)

    val model = new TensorflowAssertion(tf, encoder, getOrDefault(batchSize), Verbose.All)
    val epochs = getOrDefault(epochsNumber)

    model.train(sentences.zip(annotations),
      getOrDefault(learningRate),
      getOrDefault(batchSize),
      getOrDefault(dropout),
      0,
      epochs
    )

    new AssertionDLModel().
      setTensorflow(tf).
      setDatasetParams(model.encoder.params)
  }

  override val annotatorType: AnnotatorType = ASSERTION
  def this() = this(Identifiable.randomUID("ASSERTION"))

  /* send this to common place */
  def extractTextUdf: UserDefinedFunction = udf { document:mutable.WrappedArray[GenericRowWithSchema] =>
    document.head.getString(3)
  }
}
