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
import java.io.{ ByteArrayOutputStream, ByteArrayInputStream }
import java.io._
import scala.compat.Platform

object FasterFinal extends App {
  // Notes for types
  type Feature = Double
  type FeatureVector = Array[Double]
  type Frame = Array[FeatureVector]
  type FrameVector = Array[Double]
  type Time = Double

  // Some parameters
  val folds = 10
  val maxFrameLength = 4
  val bagCountByFrameLength = List(123, 7, 38, 100)
  val temporalPyramidLevels = 3
  val filePrefix = "data"

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

  // Parse arguments
  val helpMessage = "Syntax: FasterFinal <command>\n" +
    "Commands:\n" +
    "-d <song count>: download data\n" +
    "-c <job split number> <max jobs>: cluster data\n" +
    "-h: build histograms\n" +
    "-s <job split number> <max jobs>: create SVM classifiers\n" +
    "-e: Evaluate\n"

  args.toList match {
    case List("-d", songCount) => download(songCount.toInt)
    case List("-c", splitNum, maxSplit) => {
      val songRegionTable = denormalize(deserializeObject("music",
        manifest[Array[((String, String), Array[(Double, Array[Double])])]]))
      val paramsSet = for (
        foldIndex <- 0 until folds;
        isSplit <- List(true, false);
        frameLength <- 1 to maxFrameLength
      ) yield (foldIndex, isSplit, frameLength)
      for (params <- groupedEvenly(paramsSet, maxSplit.toInt).apply(splitNum.toInt).par)
        cluster(songRegionTable, params._1, params._2, params._3)
    }
    case List("-h") => {
      val regexString = ("^" + filePrefix + "_FrameSetInfo\\(.*\\)\\." +
        "cluster$")
      def getFileCount() =
        (new File("."))
          .listFiles()
          .map(_.getName())
          .filter(filename => filename.matches(regexString))
          .size

      // Wait until it's ready
      val startTime = Platform.currentTime
      while (getFileCount() < folds * 4 * maxFrameLength) {
        println(getFileCount() + "/" + (folds * 2 * maxFrameLength) +
          " complete")
        val filesLeft = folds * 4 * maxFrameLength - getFileCount()
        val timePassed = Platform.currentTime - startTime
        val rate = getFileCount().toDouble / timePassed
        val secondsLeft = filesLeft.toDouble / rate / 1000.0
        println(secondsLeft)
        println("Estimated time left: %02d:%02d:%02d" format 
            ((secondsLeft / 3600.0).toInt, (secondsLeft / 60.0 % 60.0).toInt,
                (secondsLeft % 60.0).toInt))
        Thread.sleep(60000)
      }
      val songs = deserializeObject("music",
        manifest[Array[((String, String), Array[(Double, Array[Double])])]])
      histograms(songs)
    }
    case List("-s", splitNum, maxSplit) =>
      val paramsSet = for (
        foldIndex <- 0 until folds;
        isSplit <- List(true, false);
        level <- 0 to temporalPyramidLevels
      ) yield (foldIndex, isSplit, level)
      for (params <- groupedEvenly(paramsSet, maxSplit.toInt).apply(splitNum.toInt).par)
        svmClassifiers(params._1, params._2, params._3)
    case List("-e") => {
      val regexString = ("^" + filePrefix + "_FrameSetInfo\\(.*\\)\\." +
        "svm$")
      def getFileCount() =
        (new File("."))
          .listFiles()
          .map(_.getName())
          .filter(filename => filename.matches(regexString))
          .size

      // Wait until it's ready
      val startTime = Platform.currentTime
      while (getFileCount() < folds * 2 * temporalPyramidLevels) {
        println(getFileCount() + "/" + (folds * 2 * maxFrameLength) +
          " complete")
        val filesLeft = folds * 4 * maxFrameLength - getFileCount()
        val timePassed = Platform.currentTime - startTime
        val rate = getFileCount().toDouble / timePassed
        val secondsLeft = filesLeft.toDouble / rate / 1000.0
        println(secondsLeft)
        println("Estimated time left: %02d:%02d:%02d" format 
            ((secondsLeft / 3600.0).toInt, (secondsLeft / 60.0 % 60.0).toInt,
                (secondsLeft % 60.0).toInt))
        Thread.sleep(60000)
      }
      evaluate()
    }
    case _ => {
      // If there are too many arguments print a help message and exit
      println(helpMessage)
      throw new IllegalArgumentException("Invalid number of arguments")
    }
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
  
  def extractSong(s: Song) = {
      ((s.getArtistName, s.getTitle),
          (for (
        segment <- s.getAnalysis.getSegments.toArray(manifest[Segment])
      ) yield {
        (segment.getStart(),
            (for (featureIndex <- 0 until 25) yield if (featureIndex < 12)
              segment.getTimbre()(featureIndex)
            else if (featureIndex < 24)
              segment.getPitches()(featureIndex - 12)
            else
              segment.getDuration).toArray(manifest[Double]))
      }).toArray)
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
  def groupedEvenly[A: Manifest](xs: Iterable[A], divisions: Int) = {
    val length = xs.size.toDouble / divisions
    // We need to know the indices
    xs.zipWithIndex
      // Make a map for each grouping
      .groupBy(x => (x._2 / length).toInt)
      // Convert map for
      .toList
      // Sorting
      .sortBy(_._1)
      // Remove indices and group numbers
      .map(m => m._2
        .map(_._1).toArray(manifest[A])).toArray
  }

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
    val attributes = new FastVector(0)
    for (
      segmentIndex <- 0 until maxFrameLength;
      featureType <- localSplitInfo.featureNames;
      l <- 0 to level;
      region <- 0 until (1 << l);
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

    val numAttributes =
      attributes.size() - 1

    val songArtistTitles = (for ((info, _) <- histograms) yield (info.artist, info.title))
      .toSet.toList

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
        (info, histogram) <- songHistograms.toArray;
        histogramLength = bagCountByFrameLength(info.frameLength - 1);
        frequency <- (0 until histogramLength)
          .map(histogram.toMap.getOrElse(_, 0.0))
      ) yield {
        frequency
      }

      for ((frequency, index) <- concatHisto.zipWithIndex) {
        instance.setValue(index, frequency)
      }

      instance.setValue(numAttributes, if (artist == "The Beatles") "T" else "F")

      unifiedHistograms.add(instance)
    }

    (songArtistTitles, unifiedHistograms)
  }

