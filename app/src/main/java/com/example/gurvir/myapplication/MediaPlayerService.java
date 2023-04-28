package com.example.gurvir.myapplication;

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
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.AudioManagerCompat;
import androidx.media.MediaBrowserServiceCompat;

public class MediaPlayerService extends MediaBrowserServiceCompat {
  private final String TAG = "MyApp";
  private MediaNotification mediaNotification;
  private Thread longRunningTask;
  private Thread playAudioThread;
  private ADTSExtractor adtsExtractor;
  private AudioTrack audioTrack;
  private MediaCodec codec;
  private MediaCodec.BufferInfo info;
  private Handler mainHandler;
  private String icy_title;
  private AudioManager audioManager;
  private AudioFocusRequest focusRequest;
  private MediaSessionCompat mediaSession;
  private PlaybackStateCompat.Builder playbackStateBuilder;
  private MediaMetadataCompat.Builder mediaMetadataBuilder;
  private BroadcastReceiver broadcastReceiver;
  private ConnectivityManager connectivityManager;
  private ConnectivityManager.NetworkCallback networkCallback;
  private int localPlaybackState;
  private Network currentNetwork;

  @Override
  public void onCreate() {
    super.onCreate();
    localPlaybackState = PlaybackStateCompat.STATE_NONE;
    networkCallback =  new ConnectivityManager.NetworkCallback() {
      @Override
      public void onAvailable(@NonNull Network network) {
        super.onAvailable(network);
        currentNetwork = network;
      }

      @Override
      public void onLost(@NonNull Network network) {
        super.onLost(network);
        currentNetwork = null;
        mainHandler.postDelayed(() -> { if (currentNetwork == null) pauseMedia(); }, 10000);
      }
    };
    connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    connectivityManager.registerDefaultNetworkCallback(networkCallback);
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
    playbackStateBuilder = new PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY);
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
    setPlaybackState(PlaybackStateCompat.STATE_NONE);
    mediaSession.setActive(true);
    setSessionToken(mediaSession.getSessionToken());
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
    adtsExtractor = new ADTSExtractor();
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
    audioTrack = new AudioTrack(
            audioAttributes,
            new AudioFormat.Builder()
                    .setSampleRate(44100)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT),
            AudioTrack.MODE_STREAM,
            0
    );
  }
  public void setPlaybackState(@Constants.PLAYBACK_STATE int state) {
    localPlaybackState = state;
    mediaSession.setPlaybackState(playbackStateBuilder.setState(state, 0, 1.0f).build());
    DataCache.getInstance().setPlayingState(state);
  }
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    boolean shouldPlay = intent.getExtras().getBoolean("MEDIA_STATE");
    if (shouldPlay) {
      playMedia();
    } else {
      pauseMedia();
    }
    return START_NOT_STICKY;
  }
  public void playMedia() {
    if (localPlaybackState != PlaybackStateCompat.STATE_PLAYING && longRunningTask == null && audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_GAIN) {
        longRunningTask = new Thread(myLongRunningTask);
        longRunningTask.start();
        Notification n = mediaNotification.updateNotification(this, true);
        if (n != null) {
          startForeground(1, n);
        }
        setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
    }
  }
  public void pauseMedia() {
    if (localPlaybackState == PlaybackStateCompat.STATE_PLAYING) {
      if (longRunningTask != null) {
        longRunningTask.interrupt();
      }
      Notification n = mediaNotification.updateNotification(this, false);
      if (n != null) {
        startForeground(1, n);
      }
      stopForeground(false);
      audioManager.abandonAudioFocusRequest(focusRequest);
      setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
    }
  }

  Runnable myLongRunningTask = new Runnable() {
    private int icy_meta;
    private int offset;
    private int icy_offset;
    private String icy_name;
    private BufferedInputStream bufferedInputStream;
    private HttpURLConnection urlConnection;
    private Network cachedNetwork;

    public void initializeConnection() throws IOException {
      icy_meta = 0;
      offset = 0;
      icy_offset = 0;
      icy_name = null;
      cachedNetwork = currentNetwork;

      String ampRadio = "https://newcap.leanstream.co/CKMPFM";
      String virginRadio = "https://18583.live.streamtheworld.com/CIBKFMAAC_SC";
      if (urlConnection != null) {
        urlConnection.disconnect();
      }
      urlConnection = (HttpURLConnection) currentNetwork.openConnection(new URL(ampRadio));
      urlConnection.setRequestProperty("Icy-Metadata", "1");
      urlConnection.setRequestProperty("Accept-Encoding", "identity");
      urlConnection.connect();

      String headerField = urlConnection.getHeaderField("icy-metaint");
      icy_meta =  headerField != null ? Integer.parseInt(headerField) : 0;
      icy_offset = icy_meta;
      icy_name = urlConnection.getHeaderField("icy-name");
      if (icy_name != null && icy_name.trim().length() > 0) icy_name = null;
      Log.i(TAG, "ICY_META: " + icy_meta);

      if (bufferedInputStream != null) {
        bufferedInputStream.close();
      }
      bufferedInputStream = new BufferedInputStream(urlConnection.getInputStream());
    }

    Runnable decodeAndWriteRunnable = new Runnable() {
      @Override
      public void run() {
        audioTrack.play();

        final long kTimeOutUs = 1000;
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        int noOutputCounter = 0;
        int noOutputCounterLimit = 50;
        while(!sawOutputEOS && noOutputCounter < noOutputCounterLimit && !playAudioThread.isInterrupted()) {
          noOutputCounter++;
          if (!sawInputEOS) {
            int inputBufIndex = codec.dequeueInputBuffer(kTimeOutUs);
            if (inputBufIndex >= 0) {
              ByteBuffer inputBuffer = codec.getInputBuffer(inputBufIndex);
              int sampleSize = -1;
              try {
                sampleSize = adtsExtractor.readSampleData(inputBuffer);
              } catch (IOException e) {
                Log.e(TAG, "READ SAMPLE DATA ERROR", e);
              }
              if (sampleSize < 0) {
                Log.i(TAG, "saw input eos");
                sawInputEOS = true;
                sampleSize = 0;
              }
              codec.queueInputBuffer(
                      inputBufIndex,
                      0,
                      sampleSize,
                      0,
                      sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0
              );
            }
          }
          int outputBufIndex = codec.dequeueOutputBuffer(info, kTimeOutUs);
          if (outputBufIndex >=0)  {
            if (info.size >  0) {
              noOutputCounter = 0;
            }

            ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufIndex);
            final byte[] chunk = new byte[info.size];
            outputBuffer.get(chunk);
            outputBuffer.clear();
            if (chunk.length > 0) {
              audioTrack.write(chunk, 0, chunk.length);
            }
            codec.releaseOutputBuffer(outputBufIndex, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              Log.i(TAG, "saw output eos");
              sawOutputEOS = true;
            }
          }
        }
        playAudioThread = null;
        Log.i(TAG, "FINISHED 2");
      }
    };
    @Override
    public void run() {
        try {
          initializeConnection();
          try
                  (
                          PipedInputStream pipedInputStream = new PipedInputStream(32000);
                          PipedOutputStream pipedOutputStream = new PipedOutputStream(pipedInputStream)
                  )
          {
            readStream(bufferedInputStream, icy_meta, pipedOutputStream);
            mainHandler.postDelayed(() -> {
              if (icy_title == null && localPlaybackState == PlaybackStateCompat.STATE_PLAYING) {
                Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.orange_music_icon);
                DataCache.getInstance().setBitmap(bitmap);
                String contentTitle = icy_name == null ? "Unknown Song" : icy_name;
                String contentText = icy_name == null ? "Unknown Artist" : "Unknown Artist - Unknown Song";
                DataCache.getInstance().setTrack(new String[] {contentTitle, contentText});
                startForeground(1, mediaNotification.createNotification(contentTitle, contentText, bitmap));
                mediaSession.setMetadata(
                        mediaMetadataBuilder
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, contentTitle)
                                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, contentText)
                                .build());
              }
            }, 500);
            adtsExtractor.setDataSource(pipedInputStream);
            MediaFormat format = adtsExtractor.getTrackFormat();
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            info = new MediaCodec.BufferInfo();
            if (playAudioThread == null) {
              playAudioThread = new Thread(decodeAndWriteRunnable);
              playAudioThread.start();
            }
            while(!longRunningTask.isInterrupted()) {
              if (cachedNetwork == currentNetwork) {
                // if network we're using is same as the one the system is using
                readStream(bufferedInputStream, icy_meta, pipedOutputStream);
              } else if (currentNetwork != null) {
                // else if we've switched networks
                initializeConnection();
              }
            }
          } finally {
            urlConnection.disconnect();
            bufferedInputStream.close();
          }
        } catch (Exception e) {
          if (e.getClass() != InterruptedIOException.class) {
            Log.e(TAG, e.getClass().toString(), e);
          }
        } finally {
          Log.i(TAG, "FINISHED 1");
          longRunningTask = null;
          if (playAudioThread != null) {
            playAudioThread.interrupt();
          } else {
            Log.i(TAG, "FINISHED 2");
          }
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
            Log.e(TAG, "Error", e);
          }
        }
      };
      Thread t = new Thread(getAlbumArt);
      t.start();
    }
    private void readStream(InputStream inputStream, int i_len, OutputStream outputStream) {
      int i_chunk = i_len;
      if (icy_meta > 0) {
        if (Integer.MAX_VALUE - i_chunk < offset)
          i_chunk = Integer.MAX_VALUE - offset;

        if (offset + i_chunk > icy_offset)
          i_chunk = icy_offset - offset;
      }
      int i_read;
      try {
        byte[] buffer = new byte[i_chunk];
        i_read = inputStream.read(buffer,0 , i_chunk);
        if (i_read < 0) return;
        outputStream.write(buffer, 0, i_read);
        offset += i_read;
        if (icy_meta > 0 && offset == icy_offset) {
          if (readICYMeta(inputStream) != 0) return;
          icy_offset = offset + icy_meta;
        }
      } catch (IOException e) {
        mainHandler.post(() -> pauseMedia());
      } catch (Exception e) {
        if (e.getClass() != InterruptedIOException.class) {
          Log.e(TAG, "error", e);
        }
      }
    }
    private int readICYMeta(InputStream stream) {
      int buffer;
      byte[] psz_meta;
      int i_read;
      try {
        buffer = stream.read();
        if (buffer == -1) {
          return -1;
        }
        int i_size = buffer << 4;
        psz_meta = new byte[i_size + 1];
        for (i_read = 0; i_read < i_size;) {
          int i_tmp = stream.read(psz_meta, 0, i_size - i_read);
          if (i_tmp <= 0) {
            return -1;
          }
          i_read += i_tmp;
        }
        String s = new String(psz_meta, StandardCharsets.UTF_8);
        if (s.contains("StreamTitle=")) {
          final String meta = s.substring(s.indexOf("'") + 1,  s.indexOf("';")).replace("*", "");
          if (!meta.equals(icy_title)) {
            Log.i(TAG, meta);
            icy_title = meta;
            getAlbumArt(meta);
          }
        }
        return 0;

      } catch (IOException e) {
        return -1;
      }
    }
  };

  @Override
  public void onDestroy() {
    super.onDestroy();
    unregisterReceiver(broadcastReceiver);
    connectivityManager.unregisterNetworkCallback(networkCallback);
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
}
