package com.example.gurvir.myapplication;

        import android.content.BroadcastReceiver;
        import android.content.Context;
        import android.content.Intent;

public class NotificationActionService extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    context.sendBroadcast(new Intent("NOTIFICATION_MEDIA_STATE_CHANGED")
            .putExtra("MEDIA_STATE", intent.getAction()));
  }
}