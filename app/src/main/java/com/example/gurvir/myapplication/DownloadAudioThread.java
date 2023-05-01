package com.example.gurvir.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PipedOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadAudioThread extends Thread {
        private static final String TAG = "MyApp";
        private int icy_meta;
        private int offset;
        private int icy_offset;
        private String icy_name;
        private String icy_title;
        private BufferedInputStream bufferedInputStream;
        private HttpURLConnection urlConnection;
        private final Handler mainHandler;
        private final AtomicInteger localPlaybackState;
        private final MediaPlayerService context;
        private final MediaNotification mediaNotification;
        private final MediaSessionCompat mediaSession;
        private final MediaMetadataCompat.Builder mediaMetadataBuilder;
        private final PipedOutputStream pipedOutputStream;
        private final Object lock;
        private final ConnectivityManager connectivityManager;
        private final ConnectivityManager.NetworkCallback networkCallback;
        private Network currentNetwork;
        private Network cachedNetwork;

        public DownloadAudioThread(
                AtomicInteger localPlaybackState,
                MediaPlayerService context,
                MediaNotification mediaNotification,
                MediaSessionCompat mediaSession,
                PipedOutputStream pipedOutputStream,
                Object lock
        ) {
            this.mainHandler = new Handler(Looper.getMainLooper());
            this.localPlaybackState = localPlaybackState;
            this.context = context;
            this.mediaSession = mediaSession;
            this.mediaNotification = mediaNotification;
            this.mediaMetadataBuilder = new MediaMetadataCompat.Builder()
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1);
            this.pipedOutputStream = pipedOutputStream;
            this.lock = lock;
            this.networkCallback =  new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    super.onAvailable(network);
                    currentNetwork = network;
                }

                @Override
                public void onLost(@NonNull Network network) {
                    super.onLost(network);
                    currentNetwork = null;
                    mainHandler.postDelayed(() -> {
                        if (currentNetwork == null) context.pauseMedia();
                    }, 10000);
                }
            };
            this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            this.connectivityManager.registerDefaultNetworkCallback(networkCallback);
            this.currentNetwork = connectivityManager.getActiveNetwork();
        }

        public void initializeConnection() throws IOException {
            icy_meta = 0;
            offset = 0;
            icy_offset = 0;
            icy_name = null;
            cachedNetwork = currentNetwork;
            if (currentNetwork == null) {
                throw new RuntimeException("Current Network null");
            }

            String ampRadio = "https://newcap.leanstream.co/CKMPFM";
            String virginRadio = "https://15313.live.streamtheworld.com/CKFMFMAAC.aac";
            URL url = new URL(virginRadio);
            urlConnection = (HttpURLConnection) url.openConnection();
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
            Log.i(TAG, "connection established");
        }

        @Override
        public void run() {
            Log.i(TAG, "DownloadAudioThread running");
            try {
                initializeConnection();
                try {
                    readStream(bufferedInputStream, pipedOutputStream);
                    mainHandler.postDelayed(() -> {
                        if (icy_title == null && localPlaybackState.get() == PlaybackStateCompat.STATE_PLAYING) {
                            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.orange_music_icon);
                            DataCache.getInstance().setBitmap(bitmap);
                            String contentTitle = icy_name == null ? "Unknown Song" : icy_name;
                            String contentText = icy_name == null ? "Unknown Artist" : "Unknown Artist - Unknown Song";
                            DataCache.getInstance().setTrack(new String[] {contentTitle, contentText});

                            context.startForeground(1, mediaNotification.createNotification(contentTitle, contentText, bitmap));
                            mediaSession.setMetadata(
                                    mediaMetadataBuilder
                                            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                                            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, contentTitle)
                                            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, contentText)
                                            .build());
                        }
                    }, 500);

                    while(shouldPlay()) {
                        if (cachedNetwork == currentNetwork) {
                            // if network we're using is same as the one the system is using
                            readStream(bufferedInputStream, pipedOutputStream);
                        } else if (currentNetwork != null) {
                            // else if we've switched networks
                            initializeConnection();
                        }
                    }
                }
                finally {
                    Log.i(TAG, "URL connection disconnected");
                    urlConnection.disconnect();
                    bufferedInputStream.close();
                }
            } catch (Exception e) {
                if (!(e instanceof InterruptedIOException)) {
                    Log.e(TAG, e.toString());
                }
            } finally {
                Log.i(TAG, "DownloadAudioThread Finished");
                connectivityManager.unregisterNetworkCallback(networkCallback);
                try {
                    pipedOutputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, e.toString());
                }
            }
        }
        private void readStream(InputStream inputStream, OutputStream outputStream) {
            int i_chunk = icy_meta;
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
                Log.e(TAG, e.toString());
                mainHandler.post(context::pauseMedia);
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
                for (i_read = 0; i_read < i_size; ) {
                    int i_tmp = stream.read(psz_meta, 0, i_size - i_read);
                    if (i_tmp <= 0) {
                        return -1;
                    }
                    i_read += i_tmp;
                }
                String s = new String(psz_meta, StandardCharsets.UTF_8);
                if (s.contains("StreamTitle=")) {
                    final String meta = s.substring(s.indexOf("'") + 1, s.indexOf("';")).replace("*", "");
                    if (!meta.equals(icy_title)) {
                        Log.i(TAG, meta);
                        icy_title = meta;
                        context.getAlbumArt(meta);
                    }
                }
                return 0;

            } catch (IOException e) {
                return -1;
            }
        }

        private boolean shouldPlay() {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            if (localPlaybackState.get() != PlaybackStateCompat.STATE_PLAYING) {
                try {
                    synchronized (lock) {
                        lock.wait();
                    }
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return true;
        }
}
