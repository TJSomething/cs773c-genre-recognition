package edu.unr.tkelly.genre

import spark.SparkContext
import SparkContext._
import spark.RDD
import scala.collection._
import scala.collection.generic._
import java.net.BindException
import com.echonest.api.v4._
import scala.collection.JavaConversions._
import java.util.Date
import java.text.SimpleDateFormat
import java.io.{ FileWriter, PrintWriter, File }
import scala.util.Random.shuffle
import weka.clusterers.SimpleKMeans
import weka.core.{ Instance, Instances, Attribute, FastVector }
import weka.classifiers.functions.SMO
import weka.classifiers.Evaluation
import scala.collection.GenSeq
import scala.util.Random
import weka.core.neighboursearch.kdtrees.KMeansInpiredMethod
import org.apache.commons.io.FileUtils._
import scala.reflect.Code
import spark.KryoRegistrator
import com.esotericsoftware.kryo.Kryo
import java.io.{ ByteArrayOutputStream, ByteArrayInputStream }
import java.io.{ ObjectOutputStream, ObjectInputStream }

object FinalExperiment extends App {
  // Notes for types
  type Feature = Double
  type FeatureVector = Array[Double]
  type Frame = Array[FeatureVector]
  type FrameVector = Array[Double]
  type Time = Double

  // Some parameters
  val folds = 10
  val songCount = 10
  val maxFrameLength = 4
  val bagCountByFrameLength = List(123, 7, 38, 100)
  val tasksPerCore = 1
  val temporalPyramidLevels = 3

  // Serialization stuff
  // Splitting info
  type SplitterFunc = Seq[Frame] => Seq[Seq[FrameVector]]
  case class FeatureSplit(splitter: SplitterFunc,
    featureLengths: List[Int], featureNames: List[String])

  val splitInfo =
    Map(
      false -> FeatureSplit(framePassthrough[Seq] _,
        List(25),
        List("timbre_pitch_duration")),
      true -> FeatureSplit(frameSplit[Seq] _,
        List(12, 12, 1),
        List("timbre", "pitch", "duration")))

  // We need a way to store everything related to a set of parameters, as
  //  Spark dislikes nested data
  case class FrameSetInfo(
    foldIndex: Int,
    isTraining: Boolean,
    isSplit: Boolean,
    frameLength: Int,
    featureType: Int,
    artist: String = "",
    title: String = "",
    level: Int = -1,
    region: Int = -1)

  object InfoOrdering extends Ordering[FrameSetInfo] {
    def compare(lhs: FrameSetInfo, rhs: FrameSetInfo) = {
       if (lhs.foldIndex != rhs.foldIndex) {
         lhs.foldIndex compare rhs.foldIndex
       } else if (lhs.isTraining != rhs.isTraining) {
         lhs.isTraining compare rhs.isTraining
       } else if (lhs.isSplit != rhs.isSplit) {
         lhs.isSplit compare rhs.isSplit
       } else if (lhs.isSplit != rhs.isSplit) {
         lhs.isSplit compare rhs.isSplit
       } else if (lhs.frameLength != rhs.frameLength) {
         lhs.frameLength compare rhs.frameLength
       } else if (lhs.featureType != rhs.featureType) {
         lhs.featureType compare rhs.featureType
       } else if (lhs.artist != rhs.artist) {
         lhs.artist compare rhs.artist
       } else if (lhs.title != rhs.title) {
         lhs.title compare rhs.title
       } else if (lhs.level != rhs.level) {
         lhs.level compare rhs.level
       } else {
         lhs.region compare rhs.region
       }
    }
  }

