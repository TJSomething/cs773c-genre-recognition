package edu.unr.tkelly.genre

import com.echonest.api.v4._

class BeatlesData(val trainingSize: Int, val testSize: Int) {
  val setSize = trainingSize + testSize
  
  private val matchParams = new PlaylistParams
  
  matchParams.addArtist("The Beatles")
  matchParams.setType(PlaylistParams.PlaylistType.ARTIST)
  matchParams.add("bucket", "audio_summary")
  
  private val matchingSongs = Util.getSongs(matchParams, setSize/2) 
  
  // Pick a wide variety of genres to compare with The Beatles
  private val notParams = new PlaylistParams
  for (style <- List("classical", "metal", "pop", "hiphop", "rock", "jazz")) {
    notParams.addStyle(style)
  }
  
  notParams.setType(PlaylistParams.PlaylistType.ARTIST_DESCRIPTION)
  notParams.add("bucket", "audio_summary")
  
  // Let's make sure that none of the found songs are by The Beatles
  println("Getting non-Beatles songs...")
  private var notSongs: Set[Song] = Set[Song]()
  do {
    notSongs ++= Util.getSongs(notParams, setSize/2 - notSongs.size)
    notSongs = notSongs.filter(song => song.getArtistName != "The Beatles")
  } while (notSongs.size < setSize/2)
  
  private val (trainingMatches, testMatches) = matchingSongs.splitAt((trainingSize)/2)
  private val (trainingNot, testNot) = notSongs.splitAt(trainingSize/2)
  
  val trainingSet = 
    (trainingMatches.zip(Stream.continually {1.0}) ++
     trainingNot.zip(Stream.continually {0.0})).toMap
  val testSet = 
    (testMatches.zip(Stream.continually {1.0}) ++
     testNot.zip(Stream.continually {0.0})).toMap
}