  // Make a histogram from a series of clusters
  def makeHistogram(clusters: Iterable[Int]) = {
    val rawHisto = clusters.foldLeft(Map[Int, Int]())(
      (acc: Map[Int, Int], cluster) => {
        acc.updated(cluster, acc.getOrElse(cluster, 0) + 1)
      })
    val total = rawHisto.map(_._2).sum
    rawHisto.mapValues(_.toDouble / total)
  }

  def serializeObject[A <: java.io.Serializable](objectGroup: String,
    key: FrameSetInfo, value: A) {
    val fos = new FileOutputStream(filePrefix + "_" + key + "." + objectGroup)
    val oos = new ObjectOutputStream(fos)
    oos.writeObject(value)
    oos.flush()
    oos.close()
    fos.flush()
    fos.close()
    writeStringToFile(
      new File(filePrefix + "_" + key + "." + objectGroup + ".txt"),
      value.toString)
  }

  def serializeObject[A <: java.io.Serializable](objectGroup: String,
    value: A) {
    val fos = new FileOutputStream(filePrefix + "." + objectGroup)
    val oos = new ObjectOutputStream(fos)
    oos.writeObject(value)
    oos.flush()
    oos.close()
    fos.flush()
    fos.close()
  }

  def deserializeObject[B <: java.io.Serializable](objectGroup: String,
    key: FrameSetInfo, manifest: ClassManifest[B]): B = {
    val fis = new FileInputStream(filePrefix + "_" + key + "." + objectGroup)
    val ois = new ObjectInputStream(fis)
    val result = ois.readObject()
    ois.close()
    fis.close()
    result.asInstanceOf[B]
  }

  def deserializeObject[B <: java.io.Serializable](objectGroup: String,
    manifest: ClassManifest[B]): B = {
    val fis = new FileInputStream(filePrefix + "." + objectGroup)
    val ois = new ObjectInputStream(fis)
    val result = ois.readObject()
    ois.close()
    fis.close()
    result.asInstanceOf[B]
  }

