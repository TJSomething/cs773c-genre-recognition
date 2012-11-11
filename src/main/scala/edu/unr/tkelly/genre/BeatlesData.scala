package edu.unr.tkelly.genre

import com.echonest.api.v4._

class BeatlesData(val trainingSize: Int, val testSize: Int) {
  val setSize = trainingSize + testSize
  private val beatlesCount = setSize / 2
  private val notCount = setSize - beatlesCount

  private val matchParams = new PlaylistParams

  matchParams.addArtist("The Beatles")
  matchParams.setType(PlaylistParams.PlaylistType.ARTIST)
  matchParams.add("bucket", "audio_summary")

  private var matchingSongs: Set[Song] = Set[Song]()
  do {
    matchingSongs ++= Util.getSongs(matchParams, beatlesCount - matchingSongs.size)
    matchingSongs = matchingSongs
      .filter(song => Option(song.getDouble("audio_summary.speechiness"))
        .exists(_ < 0.5))
  } while (matchingSongs.size < notCount)

  // Pick a wide variety of genres to compare with The Beatles
  private val notParams = new PlaylistParams
  for (style <- List("classical", "metal", "pop", "hiphop", "rock", "jazz")) {
    notParams.addStyle(style)
  }

  notParams.setType(PlaylistParams.PlaylistType.ARTIST_DESCRIPTION)
  notParams.add("bucket", "audio_summary")

  // Let's make sure that none of the found songs are by The Beatles
  private var notSongs: Set[Song] = Set[Song]()
  do {
    notSongs ++= Util.getSongs(notParams, notCount - notSongs.size)
    notSongs = notSongs
      .filter(song => song.getArtistName != "The Beatles")
      .filter(song => Option(song.getDouble("audio_summary.speechiness"))
        .exists(_ < 0.5))
  } while (notSongs.size < notCount)

  private val (trainingMatches, testMatches) = matchingSongs.splitAt((trainingSize) / 2)
  private val (trainingNot, testNot) = notSongs.splitAt(trainingSize / 2)

  val trainingSet =
    (trainingMatches.zip(Stream.continually { 1.0 }) ++
      trainingNot.zip(Stream.continually { 0.0 })).toMap
  val testSet =
    (testMatches.zip(Stream.continually { 1.0 }) ++
      testNot.zip(Stream.continually { 0.0 })).toMap
}