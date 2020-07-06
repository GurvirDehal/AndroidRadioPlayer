package com.example.gurvir.myapplication;

import android.media.MediaDataSource;
import android.util.Log;

import java.io.IOException;
public class AudioDataSource extends MediaDataSource {
  private volatile byte[] audioBuffer;
  AudioDataSource(byte[] buffer) {
    audioBuffer = buffer;
  }
  @Override
  public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
    synchronized (audioBuffer) {
      int length = audioBuffer.length;
      if (position >= length) {
        return -1; // -1 indicates EOF
      }
      if (position + size > length) {
        size -= (position + size) - length;
      }
      System.arraycopy(audioBuffer, (int)position, buffer, offset, size);
      return size;
    }
  }

  @Override
  public long getSize() throws IOException {
    synchronized (audioBuffer) {
      return audioBuffer.length;
    }
  }

  @Override
  public void close() throws IOException {

  }
}
