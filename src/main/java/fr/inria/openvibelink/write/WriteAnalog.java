
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package fr.inria.openvibelink.write;


import processing.net.*;
import java.nio.ByteBuffer;
import processing.core.PApplet;
import static processing.core.PApplet.arrayCopy;
import static processing.core.PApplet.println;
import static processing.core.PApplet.lerp;
import static processing.core.PApplet.floor;
import static processing.core.PApplet.ceil;

// send a raw stream of float in TCP.

// NB: Measures elapsed time between 2 calls in order to keep up with asked sample rate (oversampling by repeating values). This mechasism is analogous to (and doesn't interfere with) the drift correction feature of the openvibe acquisition server. Linear interpolation to sync, do not proceed to any filtering to remove aliasign though.

// In openvibe acquisiton server: generic raw telnet reader, big endian, 32 bits float

// TODO: maybe use a queue to push data from main program and *then* send values (will need interpolation though)

// TODO: doc and more comprehensible handling of data 

public class WriteAnalog {

  // buffer for sending data (1 point per channel)
  private byte[] buffer;
  // for readability, how many bytes make one float
  private final int nbBytesPerFloat = 4;

  // TCP server
  private Server s;

  // How many elements we should expect
  private int nbChans;

  // Which pace we have to keep up with to sync with client
  private int sampleRate = -1;

  // other solution, better used with round ratio, force the number of data points written in TCP
  // e.g. : to convert from 250Hz to 256, give buffer of length 125 to write(float [][]) and use a 1.024 ratio to obtain 128 points in output
  // > 1 for oversampling, < 1 for downsampling
  private float samplingFactor = -1;

  private boolean debug = false;

  // Last time we sent data (in nanoseconds since sample rate can get *really* high)
  private long tick;
  // We may have sent a little bit less or a little bit more to keep up with samplerate, record this to avoid offset
  private double leftoverDuplications = 0;

  // with this constructor, will just pass data through
  public WriteAnalog(PApplet caller, int port, int nbChans) {
    this.nbChans = nbChans;
    // 4 bytes per float values for the buffer
    buffer = new byte[nbChans*nbBytesPerFloat];
    // init network
    s = new Server(caller, port);
    // init clock with dummy value, t=0 will correspond to the first value sent
    tick = -1;
  }

  // use this consructor to correct automatically jitter based on elapsed time, trying to catch up with desired sample rate
  public WriteAnalog(PApplet caller, int port, int nbChans, int sampleRate) {
    this(caller, port, nbChans);
    // would mean nothing and mess up with internal control to have negative sample rate
    assert (sampleRate > 0);
    this.sampleRate = sampleRate;
  }

  // this constructor will interpolate by samplingFactor output data (cf before for more precisions)
  public WriteAnalog(PApplet caller, int port, int nbChans, float samplingFactor) {
    this(caller, port, nbChans);
    // would mean nothing and mess up with internal control to have negative ratio
    assert (samplingFactor > 0);
    this.samplingFactor = samplingFactor;
  }

  public void setDebug(boolean debug) {
    this.debug = debug;
  }

  // convert from float to bytes
  // http://stackoverflow.com/a/14619742
  private byte [] float2ByteArray (float value)
  {  
    return ByteBuffer.allocate(nbBytesPerFloat).putFloat(value).array();
  }

