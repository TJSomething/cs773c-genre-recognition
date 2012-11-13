package edu.unr.tkelly.genre

import scala.util.Random.shuffle
import com.echonest.api.v4._
import scala.collection.JavaConversions._
import weka.clusterers.SimpleKMeans
import weka.core.{ Instance, Instances, Attribute, FastVector }
import weka.classifiers.functions.SMO
import weka.classifiers.Evaluation

object BoFExperiment extends App {
  // Get data
  val data = new BeatlesData(10, 6)
  val maxSliceLength = 3
  
  def sliceSongs(songs: Map[Song, Double], sliceLength: Int) =
    shuffle(
          for (
            (song, result) <- songs;
            window <- song.getAnalysis.getSegments.toArray(manifest[Segment])
              .iterator.sliding(sliceLength)
          ) yield {
            ((for (
              segment <- window;
              featureIndex <- 0 until 25
            ) yield {
              if (featureIndex < 12)
                segment.getTimbre()(featureIndex)
              else if (featureIndex < 24)
                segment.getPitches()(featureIndex - 12)
              else
                segment.getDuration
            }).toArray(manifest[Double]),
              Util.songToShortString(song))
          })
  
  def convertSlicesToInstances(slices: Iterable[(Array[Double], String)],
      sliceLength: Int) = {
    val attributes = new FastVector(25 * sliceLength)
        for (
          segmentIndex <- 0 until sliceLength;
          featureIndex <- 0 until 25
        ) yield {
          val name = if (featureIndex < 12) {
            "S%02dT%02d" format (segmentIndex, featureIndex)
          } else if (featureIndex < 24) {
            "S%02dP%02d" format (segmentIndex, featureIndex - 12)
          } else {
            "S%02dD" format (segmentIndex)
          }
          attributes.addElement(new Attribute(name))
        }

        val instances = new Instances(sliceLength.toString ++ "slices",
          attributes, 0)
        for ((slice, songName) <- slices) {
          instances.add(new Instance(1.0, slice))
        }
        instances
  }
  
  def combineHistograms(histograms: Seq[Map[String, Map[Int, Int]]],
      bagSize: Int) = {
    // Make the attributes
    val attributes = new FastVector(bagSize * maxSliceLength + 1)
    for (
      sliceLength <- 0 until maxSliceLength;
      clusterIndex <- 0 until bagSize
    ) yield {
      attributes.addElement(
        new Attribute("L%02dC%02d" format (sliceLength, clusterIndex)))
    }
    val possibleBeatlesVals = new FastVector(2)
    possibleBeatlesVals.addElement("T")
    possibleBeatlesVals.addElement("F")
    val beatlesAttrib = new Attribute("isBeatles", possibleBeatlesVals)
    attributes.addElement(beatlesAttrib)

    // Build the instances
    val unifiedHistograms = new Instances("histograms", attributes, 0)
    unifiedHistograms.setClass(beatlesAttrib)
    for (song <- histograms(0).keys) {
      val instance = new Instance(bagSize * maxSliceLength + 1)
      instance.setDataset(unifiedHistograms)
      for (
        (histogram, sliceIndex) <- histograms.zipWithIndex;
        clusterIndex <- 0 until bagSize
      ) {
        instance.setValue(sliceIndex * bagSize + clusterIndex,
          histogram(song).getOrElse(clusterIndex, 0).toDouble)
      }
      instance.setValue(maxSliceLength * bagSize,
        if (song.matches("^The Beatles.*$")) "T" else "F")

      unifiedHistograms.add(instance)
    }
    
    unifiedHistograms
  }

  def makeHistograms(songToCluster: Iterable[(String, Int)]) =
    songToCluster.foldLeft(Map[String, Map[Int, Int]]())(
      (acc: Map[String, Map[Int, Int]], songCluster) => {
        val (song, cluster) = songCluster
        val histogram = acc.getOrElse(song, Map[Int, Int]())
        acc.updated(song,
          histogram.updated(cluster, histogram.getOrElse(cluster, 0) + 1))
      })

  // For several cluster counts
  for (bagSize <- 100 to 400 by 50) {
    // Using the training set
    // For several slice lengths
    val clusterInfo =
      for (sliceLength <- 1 to maxSliceLength) yield {
        // Split songs into overlapping slices for clustering
        val slices = sliceSongs(data.trainingSet, sliceLength)

        // Cluster the slices
        val instances = convertSlicesToInstances(slices, sliceLength)

        val clusterer = new SimpleKMeans
        clusterer.setSeed(10)
        clusterer.setPreserveInstancesOrder(true)
        clusterer.setNumClusters(bagSize)
        clusterer.buildClusterer(instances)

        // Find the centroid of all the clusters
        val centroids = for (i <- 0 until bagSize) yield {
          clusterer.getClusterCentroids().instance(i).toDoubleArray()
        }

        val songSliceToCluster =
          for (((slice, song), cluster) <- slices zip clusterer.getAssignments())
            yield (song, cluster)

        // For each song, make a histogram of how each song's slices cluster
        val histograms = makeHistograms(songSliceToCluster)

        // Return the information needed to cluster more data, as well as the
        //  clustering histogram.
        (clusterer, centroids, histograms)
      }

    // For every song, combine the histograms for each slice length into a 
    //   single vector.
    val trainingHistograms = combineHistograms(clusterInfo.map(_._3), bagSize)

    // Train a support vector machine to recognize which songs match our
    //   criteria.
    val classifier = new SMO
    classifier.buildClassifier(trainingHistograms)

    // Using the test set
    // For several slice lengths
    val perSliceTestHistograms = for (sliceLength <- 1 to maxSliceLength) yield {
      // Split songs into overlapping slices for clustering
      val slices = sliceSongs(data.testSet, sliceLength).toSeq
      // Use clustering data from training to cluster slices
      val instances = convertSlicesToInstances(slices, sliceLength)
      makeHistograms(for (index <- 0 until instances.numInstances()) yield
      	(slices(index)._2, clusterInfo(sliceLength)._1
      	    .clusterInstance(instances.instance(index))))
    }
    // Construct a histogram of clusters for each song
    val testHistograms = combineHistograms(perSliceTestHistograms, bagSize) 
    // Classify song histograms using trained SVM and check accuracy
    val eval = new Evaluation(trainingHistograms)
    val output = new java.lang.StringBuffer
    eval.evaluateModel(classifier, testHistograms)
    println("-" * 80)
    println("Bag size: " ++ bagSize.toString)
    println(eval.toSummaryString)
  }
}