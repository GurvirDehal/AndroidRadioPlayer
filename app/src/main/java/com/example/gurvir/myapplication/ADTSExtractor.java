package com.example.gurvir.myapplication;

import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class ADTSExtractor {
  private InputStream inputStream;
  private int protectionAbsent;
  private int profile;
  private int samplingFrequencyIndex;
  private int channelConfiguration;
  private int frameLength;

  private boolean hasReadHeader = false;

  private final int[] samplingFrequencies = { 96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

  public void setDataSource(InputStream inputStream) {
    this.inputStream = inputStream;
  }

  private void readADTSHeader() throws IOException {
    if (hasReadHeader) return;

    byte[] hdr = new byte[7];
    int syncWord;

    do {
      hdr[0] = (byte) (this.inputStream.read() & 0xFF);
      hdr[1] = (byte) (this.inputStream.read() & 0xFF);
      syncWord = ((hdr[0] & 0xFF) << 4 | (hdr[1] & 0xFF) >> 4);
    } while (syncWord != 0xFFF);

    int bytesRead = 2;
    while (bytesRead < 7) {
      hdr[bytesRead] = (byte) (this.inputStream.read() & 0xFF);
      bytesRead++;
    }

    this.protectionAbsent        =  hdr[1] & 0b1;
    this.profile                 = (((hdr[2] & 0xFF) >> 6) & 0b11) + 1;
    this.samplingFrequencyIndex  = ((hdr[2] & 0xFF) >> 2) & 0b1111;
    this.channelConfiguration    = ((hdr[2] & 0b1) << 2) | ((hdr[3] & 0xFF) >> 6);
    this.frameLength             = (((hdr[3] & 0b11) << 11) | ((hdr[4] & 0xFF) << 3) | ((hdr[5] & 0xFF) >> 5));
    if (this.protectionAbsent == 0) {
      while(bytesRead < 9) {
        bytesRead += this.inputStream.skip(1);
      }
    }
    this.hasReadHeader = true;
  }
  public int readSampleData(ByteBuffer buffer) throws IOException {
    if (!this.hasReadHeader) {
      this.readADTSHeader();
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
      this.readADTSHeader();
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
