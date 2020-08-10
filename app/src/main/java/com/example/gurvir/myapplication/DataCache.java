package com.example.gurvir.myapplication;

import android.graphics.Bitmap;
import android.os.Looper;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public class DataCache {
    private volatile static DataCache instance = null;

    private MutableLiveData<Bitmap> bitmapLiveData = new MutableLiveData<>();
    private MutableLiveData<String[]> trackLiveData = new MutableLiveData<>();
    private volatile MutableLiveData<Integer> playingStateLiveData = new MutableLiveData<>();

    private DataCache() {
        if (Looper.getMainLooper().isCurrentThread()) {
            trackLiveData.setValue(new String[] {"Listen to the Radio", ""});
            playingStateLiveData.setValue(PlaybackStateCompat.STATE_NONE);
        } else {
            trackLiveData.postValue(new String[] {"Listen to the Radio", ""});
            playingStateLiveData.postValue(PlaybackStateCompat.STATE_NONE);
        }
    }

    private synchronized static void createInstance() {
        if (instance == null)
            instance = new DataCache();
    }

    public static DataCache getInstance() {
        if (instance == null) createInstance();
        return instance;
    }

    public void setBitmap(Bitmap bmp) {
        if (Looper.getMainLooper().isCurrentThread()) {
            bitmapLiveData.setValue(bmp);
        } else {
            bitmapLiveData.postValue(bmp);
        }
    }

    public LiveData<Bitmap> getBitmap() {
        return bitmapLiveData;
    }

    public void setTrack(String[] text) {
        if (Looper.getMainLooper().isCurrentThread()) {
            trackLiveData.setValue(text);
        } else {
            trackLiveData.postValue(text);
        }
    }

    public LiveData<String[]> getTrack() {
        return trackLiveData;
    }

    public void setPlayingState(@Constants.PLAYBACK_STATE int state) {
        if (Looper.getMainLooper().isCurrentThread()) {
            playingStateLiveData.setValue(state);
        } else {
            playingStateLiveData.postValue(state);
        }
    }
    public LiveData<Integer> getPlayingState() {
        return playingStateLiveData;
    }
}