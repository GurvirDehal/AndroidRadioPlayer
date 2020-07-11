package com.example.gurvir.myapplication;

import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class ADTSExtractor {
  private InputStream inputStream;
  private int mpegVersionIndex;
  private int layer;
  private int protectionAbsent;
  private int profile;
  private int samplingFrequencyIndex;
  private int privateBit;
  private int channelConfiguration;
  private int originalCopy;
  private int home;
  private int copyRightIdBit;
  private int copyRightIdStart;
  private int frameLength;
  private int adtsBufferFullness;
  private int numAACFramesinADTSFrame;

  private boolean hasReadHeader = false;

  private final int[] samplingFrequencies = { 96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
  public void setDataSource(InputStream inputStream) {
    this.inputStream = inputStream;
  }
  private void printByteArray(byte[] b) {
    StringBuilder s = new StringBuilder();
    for(int i=0; i< b.length; i++) {
      int j = b[i] & 0xFF;
      s.append(String.format("%02x", (byte) j) + " ");
    }
    Log.i("MyApp", s.toString());
  }
  private void readAdtsHeader() throws IOException{
      if (hasReadHeader) return;
      byte[] hdr = new byte[7];
      int bytesRead = 0;
      while (bytesRead < 7) {
        bytesRead += this.inputStream.read(hdr, bytesRead, 7 - bytesRead);
      }
//      printByteArray(hdr);
      int syncWord = ((hdr[0] & 0xFF) << 4 | (hdr[1] & 0xFF) >> 4);
      if (syncWord != 0xFFF) {
        Log.i("MyApp", "Error");
      }
      this.mpegVersionIndex        = ((hdr[1] & 0xFF) >> 3) & 0b1;
      this.layer                   = ((hdr[1] & 0xFF) >> 1) & 0b11;
      this.protectionAbsent        =  hdr[1] & 0b1;
      this.profile                 = (((hdr[2] & 0xFF) >> 6) & 0b11) + 1;
      this.samplingFrequencyIndex  = ((hdr[2] & 0xFF) >> 2) & 0b1111;
      this.privateBit              = ((hdr[2] & 0xFF) >> 1) & 0b1;
      this.channelConfiguration    = ((hdr[2] & 0b1) << 2) | ((hdr[3] & 0xFF) >> 6);
      this.originalCopy            = ((hdr[3] & 0xFF) >> 5) & 0b1;
      this.home                    = ((hdr[3] & 0xFF) >> 4) & 0b1;
      this.copyRightIdBit          = ((hdr[3] & 0xFF) >> 3) & 0b1;
      this.copyRightIdStart        = ((hdr[3] & 0xFF) >> 2) & 0b1;
      this.frameLength             = (((hdr[3] & 0b11) << 11) | ((hdr[4] & 0xFF) << 3) | ((hdr[5] & 0xFF) >> 5));
      this.adtsBufferFullness      = ((hdr[5] & 0b11111) << 6) | ((hdr[6] & 0xFF) >> 2);
      this.numAACFramesinADTSFrame = ((hdr[6]) & 0b11) + 1; // should likely be 1
      if (this.protectionAbsent == 0) {
        while(bytesRead < 9) {
          bytesRead += this.inputStream.skip(1);
        }
      }
      this.hasReadHeader = true;
  }
  public int readSampleData(ByteBuffer buffer) throws IOException {
    if (!hasReadHeader) {
      this.readAdtsHeader();
    }
    int sampleSize = this.frameLength - (this.protectionAbsent != 0 ? 7 : 9);
    if (sampleSize < 0) {
      Log.i("MyApp", "wtf2");
    }
    byte[] b = new byte[sampleSize];
    int bytesRead = 0;
    while (bytesRead < sampleSize) {
      b[bytesRead] = (byte) (this.inputStream.read() & 0xFF);
      bytesRead++;
    }
    buffer.put(b);
    this.hasReadHeader = false;
    return sampleSize;
  }
  public MediaFormat getTrackFormat() throws IOException {
    if (!hasReadHeader) {
      this.readAdtsHeader();
    }
    MediaFormat format = new MediaFormat();
    format.setInteger(MediaFormat.KEY_SAMPLE_RATE, this.getSampleRate());
    format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
    format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, this.channelConfiguration);
    ByteBuffer csd = ByteBuffer.allocate(2);
    csd.put(0, (byte) (((this.profile << 3) & 0xF8) | ((this.samplingFrequencyIndex >> 1) & 0x07)));
    csd.put(1, (byte) (((this.samplingFrequencyIndex << 7) & 0x80) | ((this.channelConfiguration << 3) & 0x78)));
    format.setByteBuffer("csd-0", csd);
    return format;
  }
  private int getSampleRate() {
    int retVal = -1;
    try {
      retVal = this.samplingFrequencies[this.samplingFrequencyIndex];
    } catch(IndexOutOfBoundsException e) {
      Log.e("MyApp", "error", e);
    }
    return retVal;
  }
}
