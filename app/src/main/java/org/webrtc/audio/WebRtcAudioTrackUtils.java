package org.webrtc.audio;

import android.media.AudioTrack;
import android.util.Log;
import com.vypeensoft.videochatapp.webrtc.AudioTrackInterceptor;
import java.lang.reflect.Field;

public abstract class WebRtcAudioTrackUtils {
    private static final String TAG = "WebRtcAudioTrackUtils";

    public static boolean attachOutputCallback(
            JavaAudioDeviceModule audioDeviceModule,
            AudioTrackInterceptor.AudioDebugCallback callback
    ) {
        try {
            // Get WebRtcAudioTrack via reflection
            Field audioOutputField = JavaAudioDeviceModule.class.getDeclaredField("audioOutput");
            audioOutputField.setAccessible(true);
            Object audioOutput = audioOutputField.get(audioDeviceModule);
            if (audioOutput == null) {
                Log.w(TAG, "audioOutput is null, cannot attach callback");
                return false;
            }

            // Get the original AudioTrack inside WebRtcAudioTrack
            Field audioTrackField = audioOutput.getClass().getDeclaredField("audioTrack");
            audioTrackField.setAccessible(true);
            AudioTrack originalAudioTrack = (AudioTrack) audioTrackField.get(audioOutput);
            if (originalAudioTrack == null) {
                Log.w(TAG, "originalAudioTrack is null, cannot attach callback");
                return false;
            }

            // If already intercepted, do not double-intercept but count as success
            if (originalAudioTrack instanceof AudioTrackInterceptor) {
                Log.d(TAG, "AudioTrack is already intercepted");
                return true;
            }

            // Dynamically read sample rate and channel count from the original track!
            int sampleRate = originalAudioTrack.getSampleRate();
            int channelCount = originalAudioTrack.getChannelCount();

            // Create our interceptor and swap it
            AudioTrackInterceptor interceptor = new AudioTrackInterceptor(originalAudioTrack, sampleRate, channelCount, callback);
            audioTrackField.set(audioOutput, interceptor);
            Log.d(TAG, "AudioTrackInterceptor successfully attached (" + sampleRate + "Hz, " + channelCount + " channels)!");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to attach AudioTrackInterceptor via reflection", t);
        }
        return false;
    }

    public static void detachOutputCallback(JavaAudioDeviceModule audioDeviceModule) {
        try {
            Field audioOutputField = JavaAudioDeviceModule.class.getDeclaredField("audioOutput");
            audioOutputField.setAccessible(true);
            Object audioOutput = audioOutputField.get(audioDeviceModule);
            if (audioOutput == null) return;

            Field audioTrackField = audioOutput.getClass().getDeclaredField("audioTrack");
            audioTrackField.setAccessible(true);
            AudioTrack currentAudioTrack = (AudioTrack) audioTrackField.get(audioOutput);

            if (currentAudioTrack instanceof AudioTrackInterceptor) {
                AudioTrack original = ((AudioTrackInterceptor) currentAudioTrack).originalTrack;
                audioTrackField.set(audioOutput, original);
                Log.d(TAG, "AudioTrackInterceptor successfully detached");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to detach AudioTrackInterceptor", t);
        }
    }
}
