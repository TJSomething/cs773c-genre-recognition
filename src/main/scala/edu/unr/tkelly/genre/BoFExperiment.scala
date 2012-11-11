package edu.unr.tkelly.genre

object BoFExperiment extends App {
  // Get data
  val data = new BeatlesData(100, 50)
  
  // For several cluster counts
    // Using the training set
    // For several slice lengths
		// Split songs into overlapping slices for clustering
	    // Cluster the slices
	    // Find the centroid of all the clusters
        // For each song, make a histogram of how each song's slices cluster
        // Return the information needed to cluster more data, as well as the
        //  clustering histogram.
    // For every song, combine the histograms for each slice length into a 
    //   single vector.
    // Train a support vector machine to recognize which songs match our
    //   criteria.
  
  
    // Using the test set
    // For several slice lengths
        // Split songs into overlapping slices for clustering
        // Use clustering data from training to cluster slices
        // Construct a histogram of clusters for each song
    // Classify song histograms using trained SVM
  
    // Calculate accuracy
}