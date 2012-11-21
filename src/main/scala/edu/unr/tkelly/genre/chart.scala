package edu.unr.tkelly.genre

import com.echonest.api.v4._
import scala.collection.JavaConversions._
import breeze.plot._
import breeze.linalg.DenseMatrix
import scala.Array.canBuildFrom
import scala.actors.Futures.future
import scala.collection.mutable.ListBuffer

object Chart extends App {
  // Draw a plot of a song
  def plotSong(plot: Plot, song: Song) = {
    // Get the features
    val features = song.getAnalysis().getSegments().toArray(manifest[Segment])
    
    // Get a max and min for timber to normalize this song
    val timbres =
      (for (
        segment <- features
      ) yield segment.getTimbre).transpose.map(_.sortWith(_ < _))
      
    val timbreMin = for (timbreFeature <- timbres)
      yield timbreFeature(timbreFeature.size/100)
    val timbreMax = for (timbreFeature <- timbres)
      yield timbreFeature(99*timbreFeature.size/100)

    // Calculate lookup tables to show the data in 1/10s intervals
    val timesToSegments =
      for (
        i <- 0 until features.length-1;
        _ <- Array.fill((features(i+1).getStart * 100).toInt -
          (features(i).getStart * 100).toInt) {}
      ) yield i

    // Put the data into a data structure made for drawing
    val featureMat = DenseMatrix.tabulate[Double](24, timesToSegments.length)(
      (r: Int, c: Int) => {
        val segNum = timesToSegments(c)
        if (r < 12) {
          ((features(segNum).getTimbre()(r) - timbreMin(r))/
           (timbreMax(r) - timbreMin(r)))
        } else {
          features(segNum).getPitches()(r - 12)
        }
      })
    // Set the title
    plot.title = song.getArtistName() ++ " - " ++
      song.getTitle()
    // Draw the features
    plot += image(featureMat, new GradientPaintScale(0, 1, PaintScale.Rainbow))
  }
  def plotSongFromStyle(plot: Plot, style: String) = {
    val query = new PlaylistParams

    // Compose a query
    query.add("style", style)
    query.add("bucket", "audio_summary")
    query.add("type", "artist-description")

    // Get the features
    val song = Util.getSongs(query, 1).head
    plotSong(plot, song)
  }
  
  val fig = Figure()
  
  val params = new PlaylistParams
  
  params.addArtist("The Beatles")
  params.setType(PlaylistParams.PlaylistType.ARTIST)
  params.add("bucket", "audio_summary")
  
  val beatlesSongs = Util.getSongs(params, 3).toSeq

  plotSong(fig.subplot(3, 2, 0), beatlesSongs(0))
  plotSong(fig.subplot(3, 2, 2), beatlesSongs(1))
  plotSong(fig.subplot(3, 2, 4), beatlesSongs(2)) 
  
  val params2 = new PlaylistParams
  for (style <- List("classical", "metal", "pop", "hiphop", "rock", "jazz")) {
    params2.addStyle(style)
  }
  params2.setType(PlaylistParams.PlaylistType.ARTIST_DESCRIPTION)
  params2.add("bucket", "audio_summary")
  val otherSongs = Util.getSongs(params2, 3).toSeq
  
  plotSong(fig.subplot(3, 2, 1), otherSongs(0))
  plotSong(fig.subplot(3, 2, 3), otherSongs(1))
  plotSong(fig.subplot(3, 2, 5), otherSongs(2))
}

