package edu.unr.tkelly.genre

import scala.util.Random.shuffle
import com.echonest.api.v4._
import scala.collection.JavaConversions._
import weka.clusterers.SimpleKMeans
import weka.core.{ Instance, Instances, Attribute, FastVector }
import weka.classifiers.functions.SMO
import weka.classifiers.Evaluation
import scala.collection.GenSeq
import scala.collection.parallel.immutable.ParVector
import scala.util.Random._
import scala.collection.mutable
import scala.math.{ sqrt, pow }

object BoFExperiment extends App {
  // Get data
  val data = new BeatlesData(100, 50)

  // Print out the data
  println("Training set:")
  for ((song, _) <- data.trainingSet) {
    println(Util.songToShortString(song))
  }
  println("Test set:")
  for ((song, _) <- data.testSet) {
    println(Util.songToShortString(song))
  }

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

  def combineHistograms(histograms: GenSeq[Map[String, Map[Int, Int]]],
    bagSizes: GenSeq[Int]) = {
    // Make the attributes
    val attributes = new FastVector(bagSizes.sum + 1)
    for (
      (bagSize, sliceIndex) <- bagSizes.zipWithIndex;
      clusterIndex <- 0 until bagSize
    ) yield {
      attributes.addElement(
        new Attribute("L%02dC%02d" format (sliceIndex + 1, clusterIndex)))
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
      val instance = new Instance(bagSizes.sum + 1)
      instance.setDataset(unifiedHistograms)
      for (
        (histogram, sliceIndex) <- histograms.zipWithIndex;
        clusterIndex <- 0 until bagSizes(sliceIndex)
      ) {
        instance.setValue(bagSizes.take(sliceIndex).sum + clusterIndex,
          histogram(song).getOrElse(clusterIndex, 0).toDouble)
      }
      instance.setValue(bagSizes.sum,
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

  def evalBagSizes(bagSizes: GenSeq[Int]) = {
    // Using the training set
    // For several slice lengths
    val clusterInfo =
      for ((bagSize, sliceIndex) <- bagSizes.zipWithIndex) yield {
        // Split songs into overlapping slices for clustering
        val slices = sliceSongs(data.trainingSet, sliceIndex + 1)

        // Cluster the slices
        val instances = convertSlicesToInstances(slices, sliceIndex + 1)

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
    val trainingHistograms = combineHistograms(clusterInfo.map(_._3), bagSizes)

    // Train a support vector machine to recognize which songs match our
    //   criteria.
    val classifier = new SMO
    classifier.buildClassifier(trainingHistograms)

    // Using the test set
    // For several slice lengths
    val perSliceTestHistograms =
      for (sliceLength <- 1 to bagSizes.size) yield {
        // Split songs into overlapping slices for clustering
        val slices = sliceSongs(data.testSet, sliceLength).toSeq
        // Use clustering data from training to cluster slices
        val instances = convertSlicesToInstances(slices, sliceLength)
        makeHistograms(for (index <- 0 until instances.numInstances()) yield (slices(index)._2, clusterInfo(sliceLength - 1)._1
          .clusterInstance(instances.instance(index))))
      }
    // Construct a histogram of clusters for each song
    val testHistograms = combineHistograms(perSliceTestHistograms, bagSizes)
    // Classify song histograms using trained SVM and check accuracy
    val eval = new Evaluation(trainingHistograms)
    val output = new java.lang.StringBuffer
    eval.evaluateModel(classifier, testHistograms)

    // Print info
    println("-" * 80)
    println("Bag size: " ++ bagSizes.toString)
    println()
    for (sliceLength <- 1 to bagSizes.size) {
      println(sliceLength.toString ++ "-segment clusterer:")
      println(clusterInfo(sliceLength - 1)._1.toString)
    }

    println(classifier.toString)
    println("Test results:")
    println(eval.toSummaryString)

    eval.pctCorrect() / 100.0
  }

  def time[R](block: => R): R = {
    val t0 = System.nanoTime()
    val result = block // call-by-name
    val t1 = System.nanoTime()
    println("Elapsed time: " + (t1 - t0) + "ns")
    result
  }

  // Genetic algorithm
  // GA paramters
  val popSize = 4 // This is set so that one genome will run on each processor

  // Initialization for GA
  var bestGenome: GenSeq[Int] = GenSeq[Int]()
  var bestFitness = 0.0
  var population: GenSeq[GenSeq[Int]] = ParVector.fill(popSize) {
    Seq.fill(4) {
      nextInt(199) + 1
    }
  }
  var generation = 0

  def mutate(pop: GenSeq[GenSeq[Int]]) = {
    for (genome <- pop) yield {
      for (gene <- genome) yield {
        val newGene = gene + (nextGaussian() * 6).toInt
        if (newGene < 2)
          2
        else
          newGene
      }
    }
  }
  def crossover(parent1: GenSeq[Int], parent2: GenSeq[Int]) = {
    val splitPoint = nextInt(parent1.size.min(parent2.size))
    val splitP1 = parent1.splitAt(splitPoint)
    val splitP2 = parent2.splitAt(splitPoint)
    (splitP1._1 ++ splitP2._2, splitP2._1 ++ splitP1._2)
  }
  def selection(pop: GenSeq[GenSeq[Int]], fitnesses: GenSeq[Double]) = {
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
    val randomNum = nextDouble() * scaledFitnesses.sum
    def select(num: Double, pop: GenSeq[(GenSeq[Int], Double)]): GenSeq[Int] = {
      if (num - pop.head._2 > 0.0)
        select(num - pop.head._2, pop.tail)
      else
        pop.head._1
    }
    select(randomNum, pop zip scaledFitnesses)
  }

  val memoizedFitness = mutable.HashMap[GenSeq[Int], Double]()

  // The actual evolution loop
  while (true) {
    // Fitness evaluation
    val fitnesses = population.par map (genome =>
      memoizedFitness.getOrElse(genome, evalBagSizes(genome)))
    memoizedFitness ++= population.zip(fitnesses).toStream

    // Keep track of the best
    if (fitnesses.max > bestFitness) {
      bestFitness = fitnesses.max
      bestGenome = population.maxBy(memoizedFitness)
    }

    // Print status
    println()
    println("Generation " ++ generation.toString ++ ":")
    println("Best genome: " ++ population.maxBy(memoizedFitness).toString)
    println("Best fitness: " ++ fitnesses.max.toString)
    println("Average fitness: " ++ (fitnesses.sum / fitnesses.size).toString)
    println("Worst fitness: " ++ fitnesses.min.toString)
    println()
    println("All time:")
    println("Best genome: " ++ bestGenome.toString)
    println("Best fitness: " ++ bestFitness.toString)

    // Evolution stuff
    population =
      mutate(
        (ParVector.fill(popSize / 2) {
          val children = crossover(
            selection(population, fitnesses),
            selection(population, fitnesses))
          List(children._1, children._2)
        }).flatten)

    generation += 1
  }
}