  def deserializeMatchingObjects[B <: java.io.Serializable](objectGroup: String,
    foldIndex: Option[Int], isTraining: Option[Boolean],
    isSplit: Option[Boolean], frameLength: Option[Int],
    featureType: Option[Int], artist: Option[String], title: Option[String],
    level: Option[Int], region: Option[Int],
    manifest: ClassManifest[B]) = {
    // Make regex building easier
    def f[A](possibleThing: Option[A]) =
      possibleThing.map(_.toString).getOrElse(".*")

    // Build a regex
    val regexString = ("^" + filePrefix + "_FrameSetInfo\\((" +
      f(foldIndex) + "),(" + f(isTraining) + "),(" + f(isSplit) + "),(" +
      f(frameLength) + "),(" + f(featureType) + "),(" + f(artist) + "),(" +
      f(title) + "),(" + f(level) + "),(" + f(region) + ")\\)\\." +
      objectGroup + "$")
    val regex = regexString.r

    // Get all files in current directory
    val allFiles = (new File(".")).listFiles().map(_.getName())

    // The ones that match are deserialized
    (for (filename <- allFiles.toIterator) yield {

      filename match {
        case regex(foldIndex, isTraining, isSplit, frameLength, featureType,
          artist, title, level, region) => {
          val info = FrameSetInfo(foldIndex.toInt, isTraining.toBoolean,
            isSplit.toBoolean, frameLength.toInt, featureType.toInt,
            artist, title, level.toInt, region.toInt)
          val result = deserializeObject(objectGroup, info, manifest)
          Some((info, result))
        }
        case _ => None
      }
    }).flatten
  }

  def download(songCount: Int) = {
    // Get dataset
    val songs = (new BeatlesData(songCount, 0)).trainingSet.keys
      .map(extractSong).toArray

    // Log dataset
    writeStringToFile(new File("songs.txt"),
      songs.map(song => song._1._1 + " - " + song._1._2).mkString("\n"))
        
    serializeObject("music", songs)
  }

  def denormalize(songs:  Array[((String, String), Array[(Double, Array[Double])])]) = {
    // Split dataset for cross-validation
    val songSlices = groupedEvenly(songs, folds)
    val songSets =
      for (fold <- (0 until folds).toStream) yield {
        ((songSlices.take(fold) ++ songSlices.drop(fold+1)).flatten,
          songSlices(fold))
      }

    // Split the songs into frames for serialization
    val splitSongSets =
      // For every cross-validation,
      (for ((training, testing) <- songSets) yield {
        // Split songs with relevant information
        (for (songSet <- List(training, testing)) yield {
          (for (song <- songSet) yield {
            ((song._1._1, song._1._2),
              (for (frameLength <- 1 to maxFrameLength) yield {
                    SortedMap[Time, Frame]() ++
      (for (
        frame <- song._2.iterator.sliding(frameLength)
      ) yield {
        (frame(0)._1, frame.map(_._2).toArray)
      })
              }).toArray)
          }).toArray
        }).toArray
      }).zipWithIndex
    // Note that the dimensions on this are:
    // fold, training+testing, song, frame length,
    //    title*(start time -> (segment, feature))
      
    // Denormalize the data
    println("Denormalizing...")
      (for (
        // Dimensions: training+testing, song, 
        //    title*(start time -> (frame length, segment, feature))
        (fold, foldIndex) <- splitSongSets;
        // Dimensions: song, 
        //    title*(frame length, start time -> (segment, feature))
        (isTraining, byTraining) <- Map(true -> fold(0), false -> fold(1));
        (isSplit, splitMethod) <- splitInfo;
        // Dimensions: frame length, start time -> (segment, feature)
        ((artist, title), byFrameLength) <- byTraining;
        // Dimensions: start time -> (segment, feature)
        (byStartTime, frameIndex) <- byFrameLength.zipWithIndex;
        // Dimensions: start segment, segment, feature
        bySegment = byStartTime.values;
        // Dimensions: feature type, start segment, segment, feature
        byFeatureType = splitMethod.splitter(bySegment.toSeq);
        // Dimensions: start segment, segment, feature
        (song, featureType) <- byFeatureType.zipWithIndex;
        level <- 0 to temporalPyramidLevels;
        // region, start segment, feature
        regions = groupedEvenly(song, 1 << level);
        // segment, feature
        (region, regionIndex) <- regions.zipWithIndex
      ) yield (FrameSetInfo(foldIndex,
        isTraining,
        isSplit,
        frameIndex + 1,
        featureType,
        artist,
        title,
        level,
        regionIndex),
        region.toArray))
  }

