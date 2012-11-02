package edu.unr.tkelly.genre

import java.io.File
import java.util.Collections
import com.xtructure.xneat.evolution.impl.AbstractNEATEvolutionExperiment
import com.xtructure.xevolution.evolution.EvaluationStrategy
import com.xtructure.xneat.evolution.NEATSpeciationStrategy
import com.xtructure.xevolution.genetics.GenomeDecoder
import com.xtructure.xneat.genetics.GeneMap
import com.xtructure.xneat.network.NeuralNetwork
import com.xtructure.xutil.opt.XOption
import com.xtructure.xutil.opt.BooleanXOption
import com.xtructure.xutil.valid.ValidateUtils.or

import com.xtructure.xevolution.config.EvolutionFieldMap
import com.xtructure.xevolution.evolution.EvaluationStrategy
import com.xtructure.xevolution.evolution.EvolutionStrategy
import com.xtructure.xevolution.evolution.ReproductionStrategy
import com.xtructure.xevolution.evolution.SurvivalFilter
import com.xtructure.xevolution.genetics.GeneticsFactory
import com.xtructure.xevolution.genetics.Genome
import com.xtructure.xevolution.genetics.GenomeDecoder
import com.xtructure.xevolution.genetics.Population
import com.xtructure.xevolution.gui.XEvolutionGui
import com.xtructure.xevolution.operator.CrossoverOperator
import com.xtructure.xevolution.operator.MutateOperator
import com.xtructure.xevolution.operator.OperatorSelecter
import com.xtructure.xevolution.tool.data.DataTracker
import com.xtructure.xneat.evolution.NEATSpeciationStrategy
import com.xtructure.xneat.evolution.impl.AbstractNEATEvolutionExperiment
import com.xtructure.xneat.evolution.impl.NEATEvolutionStrategyImpl
import com.xtructure.xneat.evolution.impl.NEATReproductionStrategyImpl
import com.xtructure.xneat.evolution.impl.NEATSpeciationStrategyImpl
import com.xtructure.xneat.evolution.impl.NEATSurvivalFilterImpl
import com.xtructure.xneat.genetics.GeneMap
import com.xtructure.xneat.genetics.impl.NEATGeneticsFactoryImpl
import com.xtructure.xneat.genetics.impl.NEATGenomeDecoder
import com.xtructure.xneat.genetics.link.config.LinkGeneConfiguration
import com.xtructure.xneat.genetics.node.config.NodeGeneConfiguration
import com.xtructure.xneat.network.NeuralNetwork
import com.xtructure.xneat.operators.impl.AddLinkMutateOperator
import com.xtructure.xneat.operators.impl.AddNodeMutateOperator
import com.xtructure.xneat.operators.impl.AdjustAttributesMutateOperator
import com.xtructure.xneat.operators.impl.NEATCrossoverOperatorSelecterImpl
import com.xtructure.xneat.operators.impl.NEATMutateOperatorSelecterImpl
import com.xtructure.xneat.operators.impl.RemoveLinkMutateOperator
import com.xtructure.xneat.operators.impl.StandardCrossoverOperator
import com.xtructure.xutil.Range
import com.xtructure.xutil.coll.MapBuilder
import com.xtructure.xutil.opt._
import com.xtructure.xutil.valid.Condition
import scala.collection.JavaConversions._
import com.xtructure.xneat.evolution.config.impl.NEATEvolutionConfigurationImpl
import com.xtructure.xutil.id._
import org.apache.commons.cli.Options

object BeatlesExperiment
  extends AbstractNEATEvolutionExperiment[GeneMap, NeuralNetwork](
    (Collections.singleton(
        (new BooleanXOption("gui",
          "g",
          "gui",
          "show gui")).asInstanceOf[XOption[java.lang.Boolean]] ))) {
  
  def main(args: Array[String]) = {
    this.setArgs(args)
    this.startExperiment()
    
    val fittest =
      this.getPopulation()
      .getHighestGenomeByAttribute(Genome.FITNESS_ATTRIBUTE_ID)
    
    println(fittest.getData())
  }

  override protected def createSpeciationStrategy(): NEATSpeciationStrategy[GeneMap] = {
    new NEATSpeciationStrategyImpl(getEvolutionFieldMap())
  }

  override def startExperiment(): Unit = {
    
    
    val tracker = DataTracker.newInstance()
    val gui = new XEvolutionGui("Beatles Experiment", null, tracker)
    //tracker.
    println(getOutputDir())
    
    /*val opt = new FileXOption("output directory", "o", "outputDir",
				"directory to which population xml files are output")
    val opts = new Options
    opts.addOption(opt)
    
    XOption.parseArgs(opts, Array[String]("--outputDir", getOption("outputDir").getValue))
    gui.watchDir()*/
    
    //tracker.load(new File(getOutputDir().toString))
    
    super.startExperiment()
  }

  override protected def createGenomeDecoder(): GenomeDecoder[GeneMap, NeuralNetwork] = { 
    NEATGenomeDecoder.getInstance()
  }

  override protected def createGeneticsFactory(): GeneticsFactory[GeneMap] = {
    new NEATGeneticsFactoryImpl(
      getEvolutionFieldMap(),
      LinkGeneConfiguration.builder(null)
        .setWeight(Range.getInstance(-10.0, 10.0), Range.getInstance(1.0))
        .newInstance(),
      NodeGeneConfiguration.builder(null)
        .setActivation(Range.getInstance(-10.0, 10.0), Range.getInstance(2.0))
        .newInstance())
  }

  override protected def createReproductionStrategy(): ReproductionStrategy[GeneMap] = {
    val mutateOperatorSelecter = new NEATMutateOperatorSelecterImpl(
      Map[MutateOperator[GeneMap], java.lang.Double](
        new AddLinkMutateOperator(getGeneticsFactory()) ->
          0.20,
        new AddNodeMutateOperator(getGeneticsFactory()) ->
          0.20,
        new AdjustAttributesMutateOperator(
          true, false,
          1.0, 1.0, getGeneticsFactory()) ->
          0.8,
        new RemoveLinkMutateOperator(getGeneticsFactory()) ->
          0.3))
    val crossoverOperatorSelecter =
      new NEATCrossoverOperatorSelecterImpl(
        Map[CrossoverOperator[GeneMap], java.lang.Double](
          new StandardCrossoverOperator(getGeneticsFactory()) ->
            1.0));
    return new NEATReproductionStrategyImpl( //
      getEvolutionFieldMap(), //
      getGeneticsFactory(), //
      crossoverOperatorSelecter, //
      mutateOperatorSelecter);
  }

  override protected def createEvaluationStrategy() = { 
    BeatlesEvaluationStrategy
  }

  override protected def createSurvivalFilter() = { 
    new NEATSurvivalFilterImpl(getEvolutionFieldMap())
  }

  override protected def createEvolutionStrategy() = { 
    new NEATEvolutionStrategyImpl[NeuralNetwork](
				getEvolutionFieldMap(),
				getReproductionStrategy(),
				getEvaluationStrategy(),
				getSurvivalFilter(),
				getSpeciationStrategy(),
				getOutputDir());
  }

  override protected def createEvolutionFieldMap() = {
    NEATEvolutionConfigurationImpl
    .builder(XId.newId("beatles.config")) 
    .setPopulationSize(100) 
    .setMutationProbability(0.5) 
    .setInputNodeCount(25) 
    .setOutputNodeCount(1) 
    .setBiasNodeCount(0) 
    .newInstance().newFieldMap()
  }

}