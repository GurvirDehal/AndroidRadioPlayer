package com.example.gurvir.myapplication;

import static com.example.gurvir.myapplication.Constants.PlaybackState;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.AudioManagerCompat;
import androidx.media.MediaBrowserServiceCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MediaPlayerService extends MediaBrowserServiceCompat {
  private final String TAG = "MyApp";
  private MediaNotification mediaNotification;
  private Thread downloadAudioThread;
  private Thread playAudioThread;
  private Handler mainHandler;
  private AudioManager audioManager;
  private AudioFocusRequest focusRequest;
  private MediaSessionCompat mediaSession;
  private PlaybackStateCompat.Builder playbackStateBuilder;
  private MediaMetadataCompat.Builder mediaMetadataBuilder;
  private BroadcastReceiver broadcastReceiver;
  private AtomicInteger localPlaybackState;
  private PipedInputStream pipedInputStream;
  private PipedOutputStream pipedOutputStream;
  private Object lock;
  private ExecutorService executorService = Executors.newSingleThreadExecutor();

  @Override
  public void onCreate() {
    super.onCreate();
    localPlaybackState = new AtomicInteger();
    pipedInputStream = new PipedInputStream(32000);
    pipedOutputStream = new PipedOutputStream();
    lock = new Object();
    playbackStateBuilder = new PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY);

    mainHandler = new Handler(Looper.getMainLooper());
    broadcastReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
          pauseMedia();
        }
      }
    };
    registerReceiver(broadcastReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    mediaMetadataBuilder = new MediaMetadataCompat.Builder()
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1);
    mediaSession = new MediaSessionCompat(this, "tag");
    mediaSession.setMetadata(mediaMetadataBuilder.build());
    mediaSession.setCallback(new MediaSessionCompat.Callback() {
      @Override
      public void onPlay() {
        super.onPlay();
        playMedia();
      }

      @Override
      public void onPause() {
        super.onPause();
        pauseMedia();
      }

    });
    mediaSession.setActive(true);
    setSessionToken(mediaSession.getSessionToken());
    setPlaybackState(PlaybackStateCompat.STATE_NONE);
    NotificationChannel channel = new NotificationChannel(
            MediaNotification.CHANNEL_ID,
            "Radio Player",
            NotificationManager.IMPORTANCE_LOW
    );
    channel.setSound(null, null);
    channel.setShowBadge(false);
    NotificationManager notificationManager = getSystemService(NotificationManager.class);
    notificationManager.createNotificationChannel(channel);
    mediaNotification = new MediaNotification(this, mediaSession);
    AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
    audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    focusRequest = new AudioFocusRequest.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(i -> {
              switch (i) {
                case AudioManager.AUDIOFOCUS_GAIN:
                  playMedia();
                  break;
                case AudioManager.AUDIOFOCUS_LOSS:
                  pauseMedia();
                  break;
              }
            })
            .build();
    try {
      this.pipedOutputStream.connect(this.pipedInputStream);
    } catch (IOException e) {
      Log.e(TAG, e.getMessage());
    }

    this.downloadAudioThread = new DownloadAudioThread(
            this.localPlaybackState,
            this,
            this.mediaNotification,
            this.mediaSession,
            this.pipedOutputStream,
            this.lock
    );
    this.playAudioThread = new PlayAudioThread(pipedInputStream, localPlaybackState, lock);
    downloadAudioThread.start();
    playAudioThread.start();
  }
  public void setPlaybackState(@Constants.PLAYBACK_STATE int state) {
    localPlaybackState.set(state);
    var playbackStateCompat = playbackStateBuilder.setState(state, 0, 1.0f).build();
    mediaSession.setPlaybackState(playbackStateCompat);
    DataCache.getInstance().setPlayingState(state);
  }
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.i(TAG, "Entered on start command");
    boolean shouldPlay = intent.getExtras().getBoolean("MEDIA_STATE");
    if (shouldPlay) {
      playMedia();
    } else {
      pauseMedia();
    }
    return START_NOT_STICKY;
  }
  public void playMedia() {
    Log.i(TAG, "ENTERED PLAY MEDIA");
    boolean notPlaying = localPlaybackState.get() != PlaybackState.STATE_PLAYING.getValue();
    boolean hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_GAIN;
    if (notPlaying && hasFocus) {
        Notification n = mediaNotification.updateNotification(this, true);
        if (n != null) {
          startForeground(1, n);
        }
        setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
        synchronized (lock) {
          lock.notifyAll();
        }
    }
  }
  public void pauseMedia() {
    if (localPlaybackState.get() == PlaybackStateCompat.STATE_PLAYING) {
      Notification n = mediaNotification.updateNotification(this, false);
      if (n != null) {
        startForeground(1, n);
      }
      stopForeground(false);
      audioManager.abandonAudioFocusRequest(focusRequest);
      setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    unregisterReceiver(broadcastReceiver);
    executorService.shutdown();
    playAudioThread.interrupt();
    downloadAudioThread.interrupt();
  }

  @Nullable
  @Override
  public BrowserRoot onGetRoot(@NonNull String s, int i, @Nullable Bundle bundle) {
    return new BrowserRoot("MY_EMPTY_MEDIA_ROOT_ID", null);
  }

  @Override
  public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
    if (TextUtils.equals("MY_EMPTY_MEDIA_ROOT_ID", parentId)) {
      result.sendResult(null);
    }
  }

  public void getAlbumArt(String str) {
    Runnable getAlbumArt = new Runnable() {
      @Override
      public void run() {
        try {
          int i;
          if ((i = str.indexOf(" & ")) > -1) {
            test(str.split(" - "), i);
          } else {
            getArt(str);
          }
        } catch (AlbumArtNotFoundException e) {
          try {
            int i;
            String[] parts = str.split(" - ");
            if ((i = str.indexOf(" FT ")) > -1) {
              test(parts, i);
            } else if ((i = str.indexOf(" FEAT ")) > -1) {
              test(parts, i);
            }  else if (str.indexOf(" & ") > -1) {
              getArt(str);
            } else {
              throw e;
            }
          } catch (AlbumArtNotFoundException e2) {
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.orange_music_icon);
            DataCache.getInstance().setBitmap(bitmap);
            String[] parts = str.split(" - ");
            mediaSession.setMetadata(
                    mediaMetadataBuilder
                            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, parts[0])
                            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, parts[1])
                            .build());
            DataCache.getInstance().setTrack(parts);
            mainHandler.post(() -> startForeground(1, mediaNotification.createNotification(parts[0], parts[1], bitmap)));
          }
        }
      }
      public void test(String[] parts, int i) throws AlbumArtNotFoundException {
        int splitIndex = parts[0].length() + 2;
        if (i > splitIndex) {
          getArt(str.substring(0, i));
        } else {
          // i < splitIndex
          getArt(parts[0].substring(0 , i) + " - " + parts[1]);
        }
      }
      public void getArt(String searchTerm) throws AlbumArtNotFoundException {
        try {
          URL url = new URL(String.format("https://itunes.apple.com/search?term=%s&limit=1", URLEncoder.encode(searchTerm, "UTF-8")));
          Log.i(TAG, "iTunes Query URL: " + url.toString());
          HttpURLConnection connection = (HttpURLConnection) url.openConnection();
          try(
                  AutoCloseable ignored = connection::disconnect;
                  InputStream stream = connection.getInputStream();
                  BufferedReader reader = new BufferedReader(new InputStreamReader(stream))
          ) {
            connection.connect();
            StringBuilder sb = new StringBuilder();
            String line;
            while((line = reader.readLine()) != null) {
              sb.append(line).append("\n");
            }
            JSONArray results =  new JSONObject(sb.toString()).getJSONArray("results");
            if (results.length() > 0) {
              String artURL = results.getJSONObject(0).getString("artworkUrl100").replace("100x100", "320x320");
              Log.i(TAG, artURL);
              Bitmap bitmap = getImageBitmap(artURL);
              DataCache.getInstance().setBitmap(bitmap);
              String[] parts = str.split(" - ");
              mediaSession.setMetadata(
                      mediaMetadataBuilder
                              .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                              .putString(MediaMetadataCompat.METADATA_KEY_TITLE, parts[0])
                              .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, parts[1])
                              .build());
              DataCache.getInstance().setTrack(parts);
              mainHandler.post(() -> startForeground(1, mediaNotification.createNotification(parts[0], parts[1], bitmap)));
            } else {
              Log.i(TAG, "0 results found");
              throw new AlbumArtNotFoundException();
            }
          }
        }
        catch (AlbumArtNotFoundException e) {
          throw e;
        }
        catch (Exception e) {
          Log.e(TAG, e.toString());
        }
      }
      private Bitmap getImageBitmap(String url) {
        Bitmap bm = null;
        try {
          URL aURL = new URL(url);
          HttpURLConnection conn = (HttpURLConnection) aURL.openConnection();
          conn.connect();
          try
                  (
                          AutoCloseable ignored = conn::disconnect;
                          BufferedInputStream bis = new BufferedInputStream(conn.getInputStream())
                  )
          {
            bm = BitmapFactory.decodeStream(bis);
          }
        } catch (Exception e) {
          Log.e(TAG, "Error getting bitmap", e);
        }
        return bm;
      }
    };
    executorService.execute(getAlbumArt);
  }
}
