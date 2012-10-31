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

object Util {
  val api = new EchoNestAPI("KRPNFJRX9QKTVBG70")

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
}