  // Make these classes serializable for JSON
  /*object Protocols {frame
    import dispatch.json._
    implicit object SplitFormat extends Format[FeatureSplit] {
      def writes(method: FeatureSplit) = {
        method.featureNames match {
          case x if x == splitInfo(0).featureNames => JsString("combined")
          case x if x == splitInfo(1).featureNames => JsString("separate")
          case _ => throw new RuntimeException("Unexpected splitting method")
        }
      }
      def reads(value: JsValue) =
        value match {
          case JsString("combined") => splitInfo(0)
          case JsString("separate") => splitInfo(1)
          case _ => throw new RuntimeException("Unexpected splitter method")
        }
    }
    implicit object InfoFormat extends Format[FrameSetInfo] {
      def writes(i: FrameSetInfo): JsValue = 
        JsObject(List(
            (tojson("foldIndex").asInstanceOf[JsString], tojson(i.foldIndex))))
      def reads(value: JsValue) =
        value match {
          case JsObject(m) => 
            FrameSetInfo(fromjson[Int](m(JsString("foldIndex"))),
                fromjson[FeatureSplit](m(JsString("splitMethod"))),
                fromjson[Int](m(JsString("frameLength"))),
                fromjson[Int](m(JsString("featureType"))),
                fromjson[String](m(JsString("song"))),
                fromjson[Int](m(JsString("level"))),
                fromjson[Int](m(JsString("region"))))
          case _ => serializationError("JsObject expected")
        }
    }
    implicit val infoFormat: Format[FrameSetInfo] =
      asProduct7("foldIndex",
          "splitMethod",
          "frameLength",
          "featureType",
          "song",
          "level",
          "region")(FrameSetInfo)(FrameSetInfo.unapply(_).get)
      
    implicit object KMeansFormat extends Format[SimpleKMeans] {
      import java.io.{ ByteArrayOutputStream, ByteArrayInputStream }
      import java.io.{ ObjectOutputStream, ObjectInputStream }
      def writes(c: SimpleKMeans) = {
        val baos = new ByteArrayOutputStream(1024)
        val oos = new ObjectOutputStream(baos)
        oos.writeObject(c)
        oos.flush()
        oos.close()
        JsObject("forComputers" -> JsString(new sun.misc.BASE64Encoder().encode(baos.toByteArray)),
          "forHumans" -> JsString(c.toString()))
      }
      def reads(value: JsValue) = {
        value.asJsObject.getFields("forComputers", "forHumans") match {
          case Seq(JsString(forComputers), JsString(forHumans)) => {
            val bytes = new sun.misc.BASE64Decoder().decodeBuffer(forComputers)
            val bais = new ByteArrayInputStream(bytes)
            val ois = new ObjectInputStream(bais)
            val result = ois.readObject() match {
              case clusterer: SimpleKMeans => clusterer
              case _ => throw new DeserializationException("SimpleKMeans expected")
            }
            ois.close()
            result
          }
          case _ => throw new DeserializationException("SimpleKMeans expected")
        }
      }
    }
    
  }*/

  // Also for Kryo serialization
  class MyRegistrator extends KryoRegistrator {
    override def registerClasses(kryo: Kryo) {
      kryo.register(classOf[SimpleKMeans])
      kryo.register(classOf[Instances])
      kryo.register(classOf[FeatureSplit])
      kryo.register(classOf[FrameSetInfo])
    }
  }

  /*case class FrameSetInfo(
    foldIndex: Int,
    splitMethod: FeatureSplit,
    frameLength: Int,
    featureType: Int,
    song: String = "",
    level: Int = -1,
    region: Int = -1)*/

  // Spark properties
  System.setProperty("spark.serializer", "spark.KryoSerializer")
  System.setProperty("spark.kryo.registrator", "edu.unr.tkelly.genre.FinalExperiment$MyRegistrator")

  // Parse arguments
  val jobName = "MusicClassification"
  val helpMessage = "Syntax: FinalExperiment <Spark master URL>"

  val sc = if (args.size == 0) {
    // If there are no arguments, run locally without threading
    new SparkContext("local", jobName)
  } else if (args.size == 1) {
    // If there is an argument, use that as a URL to create an execution context
    try {
      new SparkContext(args(0), jobName)
    } catch {
      // If that fails, print the exception, a help message, and exit
      case e: Exception => {
        println(e)
        println(helpMessage)
        throw e
      }
    }
  } else {
    // If there are too many arguments print a help message and exit
    println(helpMessage)
    throw new IllegalArgumentException("Invalid number of arguments")
  }

  // Set parallelism based on number of Spark cores available
  collection.parallel.ForkJoinTasks.defaultForkJoinPool.setParallelism(
    sc.defaultMinSplits * sc.defaultParallelism * tasksPerCore)