  def convertResults(regions: Iterable[(FrameSetInfo, Array[FrameVector])]) = {
    for ((info, region) <- regions.toStream) yield (info, convertFramesToInstances(region,
      splitInfo(info.isSplit).featureLengths(info.featureType),
      splitInfo(info.isSplit).featureNames(info.featureType)))
  }

  // Cluster instances
  def cluster(songRegionTable: Iterable[(FrameSetInfo, Array[FrameVector])],
    foldIndex: Int, isSplit: Boolean, frameLength: Int) = {
    for (featureType <- splitInfo(isSplit).featureLengths.indices) yield {
      val matchingRegions = convertResults(
        songRegionTable.filter(
          record => {
            val info = record._1
            info.foldIndex == foldIndex &&
              info.isSplit == isSplit &&
              info.frameLength == frameLength &&
              info.featureType == featureType &&
              info.isTraining == true &&
              info.level == 0
          }))
      val instances = new Instances(matchingRegions(0)._2, 0)
      for (
        subset <- matchingRegions;
        index <- 0 until subset._2.numInstances()
      ) {
        instances.add(subset._2.instance(index))
      }
      val result = (FrameSetInfo(foldIndex,
        true,
        isSplit,
        frameLength,
        featureType),
        trainClusterer(instances, bagCountByFrameLength(frameLength - 1)))
      println("Clustered: " + result._1 + " (" + instances.numAttributes() + ")" )
      serializeObject("cluster", result._1, result._2)
    }
  }

  // Make histograms of all regions
  def histograms(songs:  Array[((String, String), Array[(Double, Array[Double])])]) = {
	val clusterersWithInfo =
		      deserializeMatchingObjects("cluster", None, None, None, None,
		          None, None, None, None, None, manifest[SimpleKMeans])
    
	val startTime = Platform.currentTime
	var index = 0
    val histograms =
      (for (
        clusterChunks <- clusterersWithInfo.grouped(8);
        (cInfo, clusterer) <- clusterChunks.par;
        songSlices = groupedEvenly(songs, folds);
        trainingSet = (songSlices.take(cInfo.foldIndex) ++ 
            songSlices.drop(cInfo.foldIndex+1)).flatten;
        testSet = songSlices(cInfo.foldIndex);
        (isTraining, songSet) <- Map(true -> trainingSet, false -> testSet);
        ((artist, title), song) <- songSet;
        level <- 0 to temporalPyramidLevels;
        regionIndex <- 0 until 1 << level;
        region = 
          splitInfo(cInfo.isSplit).splitter(
              groupedEvenly(song.map(_._2), 1 << level)
          .apply(regionIndex).iterator.sliding(cInfo.frameLength)
          .map(_.toArray).toSeq)(cInfo.featureType);
        instances = convertFramesToInstances(region,
	      splitInfo(cInfo.isSplit).featureLengths(cInfo.featureType),
	      splitInfo(cInfo.isSplit).featureNames(cInfo.featureType))
      ) yield {
        if (index % 500 == 0) {
	        println(index + "/" + 240000 +
	          " complete")
	        val left = 240000 - index
	        val timePassed = Platform.currentTime - startTime
	        val rate = index.toDouble / timePassed
	        val secondsLeft = left.toDouble / rate / 1000.0
	        println(secondsLeft)
	        println("Estimated time left: %02d:%02d:%02d" format 
	            ((secondsLeft / 3600.0).toInt, (secondsLeft / 60.0 % 60.0).toInt,
	                (secondsLeft % 60.0).toInt))
        }
        index += 1
        (FrameSetInfo(cInfo.foldIndex,
            isTraining,
            cInfo.isSplit,
            cInfo.frameLength,
            cInfo.featureType,
            artist,
            title,
            level,
            regionIndex
            ),
          makeHistogram(
            (0 until instances.numInstances())
              .map(i => clusterer.clusterInstance(instances.instance(i)))).toArray)
      }).toArray
    //val titles = histograms.map(_._1.title).toSet.mkString("\n")
    serializeObject("histograms", histograms)
  }

