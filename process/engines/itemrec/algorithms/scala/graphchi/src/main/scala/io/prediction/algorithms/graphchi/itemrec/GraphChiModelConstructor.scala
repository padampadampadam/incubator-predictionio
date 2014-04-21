package io.prediction.algorithms.graphchi.itemrec

import grizzled.slf4j.Logger
import breeze.linalg._
import com.twitter.scalding.Args
import scala.io.Source
import scala.collection.mutable.PriorityQueue

import io.prediction.algorithms.graphchi.itemrec.MatrixMarketReader
import io.prediction.commons.Config
import io.prediction.commons.modeldata.{ ItemRecScore }

/**
 * Input files:
 * - usersIndex.tsv (uindex uid)
 * - itemsIndex.tsv (iindex iid itypes) (only recommend items in this list)
 * - ratings.mm ratings file in matrix market file
 * - ratings.mm_U.mm User x feature matrix generated by GraphChi
 * - ratings.mm_V.mm Item x feature matrix generated by GraphChi
 *
 */
object GraphChiModelConstructor {

  /* global */
  val logger = Logger(GraphChiModelConstructor.getClass)
  //println(logger.isInfoEnabled)
  val commonsConfig = new Config

  // argument of this job
  case class JobArg(
    val inputDir: String,
    val appid: Int,
    val algoid: Int,
    val evalid: Option[Int],
    val modelSet: Boolean,
    val unseenOnly: Boolean,
    val numRecommendations: Int)

  def main(cmdArgs: Array[String]) {
    logger.info("Running model constructor for GraphChi ...")
    logger.info(cmdArgs.mkString(","))

    /* get arg */
    val args = Args(cmdArgs)

    val arg = JobArg(
      inputDir = args("inputDir"),
      appid = args("appid").toInt,
      algoid = args("algoid").toInt,
      evalid = args.optional("evalid") map (x => x.toInt),
      modelSet = args("modelSet").toBoolean,
      unseenOnly = args("unseenOnly").toBoolean,
      numRecommendations = args("numRecommendations").toInt
    )

    /* run job */
    modelCon(arg)
    cleanUp(arg)
  }

  def modelCon(arg: JobArg) = {

    // NOTE: if OFFLINE_EVAL, write to training modeldata and use evalid as appid
    val OFFLINE_EVAL = (arg.evalid != None)

    val modeldataDb = if (!OFFLINE_EVAL)
      commonsConfig.getModeldataItemRecScores
    else
      commonsConfig.getModeldataTrainingItemRecScores

    val appid = if (OFFLINE_EVAL) arg.evalid.get else arg.appid

    // user index file
    // uindex -> uid
    val usersMap: Map[Int, String] = Source.fromFile(s"${arg.inputDir}usersIndex.tsv").getLines()
      .map[(Int, String)] { line =>
        val (uindex, uid) = try {
          val data = line.split("\t")
          (data(0).toInt, data(1))
        } catch {
          case e: Exception => {
            throw new RuntimeException(s"Cannot get user index and uid in line: ${line}. ${e}")
          }
        }
        (uindex, uid)
      }.toMap

    case class ItemData(
      val iid: String,
      val itypes: Seq[String])

    // item index file (iindex iid itypes)
    // iindex -> ItemData
    val itemsMap: Map[Int, ItemData] = Source.fromFile(s"${arg.inputDir}itemsIndex.tsv")
      .getLines()
      .map[(Int, ItemData)] { line =>
        val (iindex, item) = try {
          val fields = line.split("\t")
          val itemData = ItemData(
            iid = fields(1),
            itypes = fields(2).split(",").toList
          )
          (fields(0).toInt, itemData)
        } catch {
          case e: Exception => {
            throw new RuntimeException(s"Cannot get item info in line: ${line}. ${e}")
          }
        }
        (iindex, item)
      }.toMap

    // ratings file (for unseen filtering) 
    val seenSet: Map[Int, Set[Int]] = if (arg.unseenOnly) {
      Source.fromFile(s"${arg.inputDir}ratings.mm")
        .getLines()
        // discard all empty line and comments
        .filter(line => (line.length != 0) && (!line.startsWith("%")))
        .drop(1) // 1st line is matrix size
        .map { line =>
          val (u, i) = try {
            val fields = line.split("""\s+""")
            // u, i, rating
            (fields(0).toInt, fields(1).toInt)
          } catch {
            case e: Exception => throw new RuntimeException(s"Cannot get user and item index from this line: ${line}. ${e}")
          }
          (u, i)
        }.toSeq.groupBy(_._1)
        .mapValues(_.map(_._2).toSet)
    } else {
      Map() // empty map
    }

    // feature x user matrix
    val userMatrix = MatrixMarketReader.readDense(s"${arg.inputDir}ratings.mm_U.mm")

    // feature x item matrix
    val itemMatrix = MatrixMarketReader.readDense(s"${arg.inputDir}ratings.mm_V.mm")

    val allUindex = for (uindex <- 1 to userMatrix.cols if usersMap.contains(uindex)) yield (uindex, userMatrix(::, uindex - 1), seenSet.getOrElse(uindex, Set()))

    val validIindex = for (iindex <- 1 to itemMatrix.cols if validItemFilter(true, iindex, itemsMap)) yield (iindex)

    val allScores = allUindex.par
      .foreach { fields =>
        val (uindex, userVector, seenItemSet) = fields
        val q = new TopNQueue[(Int, Double)](arg.numRecommendations)(ScoreOrdering.reverse)
        val candidateIindex = validIindex.toIterator
          .filter(iindex => unseenItemFilter(arg.unseenOnly, iindex, seenItemSet))

        while (candidateIindex.hasNext) {
          val iindex = candidateIindex.next()
          val score = userVector dot itemMatrix(::, iindex - 1)
          q.add((iindex, score))
        }

        val topScores = q.toSeq

        modeldataDb.insert(ItemRecScore(
          uid = usersMap(uindex),
          iids = topScores.map(x => itemsMap(x._1).iid),
          scores = topScores.map(_._2),
          itypes = topScores.map(x => itemsMap(x._1).itypes),
          appid = appid,
          algoid = arg.algoid,
          modelset = arg.modelSet))
      }
  }

  def unseenItemFilter(enable: Boolean, iindex: Int, seenSet: Set[Int]): Boolean = {
    if (enable) (!seenSet(iindex)) else true
  }

  def validItemFilter(enable: Boolean, iindex: Int, validMap: Map[Int, Any]): Boolean = {
    if (enable) validMap.contains(iindex) else true
  }

  def cleanUp(arg: JobArg) = {

  }

  object ScoreOrdering extends Ordering[(Int, Double)] {
    override def compare(a: (Int, Double), b: (Int, Double)) = a._2 compare b._2
  }

  def getTopN[T](s: Seq[T], n: Int)(implicit ord: Ordering[T]): Seq[T] = {
    val q = PriorityQueue()

    for (x <- s) {
      if (q.size < n)
        q.enqueue(x)
      else {
        // q is full
        if (ord.compare(x, q.head) < 0) {
          q.dequeue()
          q.enqueue(x)
        }
      }
    }

    q.dequeueAll.toSeq.reverse
  }

  class TopNQueue[T](val n: Int)(implicit ord: Ordering[T]) {
    val q = PriorityQueue[T]()

    def add(x: T) = {
      if (q.size < n)
        q.enqueue(x)
      else {
        // q is full
        if (ord.compare(x, q.head) < 0) {
          q.dequeue()
          q.enqueue(x)
        }
      }
    }

    def toSeq[T] = {
      q.dequeueAll.toSeq.reverse
    }
  }

}