  // Log to file
  val startTime = (new SimpleDateFormat("yyy-MM-dd HH:mm") format
    new java.util.Date)
  val logFileWriter = new FileWriter(startTime + ".log", true)
  def log(src: String, info: String) = {
    val p = new PrintWriter(logFileWriter)
    p.append("[" + (new SimpleDateFormat("yyy-MM-dd HH:mm") format
      new java.util.Date) + "] " + src + ": " + info + "\n")
    p.close
  }

  // Functions that take a sequence of frames and output a sequence of 
  //  one or more sequences of concatenated feature vectors. 
  // Outputs a sequence containing of single sequence of the concatenated 
  //  feature vectors
  def framePassthrough[B[_] <: GenTraversable[_]](frames: B[Frame])(implicit bf: CanBuildFrom[Nothing, B[FrameVector], B[B[FrameVector]]]): B[B[FrameVector]] = {
    val builder = bf()
    def helper() = {
      (for (frame <- frames) yield for (
        segment <- frame.asInstanceOf[Frame];
        feature <- segment
      ) yield feature).asInstanceOf[B[FrameVector]]
    }
    builder += helper()
    builder.result
  }

  // Splits a sequence containing sequences of concatenated, homogeneous feature
  //  vectors
  def frameSplit[B[_] <: GenTraversable[_]](frames: B[Frame])(implicit bf: CanBuildFrom[Nothing, B[FrameVector], B[B[FrameVector]]]): B[B[FrameVector]] = {
    val builder = bf()
    // This function is needed to get around the implicit builder
    def sliceFrames[T, B[_] <: GenTraversable[_]](start: Int, end: Int): B[FrameVector] = {
      (for (frame <- frames) yield for (
        segment <- frame.asInstanceOf[Frame];
        feature <- segment.slice(start, end)
      ) yield feature)
        .asInstanceOf[B[FrameVector]]
    }
    builder += sliceFrames(0, 12)
    builder += sliceFrames(12, 24)
    builder += sliceFrames(24, 25)
    builder.result
  }

  // Turns song into an array of frames with attached times
  def splitSong(s: Song, frameLength: Int): SortedMap[Time, Frame] = {
    SortedMap[Time, Frame]() ++
      (for (
        frame <- s.getAnalysis.getSegments.toArray(manifest[Segment])
          .iterator.sliding(frameLength)
      ) yield {
        (frame(0).getStart(),
          (for (
            segment <- frame
          ) yield {
            (for (featureIndex <- 0 until 25) yield if (featureIndex < 12)
              segment.getTimbre()(featureIndex)
            else if (featureIndex < 24)
              segment.getPitches()(featureIndex - 12)
            else
              segment.getDuration).toArray(manifest[Double])
          }).toArray(manifest[FeatureVector]))
      })
  }

  def convertFramesToInstances(frames: Iterable[Array[Double]],
    featuresPerSegment: Int, featureType: String) = {
    val vectorLength = frames.head.size
    val attributes = new FastVector(vectorLength)
    for (
      segmentIndex <- 0 until vectorLength / featuresPerSegment;
      featureIndex <- 0 until featuresPerSegment
    ) yield {
      attributes.addElement(
        new Attribute("S%02dF%02d" format (segmentIndex, featureIndex)))
    }

    val instances = new Instances(vectorLength / featuresPerSegment +
      "-segments-of-" + featureType,
      attributes, 0)
    for (frame <- frames) {
      instances.add(new Instance(1.0, frame))
    }
    instances
  }

  // Split a sequence into approximately even divisions
  def groupedEvenly[A](xs: Iterable[A], divisions: Int) =
    // We need to know the indices
    xs.zipWithIndex
      // Make a map for each grouping
      .groupBy(x => (x._2 / (xs.size.toDouble / divisions)).toInt)
      // Convert map for
      .toList
      // Sorting
      .sortBy(_._1)
      // Remove indices and group numbers
      .map(m => m._2
        .map(_._1))

  // Cluster a song with several clusterers

  // Make a clusterer from a collection of frames
  def trainClusterer(xs: Instances, bagCount: Int) = {
    val clusterer = new SimpleKMeans
    clusterer.setSeed(Random.nextInt)
    clusterer.setPreserveInstancesOrder(true)
    clusterer.setNumClusters(bagCount)
    clusterer.buildClusterer(xs)
    clusterer
  }
  
