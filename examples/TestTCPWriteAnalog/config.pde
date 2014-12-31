
// various config for the experiment

// if true, will spam stdout with sent values
final boolean debug = false;

final int TCPServerPort = 12345;
// how may channels do you want challenge openvibe with?
final int nbChans = 16;

// greater than 0 to explicitely set framerate
final int FPS = 0;
// How many chunks are sent each second ; at least 128 to make openvibe happy (128 / 256 / ... / 4096 )
final int sampleRate = 128;


