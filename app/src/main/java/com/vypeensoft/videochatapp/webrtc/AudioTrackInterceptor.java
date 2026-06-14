package com.vypeensoft.videochatapp.webrtc;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import java.nio.ByteBuffer;

public final class AudioTrackInterceptor extends AudioTrack {
    public final AudioTrack originalTrack;
    
    public interface AudioDebugCallback {
        void onWebRtcAudioPlayoutSamplesReady(byte[] data, int sampleRate, int channelCount);
    }
    
    private final AudioDebugCallback callback;
    private final int sampleRate;
    private final int channelCount;

    public AudioTrackInterceptor(@NonNull AudioTrack originalTrack, int sampleRate, int channelCount, @NonNull AudioDebugCallback callback) {
        super(
            AudioManager.STREAM_VOICE_CALL,
            sampleRate,
            channelCount == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            Math.max(2048, AudioTrack.getMinBufferSize(sampleRate, channelCount == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)),
            AudioTrack.MODE_STREAM
        );
        this.originalTrack = originalTrack;
        this.callback = callback;
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
    }

    @Override
    public void play() {
        originalTrack.play();
    }

    @Override
    public void stop() {
        originalTrack.stop();
    }

    @Override
    public void pause() {
        originalTrack.pause();
    }

    @Override
    public void flush() {
        originalTrack.flush();
    }

    @Override
    public void release() {
        originalTrack.release();
    }

    @Override
    public int getPlayState() {
        return originalTrack.getPlayState();
    }

    @Override
    public int getPlaybackHeadPosition() {
        return originalTrack.getPlaybackHeadPosition();
    }

    @Override
    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes) {
        int result = originalTrack.write(audioData, offsetInBytes, sizeInBytes);
        if (result > 0) {
            byte[] dataCopy = new byte[result];
            System.arraycopy(audioData, offsetInBytes, dataCopy, 0, result);
            callback.onWebRtcAudioPlayoutSamplesReady(dataCopy, sampleRate, channelCount);
        }
        return result;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes, int writeMode) {
        int result = originalTrack.write(audioData, offsetInBytes, sizeInBytes, writeMode);
        if (result > 0) {
            byte[] dataCopy = new byte[result];
            System.arraycopy(audioData, offsetInBytes, dataCopy, 0, result);
            callback.onWebRtcAudioPlayoutSamplesReady(dataCopy, sampleRate, channelCount);
        }
        return result;
    }

    @Override
    public int write(short[] audioData, int offsetInShorts, int sizeInShorts) {
        int result = originalTrack.write(audioData, offsetInShorts, sizeInShorts);
        if (result > 0) {
            byte[] dataCopy = new byte[result * 2];
            ByteBuffer.wrap(dataCopy).asShortBuffer().put(audioData, offsetInShorts, result);
            callback.onWebRtcAudioPlayoutSamplesReady(dataCopy, sampleRate, channelCount);
        }
        return result;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public int write(short[] audioData, int offsetInShorts, int sizeInShorts, int writeMode) {
        int result = originalTrack.write(audioData, offsetInShorts, sizeInShorts, writeMode);
        if (result > 0) {
            byte[] dataCopy = new byte[result * 2];
            ByteBuffer.wrap(dataCopy).asShortBuffer().put(audioData, offsetInShorts, result);
            callback.onWebRtcAudioPlayoutSamplesReady(dataCopy, sampleRate, channelCount);
        }
        return result;
    }

    @Override
    public int write(ByteBuffer audioData, int sizeInBytes, int writeMode) {
        ByteBuffer duplicate = audioData.duplicate();
        int result = originalTrack.write(audioData, sizeInBytes, writeMode);
        if (result > 0) {
            byte[] dataCopy = new byte[result];
            duplicate.get(dataCopy, 0, result);
            callback.onWebRtcAudioPlayoutSamplesReady(dataCopy, sampleRate, channelCount);
        }
        return result;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public int write(ByteBuffer audioData, int sizeInBytes, int writeMode, long timestamp) {
        ByteBuffer duplicate = audioData.duplicate();
        int result = originalTrack.write(audioData, sizeInBytes, writeMode, timestamp);
        if (result > 0) {
            byte[] dataCopy = new byte[result];
            duplicate.get(dataCopy, 0, result);
            callback.onWebRtcAudioPlayoutSamplesReady(dataCopy, sampleRate, channelCount);
        }
        return result;
    }
}