  // sends floats values to client
  // if array size is too big compared to nbChans sends only first elements, if too small fills with 0
  // WARNING: in this method no buffer is used, will try to comply with sample rate by repeating values. easy but not efficient.
  public void write(float[] data) {

    // fill float buffer and then pass it to TCPWriteAnalog to send over network
    for (int i = 0; i < nbChans; i++) {
      // fetch float value
      float chan = 0;
      if (i < data.length) {
        chan  = data[i];
      }
      // copy byte value to the correct place of the buffer buffer
      arrayCopy(float2ByteArray(chan), 0, buffer, i*nbBytesPerFloat, nbBytesPerFloat);
    }

    double neededDuplications = 1;

    if (sampleRate > 0) {
      // elapsed time since last call, update tick
      long now = System.nanoTime();
      long elapsedTime = now - tick;

      // only try to duplicate if we already started to send data
      if (tick >= 0) {
        // now we have to compute how many times we should send data to keep up with sample rate (oversampling)
        // NB: could be 0 if framerate is very high
        neededDuplications = sampleRate * (elapsedTime / 1000000000.0) + leftoverDuplications;
      }
      tick = now;
    } else if (samplingFactor > 0) {
      neededDuplications = neededDuplications * samplingFactor + leftoverDuplications;
    }

    // since we can't send only a fraction to be perfect, at the moment we're ok with an approximation
    long nbDuplications = Math.round(neededDuplications);
    // nbDuplications could be 0 if framerate is very high, remember offset for next time
    leftoverDuplications = neededDuplications - nbDuplications;

    // write as many times data as we need to sync with openvibe 
    for (int i = 0; i < nbDuplications; i++) {
      s.write(buffer);
    }
  }

  // this method, on the other hand, pipe buffered data but do not care about sample rate
  // data[nbChans][nbPoints]
  // TODO: check number of channels
  public void write(float[][] data) {

    // if no data, pass
    if (data.length < 1) {
      return;
    }

    int nbPoints = data[0].length;

    // only try to duplicate if we already started to send data and if option set
    double neededDuplications = nbPoints;

    if (sampleRate > 0) {
      // elapsed time since last call, update tick
      long now = System.nanoTime() ;
      long elapsedTime = now - tick;


      if (tick >= 0) {
        // now we have to compute how many points we should have in the buffer to keep up with sample rate
        // (NB: use same name as in write(float []) because could be shared, possible to switch between both methods on the fly)
        neededDuplications = sampleRate * (elapsedTime / 1000000000.0) + leftoverDuplications;
      }
      tick = now;
    } else if (samplingFactor > 0) {
      neededDuplications = neededDuplications * samplingFactor + leftoverDuplications;
    }

    // since we can't send only a fraction to be perfect, at the moment we're ok with an approximation
    long nbDuplications = Math.round(neededDuplications);
    // nbDuplications could be 0 if framerate is very high, remember offset for next time
    leftoverDuplications = neededDuplications - nbDuplications;

    if (debug) {
      println("neededDupli: " + nbDuplications);
      println("leftoverDuplications: " + leftoverDuplications);
    }

    // will interpolate and send values altogether
    // maybe not very efficient, but acquisition server expects data points for each channels in turns, so invert i and j
    for (int j = 0; j < nbDuplications; j++) {

      // we have to find to which cell correspond the current data point
      float ratio = (float) j/(nbDuplications-1);
      float origPoint = lerp(0, nbPoints-1, ratio);
      // likely it will be between two points
      int origPointPrev = floor(origPoint);
      int origPointNext = ceil(origPoint);

      if (debug) {
        println(j + "/" + nbDuplications + " -- pointPrev: " + origPointPrev + ", pointNext: " + origPointNext + ", shift: " + (origPoint - origPointPrev));
      }

      // fill float buffer and then pass it to TCPWriteAnalog to send over network
      for (int i = 0; i < nbChans; i++) {
        // fetch float value
        float chan = 0;
        if (i < data.length) {
          // except if we just pinpoined an exact cell...
          if (origPointPrev == origPointNext) {
            chan  = data[i][origPointPrev];
          }
          // ...we have to actually interpolate data now.
          else {
            chan  = lerp(data[i][origPointPrev], data[i][origPointNext], origPoint - origPointPrev);
          }
        }
        // copy byte value to the correct place of the buffer
        arrayCopy(float2ByteArray(chan), 0, buffer, i*nbBytesPerFloat, nbBytesPerFloat);
      }
      // send channels values for this chunk
      s.write(buffer);
    }
  }
}
