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
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.Traversable
import scala.annotation.tailrec
import scala.math.{ sqrt, pow }

class Timer(maxItems: Int) {
  val count = new AtomicInteger(0)
  val startTime = Platform.currentTime

  def +=(i: Int) {
    count.addAndGet(i)
  }

  def status() = {
    val itemsLeft = maxItems - count.get
    val timePassed = Platform.currentTime - startTime
    val rate = count.doubleValue / timePassed
    val secondsLeft = itemsLeft.toDouble / rate / 1000.0
    (count.get + "/" + maxItems +
      " complete\n") +
      ("Estimated time left: %02d:%02d:%02d" format
        ((secondsLeft / 3600.0).toInt, (secondsLeft / 60.0 % 60.0).toInt,
          (secondsLeft % 60.0).toInt))
  }
}

object FasterFinal {
  // Notes for types
  type Feature = Double
  type FeatureVector = Array[Double]
  type Frame = Array[FeatureVector]
  type FrameVector = Array[Double]
  type Time = Double

  // Some parameters
  val folds = 10
  val maxFrameLength = 4
  //val bagCountByFrameLength = List(123, 7, 38, 100)
  val temporalPyramidLevels = 4
  //val filePrefix = "data"

  // Serialization stuff
  // Splitting info
  type SplitterFunc = Iterable[Frame] => Array[Iterator[FrameVector]]
  case class FeatureSplit(splitter: SplitterFunc,
    featureLengths: List[Int], featureNames: List[String])

