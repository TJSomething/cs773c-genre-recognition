package edu.unr.tkelly.genre

import com.echonest.api.v4._
import scala.collection.JavaConversions._
import java.io._
import scala.util.Random.shuffle

/** This object doesn't have a fixed function. It acts as a framework to
 * to get data to test in WEKA.
 */
object BeatlesCSV extends App {
  private def printToFile(f: java.io.File)(op: java.io.PrintWriter => Unit) {
    val p = new java.io.PrintWriter(f)
    try { op(p) } finally { p.close() }
  }

  val data = new BeatlesData(90, 10)

  def writeDataSet(songs: Map[Song, Double], dest: File) {
    // Segment the data into 4 segment samples
    printToFile(dest)(p => {
      p.println(
        (for (
          segmentIndex <- 0 until 60;
          featureIndex <- 0 until 25
        ) yield {
          if (featureIndex < 12) {
            "S%02dT%02d" format (segmentIndex, featureIndex)
          } else if (featureIndex < 24) {
            "S%02dP%02d" format (segmentIndex, featureIndex - 12)
          } else {
            "S%02dD" format (segmentIndex)
          }
        }).mkString("\t") ++ "\tis-Beatles\tartist\tsong-title")

      shuffle(for (
        (song, result) <- songs
      ) yield {
        val window= song.getAnalysis()
          .getSegments()
          .toArray(manifest[Segment]).take(60)

        (for (
          segment <- window;
          featureIndex <- 0 until 25
        ) yield {
          if (featureIndex < 12) {
            segment.getTimbre()(featureIndex)
          } else if (featureIndex < 24) {
            segment.getPitches()(featureIndex - 12)
          } else {
            segment.getDuration()
          }
        }).mkString("\t") ++ "\t" ++
          (if (result > 0.5) "T" else "F") ++ "\t" ++
          song.getArtistName() ++ "\t" ++
          song.getTitle()
      }).foreach(p.println)
    })
  }
  
  writeDataSet(data.trainingSet, new File("full_song_training.txt"))
  writeDataSet(data.testSet, new File("full_song_test.txt"))
}
