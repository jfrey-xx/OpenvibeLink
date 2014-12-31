

// Creates a client that listens for input and puts 
// the bytes it gets into a byte[] buffer.
import fr.inria.openvibelink.read.*;

import processing.net.*; 

// TCP client
ReadAnalog myClient; 

void setup() { 
  size (300, 100);
  // explicit framerate if option is set
  if (FPS > 0) {
    frameRate(FPS);
  }
  // init client, first attempt to connect
  myClient = new ReadAnalog(this, TCPServerIP, TCPServerPort);
} 

void draw() { 
  println("-- FPS:" + frameRate + " --");
  double[][] data = myClient.read();

  if (data == null) {
    println("Waiting for data...");
  }
  // nice output to stdout
  else {
    int nbChans = myClient.getNbChans();
    int chunkSize = myClient.getChunkSize();
    println("Read " + chunkSize + " samples from " + nbChans + " channels:");
    // I can fill... can you fill it?
    for (int i=0; i < nbChans; i++) {
      for (int j=0; j < chunkSize; j++) {
        print(data[i][j] + "\t");
      }
      println();
    }
  }
}  

