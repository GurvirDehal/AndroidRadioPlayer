package com.example.gurvir.myapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

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
  private boolean playingState;


  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    imageButton = findViewById(R.id.playButton);
    txt = findViewById(R.id.currentSong);
    albumArt = findViewById(R.id.albumArt);
    playingState = false;

    registerReceiver(new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        String[] type = intent.getExtras().getStringArray("UPDATE_TYPE");
        switch (type[0]) {
          case "MEDIA_STATE":
            updateMediaStateUI(type[1]);
            break;
          case "TEXT":
            txt.setText(type[1]);
            break;
          case "ALBUM_ART":
            albumArt.setImageBitmap(MediaPlayerService.getBitmap());
            break;
        }
      }
    }, new IntentFilter("ACTION_UPDATE_UI"));

    imageButton.setOnClickListener(v -> {
      Log.i("MyApp", "Button Clicked");
      if (!playingState) {
        startService("PLAYING");
      } else {
        startService("PAUSED");
      }
    });
  }

  private void updateMediaStateUI(String state) {
    switch (state) {
      case "PLAYING":
        imageButton.setImageResource(R.drawable.ic_baseline_pause_24);
        playingState = true;
        break;
      case "PAUSED":
        imageButton.setImageResource(R.drawable.ic_baseline_play_arrow_24);
        playingState = false;
        break;
    }
  }

  private void stopService() {
    Intent serviceIntent = new Intent(this, MediaPlayerService.class);
    stopService(serviceIntent);
  }

  private void startService(String state) {
    Intent serviceIntent = new Intent(this, MediaPlayerService.class);
    serviceIntent.putExtra("MEDIA_STATE", state);
    startService(serviceIntent);
  }
}