  val splitInfo =
    Map(
      false -> FeatureSplit(framePassthrough _,
        List(25),
        List("combined")),
      true -> FeatureSplit(frameSplit _,
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
    "-c <bag counts> <job split number> <max jobs>: cluster data\n" +
    "-h <job split number> <max jobs>: build histograms\n" +
    "-s <bag counts> <job split number> <max jobs>: create SVM classifiers\n" +
    "-e: Evaluate\n" +
    "--evolve <Spark server> <population size> <is split>: Evolve an better bag count\n\n" +
    "Bag counts are comma-separated lists of integers representing\n" +
    "the number of bags used for each kind of feature:\n" +
    "C: combined\n" +
    "T: timbre\n" +
    "P: pitch\n" +
    "D: duration\n" +
    "<number>: segment length\n\n" +
    "In order, that's:\n" +
    "T1,T2,T3,T4,P1,P2,P3,P4,D1,D2,D3,D4,C1,C2,C3,C4\n\n" +
    "Note that C1 is not bagged, so it doesn't do anything."

  def main(args: Array[String]) {
    args.toList match {
      case List("-d", songCount) => download(songCount.toInt)
      case List("-c", bagCounts, splitNum, maxSplit) => clusterChunk(
          bagCounts,
          parseBagCounts(bagCounts), splitNum.toInt,
          maxSplit.toInt)
      case List("-h", bagCounts, splitNum, maxSplit) => histogramChunk(
          bagCounts, splitNum.toInt,
          maxSplit.toInt)
      case List("-s", bagCounts, splitNum, maxSplit) => svmChunk(
          bagCounts,
          parseBagCounts(bagCounts), splitNum.toInt,
          maxSplit.toInt)
      case List("-e", bagCounts) => evaluateAll(bagCounts, parseBagCounts(bagCounts))
      case List("--evolve", sparkServer, popSize, isSplit) =>
        evolve(popSize.toInt, sparkServer, isSplit.toBoolean, 0)
      case _ => {
        // If there are too many arguments print a help message and exit
        println(helpMessage)
        throw new IllegalArgumentException("Invalid number of arguments")
      }
    }
  }
  
  // Run evolution
  def evolve(popSize: Int, sparkServer: String, isSplit: Boolean, 
    maxLevels: Int): Unit = {
    // Make a genome printable
    def makePrintable(genome: (Array[Int], String, Double)) = 
      (genome._1.toSeq, genome._2, genome._3)
    // Try to load a population
    val (generation, population, bestGenome) = try {
      deserializeObject("data", "population",
      manifest[(Int, Array[Array[Int]], (Array[Int], String, Double))])
    } catch {
      case _ => {
        // Otherwise, make a population
        (0, // First generation
        Array.fill(popSize) {
          Array.fill(16) {Random.nextInt(200)+1}
        },
        // Nonsense best genome
        (Array.fill(16) {0}, "nothing", Double.NegativeInfinity))
      }
    }
    
    // Connect to Spark server
    println(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    val sc = new SparkContext(sparkServer, "BeatleEvolution"/*,
        "/home/tommy/Applications/spark-0.6.1",
      Seq(getClass.getProtectionDomain.getCodeSource.getLocation.toURI.toString)*/)

    // Load up the music
    val songs = sc.broadcast(
      try {
        deserializeObject("data", "music",
          manifest[Array[((String, String), Array[(Double, Array[Double])])]])
      } catch {
        case _ => {
          // download the music
          download(100)
          // If that didn't download it, it's okay to crash
          deserializeObject("data", "music",
            manifest[Array[((String, String), Array[(Double, Array[Double])])]])
        }
      })
    
    @tailrec
    def runGeneration(generation: Int, population: Array[Array[Int]],
      bestGenome: (Array[Int], String, Double)): Unit = {
      // Evaluate fitnesses
      val fitnessInfo =
        (sc.parallelize(population, population.size).flatMap(genome => {
         println("Job started")
         val evalInfo = evaluateBagCounts(genome, 0, 1, isSplit, 0, true,
             Some(songs.value))
         
         evalInfo.map(x => (genome, x._1, x._2))
        })).collect

      // Gather info for convinient use
      val fitnesses = fitnessInfo.map(_._3)
      val thisBestGenome = fitnessInfo.maxBy(_._3)
      val newBestGenome = 
          List(thisBestGenome, bestGenome).maxBy(_._3)
      val newBestInfo = newBestGenome._1.toSeq + " using " + newBestGenome._2
      val thisBestInfo = thisBestGenome._1.toSeq + " using " + thisBestGenome._2

      println()
      println("Generation " ++ generation.toString ++ ":")
      println("Best genome: " ++ thisBestInfo.toString)
      println("Best fitness: " ++ fitnesses.max.toString)
      println("Average fitness: " ++ (fitnesses.sum / fitnesses.size).toString)
      println("Worst fitness: " ++ fitnesses.min.toString)
      println()
      println("All time:")
      println("Best genome: " ++ newBestInfo)
      println("Best fitness: " ++ newBestGenome._3.toString)

      // Evolution stuff
      val newPopulation =
        mutate(
          (Array.fill(popSize / 2) {
            val children = crossover(
              selection(population, fitnesses),
              selection(population, fitnesses))
            List(children._1, children._2)
          }).flatten)

      // Save information
      serializeObject("data", "population",
        (generation + 1, newPopulation, newBestGenome))

      runGeneration(generation + 1, newPopulation, newBestGenome)
    }
    
    runGeneration(generation, population, bestGenome)
  }
  
  // Wait for files that match the regex
  def waitForMatchingFiles(regex: String, count: Int) = {
    def getFileCount() =
        (new File("."))
          .listFiles()
          .map(_.getName())
          .filter(filename => filename.matches(regex))
          .size
    
    // Every time we don't have enough files, wait one tenth of a second
    while (getFileCount() < count) {
      Thread.sleep(100)
    }
  }
  
  // Evaluates a bag count genome,
  def evaluateBagCounts(bagCountGenome: Iterable[Int], splitNum: Int,
      maxSplit: Int, cleanup: Boolean, 
      possibleSongs: Option[Array[((String, String), Array[(Double, Array[Double])])]]) = {
    val songs = possibleSongs.getOrElse {
      try {
        deserializeObject("data", "music",
          manifest[Array[((String, String), Array[(Double, Array[Double])])]])
      } catch {
        case _ => {
          // If we're the master, download the music
          if (splitNum == 0)
            download(100)
          // Otherwise, wait for the music
          else
            waitForMatchingFiles("""^data\.music$""", 1)
          // If that didn't download it, it's okay to crash
          deserializeObject("data", "music",
            manifest[Array[((String, String), Array[(Double, Array[Double])])]])
        }
      }
    }
    val bagCountsString = bagCountGenome.mkString(",")
    val bagCounts = parseBagCounts(bagCountsString)

    // Run all the stuff
    clusterChunk(
      bagCountsString,
      bagCounts, splitNum,
      maxSplit,
      Some(songs))
    // Barrier
    val clusterRegex = """^""" + bagCountsString + 
        """_FrameSetInfo\(.*\)\.cluster$"""
    waitForMatchingFiles(clusterRegex, folds * 2 * maxFrameLength)
    histogramChunk(
      bagCountsString, splitNum,
      maxSplit,
      Some(songs))
    // Barrier
    val histoRegex = """^""" + bagCountsString + 
        """_FrameSetInfo\(.*\)\.histograms"""
    waitForMatchingFiles(histoRegex, folds*2*4*maxFrameLength)
    if (cleanup) {
      for (files <- Option(new File(".").listFiles);
           file <- files if file.getName matches clusterRegex)
        file.delete
    }
    svmChunk(
      bagCountsString,
      bagCounts, splitNum,
      maxSplit)
    val svmRegex = """^""" + bagCountsString + 
        """_FrameSetInfo\(.*\)\.svm"""
    waitForMatchingFiles(svmRegex, folds*2*temporalPyramidLevels)
    
    val result = if (splitNum == 0) {
        Some(evaluateAll(bagCountsString, bagCounts))
    } else {
      None
    }
    
    // Cleanup files
    if (cleanup) {
      for (files <- Option(new File(".").listFiles);
           file <- files if file.getName matches (histoRegex+"|"+svmRegex))
        file.delete
    }
    
    result
  }
  
    // Evaluates a bag count genome,
  def evaluateBagCounts(bagCountGenome: Iterable[Int], splitNum: Int,
      maxSplit: Int, isSplit: Boolean, maxLevel: Int, cleanup: Boolean, 
      possibleSongs: Option[Array[((String, String), Array[(Double, Array[Double])])]]) = {
    val songs = possibleSongs.getOrElse {
      try {
        deserializeObject("data", "music",
          manifest[Array[((String, String), Array[(Double, Array[Double])])]])
      } catch {
        case _ => {
          // If we're the master, download the music
          if (splitNum == 0)
            download(100)
          // Otherwise, wait for the music
          else
            waitForMatchingFiles("""^data\.music$""", 1)
          // If that didn't download it, it's okay to crash
          deserializeObject("data", "music",
            manifest[Array[((String, String), Array[(Double, Array[Double])])]])
        }
      }
    }
    val bagCountsString = bagCountGenome.mkString(",")
    val bagCounts = parseBagCounts(bagCountsString)

    // Run all the stuff
    clusterChunk(
      bagCountsString,
      bagCounts, splitNum,
      maxSplit,
      Some(songs))
    // Barrier
    val clusterRegex = """^""" + bagCountsString + 
        """_FrameSetInfo\(.*\)\.cluster$"""
    waitForMatchingFiles(clusterRegex, folds * 2 * maxFrameLength)
    histogramChunk(
      bagCountsString, splitNum,
      maxSplit,
      Some(songs))
    // Barrier
    val histoRegex = """^""" + bagCountsString + 
        """_FrameSetInfo\(.*\)\.histograms"""
    waitForMatchingFiles(histoRegex, folds*2*4*maxFrameLength)
    if (cleanup) {
      for (files <- Option(new File(".").listFiles);
           file <- files if file.getName matches clusterRegex)
        file.delete
    }
    for (foldIndex <- 0 until folds)
      svmClassifiers(bagCountsString, bagCounts, foldIndex, isSplit, maxLevel)
    val svmRegex = """^""" + bagCountsString + 
        """_FrameSetInfo\(.*\)\.svm"""
    waitForMatchingFiles(svmRegex, folds*(maxLevel+1))
    
    val result = if (splitNum == 0) {
        Some(evaluateOne(bagCountsString, bagCounts, isSplit, maxLevel))
    } else {
      None
    }
    
    // Cleanup files
    if (cleanup) {
      for (files <- Option(new File(".").listFiles);
           file <- files if file.getName matches (histoRegex+"|"+svmRegex))
        file.delete
    }
    
    result
  }
  
  def parseBagCounts(arg: String) = {
    val keys = for (isSplit <- List(true, false);
     featureType <- splitInfo(isSplit).featureLengths.indices;
     lengthIndex <- 0 until maxFrameLength) yield
     (isSplit, featureType, lengthIndex)
     
    val values = arg.split(',').map(_.toInt)
    
    keys zip values toMap
  }

  def clusterChunk(prefix: String,
    bagCounts: Map[(Boolean, Int, Int), Int], nodeIndex: Int,
    nodeCount: Int,
    possibleSongs: Option[Array[((String, String), Array[(Double, Array[Double])])]] = None) = {
    val songs = possibleSongs.getOrElse {
      deserializeObject("data", "music",
        manifest[Array[((String, String), Array[(Double, Array[Double])])]])
    }
      

    val paramsSet = groupedEvenly(for (
      foldIndex <- 0 until folds;
      isSplit <- List(true, false);
      frameLength <- 1 to maxFrameLength
    ) yield (foldIndex, isSplit, frameLength),
      nodeCount).apply(nodeIndex)

    val t = new Timer(paramsSet.size)
    for (params <- paramsSet.par) {
      cluster(prefix, songs, bagCounts, params._1, params._2, params._3)
      t += 1
      println(t.status())
    }
  }

  def histogramChunk(prefix: String, nodeIndex: Int, nodeCount: Int,
    possibleSongs: Option[Array[((String, String), Array[(Double, Array[Double])])]] = None) = {
    val songs = possibleSongs.getOrElse {
      deserializeObject("data", "music",
        manifest[Array[((String, String), Array[(Double, Array[Double])])]])
    }
    val paramsSet = groupedEvenly(for (
      foldIndex <- 0 until folds;
      isTraining <- List(true, false);
      isSplit <- List(true, false);
      frameLength <- 1 to maxFrameLength;
      featureType <- splitInfo(isSplit).featureLengths.indices
    ) yield (prefix, foldIndex, isTraining, isSplit, frameLength, featureType, songs),
      nodeCount).apply(nodeIndex)

    val t = new Timer(paramsSet.size)
    for (p <- paramsSet.par) {
      (histograms _).tupled(p)
      t += 1
      println(t.status())
    }
  }

  def svmChunk(prefix: String,
      bagCounts: Map[(Boolean, Int, Int), Int], nodeIndex: Int,
      nodeCount: Int) = {
    val paramsSet = groupedEvenly(for (
      foldIndex <- 0 until folds;
      isSplit <- List(true, false);
      level <- 0 to temporalPyramidLevels
    ) yield (foldIndex, isSplit, level), nodeCount).apply(nodeIndex)

    val t = new Timer(paramsSet.size)
    for (params <- paramsSet.par) {
      svmClassifiers(prefix, bagCounts, params._1, params._2, params._3)
      t += 1
      println(t.status())
    }
  }

  // Functions that take a sequence of frames and output a sequence of 
  //  one or more sequences of concatenated feature vectors. 
  // Outputs a sequence containing of single sequence of the concatenated 
  //  feature vectors
  def framePassthrough(frames: Iterable[Frame]): Array[Iterator[FrameVector]] = {
    // Make 
    val results = new Array[Iterator[FrameVector]](1)
    results(0) = for (frame <- frames.toIterator) yield {
      val result = new FrameVector(25 * frame.size)
      for (i <- frame.indices.par) {
        var j = 0
        while (j < 25) {
          result(i * 25 + j) = frame(i)(j)
          j += 1
        }
      }
      result
    }
    results
  }

  // Splits a sequence containing sequences of concatenated, homogeneous feature
  //  vectors
  def frameSplit(frames: Iterable[Frame]): Array[Iterator[FrameVector]] = {
    val results = new Array[Iterator[FrameVector]](3)
    results(0) = for (frame <- frames.toIterator) yield {
      val result = new FrameVector(12 * frame.size)
      for (i <- frame.indices.par) {
        var j = 0
        while (j < 12) {
          result(i * 12 + j) = frame(i)(j)
          j += 1
        }
      }
      result
    }
    results(1) = for (frame <- frames.toIterator) yield {
      val result = new FrameVector(12 * frame.size)
      var i = 0
      for (i <- frame.indices.par) {
        var j = 0
        while (j < 12) {
          result(i * 12 + j) = frame(i)(j+12)
          j += 1
        }
      }
      result
    }
    results(2) = for (frame <- frames.toIterator) yield {
      frame.map(_(24))
    }
    results
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
  def groupedEvenly[A: Manifest](xs: Seq[A], divisions: Int) = {
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

  def combineHistograms(bagCounts: Map[(Boolean, Int, Int), Int],
      histograms: GenSeq[(FrameSetInfo, Map[Int, Double])],
    level: Int) = {
    // Make the attributes
    val localSplitInfo = splitInfo(histograms(0)._1.isSplit)
    val attributes = new FastVector(0)
    for (
      segmentIndex <- 0 until maxFrameLength;
      (featureType, featureIndex) <- localSplitInfo.featureNames.zipWithIndex;
      l <- 0 to level;
      region <- 0 until (1 << l);
      attributeCount = 
        if (localSplitInfo.featureLengths(featureIndex) * (segmentIndex+1) == 1)
          2
        else
          bagCounts(histograms(0)._1.isSplit, featureIndex, segmentIndex); 
      clusterIndex <- 0 until attributeCount
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
    //println(unifiedHistograms)
    for ((artist, title) <- songArtistTitles) {
      val instance = new Instance(numAttributes + 1)
      instance.setDataset(unifiedHistograms)
      val songHistograms = histograms.filter(record =>
        record._1.artist == artist &&
          record._1.title == title)
      val concatHisto = for (
        (info, histogram) <- songHistograms.toArray;
        //_ = println(info);
        histogramLength = if (localSplitInfo.featureLengths(info.featureType) *
            (info.frameLength) == 1)
          2
        else
          bagCounts(histograms(0)._1.isSplit, info.featureType, info.frameLength - 1);
        //_ = println(histogramLength);
        frequency <- (0 until histogramLength)
          .map(histogram.toMap.getOrElse(_, 0.0))
      ) yield {
        frequency
      }
      //println(level)
      
      //println(concatHisto.size, numAttributes+1)
      assert(concatHisto.size == numAttributes)
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

  def serializeObject[A <: java.io.Serializable](filePrefix: String, 
      objectGroup: String,
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

  def serializeObject[A <: java.io.Serializable](filePrefix: String, 
      objectGroup: String,
    value: A) {
    val fos = new FileOutputStream(filePrefix + "." + objectGroup)
    val oos = new ObjectOutputStream(fos)
    oos.writeObject(value)
    oos.flush()
    oos.close()
    fos.flush()
    fos.close()
  }

  def deserializeObject[B <: java.io.Serializable](filePrefix: String, 
      objectGroup: String,
    key: FrameSetInfo, manifest: ClassManifest[B]): B = {
    val fis = new FileInputStream(filePrefix + "_" + key + "." + objectGroup)
    val ois = new ObjectInputStream(fis)
    val result = ois.readObject()
    ois.close()
    fis.close()
    result.asInstanceOf[B]
  }

  def deserializeObject[B <: java.io.Serializable](filePrefix: String, 
      objectGroup: String,
    manifest: ClassManifest[B]): B = {
    val fis = new FileInputStream(filePrefix + "." + objectGroup)
    val ois = new ObjectInputStream(fis)
    val result = ois.readObject()
    ois.close()
    fis.close()
    result.asInstanceOf[B]
  }

  def deserializeMatchingObjects[B <: java.io.Serializable](filePrefix: String, 
      objectGroup: String,
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
          val result = deserializeObject(filePrefix, objectGroup, info, manifest)
          Some((info, result))
        }
        case _ => None
      }
    }).flatten
  }

  def download(songCount: Int) = {
    // Get dataset
    val songs = Random.shuffle((new BeatlesData(songCount, 0)).trainingSet.keys
      .map(extractSong)).toArray

    // Log dataset
    writeStringToFile(new File("data_songs.txt"),
      songs.map(song => song._1._1 + " - " + song._1._2).mkString("\n"))

    serializeObject("data", "music", songs)
  }

  def denormalize(
    songs: Array[((String, String), Array[(Double, Array[Double])])]) = {
    // Split dataset for cross-validation
    val songSlices = groupedEvenly(songs, folds)
    val songSets =
      for (fold <- (0 until folds).toIterator) yield {
        ((songSlices.take(fold) ++ songSlices.drop(fold + 1)).flatten,
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
      byFeatureType = splitMethod.splitter(bySegment);
      // Dimensions: start segment, segment, feature
      (song, featureType) <- byFeatureType.zipWithIndex;
      level <- 0 to temporalPyramidLevels;
      // region, start segment, feature
      regions = groupedEvenly(song.toSeq, 1 << level);
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

  def convertResults(regions: Iterator[(FrameSetInfo, Array[FrameVector])]) = {
    for ((info, region) <- regions) yield (info, convertFramesToInstances(region,
      splitInfo(info.isSplit).featureLengths(info.featureType),
      splitInfo(info.isSplit).featureNames(info.featureType)))
  }

  // Cluster instances
  def cluster(prefix: String, 
      songs: Array[((String, String), Array[(Double, Array[Double])])],
      bagCounts: Map[(Boolean, Int, Int), Int],
    foldIndex: Int, isSplit: Boolean, frameLength: Int) = {
    def randSubset(count: Int, lower: Int, upper: Int, sofar: Set[Int] = Set.empty): Set[Int] =
      if (count == sofar.size) sofar else
        randSubset(count, lower, upper, sofar + (Random.nextInt(upper - lower) + lower))

        
    val songSlices = groupedEvenly(songs, folds)
    val trainingSet = (songSlices.take(foldIndex) ++
        songSlices.drop(foldIndex + 1)).flatten
    // Dimensions: feature type, frame, feature
    val byFeatureType = trainingSet.map(song => {
      // Frame, segment, feature
      val frames = song._2.map(_._2).iterator.sliding(frameLength).toArray
      // Feature type, frame, feature
      val byFeatureType = splitInfo(isSplit)
      .splitter(frames.map(_.toArray).toSeq)
      byFeatureType.map(_.toArray)
    }).transpose
    .map(xs => xs.flatten)
        
    for ((frames,featureType) <- byFeatureType.zipWithIndex) yield {
      val convertedRegion = convertResults(
          Array((FrameSetInfo(foldIndex,
        true,
        isSplit,
        frameLength,
        featureType),frames)).toIterator
        ).buffered   
      val instances = new Instances(convertedRegion.head._2, 0)
      for (subset <- convertedRegion) {
        for (
          index <- randSubset(subset._2.numInstances() / 10,
            0, subset._2.numInstances())
        ) {
          instances.add(subset._2.instance(index))
        }
        subset._2.delete()
      }
      
      /*println("Clustering " + FrameSetInfo(foldIndex,
        true,
        isSplit,
        frameLength,
        featureType))*/
      val result = (FrameSetInfo(foldIndex,
        true,
        isSplit,
        frameLength,
        featureType),
        trainClusterer(instances, bagCounts(isSplit, 
            featureType, frameLength - 1)))
      instances.delete()
      serializeObject(prefix, "cluster", result._1, result._2)
    }
  }

  // Make histograms of all regions
  def histograms(prefix: String,
      foldIndex: Int, isTraining: Boolean, isSplit: Boolean, frameLength: Int,
    featureType: Int, songs: Array[((String, String), Array[(Double, Array[Double])])]) = {
    val clustererWithInfo =
      deserializeMatchingObjects(prefix, "cluster", Some(foldIndex), Some(isSplit),
        Some(isTraining), Some(frameLength), Some(featureType),
        None, None, None, None, manifest[SimpleKMeans])

    for (
      (cInfo, clusterer) <- clustererWithInfo;
      songSlices = groupedEvenly(songs, folds);
      trainingSet = (songSlices.take(cInfo.foldIndex) ++
        songSlices.drop(cInfo.foldIndex + 1)).flatten;
      testSet = songSlices(cInfo.foldIndex);
      (isTraining, songSet) <- Map(true -> trainingSet, false -> testSet)
    ) {
      val currentHistograms =
        (for (
          level <- 0 to temporalPyramidLevels;
          regionIndex <- 0 until 1 << level;
          ((artist, title), song) <- songSet;
          region = splitInfo(cInfo.isSplit).splitter(
            groupedEvenly(song.map(_._2), 1 << level)
              .apply(regionIndex).iterator.sliding(cInfo.frameLength)
              .map(_.toArray).toSeq)(cInfo.featureType);
          instances = convertFramesToInstances(region.toSeq,
            splitInfo(cInfo.isSplit).featureLengths(cInfo.featureType),
            splitInfo(cInfo.isSplit).featureNames(cInfo.featureType))
        ) yield {
          val histogram = if (instances.numAttributes() == 1) {
            Array((0,instances.meanOrMode(0)),
            (0, instances.variance(0)))
          } else {
            makeHistogram(
            (0 until instances.numInstances())
              .map(i => clusterer.clusterInstance(instances.instance(i))))
          .toArray
          }
          (FrameSetInfo(cInfo.foldIndex,
          isTraining,
          cInfo.isSplit,
          cInfo.frameLength,
          cInfo.featureType,
          artist,
          title,
          level,
          regionIndex),
          histogram
          )
        }).toArray

      val key = FrameSetInfo(cInfo.foldIndex,
        isTraining,
        cInfo.isSplit,
        cInfo.frameLength,
        cInfo.featureType,
        "",
        "",
        -1)
      //println(key)
      serializeObject(prefix, "histograms", key, currentHistograms)
    }
  }

  // Train BoF SVM classifiers
  def svmClassifiers(prefix: String, bagCounts: Map[(Boolean, Int, Int), Int],
      foldIndex: Int, isSplit: Boolean, level: Int) = {
    val trainingHistograms =
      deserializeMatchingObjects(prefix, "histograms", Some(foldIndex), Some(true),
        Some(isSplit), None, None, None, None, None, None,
        manifest[Array[(FrameSetInfo, Array[(Int, Double)])]])
        .flatMap(_._2)
        .filter(_._1.level <= level)
        .toArray
        .sortBy(_._1)(InfoOrdering)
        .map(record => (record._1, record._2.toMap))
    val (artistTitles, concatedHistos) = 
      combineHistograms(bagCounts, trainingHistograms, level)

    val classifier = new SMO
    classifier.setRandomSeed(Random.nextInt)
    classifier.buildClassifier(concatedHistos)
    val info = FrameSetInfo(foldIndex, true, isSplit, -1, -1, "", "", level, -1)

    serializeObject(prefix, "svm", info, classifier)
  }

  // Evaluate classifiers
  def evaluateAll(prefix: String, bagCounts: Map[(Boolean, Int, Int), Int]) = {
    (for (
      isSplit <- List(true, false);
      level <- 0 to temporalPyramidLevels
    ) yield evaluateOne(prefix, bagCounts, isSplit, level)).maxBy(_._2)
  }
  
  def evaluateOne(prefix: String,
      bagCounts: Map[(Boolean, Int, Int), Int], isSplit: Boolean, level: Int) = {
    val results = (for (
      foldIndex <- (0 until folds).par
    ) yield {
      val (_, classifier) = deserializeMatchingObjects(prefix, "svm", Some(foldIndex),
        Some(true), Some(isSplit), None, None, None, None, Some(level),
        None, manifest[SMO]).next
      val testHistograms =
        deserializeMatchingObjects(prefix, 
            "histograms", Some(foldIndex), Some(false),
          Some(isSplit), None, None, None, None, None, None,
          manifest[Array[(FrameSetInfo, Array[(Int, Double)])]])
          .flatMap(_._2)
          .filter(record => record._1.level <= level)
          .toArray
          .sortBy(_._1)(InfoOrdering)
          .map(record => (record._1, record._2.toMap))
      val (artistTitles, concatedHistos) =
        combineHistograms(bagCounts, testHistograms, level)

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
    println("Features were clustered by: " +
      splitInfo(isSplit).featureNames.mkString(", "))
    println("Temporal pyramid height: " + level)
    println()

    // Get the results for the current technique
    val matchingResult = results
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

    (splitInfo(isSplit).featureNames.mkString(",") + "_level" + level,
      accuracy)
  }
  
    // All genes are mutated by adding a Gaussian
  def mutate(pop: Array[Array[Int]]) = {
    for (genome <- pop) yield {
      for (gene <- genome) yield {
        val newGene = gene + (Random.nextGaussian() * 10).toInt
        if (newGene < 2)
          2
        else
          newGene
      }
    }
  }
  // Single-point crossover
  def crossover(parent1: Array[Int], parent2: Array[Int]) = {
    val splitPoint = Random.nextInt(parent1.size.min(parent2.size))
    val splitP1 = parent1.splitAt(splitPoint)
    val splitP2 = parent2.splitAt(splitPoint)
    (splitP1._1 ++ splitP2._2, splitP2._1 ++ splitP1._2)
  }
  
  def selection(pop: Array[Array[Int]], fitnesses: Iterable[Double]) = {
    // Implement sigma truncation of fitnesses
    val totalFitness = fitnesses.sum
    val averageFitness = totalFitness / fitnesses.size
    val sigmaFitness = sqrt(
      (for (fitness <- fitnesses) yield pow(fitness - averageFitness, 2)).sum / (fitnesses.size - 1))
    val scaledFitnesses =
      for (fitness <- fitnesses) yield {
        val afterScaling = fitness - (averageFitness - sigmaFitness) // c = 1
        if (afterScaling < 0)
          0
        else
          afterScaling
      }
    
    // Roulette selection
    val randomNum = Random.nextDouble() * scaledFitnesses.sum
    def select(num: Double, pop: Array[(Array[Int], Double)]): Array[Int] = {
      if (num - pop.head._2 > 0.0)
        select(num - pop.head._2, pop.tail)
      else
        pop.head._1
    }
    select(randomNum, pop zip scaledFitnesses)
  }
}
