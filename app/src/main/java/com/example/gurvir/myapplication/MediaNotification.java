package com.example.gurvir.myapplication;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;

public class MediaNotification {
  public static final String CHANNEL_ID = "channel1";
  public static final String ACTION_PLAY = "actionplay";
  private Notification notification;
  private MediaSessionCompat mediaSession;
  private NotificationCompat.Builder builder;

  MediaNotification(Context context) {
    mediaSession = new MediaSessionCompat(context, "tag");
    mediaSession.setMetadata(new MediaMetadataCompat.Builder()
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1)
            .build()
    );
    mediaSession.setPlaybackState(new PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f).build());
    Intent intentPlay = new Intent(context, NotificationActionService.class)
            .setAction(ACTION_PLAY);
    PendingIntent pendingIntentPlay = PendingIntent.getBroadcast(context, 0, intentPlay, PendingIntent.FLAG_UPDATE_CURRENT);

    builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setWhen(0)
            .setSmallIcon(R.drawable.ic_music_note)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(R.drawable.ic_baseline_skip_previous_24, "Previous", null)
            .addAction(R.drawable.ic_baseline_pause_24, "Play", pendingIntentPlay)
            .addAction(R.drawable.ic_baseline_skip_next_24, "Next", null)
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(mediaSession.getSessionToken())
            )
            .setContentIntent(PendingIntent.getActivity(
                    context,
                    (int) System.currentTimeMillis(),
                    new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    0
            ));
  }

  public Notification createNotification(String trackName, Bitmap bitmap) {
    String[] array = trackName.split(" - ");
    return createNotification(array[0], array[1], bitmap);
  }
  public Notification createNotification(String contentTitle, String contentText, Bitmap bitmap) {
    builder.setContentTitle(contentTitle)
           .setContentText(contentText)
           .setLargeIcon(bitmap);

    return notification = builder.build();
  }

  public Notification updateNotification(Context context, boolean isPlaying) {
    if (notification != null) {
      int playButton = isPlaying ? R.drawable.ic_baseline_pause_24 : R.drawable.ic_baseline_play_arrow_24;
      notification.actions[1] = new Notification.Action.Builder(
              Icon.createWithResource(context, playButton),
              "Play",
              notification.actions[1].actionIntent
      ).build();
      return notification;
    }
    return null;
  }
}
