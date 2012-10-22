import com.echonest.api.v4.{Params, EchoNestAPI, Playlist, Segment}
import scala.collection.JavaConversions._
import breeze.plot._
import com.echonest.api.v4.Song
import breeze.linalg.DenseMatrix
import scala.reflect.Manifest

object chart extends App {
	def getSongsByStylePlaylist(en: EchoNestAPI, style: String, qty: Int) = {
	  val query = new Params
	  
	  // Compose a query
	  query.add("style", style)
	  query.add("results", qty)
	  query.add("bucket", "audio_summary")
	  query.add("type", "artist-description")
	  
	  en.createStaticPlaylist(query)
	}
	
	def plotSongFromStyle(en: EchoNestAPI, plot: Plot, style: String) = {
	  val song = getSongsByStylePlaylist(en, style, 1).getSongs()(0)
	  val features = song.getAnalysis().getSegments().toArray(manifest[Segment])
	  val featureCount = features(0).getTimbre().length + 
	                     features(0).getPitches().length
	  val timbres =
	    for (segment <- features;
	          tFeature <- segment.getTimbre) yield tFeature
	  val timbreMin = timbres.min
	  val timbreMax = timbres.max
	  
	  val featureMat = DenseMatrix.tabulate(24, features.length) (
	      (r:Int, c: Int) => {
	  	    if (r < 12) {
	  	      (features(c).getTimbre()(r) - timbreMin) / (timbreMax - timbreMin)
	  	    } else {
	  	      features(c).getPitches()(r-12)
	  	    }
	  	  })
	  plot.title = style ++ " - " ++ song.getArtistName() ++ " - " ++
	            song.getTitle()
	  plot += image(featureMat, new GradientPaintScale(0, 1, PaintScale.Rainbow))
	}
	
	val en = new EchoNestAPI("KRPNFJRX9QKTVBG70")
	val fig = Figure()
	
	plotSongFromStyle(en, fig.subplot(3, 2, 0), "classical")
	plotSongFromStyle(en, fig.subplot(3, 2, 2), "classical")
	plotSongFromStyle(en, fig.subplot(3, 2, 4), "classical")
	plotSongFromStyle(en, fig.subplot(3, 2, 1), "rock")
	plotSongFromStyle(en, fig.subplot(3, 2, 3), "metal")
	plotSongFromStyle(en, fig.subplot(3, 2, 5), "pop")
}
