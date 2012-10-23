package edu.unr.tkelly.genre

import com.echonest.api.v4._
import scala.collection.JavaConversions._
import breeze.plot._
import breeze.linalg.DenseMatrix
import scala.Array.canBuildFrom

object Chart extends App {
  def plotSongFromStyle(plot: Plot, style: String) = {
    val query = new PlaylistParams

    // Compose a query
    query.add("style", style)
    query.add("bucket", "audio_summary")
    query.add("type", "artist-description")

    val song = Util.getSongs(query, 1).head
    val features = song.getAnalysis().getSegments().toArray(manifest[Segment])
    val featureCount = features(0).getTimbre().length +
      features(0).getPitches().length
    val timbres =
      for (
        segment <- features;
        tFeature <- segment.getTimbre
      ) yield tFeature
    val timbreMin = timbres.min
    val timbreMax = timbres.max

    val featureMat = DenseMatrix.tabulate(24, features.length)(
      (r: Int, c: Int) => {
        if (r < 12) {
          (features(c).getTimbre()(r) - timbreMin) / (timbreMax - timbreMin)
        } else {
          features(c).getPitches()(r - 12)
        }
      })
    plot.title = style ++ " - " ++ song.getArtistName() ++ " - " ++
      song.getTitle()
    plot += image(featureMat, new GradientPaintScale(0, 1, PaintScale.Rainbow))
  }

  val en = new EchoNestAPI("KRPNFJRX9QKTVBG70")
  val fig = Figure()

  plotSongFromStyle(fig.subplot(3, 2, 0), "classical")
  plotSongFromStyle(fig.subplot(3, 2, 2), "classical")
  plotSongFromStyle(fig.subplot(3, 2, 4), "classical")
  plotSongFromStyle(fig.subplot(3, 2, 1), "rock")
  plotSongFromStyle(fig.subplot(3, 2, 3), "metal")
  plotSongFromStyle(fig.subplot(3, 2, 5), "pop")
}
