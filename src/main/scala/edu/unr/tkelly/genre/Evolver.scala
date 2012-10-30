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
import com.xtructure.xutil.id.XId

object BeatlesEvaluationStrategy
  extends AbstractEvaluationStrategy[GeneMap, NeuralNetwork](
      NEATGenomeDecoder.getInstance())
  with EvaluationStrategy[GeneMap, NeuralNetwork] {
  override def simulate(genome : Genome[GeneMap]) : Double = {
    return 0.0
  }
}

object ArtistEvolver extends App {
  // specify parameters
  val evolutionFieldMap = NEATEvolutionConfigurationImpl
    .builder(XId.newId("xor.neat.evolution.config")) 
    .setPopulationSize(100) 
    .setMutationProbability(0.5) 
    .setInputNodeCount(2) 
    .setOutputNodeCount(1) 
    .setBiasNodeCount(0) 
    .newInstance().newFieldMap()
  // define operator distribution and build reproduction strategy
  val geneticsFactory = new NEATGeneticsFactoryImpl(evolutionFieldMap)
  val mutateOperatorSelecter = new NEATMutateOperatorSelecterImpl( 
    new MapBuilder[MutateOperator[GeneMap], java.lang.Double]() 
      .put( 
        new AddLinkMutateOperator(geneticsFactory), 
        0.01) 
      .put( 
        new AddNodeMutateOperator(geneticsFactory), 
        0.02) 
      .put( // mutate few links, one parameter at random
        new AdjustAttributesMutateOperator( 
          true, false, 
          0.0, 0.5, geneticsFactory), 
        0.95) 
      .put( 
        new RemoveLinkMutateOperator(geneticsFactory), 
        0.01) 
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
  // create evaluationStrategy
  val evaluationStrategy = BeatlesEvaluationStrategy
  // create survival filter
  val survivalFilter = new NEATSurvivalFilterImpl(evolutionFieldMap)
  // create speciation strategy
  val speciationStrategy = new NEATSpeciationStrategyImpl(evolutionFieldMap)
  // create evolution strategy
  val evolutionStrategy = new NEATEvolutionStrategyImpl[NeuralNetwork](
    evolutionFieldMap,
    reproductionStrategy,
    evaluationStrategy,
    survivalFilter,
    speciationStrategy, null)
  // start evolution
  val population = geneticsFactory.createPopulation(0)
  evolutionStrategy.start(population)
  println(population.getHighestGenomeByAttribute(Genome.FITNESS_ATTRIBUTE_ID).getData())
}