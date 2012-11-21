package edu.unr.tkelly.genre

import com.echonest.api.v4._
import scala.collection.JavaConversions._
import breeze.plot._
import breeze.linalg.DenseMatrix
import scala.reflect.Manifest
import scala.collection.parallel._
import scala.actors.Futures.future
import scala.concurrent.forkjoin.ForkJoinPool
import scala.annotation.tailrec
import scala.util.control.Exception.allCatch
import scala.math._
import scala.collection.SeqLike

object Util {
  val api = new EchoNestAPI("KRPNFJRX9QKTVBG70")
  val verbose = false

  // Gets any number of unique songs following specified parameters
  def getSongs(params: PlaylistParams, qty: Int) = {
    val sessionID = api.createDynamicPlaylist(params).getSession
    val needFeatures = params.getMap()("bucket") == "audio_summary"

    // Gets one song, however many tries it takes
    @tailrec
    def getSong(): Song =
      // Wrap the call in an Either, which is pretty much functional exceptions
      allCatch.either {
        val song = api.getNextInDynamicPlaylist(sessionID).getSongs().get(0)
        if (needFeatures)
          song.getAnalysis()
        if (verbose)
          println("Downloading " ++ song.getArtistName ++ " - " ++ song.getTitle)
        song
      } match {
        case Left(_) => {
          // If anything bad happens, wait one second
          Thread.sleep(1000)
          // And try again
          getSong()
        }
        // If if works, give us the results 
        case Right(x) => x
      }

    // Gets all of the unique songs needed
    /* Note that the reason that we need all of this fanciness is that,
     * when we grab songs in parallel, we may get duplicate songs.
     */
    @tailrec
    def getUniqueSongs(accum: Set[Song]): Set[Song] = {
      if (accum.size >= qty) {
        // If we have enough, return the songs
        accum
      } else {
        // In parallel, try to get however many songs we are short
        val songs = for (_ <- (1 to (qty - accum.size)).par) yield getSong()

        // Hold on to the union of the songs already obtained and the songs
        // we just got
        getUniqueSongs(accum ++ songs)
      }
    }

    getUniqueSongs(Set())
  }

  def songToShortString(s: Song) = {
    s.getArtistName ++ " - " ++ s.getTitle
  }
  
  def timbreStatistics(songs: Array[Song]) = {
	  def songStatistics(s: Song) = {
	    // Get the features
	    val features = s.getAnalysis().getSegments().toArray(manifest[Segment])
	    
	    val timbres =
	      (for (
	        segment <- features
	      ) yield segment.getTimbre).transpose.map(_.sortWith(_ < _))
	      
	    val timbreLowerPercentile = for (timbreFeature <- timbres)
	      yield timbreFeature(timbreFeature.size/100)
	    val timbreUpperPercentile = for (timbreFeature <- timbres)
	      yield timbreFeature(99*timbreFeature.size/100)
	    val timbreMean = for (timbreFeature <- timbres)
	      yield timbreFeature.sum/timbreFeature.size
	    val timbreMedian = for (timbreFeature <- timbres)
	      yield timbreFeature(timbreFeature.size/2)
	    val timbreUpperQuartile = for (timbreFeature <- timbres)
	      yield timbreFeature(3*timbreFeature.size/4)
	    val timbreLowerQuartile = for (timbreFeature <- timbres)
	      yield timbreFeature(timbreFeature.size/4)
	    val timbreMax = timbres.map(_.last)
	    val timbreMin = timbres.map(_.head)
	    val timbreStdDev = 
	      (for ((timbreFeature, mean) <- timbres.zip(timbreMean)) yield
	        timbreFeature.map(x => pow(x - mean, 2)))
		  .map(x => sqrt(x.sum / (x.size-1)))
	    
	    Array(timbreLowerPercentile,
	        timbreUpperPercentile,
	        timbreMean,
	        timbreMedian,
	        timbreUpperQuartile,
	        timbreLowerQuartile,
	        timbreMax,
	        timbreMin,
	        timbreStdDev)
	  }
  
	def median[B: Fractional](seq: scala.collection.Seq[B]) = {
	  val sortedSeq = seq.sorted
	  if (seq.size % 2 == 0)
	    implicitly[Fractional[B]].div(
		    implicitly[Fractional[B]].plus(
		        seq(seq.size/2),
		        seq(seq.size/2+1)),
		    implicitly[Fractional[B]].fromInt(2))
	  else
	    seq(seq.size/2)
	}
	
	// Find the median of each statistic by
	// Getting each song's stats
    val songTimbreStatArray = songs.map(songStatistics(_))
    // Make the first dimension statistic, the second timbre, and the third
    //  song
    .transpose.map(_.transpose)
    // For every statistic, in every timbre, find the median across the songs
    .map(t => t.map(s => median(s))).map(_.toSeq)
    
    Map("Lower Percentile" -> songTimbreStatArray(0),
        "Upper Percentile" -> songTimbreStatArray(1),
        "Mean" -> songTimbreStatArray(2),
        "Median" -> songTimbreStatArray(3),
        "Upper Quartile" -> songTimbreStatArray(4),
        "Lower Quartile" -> songTimbreStatArray(5),
        "Maximum" -> songTimbreStatArray(6),
        "Minimum" -> songTimbreStatArray(7),
        "Standard Deviation" -> songTimbreStatArray(8))
  }
}