/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package fr.inria.openvibelink.read;


import processing.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import processing.core.PApplet;
import static processing.core.PApplet.arrayCopy;
import static processing.core.PApplet.println;

// read stream of floats in TCP. Works in two passes: first fill byte buffer, then read data from buffer. If server disconnect, will re-read last chunk. As soon as a new connexion is established (wether the correct number of channels is reached or not), chunk is reset.

// FIXME: very dumb at the moment, only return last chunk of data since we don't care about sync and drift. not really for signal processing, should do interpolation and/or use callback. At the moment reading from main program will result in a decimation. At least it's doesn't cost much CPU...

// WARNING: automatic reconnection, if the server change, martix size could also change. Carefully check parameters with each read()

public class ReadAnalog {

  // if connection is lost, will wait before tying to reco (in ms)
  private final float TCP_RETRY_DELAY = 2000;
  // time since last connection attempt
  private long TCPlastAttempt = 0;

  // if > 0, will keep trying to connect until the feed provides this many channels
  private int wantedNbChans = 0;

  // for readability, how many bytes make one int32
  private final int nbBytesPerInt = 4;
  // for readability, how many bytes make one float64/double
  private final int nbBytesPerDouble = 8;

  // TCP client
  private Client ovTCPclient; 
  private String IP;
  private int port;

  // for Processing magical stuff to happen, we need a pointer to main instance
  PApplet caller;

  // we have to wait for whole header before goning further
  private boolean headerReady = false;
  // and at least we fead one complete chunk of data
  private boolean chunkReady = false;

  // chunk that will be filled as data stream is read.  Warning: size change depending on server
  private byte[] chunkBuffer;
  // number of bytes fetched from TCP
  private int chunkBufferFill = 0;

  // a complete chunk of data. Warning: size change depending on server
  private byte[] chunk;
  // position in the chunk since last read
  private int chunkPos = 0;

  // version number of the protocol used in openvibe
  private int protocolVersion;
  // How many elements we should expect
  private int nbChans;
  // Which pace we have to keep up with to sync with client
  private int sampleRate;
  // number of values that are sent for each channel
  private int chunkSize;
  // in which order data are sent over network
  private ByteOrder endianness;

  // to speak or not to speak?
  private boolean debug = false;

  // by default, no verbosity
  public ReadAnalog(PApplet caller, String IP, int port) {
    this(caller, IP, port, false);
  }

  // we may want to reduce a bit verbosity
  public ReadAnalog(PApplet caller, String IP, int port, boolean debug) {
    this(caller, IP, port, debug, 0);
  }

  // and to get into business only if we got a precise amount of channels
  public ReadAnalog(PApplet caller, String IP, int port, boolean debug, int wantedNbChans) {
    this.IP = IP;
    this.port = port;
    this.caller = caller;
    this.debug = debug;
    this.wantedNbChans = wantedNbChans;
    // init state
    resetState();
    // init network
    connect();
  }

  // re-init internal state
  // (called only upon (re)connection so we still get last chunk between connections)
  private void resetState() {
    // reset flag
    headerReady = false;
    chunkReady = false;
    // reset headers
    nbChans = 0;
    sampleRate = 0;
    chunkSize = 0;

    // (re)init buffer
    chunkPos = 0;
    chunkBufferFill = 0;
    // buffer size for header: 8 int32
    chunkBuffer = new byte[8*nbBytesPerInt];
    chunk = new byte[chunkBuffer.length];
  }

  // (re)connect to server
  private void connect() {
    //    println( "Will connect to: " + IP + ":" + port);
    try {
      ovTCPclient = new ConnectedClient(caller, IP, port);
    }
    // we'll mostly catch NullPointer if socket failed in ClientTaciturne
    catch (Exception e) {
      println("Couldn't create ClientTaciturne, exception: " + e);
    }
    // check state    
    if (ovTCPclient != null && ovTCPclient.active()) {
      // New connection, time to reset state
      println( "Connected!");
      resetState();
    } else {
      println( "Connection failed, will try again in " + TCP_RETRY_DELAY + "ms.");
    }
    TCPlastAttempt = caller.millis();
  }

