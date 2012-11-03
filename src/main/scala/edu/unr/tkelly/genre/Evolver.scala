package edu.unr.tkelly.genre

import javolution.xml.stream.XMLStreamException

import com.xtructure.xevolution.evolution.EvaluationStrategy
import com.xtructure.xevolution.evolution.impl.AbstractEvaluationStrategy
import com.xtructure.xevolution.genetics.Genome
import com.xtructure.xevolution.operator.CrossoverOperator
import com.xtructure.xevolution.operator.MutateOperator
import com.xtructure.xneat.evolution.config.impl.NEATEvolutionConfigurationImpl
import com.xtructure.xneat.evolution.impl.NEATEvolutionStrategyImpl
import com.xtructure.xneat.evolution.impl.NEATReproductionStrategyImpl
import com.xtructure.xneat.evolution.impl.NEATSpeciationStrategyImpl
import com.xtructure.xneat.evolution.impl.NEATSurvivalFilterImpl
import com.xtructure.xneat.genetics.GeneMap
import com.xtructure.xneat.genetics.impl.NEATGeneticsFactoryImpl
import com.xtructure.xneat.genetics.impl.NEATGenomeDecoder
import com.xtructure.xneat.network.NeuralNetwork
import com.xtructure.xneat.operators.impl.AddLinkMutateOperator
import com.xtructure.xneat.operators.impl.AddNodeMutateOperator
import com.xtructure.xneat.operators.impl.AdjustAttributesMutateOperator
import com.xtructure.xneat.operators.impl.NEATCrossoverOperatorSelecterImpl
import com.xtructure.xneat.operators.impl.NEATMutateOperatorSelecterImpl
import com.xtructure.xneat.operators.impl.RemoveLinkMutateOperator
import com.xtructure.xneat.operators.impl.StandardCrossoverOperator
import com.xtructure.xutil.coll.MapBuilder
import com.xtructure.xutil.id._

import scala.collection.JavaConversions._

import com.echonest.api.v4._

import scala.math._

object BeatlesEvaluationStrategy
  extends AbstractEvaluationStrategy[GeneMap, NeuralNetwork](
      NEATGenomeDecoder.getInstance())
  with EvaluationStrategy[GeneMap, NeuralNetwork] {
  val setSize = 100
  val testSize = 10
  
  private val matchParams = new PlaylistParams
  
  matchParams.addArtist("The Beatles")
  matchParams.setType(PlaylistParams.PlaylistType.ARTIST)
  matchParams.add("bucket", "audio_summary")
  
  println("Getting Beatles songs...")
  private val matchingSongs = Util.getSongs(matchParams, setSize/2) 
  
  // Pick a wide variety of genres to compare with The Beatles
  private val notParams = new PlaylistParams
  for (style <- List("classical", "metal", "pop", "hiphop", "rock", "jazz")) {
    notParams.addStyle(style)
  }
  // Show names
  for (song <- matchingSongs.par) {
    println(song.getArtistName ++ " - " ++ song.getTitle)
  }
  
  notParams.setType(PlaylistParams.PlaylistType.ARTIST_DESCRIPTION)
  notParams.add("bucket", "audio_summary")
  
  // Let's make sure that none of the found songs are by The Beatles
  println("Getting non-Beatles songs...")
  private var notSongs: Set[Song] = Set[Song]()
  do {
    notSongs ++= Util.getSongs(notParams, setSize/2 - notSongs.size)
    notSongs = notSongs.filter(song => song.getArtistName != "The Beatles")
  } while (notSongs.size < setSize/2)

  // Show names
  for (song <- notSongs.par) {
    println(song.getArtistName ++ " - " ++ song.getTitle)
    song.getAnalysis()
  }
  
  private val (trainingMatches, testMatches) = matchingSongs.splitAt((setSize - 
      testSize)/2)
  private val (trainingNot, testNot) = notSongs.splitAt((setSize-testSize)/2)
  
  val trainingSet = 
    (trainingMatches.zip(Stream.continually {1.0}) ++
     trainingNot.zip(Stream.continually {0.0})).toMap
  val testSet = 
    (testMatches.zip(Stream.continually {1.0}) ++
     testNot.zip(Stream.continually {0.0})).toMap

  def calcFitness(sampleSet: Map[Song, Double], genome: Genome[GeneMap]) = {
    val network = getGenomeDecoder().decode(genome)
    println("Evaluating " ++ genome.getId.toString)

    // Calculate the RMS error for the network across all songs in the set
    (1.0 - (
      (
        for ((song, matches) <- sampleSet.par) yield {
          var input = Array[Double](30)
          var timbre: Array[Double] = null
          var pitch: Array[Double] = null
          var currentSegment: Segment = null
          var state: Array[Double] = null
          val segmentsIter = song.getAnalysis().getSegments().iterator()
          
          // Clear the network
          network.clearSignals()
          // Feed the whole song into the NN
          // Note that this bit is painfully imperative because performance is
          // key
          var i = 0
          while (segmentsIter.hasNext) {
            i = 0
            currentSegment = segmentsIter.next()
            
            while (i < 12) {
              input(i) = currentSegment.getTimbre()(i)
              i += 1
            }
            
            while (i < 24) {
              input(i) = currentSegment.getPitches()(i-12)/100.0
              i += 1
            }
            
            input(24) = currentSegment.getDuration
            i += 1
            
            state = network.getOutputSignals
            while (i < 30) {
              input(i) = state(i-24)
            }
            
            network.setInputSignals(input)
            network.singleStep()
          }
          // Calculate match or not
          abs(network.getOutputSignals()(0) - matches)
        }).sum.toFloat / trainingSet.size))
  }
  
  override def simulate(genome: Genome[GeneMap]): Double = {
    if (genome.getEvaluationCount == 0) {
      // Calculate the RMS error for the network across all songs
      genome.setAttribute(Genome.FITNESS_ATTRIBUTE_ID,
        calcFitness(trainingSet, genome).asInstanceOf[java.lang.Double])
    }
    genome.incrementEvaluationCount()
    genome.getFitness
  }
  
  /*private def processSegment(network: NeuralNetwork, s: Segment) = {
    val state = network.getOutputSignals().slice(1, 6)
    network.setInputSignals(
        (s.getTimbre ++ s.getPitches.map(_/100.0) ++
            Array[Double](s.getDuration) ++ state))
    network.singleStep()
  }*/
}

