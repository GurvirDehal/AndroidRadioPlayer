package com.example.gurvir.myapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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

import androidx.annotation.Nullable;
import androidx.media.AudioAttributesCompat;
import androidx.media.AudioFocusRequestCompat;
import androidx.media.AudioManagerCompat;

public class MediaPlayerService extends Service {
  private final String TAG = "MyApp";
  private MediaNotification mediaNotification;
  private boolean playingState;
  private Thread longRunningTask;
  private Thread playAudioThread;
  private ADTSExtractor adtsExtractor;
  private AudioTrack audioTrack;
  private MediaCodec codec;
  private MediaCodec.BufferInfo info;
  private static Bitmap bitmap;
  private Handler mainHandler;
  private String icy_title;
  private String icy_name;
  private AudioManager audioManager;
  private AudioFocusRequest focusRequest;

  @Override
  public void onCreate() {
    super.onCreate();
    mainHandler = new Handler(Looper.getMainLooper());
    playingState = false;
    NotificationChannel channel = new NotificationChannel(
            MediaNotification.CHANNEL_ID,
            "Radio Player",
            NotificationManager.IMPORTANCE_HIGH
    );
    channel.setSound(null, null);
    NotificationManager notificationManager = getSystemService(NotificationManager.class);
    notificationManager.createNotificationChannel(channel);
    mediaNotification = new MediaNotification(this);

    registerReceiver(new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (playingState) {
          pauseMedia();
        } else {
          playMedia();
        }
      }
    }, new IntentFilter("NOTIFICATION_MEDIA_STATE_CHANGED"));

    startService(new Intent(getBaseContext(), OnClearFromRecentService.class));

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

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent.getExtras().getString("MEDIA_STATE");
    switch (action) {
      case "PLAYING":
        playMedia();
        break;
      case "PAUSED":
        pauseMedia();
        break;
    }
    return START_NOT_STICKY;
  }
  public void updateUI(String updateType, String data) {
    Intent broadcastIntent = new Intent("ACTION_UPDATE_UI");
    broadcastIntent.putExtra("UPDATE_TYPE", new String[] { updateType, data});
    sendBroadcast(broadcastIntent);
  }

  public static Bitmap getBitmap() {
    return bitmap;
  }
  public void playMedia() {
    if (!playingState && audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_GAIN) {
      longRunningTask = new Thread(myLongRunningTask);
      longRunningTask.start();
      updateUI("MEDIA_STATE", "PLAYING");
      Notification n = mediaNotification.updateNotification(this, true);
      if (n != null) {
        startForeground(1, n);
      }
      playingState = !playingState;
    }
  }
  public void pauseMedia() {
    if (playingState) {
      longRunningTask.interrupt();
      updateUI("MEDIA_STATE", "PAUSED");
      Notification n = mediaNotification.updateNotification(this, false);
      if (n != null) {
        startForeground(1, n);
      }
      stopForeground(false);
      playingState = !playingState;
    }
  }

  Runnable myLongRunningTask = new Runnable() {
    private int icy_meta;
    private int offset;
    private int icy_offset;

    Runnable runnable = new Runnable() {
      @Override
      public void run() {
//        audioTrack.pause();
//        audioTrack.setPlaybackHeadPosition(100);
        audioTrack.play();

        final long kTimeOutUs = 1000;
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        int noOutputCounter = 0;
        int noOutputCounterLimit = 50;
        boolean doStop = playAudioThread.isInterrupted();
        while(!sawOutputEOS && noOutputCounter < noOutputCounterLimit && !doStop) {
          noOutputCounter++;
          if (!sawInputEOS) {
            int inputBufIndex = codec.dequeueInputBuffer(kTimeOutUs);
            if (inputBufIndex >= 0) {
              ByteBuffer inputBuffer = codec.getInputBuffer(inputBufIndex);
              int sampleSize = -1;
              try {
                sampleSize = adtsExtractor.readSampleData(inputBuffer);
              } catch (IOException e) {
                Log.e(TAG, "Error", e);
              }
              if (sampleSize < 0) {
                Log.i("MyApp", "saw input eos");
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
              Log.i("MyApp", "saw output eos");
              sawOutputEOS = true;
            }
          }
          doStop = playAudioThread.isInterrupted();
        }
      }
    };

    @Override
    public void run() {
      icy_title = null;
      icy_meta = 0;
      offset = 0;
      icy_offset = 0;
      try {
        String ampRadio = "https://newcap.leanstream.co/CKMPFM";
        String virginRadio = "https://18583.live.streamtheworld.com/CIBKFMAAC_SC";
        URL url = new URL(ampRadio);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setRequestProperty("Icy-Metadata", "1");
        urlConnection.setRequestProperty("Accept-Encoding", "identity");
        String headerField = urlConnection.getHeaderField("icy-metaint");
        icy_meta = Integer.parseInt(headerField);
        icy_offset = icy_meta;
        icy_name = urlConnection.getHeaderField("icy-name");
        if (icy_name.trim().length() > 0) icy_name = null;
        Log.i("MyApp", Integer.toString(icy_meta));
        try
                (
                  InputStream in = new BufferedInputStream((urlConnection.getInputStream()));
                  PipedInputStream pipedInputStream = new PipedInputStream(icy_meta * 2);
                  PipedOutputStream pipedOutputStream = new PipedOutputStream(pipedInputStream);
                  AutoCloseable ignored = urlConnection::disconnect;
                )
        {
          adtsExtractor.setDataSource(pipedInputStream);
          readStream(in, icy_meta, pipedOutputStream);
          mainHandler.postDelayed(() -> {
            if (icy_title == null) {
              updateUI("TEXT", icy_name == null ? "Unknown Artist - Unknown Song" : icy_name);
              bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.orange_music_icon);
              String contentTitle = icy_name == null ? "Unknown Song" : icy_name;
              String contentText = icy_name == null ? "Unknown Artist" : "Unknown Artist - Unknown Song";
              Notification n = mediaNotification.createNotification(contentTitle, contentText, bitmap);
              startForeground(1, n);
            }
          }, 500);
          MediaFormat format = adtsExtractor.getTrackFormat();
          String mime = format.getString(MediaFormat.KEY_MIME);
          codec = MediaCodec.createDecoderByType(mime);
          codec.configure(format, null, null, 0);
          codec.start();
          info = new MediaCodec.BufferInfo();
          playAudioThread = new Thread(runnable);
          playAudioThread.start();
          while(!longRunningTask.isInterrupted()) {
            readStream(in, icy_meta, pipedOutputStream);
          }
        } finally {
          playAudioThread.interrupt();
          Log.i("MyApp", "Finished");
        }
      } catch (Exception e) {
        Log.e("MyApp", "error", e);
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
            getArt(str);
          } catch (AlbumArtNotFoundException e) {
            try {
              int i;
              if ((i = str.indexOf(" FT ")) > -1) {
                getArt(str.substring(0, i));
              } else if ((i = str.indexOf(" FEAT ")) > -1) {
                getArt(str.substring(0, i));
              }
            } catch (AlbumArtNotFoundException e2) {
              bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.orange_music_icon);
              updateUI("ALBUM_ART", "");
              mainHandler.post(() -> {
                Notification notification = mediaNotification.createNotification(str, bitmap);
                startForeground(1, notification);
              });
            }
          }
        }
        public void getArt(String searchTerm) throws AlbumArtNotFoundException {
          try {
            URL url = new URL(String.format("https://itunes.apple.com/search?term=%s&limit=1", URLEncoder.encode(searchTerm, "UTF-8")));
            Log.i("MyApp", "iTunes Query URL: " + url.toString());
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
                String artURL = results.getJSONObject(0).getString("artworkUrl100").replace("100x100", "200x200");
                Log.i(TAG, artURL);
                bitmap = getImageBitmap(artURL);
                updateUI("ALBUM_ART", "");
                mainHandler.post(() -> {
                  Notification notification = mediaNotification.createNotification(str, bitmap);
                  startForeground(1, notification);
                });
              } else {
                Log.i("MyApp", "0 results found");
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
    private void readStream(InputStream stream, int i_len, OutputStream b) {
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
        i_read = stream.read(buffer,0 , i_chunk);
        if (i_read < 0) return;
        b.write(buffer, 0, i_read);
        offset += i_read;
        if (icy_meta > 0 && offset == icy_offset) {
          if (readICYMeta(stream) != 0) return;
          icy_offset = offset + icy_meta;
        }
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
            Log.i("MyApp", meta);
            icy_title = meta;
            getAlbumArt(meta);
            updateUI("TEXT", meta);
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
  }
  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
