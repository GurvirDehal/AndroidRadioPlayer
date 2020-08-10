package com.example.gurvir.myapplication;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;

public class MediaNotification {
  public static final String CHANNEL_ID = "channel1";
  private Notification notification;
  private NotificationCompat.Builder builder;

  MediaNotification(Context context, MediaSessionCompat mediaSession) {

    builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setWhen(0)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_music_note)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_previous, "Previous", null)
            .addAction(R.drawable.ic_pause, "Play", MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY_PAUSE))
            .addAction(R.drawable.ic_next, "Next", null)
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

  public Notification createNotification(String contentTitle, String contentText, Bitmap bitmap) {
    builder.setContentTitle(contentTitle)
           .setContentText(contentText)
           .setLargeIcon(bitmap);

    return notification = builder.build();
  }

  public Notification updateNotification(Context context, boolean isPlaying) {
    if (notification != null) {
      int playButton = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
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
