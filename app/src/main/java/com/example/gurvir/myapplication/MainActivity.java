package com.example.gurvir.myapplication;

import android.content.Intent;
import android.os.Bundle;

import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;


import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
  private final String TAG = "MyApp";
  private TextView txt;
  private ImageView albumArt;
  private ImageButton imageButton;


  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    imageButton = findViewById(R.id.playButton);
    txt = findViewById(R.id.currentSong);
    albumArt = findViewById(R.id.albumArt);

    DataCache.getInstance().getBitmap().observe(
            this,
            bitmap -> albumArt.setImageBitmap(bitmap)
    );
    DataCache.getInstance().getTrack().observe(
            this,
            track -> txt.setText(String.format("%s%s%s", track[0], System.lineSeparator(), track[1]))
    );
    DataCache.getInstance().getPlayingState().observe(
            this,
            state-> {
              int img = state == PlaybackStateCompat.STATE_PLAYING ? R.drawable.ic_pause : R.drawable.ic_play;
              imageButton.setImageResource(img);
            }
    );

    imageButton.setOnClickListener(v -> {
//      Log.i("MyApp", "Button Clicked");
      if (DataCache.getInstance().getPlayingState().getValue() != PlaybackStateCompat.STATE_PLAYING) {
        startService(true);
      } else {
        startService(false);
      }
    });
  }

  private void stopService() {
    Intent serviceIntent = new Intent(this, MediaPlayerService.class);
    stopService(serviceIntent);
  }

  private void startService(boolean state) {
    Intent serviceIntent = new Intent(this, MediaPlayerService.class);
    serviceIntent.putExtra("MEDIA_STATE", state);
    startService(serviceIntent);
  }
}
