// try out TCPWriteAnalog with a nice GUI
import fr.inria.openvibelink.write.*;
import processing.net.*;
import controlP5.*;

// TCP server
WriteAnalog server;

// For GUI, one controller to rule all sliders
ControlP5 MyController;
Slider[] sliders;

// buffer for storing GUI floats
float[] slidersValues;

void setup() 
{
  // window size varies depending on the number of channels we got
  int sliderWidth = 15;
  int sliderSpace = 30;
  int windowBorder = 50;
  int windowWidth = nbChans * (sliderWidth + sliderSpace) + windowBorder*2;
  int windowHeight = 500; 
  size(windowWidth, windowHeight);

  // explicit framerate if option is set
  if (FPS > 0) {
    frameRate(FPS);
  }
  // init network
  server = new WriteAnalog(this, TCPServerPort, nbChans, sampleRate);

  // setup GUI
  MyController = new ControlP5(this);
  // we got a bunch of sliders to generate
  sliders = new Slider[nbChans];
  for (int i = 0; i < nbChans; i++) {
    // sliders from -10 to 10
    String chanName = "chan" + i;
    // 10 pixels margin
    int chanPos = windowBorder + (sliderSpace + sliderWidth) * i;
    // from -10 to 10, default value 0
    sliders[i] = MyController.addSlider(chanName, -10, 10, 0, chanPos, windowBorder, sliderWidth, windowHeight - windowBorder * 2);
  }

  // init float buffer
  slidersValues = new float[nbChans];
}

void draw() 
{
  background(204);
  // read values from sliders and fill buffer
  if (debug) {
    print("send: ");
  }

  // fill float buffer and then pass it to TCPWriteAnalog to send over network
  for (int i = 0; i < nbChans; i++) {
    // fetch float value
    slidersValues[i]  = sliders[i].getValue();
    if (debug) {
      print(slidersValues[i] + ", ");
    }
  }
  if (debug) {
    println();
  }

  // now let the expert handle the data
  server.write(slidersValues);
}

