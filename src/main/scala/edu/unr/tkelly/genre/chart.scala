package edu.unr.tkelly.genre

import com.echonest.api.v4._
import scala.collection.JavaConversions._
import breeze.plot._
import breeze.linalg.DenseMatrix
import scala.Array.canBuildFrom

object Chart extends App {
  // Draw a plot of a random song of the specified style
  def plotSongFromStyle(plot: Plot, style: String) = {
    val query = new PlaylistParams

    // Compose a query
    query.add("style", style)
    query.add("bucket", "audio_summary")
    query.add("type", "artist-description")

    // Get the features
    val song = Util.getSongs(query, 1).head
    val features = song.getAnalysis().getSegments().toArray(manifest[Segment])
    
    // Get a max and min for timber to normalize this song
    val timbres =
      for (
        segment <- features;
        tFeature <- segment.getTimbre
      ) yield tFeature
    val timbreMin = timbres.min
    val timbreMax = timbres.max

    // Calculate lookup tables to show the data in 1/10s intervals
    val timesToSegments =
      for (
        i <- 0 until features.length-1;
        _ <- Array.fill((features(i+1).getStart * 100).toInt -
          (features(i).getStart * 100).toInt) {}
      ) yield i

    // Put the data into a data structure made for drawing
    val featureMat = DenseMatrix.tabulate(24, timesToSegments.length)(
      (r: Int, c: Int) => {
        val segNum = timesToSegments(c)
        if (r < 12) {
          (features(segNum).getTimbre()(r) - timbreMin)/ (timbreMax - timbreMin)
        } else {
          features(segNum).getPitches()(r - 12)
        }
      })
    // Set the title
    plot.title = style ++ " - " ++ song.getArtistName() ++ " - " ++
      song.getTitle()
    // Draw the features
    plot += image(featureMat, new GradientPaintScale(0, 1, PaintScale.Rainbow))
  }
  
  val fig = Figure()

  plotSongFromStyle(fig.subplot(3, 2, 0), "classical")
  plotSongFromStyle(fig.subplot(3, 2, 2), "classical")
  plotSongFromStyle(fig.subplot(3, 2, 4), "classical")
  plotSongFromStyle(fig.subplot(3, 2, 1), "rock")
  plotSongFromStyle(fig.subplot(3, 2, 3), "metal")
  plotSongFromStyle(fig.subplot(3, 2, 5), "pop")
}