  // Train BoF SVM classifiers
  def svmClassifiers(foldIndex: Int, isSplit: Boolean, level: Int) = {
    val trainingHistograms =
      deserializeObject("histograms",
        manifest[Array[(FrameSetInfo, Array[(Int, Double)])]])
        .filter(record => record._1.foldIndex == foldIndex &&
          record._1.isSplit == isSplit &&
          record._1.level <= level &&
          record._1.isTraining == true)
          .sortBy(_._1)(InfoOrdering)
          .map(record => (record._1, record._2.toMap))
    val (artistTitles, concatedHistos) = combineHistograms(trainingHistograms, level)

    val classifier = new SMO
    classifier.setRandomSeed(Random.nextInt)
    classifier.buildClassifier(concatedHistos)
    val info = FrameSetInfo(foldIndex, true, isSplit, -1, -1, "", "", level, -1)
    println("Classifier built: " + info)
    serializeObject("svm", info, classifier)
  }

  // Evaluate classifiers
  def evaluate() = {
    val results = (for (
      foldIndex <- (0 until folds).par;
      isSplit <- List(true, false);
      level <- 0 to temporalPyramidLevels;
      (_, classifier) = deserializeMatchingObjects("svm", Some(foldIndex),
        Some(true), Some(isSplit), None, None, None, None, Some(level),
        None, manifest[SMO]).next
    ) yield {
      val testHistograms =
        deserializeObject("histograms",
          manifest[Array[(FrameSetInfo, Array[(Int, Double)])]])
          .filter(record => record._1.foldIndex == foldIndex &&
            record._1.isSplit == isSplit &&
            record._1.level <= level &&
            record._1.isTraining == false).sortBy(_._1)(InfoOrdering)
          .map(record => (record._1, record._2.toMap))
      val (artistTitles, concatedHistos) =
        combineHistograms(testHistograms, level)

      // Evaluate it
      val predictedNumericClasses =
        for (instanceIndex <- artistTitles.indices) yield {
          classifier.classifyInstance(concatedHistos.instance(instanceIndex))
        }
      val actualClasses =
        for ((artist, title) <- artistTitles) yield {
          artist == "The Beatles"
        }
      val beatlesClassNumber = 0.0
      // This code was used to find the above number
      /*actualClasses.zip(predictedNumericClasses)
           .filter(_._1 == true)
           .groupBy(identity)
           .maxBy(_._2.size)._1._2*/
      val predictedClasses = predictedNumericClasses.map(_ == beatlesClassNumber)

      for (
        ((artist, title), actual, predicted) <- (artistTitles, actualClasses, predictedClasses).zipped.toList
      ) yield {
        (FrameSetInfo(foldIndex,
          false,
          isSplit,
          -1,
          -1,
          artist,
          title,
          level,
          -1), (actual, predicted))
      }
    }).flatten.seq

    // Print summary statistics by technique
    for (
      isSplit <- List(true, false);
      level <- (0 to temporalPyramidLevels)
    ) yield {
      println("Features were clustered by: " +
        splitInfo(isSplit).featureNames.mkString(", "))
      println("Temporal pyramid height: " + level)
      println()

      // Get the results for the current technique
      val matchingResult = results
        .filter(record => record._1.isSplit == isSplit &&
          record._1.level == level)
        .sortBy(_._1)(InfoOrdering)

      // Stats 
      val truePositives = matchingResult.count(_._2 == (true, true))
      val trueNegatives = matchingResult.count(_._2 == (false, false))
      val falsePositives = matchingResult.count(_._2 == (false, true))
      val falseNegatives = matchingResult.count(_._2 == (true, false))
      val accuracy = (truePositives + trueNegatives).toDouble /
        matchingResult.size

      println("Confusion matrix:")
      println("                               Actual")
      println("                        Beatles     Not Beatles")
      println("Predicted      Beatles %7d     %11d" format (truePositives, falsePositives))
      println("           Not Beatles %7d     %11d" format (falseNegatives, trueNegatives))
      println()
      println("Accuracy: " + (accuracy.toString))
      println()
      println(matchingResult
        .filter(_._2 == (true, true))
        .map(record => record._1.artist + " - " + record._1.title)
        .mkString("True positives:\n", "\n", "\n"))
      println(matchingResult
        .filter(_._2 == (false, false))
        .map(record => record._1.artist + " - " + record._1.title)
        .mkString("True negatives:\n", "\n", "\n"))
      println(matchingResult
        .filter(_._2 == (true, false))
        .map(record => record._1.artist + " - " + record._1.title)
        .mkString("False negative:\n", "\n", "\n"))
      println(matchingResult
        .filter(_._2 == (false, true))
        .map(record => record._1.artist + " - " + record._1.title)
        .mkString("False positives:\n", "\n", "\n"))
      println()
    }
  }
}