object ArtistEvolver extends App {
  // specify parameters
  val evolutionFieldMap = NEATEvolutionConfigurationImpl
    .builder(XId.newId("xor.neat.evolution.config")) 
    .setPopulationSize(10) 
    .setMutationProbability(0.5) 
    .setInputNodeCount(30) 
    .setOutputNodeCount(6) 
    .setBiasNodeCount(10)
    .newInstance().newFieldMap()
  // define operator distribution and build reproduction strategy
  val geneticsFactory = new NEATGeneticsFactoryImpl(evolutionFieldMap)
  val mutateOperatorSelecter = new NEATMutateOperatorSelecterImpl( 
    new MapBuilder[MutateOperator[GeneMap], java.lang.Double]() 
      .put( 
        new AddLinkMutateOperator(geneticsFactory), 
        0.3) 
      .put( 
        new AddNodeMutateOperator(geneticsFactory), 
        0.2) 
      .put( // mutate few links, one parameter at random
        new AdjustAttributesMutateOperator( 
          true, false, 
          0.0, 0.5, geneticsFactory), 
        0.95) 
      .put( 
        new RemoveLinkMutateOperator(geneticsFactory), 
        0.04) 
      .newImmutableInstance())
  val crossoverOperatorSelecter = new NEATCrossoverOperatorSelecterImpl( 
    new MapBuilder [ CrossoverOperator [ GeneMap ], java.lang.Double ] () 
      .put( 
        new StandardCrossoverOperator(geneticsFactory), 
        1.0) 
      .newImmutableInstance())
  val reproductionStrategy = new NEATReproductionStrategyImpl(
    evolutionFieldMap,
    geneticsFactory,
    crossoverOperatorSelecter,
    mutateOperatorSelecter)
  // create survival filter
  val survivalFilter = new NEATSurvivalFilterImpl(evolutionFieldMap)
  // create speciation strategy
  val speciationStrategy = new NEATSpeciationStrategyImpl(evolutionFieldMap)
  // create evolution strategy
  println("create evolution strategy...")
  val evolutionStrategy = new NEATEvolutionStrategyImpl[NeuralNetwork](
    evolutionFieldMap,
    reproductionStrategy,
    BeatlesEvaluationStrategy,
    survivalFilter,
    speciationStrategy, null)
  // start evolution
  val population = geneticsFactory.createPopulation(0)
  evolutionStrategy.start(population)
  println(population.getHighestGenomeByAttribute(Genome.FITNESS_ATTRIBUTE_ID).getData())
}