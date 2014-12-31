/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package fr.inria.openvibelink.read;

// Replace constuctor of Client to register by itself the socket and not print the stack trace if an exception occurs. It's damn a dirty workaround (static method called upon call to constructor...)

// WARNING: in the end the object may not have the exact same internal state as if Client is directly used (eg: disconnectEvent, also "host" variables kind)

// WARNING: contrary to Client, calling constsructor (PApplet, String, int) may fail return a null object (the same as (PApplet, Socket), though

// TODO: could be hard to maintain with new versions of processing. Here Client.java of 2.1.2 was examined. Good idea to look into https://github.com/processing/processing/blob/master/java/libraries/net/src/processing/net/Client.java

import java.net.Socket;
import processing.core.PApplet;
import processing.net.Client;
import java.io.IOException;

class ConnectedClient extends Client {

  // The worst part: this code is here to be executed before the call to Client constructor, in order to deal with socket exception in this class
  private static Socket ugly(String host, int port) {
    System.out.println("Host: " + host + ", port: " + port);
    Socket sock = null;
    // we only want to print the type of error, not the whole stack trace
    try {
      sock = new Socket(host, port);
    }
    catch (Exception e) {
      System.out.println("Error while initialazing socket: " + e);
    }
    return sock;
  }

  // register by itself the socket in order to not print the stack trace an exception
  // The IOException comes from the Thread call used by Client... but if the socket hasn't been initialized, then a NullPointerException may also go through, be warned
  ConnectedClient(PApplet parent, String host, int port) throws IOException {
    // I'm not proud of it: execute code before calling mummy
    super(parent, ugly(host, port));
    // try to mimick what's inside Client.java...
    parent.registerMethod("dispose", this);
  }
}

    