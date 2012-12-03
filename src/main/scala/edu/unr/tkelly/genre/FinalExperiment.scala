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
import java.io.{ FileWriter, PrintWriter }
import scala.util.Random.shuffle
import weka.clusterers.SimpleKMeans
import weka.core.{ Instance, Instances, Attribute, FastVector }
import weka.classifiers.functions.SMO
import weka.classifiers.Evaluation
import scala.collection.GenSeq
import scala.util.Random

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
  val spatialPyramidLevels = 3

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
  val logFileWriter = new FileWriter((new SimpleDateFormat("yyy-MM-dd HH:mm") format
    new java.util.Date) + ".log", true)
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

  // Splitting info
  case class FeatureSplit(splitter: Seq[Frame] => Seq[Seq[FrameVector]],
    featureLengths: List[Int], featureNames: List[String])
  val splitInfo =
    Seq(
      FeatureSplit(framePassthrough[Seq] _,
        List(25),
        List("timbre_pitch_duration")),
      FeatureSplit(frameSplit[Seq] _,
        List(12, 12, 1),
        List("timbre", "pitch", "duration")))

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

  // Cluster a song with a several clusterers

  // Make a clusterer from a collection of frames
  def trainClusterer(xs: Instances, bagCount: Int) = {
    val clusterer = new SimpleKMeans
    clusterer.setSeed(Random.nextInt)
    clusterer.setPreserveInstancesOrder(true)
    clusterer.setNumClusters(bagCount)
    clusterer.buildClusterer(xs)
    clusterer
  }

  // Split a song into clusters

  // Save centroids

  // Make clusterer from centroid file

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
    for ((training, testing) <- songSets) yield {
      // Split songs with relevant information
      for (songSet <- List(training, testing)) yield {
        for (song <- songSet) yield {
          (Util.songToShortString(song),
            for (frameLength <- 1 to maxFrameLength) yield {
              splitSong(song, frameLength)
            })
        }
      }
    }
  // Note that the dimensions on this are:
  // fold, training+testing, song, title*(start time -> frame)

  // We need a way to store everything related to a set of parameters, as
  //  Spark dislikes nested data
  case class InfoWith[T](
    data: T,
    foldIndex: Int,
    splitMethod: FeatureSplit,
    frameLength: Int,
    featureType: Int,
    song: String = "",
    level: Int = -1,
    region: Int = -1)

  // Split instances for clustering
  val splitForClustering = for (
    (List(training, testing), foldIndex) <- sc.parallelize(splitSongSets.zipWithIndex);
    // Note that the dimensions of training and testing are:
    //  song, title*(frame length, time -> frames)

    // For combined or separated features (duration, timbre, pitch)
    splitMethod <- splitInfo;
    framesByFrameLengthThenSong = 
      (training map (song => song._2 map {_.values})).transpose.toSeq.sortBy(_.head.head.size);
    // Dimensions: frame length, song, frames, segments, features
    
    framesByFrameLength = framesByFrameLengthThenSong map {_.flatten};
    // Dimensions: frame length, frame, segment, feature
    
    // Format frames for clustering
    frameVectorsByFrameLength = (framesByFrameLength map { frames: Iterable[Frame] =>
      (splitMethod.splitter(shuffle(frames.toSeq).toArray(manifest[Frame])) map {
        _ toArray
      }) toArray
    }).toArray;
    // Note that the dimensions of frameVectorsByFrameLength are:
    //  frame length, type of feature vector, frame, feature

    // Convert frame vectors to Weka instances
    instancesByFrameLength_Type = for (frameVectorsByFeatureType <- frameVectorsByFrameLength) yield {
      for ((frameVectors, typeIndex) <- frameVectorsByFeatureType.zipWithIndex) yield {
        convertFramesToInstances(frameVectors,
          splitMethod.featureLengths(typeIndex),
          splitMethod.featureNames(typeIndex))
      }
    };
    (instancesByType, frameIndex) <- instancesByFrameLength_Type.zipWithIndex.par;
    (instances, typeIndex) <- instancesByType.toSeq.zipWithIndex
  ) yield {
    /*println("Frame Length Index: " + frameIndex)
    println("Actual frame length = " + (instances.numAttributes/splitMethod.featureLengths(typeIndex)))*/
    InfoWith[Instances](
    instances,
    foldIndex,
    splitMethod,
    frameIndex+1,
    typeIndex)
  }
  
  println(splitForClustering.count)
  // Build the clusterers
  val clusterersWithInfo =
    for (instancesWithInfo <- splitForClustering) yield {
      println("Frame length: " + instancesWithInfo.frameLength)
      println("Bags: " + bagCountByFrameLength(instancesWithInfo.frameLength-1))
      InfoWith(trainClusterer(instancesWithInfo.data,
        bagCountByFrameLength(instancesWithInfo.frameLength-1)),
        instancesWithInfo.foldIndex,
        instancesWithInfo.splitMethod,
        instancesWithInfo.frameLength,
        instancesWithInfo.featureType)
    }
  
  for (clusterer <- clusterersWithInfo.filter(_.frameLength == 2)) {
    println(clusterer.toString)
  }

  // Test plain BoF, spatial BoF, NEAT+BoF

}