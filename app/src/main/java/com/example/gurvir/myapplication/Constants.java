package com.example.gurvir.myapplication;

import android.support.v4.media.session.PlaybackStateCompat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import androidx.annotation.IntDef;

public class Constants {
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
          PlaybackStateCompat.STATE_NONE,
          PlaybackStateCompat.STATE_PLAYING,
          PlaybackStateCompat.STATE_PAUSED,
          PlaybackStateCompat.STATE_STOPPED
  })
  public @interface PLAYBACK_STATE {}
}
