package com.example.gurvir.myapplication;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.io.PipedInputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public class PlayAudioThread extends Thread {
        private static final String TAG = "MyApp";
        private final AudioTrack audioTrack;
        private MediaCodec codec;
        private final ADTSExtractor adtsExtractor;
        private final PipedInputStream pipedInputStream;
        private final MediaCodec.BufferInfo info;
        private final AtomicInteger localPlaybackState;

        private final Object lock;

        public PlayAudioThread(PipedInputStream inputStream, AtomicInteger localPlaybackState, Object lock) {
            this.pipedInputStream = inputStream;
            this.adtsExtractor = new ADTSExtractor();
            this.info = new MediaCodec.BufferInfo();
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            this.audioTrack = new AudioTrack(
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
            this.localPlaybackState = localPlaybackState;
            this.lock = lock;
        }

        private void initialize() {
            try {
                this.adtsExtractor.setDataSource(pipedInputStream);
                MediaFormat format = adtsExtractor.getTrackFormat();
                var x = format.getString(MediaFormat.KEY_MIME);
                if (x == null) {
                    throw new RuntimeException("MimeKEy is null");
                }
                codec = MediaCodec.createDecoderByType(x);
                codec.configure(format, null, null, 0);
                codec.start();
            } catch (IOException e) {
                Log.e(TAG, e.toString());
            }
        }

        @Override
        public void run() {
            Log.i(TAG, "Started PlayAudioThread");
            this.initialize();
            audioTrack.play();

            final long kTimeOutUs = 1000;
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;
            int noOutputCounter = 0;
            int noOutputCounterLimit = 50;

            while(isPlayingState(sawOutputEOS, noOutputCounter, noOutputCounterLimit)) {
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
                    Objects.requireNonNull(outputBuffer).get(chunk);
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
            audioTrack.pause();
            audioTrack.flush();
            audioTrack.stop();
            try {
                pipedInputStream.close();
            } catch (IOException e) {
                Log.e(TAG, e.toString());
            }
            Log.i(TAG, "FINISHED PlayAudioThread");
        }

    /**
     * @return Whether or not it is okay to play the audio.
     */
    private boolean isPlayingState(boolean sawOutputEOS, int noOutputCounter, int noOutputCounterLimit) {
            boolean isInterrupted = Thread.currentThread().isInterrupted();
            boolean overLimit = noOutputCounter >= noOutputCounterLimit;
            if (isInterrupted || sawOutputEOS || overLimit) {
                return false;
            }
            if (localPlaybackState.get() != Constants.PlaybackState.STATE_PLAYING.getValue()) {
                try {
                    audioTrack.pause();
                    synchronized (lock) {
                        lock.wait();
                    }
                    audioTrack.play();
                } catch (InterruptedException e) {
                    Log.e(TAG, e.toString());
                    return false;
                }
            }
            return true;
        }
 }
