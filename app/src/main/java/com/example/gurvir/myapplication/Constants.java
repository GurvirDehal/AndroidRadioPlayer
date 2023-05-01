package com.example.gurvir.myapplication;

import android.support.v4.media.session.PlaybackStateCompat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import androidx.annotation.IntDef;

public class Constants {
  enum PlaybackState {
    STATE_PAUSED(PlaybackStateCompat.STATE_PAUSED),
    STATE_NONE(PlaybackStateCompat.STATE_NONE),
    STATE_STOPPED(PlaybackStateCompat.STATE_STOPPED),
    STATE_PLAYING(PlaybackStateCompat.STATE_PLAYING);

    public final int value;

    public int getValue() {
      return this.value;
    }

    private PlaybackState(int value) {
      this.value = value;
    }

    private static PlaybackState[] cachedValues = null;
    public static PlaybackState fromInt(int i) {
      if(cachedValues == null) {
        cachedValues = PlaybackState.values();
      }
      for(PlaybackState s: cachedValues) {
        if (s.getValue() == i) return s;
      }
      throw new IllegalArgumentException("Not valid integer i");
    }
  }
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
          PlaybackStateCompat.STATE_NONE,
          PlaybackStateCompat.STATE_PLAYING,
          PlaybackStateCompat.STATE_PAUSED,
          PlaybackStateCompat.STATE_STOPPED
  })
  public @interface PLAYBACK_STATE {}
}