  // read a *new* int32 from chunk (move position inside). Should not be used outside readHeader()!
  // TODO: throw exception if overflow or bad position + *maybe* I should use ByteBuffer more extensively at some point
  private int readInt() {
    // int32: 4 bytes
    byte[] intBuffer =  new byte[4];
    // copy int from chunk buffer to local buffer 
    arrayCopy(chunk, chunkPos, intBuffer, 0, nbBytesPerInt);
    // advance pos for next read
    chunkPos += nbBytesPerInt;
    // and magically get int
    return ByteBuffer.wrap(intBuffer).order(endianness).getInt();
  }

  // read a *new* float64 from chunk (move position inside). Should not be used outside chunk2floats()!
  // TODO: throw exception if overflow or bad position + *maybe* I should use ByteBuffer more extensively at some point
  private double readDouble() {
    // float64: 8 bytes
    byte[] doubleBuffer =  new byte[8];
    // copy int from chunk buffer to local buffer 
    arrayCopy(chunk, chunkPos, doubleBuffer, 0, nbBytesPerDouble);
    // advance pos for next read
    chunkPos += nbBytesPerDouble;
    // and magically get int
    return ByteBuffer.wrap(doubleBuffer).order(endianness).getDouble();
  }

  // fill as many on the chunkBuffer as we can
  private void fillBuffer() {
    if (debug) {
      println("Filling buffer... " + chunkBufferFill + "/" + chunkBuffer.length);
    }
    int nbReads = ovTCPclient.available();
    if (debug) {
      println(nbReads + " bytes waiting in stream");
    }
    if (nbReads > 0) {
      // if there is more available data than the chunkBuffer can still handle, we will stop before loosing data
      int bufSize = nbReads;
      if (nbReads > chunkBuffer.length - chunkBufferFill) {
        bufSize = chunkBuffer.length - chunkBufferFill;
      }
      // create steam buffer
      if (debug) {
        println("Will read: " + bufSize);
      }
      // a local buffer suited to what we will read, and to what
      // FIXME: not efficient to recreate it with each call, but no other way to ensure number of bytes read
      byte[] buf = new byte[bufSize];
      // Read in the bytes
      int byteCount = ovTCPclient.readBytes(buf);
      if (byteCount !=  bufSize) {
        println("uh oh... bad match between bytes read and buffer size");
      }
      if (debug) {
        println("Read: " +  byteCount);
      }
      // copy to main buffer
      arrayCopy(buf, 0, chunkBuffer, chunkBufferFill, bufSize);
      chunkBufferFill += bufSize;
    }
  }

  // Fetch stream parameters, see TCP writer box help for protocol
  private void readHeader() {
    // read from stream
    fillBuffer();

    // got a trouble if we got too far
    if (chunkBufferFill >  chunkBuffer.length) {
      println("TCPClientReadAnalog error: buffer overflow");
      caller.exit();
    }
    // perfect situation: buffer finally filled, we can get to work
    else if (chunkBufferFill == chunkBuffer.length) {
      println("Buffer filled, fetch header");
      // copy chunkBuffer to chunk
      arrayCopy(chunkBuffer, 0, chunk, 0, chunkBuffer.length);

      // first two values in network order, aka big endian
      endianness = ByteOrder.BIG_ENDIAN;

      // the info we'll got from header
      protocolVersion = readInt();
      println("protocolVersion: " + protocolVersion);
      int endianness_code =  readInt();
      println("endianness_code: " + endianness_code);
      // at this point we should get the real endianness. It's a proof of concept, will deal only with little (1) and big (2), not with unknown (0) status and pdp cpu (3) weirdos
      if (endianness_code == 1) {
        endianness = ByteOrder.LITTLE_ENDIAN;
      }
      sampleRate = readInt();
      println("sampleRate: " + sampleRate);
      nbChans = readInt();
      println("nbChans: " + nbChans);
      chunkSize = readInt();
      println("chunkSize: " + chunkSize);

      // our 3 empty variables
      readInt();
      readInt();
      readInt();

      // quick fix: with classifier output (and maybe other epoched values), nb sample is set at 0 ; nb channels at 0 (openvibe 0.17) or 1 (openvibe 0.18). Will try workaround
      // TODO: test with multi-class
      // FIXME: a 0 chunkSize could also mean that ov sent a bad header; if so, use same deco as below with wantedNbChans
      if (chunkSize == 0) {
        chunkSize = 1;
        println("Correct from 0 to 1 chunkSize");
        // override with your needs
        if (nbChans == 0) {
          nbChans = 1;
          println("Correct from 0 to 1 channel");
        }
      }

      // if option is set, we won't go further until we got the correct number of channels
      if (wantedNbChans > 0 && wantedNbChans != nbChans) {
        println("Number of channels mismatch: " + nbChans + " instead of " + wantedNbChans + ". Disconnect.");
        ovTCPclient.stop();
        ovTCPclient = null;
        resetState();
      } else {
        // update chunk size for floats and set flag
        chunkBuffer = new byte[nbChans*chunkSize*nbBytesPerDouble];
        chunk = new byte[chunkBuffer.length];
        chunkBufferFill = 0;
        chunkPos = 0;
        headerReady = true;
      }
    }
  }