  def combineHistograms(histograms: GenSeq[(FrameSetInfo, Map[Int, Double])],
      level: Int) = {
    // Make the attributes
    val localSplitInfo = splitInfo(histograms(0)._1.isSplit)
    val numAttributes =
      localSplitInfo.featureLengths.size *
        bagCountByFrameLength.sum
    val attributes = new FastVector(numAttributes + 1)
    for (
      segmentIndex <- 0 until maxFrameLength;
      featureType <- localSplitInfo.featureNames;
      l <- 0 to level;
      region <- 0 until (1<<l);
      clusterIndex <- 0 until bagCountByFrameLength(segmentIndex)
    ) yield {
      attributes.addElement(
        new Attribute("segment%02d-%s-level%02d-region%02d-C%02d" 
            format 
            (segmentIndex + 1,
                featureType,
                l,
                region,
                clusterIndex)))
    }
    val possibleBeatlesVals = new FastVector(2)
    possibleBeatlesVals.addElement("T")
    possibleBeatlesVals.addElement("F")
    val beatlesAttrib = new Attribute("isBeatles", possibleBeatlesVals)
    attributes.addElement(beatlesAttrib)
    
    val songArtistTitles = (for ((info, _) <- histograms) yield
        (info.artist, info.title))
        .toSet

    // Build the instances
    val unifiedHistograms = new Instances("histograms", attributes, 0)
    unifiedHistograms.setClass(beatlesAttrib)
    for ((artist, title) <- songArtistTitles) {
      val instance = new Instance(numAttributes + 1)
      instance.setDataset(unifiedHistograms)
      val songHistograms = histograms.filter(record => 
        record._1.artist == artist &&
        record._1.title == title)
      val concatHisto = for (
          (info, histogram) <- songHistograms;
          histogramLength = bagCountByFrameLength(info.frameLength-1);
          frequency <- (0 until histogramLength)
          .map(histogram.getOrElse(_, 0.0))
          ) yield {
        frequency
      }
        
      for ((frequency, index) <- concatHisto.zipWithIndex) {
        instance.setValue(index, frequency)
      }
      
      instance.setValue(numAttributes, if (artist == "The Beatles") "T" else "F")
    }

    unifiedHistograms
  }
  
  // Make a histogram from a series of clusters
  def makeHistogram(clusters: Iterable[Int]) = {
    val rawHisto = clusters.foldLeft(Map[Int, Int]())(
      (acc: Map[Int, Int], cluster) => {
          acc.updated(cluster, acc.getOrElse(cluster, 0) + 1)
      })
    val total = rawHisto.map(_._2).sum
    rawHisto.mapValues(_.toDouble/total)
  }

  def serializeObjects[A, B <: java.io.Serializable]
    (fileName: String, data: GenSeq[(A,B)]) = {
    for ((info, thing) <- data) {
      val baos = new ByteArrayOutputStream(1024)
      val oos = new ObjectOutputStream(baos)
      oos.writeObject(thing)
      oos.flush()
      oos.close()
      writeStringToFile(new File(fileName + "_" + startTime + ".log"),
        info + ":\n" + new sun.misc.BASE64Encoder().encode(baos.toByteArray) 
        + "\n" + thing,
        true)
    }
  }

  // Get dataset
  val songs = (new BeatlesData(songCount, 0)).trainingSet.keys

  // Log dataset
  log("songs", songs.map(Util.songToShortString).mkString("\n"))

  // Split dataset for cross-validation
  val testSetSize = songs.size / folds
  val songSets =
    for (fold <- (0 until folds)) yield {
      val start = (fold * testSetSize).toInt
      val end = ((fold + 1) * testSetSize).toInt
      (songs.take(start) ++ songs.drop(end),
        songs.slice(start, end))
    }

  // Split the songs into frames for serialization
  val splitSongSets =
    // For every cross-validation,
    (for ((training, testing) <- songSets) yield {
      // Split songs with relevant information
      for (songSet <- List(training, testing)) yield {
        for (song <- songSet) yield {
          ((song.getArtistName, song.getTitle),
            for (frameLength <- 1 to maxFrameLength) yield {
              splitSong(song, frameLength)
            })
        }
      }
    }).zipWithIndex
  // Note that the dimensions on this are:
  // fold, training+testing, song, frame length,
  //    title*(start time -> (feature type, feature))

