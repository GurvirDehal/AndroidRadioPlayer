package com.example.gurvir.myapplication;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.AsyncTask;
import android.os.Bundle;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;


import com.google.android.exoplayer2.SimpleExoPlayer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
  private final String TAG = "MyApp";
  MediaExtractor extractor;
  MediaCodec codec;
  AudioTrack audioTrack;
  SimpleExoPlayer player;
  PipedOutputStream pipedOutputStream;
  PipedInputStream pipedInputStream;
  MediaCodec.BufferInfo info;
  ADTSExtractor adtsExtractor;
  TextView txt;
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    adtsExtractor = new ADTSExtractor();
    extractor = new MediaExtractor();
    audioTrack = new AudioTrack(
            AudioManager.STREAM_MUSIC,
            44100,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioTrack.getMinBufferSize(
                    44100,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
            ),
            AudioTrack.MODE_STREAM
    );
    audioTrack.play();
    Button btn = (Button) findViewById(R.id.mainButton);
    txt = (TextView) findViewById(R.id.currentSong);
    btn.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        Log.i("MyApp", "Button Clicked");
        new LongRunningTask().execute();
      }
    });
  }

  private class LongRunningTask extends AsyncTask<Void, Void, Void> {
    private String icy_title;
    private int icy_meta;
    private int offset;
    private int icy_offset;
    private void printByteArray(byte[] b, int length, String str) {
      StringBuilder s = new StringBuilder();
      int l=  Math.min(length, b.length);
      for(int i=0; i< l; i++) {
        int j = b[i] & 0xFF;
        s.append(String.format("%02x", (byte) j) + " ");
      }
      Log.i("MyApp", str + ": " + s.toString());
    }

    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        audioTrack.pause();
        audioTrack.setPlaybackHeadPosition(100);
        audioTrack.play();
        final long kTimeOutUs = 1000;
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        int noOutputCounter = 0;
        int noOutputCounterLimit = 50;
        boolean doStop = false;
        while(!sawOutputEOS && noOutputCounter < noOutputCounterLimit && !doStop) {
          noOutputCounter++;
          if (!sawInputEOS) {
            int inputBufIndex = codec.dequeueInputBuffer(kTimeOutUs);
            if (inputBufIndex >= 0) {
              ByteBuffer dstBuf = codec.getInputBuffer(inputBufIndex);
              int sampleSize = -1;
              try {
                sampleSize = adtsExtractor.readSampleData(dstBuf);
              } catch (IOException e) {
                System.exit(-1);
              }
              long presentationTimeUs = 0;
              if (sampleSize < 0) {
                Log.i("MyApp", "saw input eos");
                sawInputEOS = true;
                sampleSize = 0;
              } else {
                presentationTimeUs = 0;
              }
              codec.queueInputBuffer(
                      inputBufIndex,
                      0,
                      sampleSize,
                      presentationTimeUs,
                      sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0
              );
            }
          }
          int res = codec.dequeueOutputBuffer(info, kTimeOutUs);
          if (res >=0)  {
            if (info.size >  0) {
              noOutputCounter = 0;
            }

            int outputBufIndex = res;
            ByteBuffer buf2 = codec.getOutputBuffer(outputBufIndex);
            final byte[] chunk = new byte[info.size];
            buf2.get(chunk);
            buf2.clear();
            if (chunk.length > 0) {
              audioTrack.write(chunk, 0, chunk.length);
            }
            codec.releaseOutputBuffer(outputBufIndex, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              Log.i("MyApp", "saw output eos");
              sawOutputEOS = true;
            }
          }
        }
      }
    };

    @Override
    protected Void doInBackground(Void... voids) {
      icy_title = null;
      icy_meta = 0;
      offset = 0;
      icy_offset = 0;
      try {
        String ampRadio = "https://newcap.leanstream.co/CKMPFM";
        String virginRadio = "https://18583.live.streamtheworld.com/CIBKFMAAC_SC";
        URL url = new URL(virginRadio);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setRequestProperty("Icy-Metadata", "1");
        String headerField = urlConnection.getHeaderField("icy-metaint");
        icy_meta = Integer.parseInt(headerField);
        icy_offset = icy_meta;
        Log.i("MyApp", Integer.toString(icy_meta));
        try {
          InputStream in = new BufferedInputStream((urlConnection.getInputStream()));
          pipedInputStream = new PipedInputStream(icy_meta* 2);
          pipedOutputStream = new PipedOutputStream(pipedInputStream);
          adtsExtractor.setDataSource(pipedInputStream);
          readStream(in, icy_meta, pipedOutputStream);
          MediaFormat format = adtsExtractor.getTrackFormat();
          String mime = format.getString(MediaFormat.KEY_MIME);
          codec = MediaCodec.createDecoderByType(mime);
          codec.configure(format, null, null, 0);
          codec.start();
          info = new MediaCodec.BufferInfo();
          new Thread(runnable).start();
          for(;;) {
            readStream(in, icy_meta, pipedOutputStream);
          }
        } finally {
          urlConnection.disconnect();
          Log.i("MyApp", "Finished");
        }
      } catch (Exception e) {
        Log.e("MyApp", "error", e);
      } finally {
        return null;
      }
    }
    private int readStream(InputStream stream, int i_len, OutputStream b) {
      int i_chunk = i_len;
      if (icy_meta > 0) {
        if (Integer.MAX_VALUE - i_chunk < offset) {
          i_chunk = Integer.MAX_VALUE - offset;
        }
        if (offset + i_chunk > icy_offset)
          i_chunk = icy_offset - offset;
      }
      int i_read = 0;
      try {
        byte[] buffer = new byte[i_chunk];
        i_read = stream.read(buffer,0 , i_chunk);
        if (i_read < 0) return -1;
        b.write(buffer, 0, i_read);
        offset += i_read;
        if (icy_meta > 0 && offset == icy_offset) {
          if (readICYMeta(stream) != 0) return 0;
          icy_offset = offset + icy_meta;
        }
        return i_read;
      } catch (Exception e) {
        Log.e("MyApp", "error", e);
        return -1;
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
        String s = new String(psz_meta, "UTF-8");
        if (s.indexOf("StreamTitle=") != -1) {
          String meta = s.substring(s.indexOf("'") + 1,  s.indexOf("';"));
          if (icy_title == null || !meta.equals(icy_title)) {
            Log.i("MyApp", meta);
            icy_title = meta;
            txt.post(new Runnable() {
              @Override
              public void run() {
                txt.setText(icy_title);
              }
            });
          }
        }
        return 0;

      } catch (IOException e) {
        return -1;
      }
    }
  }
}