  // fetch data from steam to buffer, as soon as chunkBuffer is filled, ready to read new data
  // return true if a new chunk has been read
  private boolean readData() {
    // read from stream
    fillBuffer();

    // got a trouble if we got too far
    if (chunkBufferFill >  chunkBuffer.length) {
      println("TCPClientReadAnalog error: buffer overflow");
      caller.exit();
    }
    // perfect situation: buffer finally filled, we can get to work
    else if (chunkBufferFill == chunkBuffer.length) {
      // reset pos in chunkBuffer
      chunkBufferFill = 0;
      chunkPos = 0;
      // copy chunkBuffer to chunk, update flag
      if (debug) {
        println("Buffer filled, fill chunk");
      }
      arrayCopy(chunkBuffer, 0, chunk, 0, chunkBuffer.length);
      chunkReady = true;
      return true;
    }
    return false;
  }

  // return a nice array [nbChans][chunkSize] from last chunk.
  private double[][] chunk2float() {
    // we want to start from the beginning
    chunkPos = 0;
    double[][] floatChunk = new double[nbChans][chunkSize];
    // I can fill... can you fill it?
    for (int i=0; i < nbChans; i++) {
      for (int j=0; j < chunkSize; j++) {
        // openvibe sends every samples of one channel before moving to the next channel, so we just have to read sequentially
        floatChunk[i][j] = readDouble();
      }
    }
    // in case someone comes after...
    chunkPos = 0;
    return floatChunk;
  }

  // read data from stream, return a chunk. channel in row, samples in columns.
  // null if data not ready
  // also handles reconnection
  // FIXME: so we don't loose data the function will not return as long as stream can be read. Unlikely, but if the bitrate is high it could lock the program (by the time stream is read, more data comes). Use sample rate information and interpolation instead...
  public double[][] read() {

    // if connection is inactive, try to reco after TCPRetryDelay has been reached
    // TODO: another way than creating a client? No reco in processing?
    if ( 
    (ovTCPclient == null  || !ovTCPclient.active()) // object not created or deco happend
    && caller.millis() - TCPlastAttempt > TCP_RETRY_DELAY // and times up
    ) {
      connect();
    }

    // if we got a conection and there is data, time to read somemething
    if (ovTCPclient != null && ovTCPclient.available() > 0) {
      // first update buffers and chunk
      // as long as not ready, will try to read header
      if (!headerReady) {
        readHeader();
      } 
      // now if header is net, we can listen to actual data
      if (headerReady) {
        // as long as there is data to read... we'll read it
        int nbReads = ovTCPclient.available();
        if (debug) {
          println("Reading data...");
          println(nbReads + " bytes waiting in stream");
        }
        // FIXME: if one channel and one chunk (eg: classifier output), may not detect correctly disconnection
        while (nbReads > 0) {
          // update chunkBuffer
          if (readData()) {
            // FIXME: got new chunk, here add callback
            ;
          }
          nbReads = ovTCPclient.available();
        }
      }
    }

    // we got at least one chunk since the beginning, let's read floats!
    if (chunkReady) {
      return chunk2float();
    } else {
      return null;
    }
  }

  // it's  getting late but we take time to code some complicated getters

  public int getNbChans() {
    return nbChans;
  }

  public int getChunkSize() {
    return chunkSize;
  }
}