  // Denormalize and broadcast the data
  val songRegionTable =
    sc.parallelize(
      for (
        // Dimensions: training+testing, song, 
        //    title*(start time -> (frame length, feature type, feature))
        (fold, foldIndex) <- splitSongSets;
        // Dimensions: song, 
        //    title*(frame length, start time -> (feature type, feature))
        (isTraining, byTraining) <- Map(true -> fold(0), false -> fold(1));
        (isSplit, splitMethod) <- splitInfo;
        // Dimensions: frame length, start time -> (feature type, feature)
        ((artist, title), byFrameLength) <- byTraining;
        // Dimensions: start time -> (feature type, feature)
        (byStartTime, frameIndex) <- byFrameLength.zipWithIndex;
        // Dimensions: segment, feature type, feature
        bySegment = byStartTime.values;
        // Dimensions: feature type, segment, feature
        byFeatureType = bySegment.transpose;
        // Dimensions: segment, feature
        (song, featureType) <- byFeatureType.zipWithIndex;
        level <- 0 to temporalPyramidLevels;
        // region, segment, feature
        regions = groupedEvenly(song, 1 << level);
        // segment, feature
        (region, regionIndex) <- regions.zipWithIndex
      ) yield (FrameSetInfo(foldIndex,
        isTraining,
        isSplit,
        frameIndex + 1,
        featureType),
        convertFramesToInstances(region,
          splitInfo(isSplit).featureLengths(featureType),
          splitInfo(isSplit).featureNames(featureType)))).cache()

  // Cluster instances
  val clusterersWithInfo = sc.broadcast((for (
    foldIndex <- sc.parallelize(0 until folds);
    isSplit <- List(true, false);
    frameLength <- 1 to maxFrameLength;
    featureType <- splitInfo(isSplit).featureLengths.indices;
    matchingRegions = songRegionTable
      .filter(record => {
        val info = record._1
        info.foldIndex == foldIndex &&
          info.isSplit == isSplit &&
          info.frameLength == frameLength &&
          info.featureType == featureType &&
          info.isTraining == true
      })
  ) yield {
    val instances = matchingRegions.map(_._2).reduce(Instances.mergeInstances)
    (FrameSetInfo(foldIndex,
      true,
      isSplit,
      frameLength,
      featureType),
      trainClusterer(instances, bagCountByFrameLength(frameLength - 1)))
  }).collect())

  // Serialize clusterers
  serializeObjects("clusterers", clusterersWithInfo.value)

  // Make histograms of all regions
  val regionHistograms =
    for (
      (info, region) <- songRegionTable;
      clusterer <- clusterersWithInfo.value.collectFirst {
        case (FrameSetInfo(info.foldIndex,
          true,
          info.isSplit,
          info.frameLength,
          info.featureType,
          _,
          _,
          _,
          _), c) => c
      }
    ) yield {
      (info,
      makeHistogram(
        (0 until region.numInstances())
          .map(i => clusterer.clusterInstance(region.instance(i)))))
    }
  
  // Train BoF SVM classifiers
  val histogramClassifiers = for (
    foldIndex <- 0 until folds;
    isSplit <- List(true, false);
    level <- 0 to temporalPyramidLevels
    // Get the song titles for this fold
    /*songTitles = (for ((info, _) <- songRegionTable 
        if info.foldIndex == foldIndex &&
        info.isTraining == true) yield
        info.title)
        .collect
        .toSet;
    songTitle <- songTitles*/
      ) yield {
    val allHistograms =
      regionHistograms
        .filter(record => record._1.foldIndex == foldIndex &&
            record._1.isSplit == isSplit &&
            record._1.level <= level)
        .collect.sortBy(_._1)(InfoOrdering)
    val concatedHistos = combineHistograms(allHistograms, level)
    val classifier = new SMO
    classifier.buildClassifier(concatedHistos)
    (FrameSetInfo(foldIndex, true, isSplit, -1, -1, "", "", level, -1),
        classifier)
  }
  
  serializeObjects("histogram-svm", histogramClassifiers)
}