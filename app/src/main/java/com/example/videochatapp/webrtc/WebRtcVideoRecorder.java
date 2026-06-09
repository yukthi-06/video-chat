package com.example.videochatapp.webrtc;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;
import org.webrtc.*;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class WebRtcVideoRecorder implements VideoSink {
    private static final String TAG = "WebRtcVideoRecorder";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC; // H.264
    private static final int FRAME_RATE = 30;
    private static final int IFRAME_INTERVAL = 2; // 2 seconds between keyframes
    private static final int BITRATE = 1500000; // 1.5 Mbps

    private final String baseFilePath;
    private final String fileExtension;
    private int segmentIndex = 1;
    private final EglBase.Context sharedContext;
    private final boolean recordAudio;
    private int width;
    private int height;

    private MediaCodec mediaCodec;
    private MediaCodec audioCodec;
    private MediaMuxer mediaMuxer;
    private Surface inputSurface;
    private EglBase recorderEglBase;
    private GlRectDrawer drawer;
    private VideoFrameDrawer frameDrawer;

    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private boolean isStartRequested = false;
    private boolean isRecording = false;
    private boolean isMuxerStarted = false;
    private boolean isAudioEncoderStarted = false;
    private Thread encoderThread;
    private Thread audioEncoderThread;
    
    private final Object muxerLock = new Object();
    private final java.util.concurrent.LinkedBlockingQueue<AudioFrame> audioQueue = new java.util.concurrent.LinkedBlockingQueue<>();
    private long firstVideoTimestampNs = -1;

    private static class AudioFrame {
        final byte[] data;
        final int sampleRate;
        final int channelCount;

        AudioFrame(byte[] data, int sampleRate, int channelCount) {
            this.data = data;
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
        }
    }

    public WebRtcVideoRecorder(String filePath, EglBase.Context sharedContext) {
        this(filePath, sharedContext, false);
    }

    public WebRtcVideoRecorder(String filePath, EglBase.Context sharedContext, boolean recordAudio) {
        int dotIndex = filePath.lastIndexOf('.');
        if (dotIndex != -1) {
            this.baseFilePath = filePath.substring(0, dotIndex);
            this.fileExtension = filePath.substring(dotIndex);
        } else {
            this.baseFilePath = filePath;
            this.fileExtension = ".mp4";
        }
        this.sharedContext = sharedContext;
        this.recordAudio = recordAudio;
    }

    public synchronized void start() {
        if (isRecording || isStartRequested) {
            Log.w(TAG, "Recorder is already running or start has been requested");
            return;
        }
        isStartRequested = true;
        Log.d(TAG, "Start requested. Recording initialization deferred until first frame arrives.");
    }

    private void startInternal() {
        String currentFilePath = baseFilePath + "_" + segmentIndex + fileExtension;
        File file = new File(currentFilePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean created = parentDir.mkdirs();
            Log.d(TAG, "Recording directory created: " + created);
        }

        // Save current EGL state of the starting thread to prevent context leakage/blocking
        android.opengl.EGLContext oldContext = android.opengl.EGL14.eglGetCurrentContext();
        android.opengl.EGLDisplay oldDisplay = android.opengl.EGL14.eglGetCurrentDisplay();
        android.opengl.EGLSurface oldDrawSurface = android.opengl.EGL14.eglGetCurrentSurface(android.opengl.EGL14.EGL_DRAW);
        android.opengl.EGLSurface oldReadSurface = android.opengl.EGL14.eglGetCurrentSurface(android.opengl.EGL14.EGL_READ);

        try {
            // Configure MediaCodec for Video
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = mediaCodec.createInputSurface();
            mediaCodec.start();

            // Configure MediaMuxer
            mediaMuxer = new MediaMuxer(currentFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Configure EglHelper to draw frames to MediaCodec's input surface
            try {
                recorderEglBase = EglBase.create(sharedContext, EglBase.CONFIG_RECORDABLE);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to create EglBase with CONFIG_RECORDABLE, trying CONFIG_PLAIN", t);
                recorderEglBase = EglBase.create(sharedContext, EglBase.CONFIG_PLAIN);
            }
            recorderEglBase.createSurface(inputSurface);
            recorderEglBase.makeCurrent();

            drawer = new GlRectDrawer();
            frameDrawer = new VideoFrameDrawer();

            isRecording = true;
            isMuxerStarted = false;

            encoderThread = new Thread(this::drainEncoder, "VideoEncoderThread");
            encoderThread.start();

            if (recordAudio) {
                audioEncoderThread = new Thread(this::drainAudioEncoder, "AudioEncoderThread");
                audioEncoderThread.start();
            }

            Log.d(TAG, "Video recorder started dynamically for: " + currentFilePath + " (" + width + "x" + height + ")");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize recorder", t);
            releaseCodec();
        } finally {
            // Restore original EGL state to the starting thread
            try {
                if (oldDisplay != android.opengl.EGL14.EGL_NO_DISPLAY && oldContext != android.opengl.EGL14.EGL_NO_CONTEXT) {
                    android.opengl.EGL14.eglMakeCurrent(oldDisplay, oldDrawSurface, oldReadSurface, oldContext);
                } else {
                    // Detach context if none was current
                    android.opengl.EGLDisplay defaultDisplay = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
                    android.opengl.EGL14.eglMakeCurrent(defaultDisplay, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error restoring EGL context on start", t);
            }
        }
    }

    @Override
    public synchronized void onFrame(VideoFrame frame) {
        if (!isStartRequested) {
            return;
        }

        if (!isRecording) {
            // Retrieve actual video source dimensions (taking rotation into account)
            int frameWidth = frame.getRotatedWidth();
            int frameHeight = frame.getRotatedHeight();

            // Encoders prefer dimension parameters to be multiples of 16
            this.width = Math.max(16, (frameWidth / 16) * 16);
            this.height = Math.max(16, (frameHeight / 16) * 16);

            startInternal();

            if (!isRecording) {
                return; // startInternal failed
            }
        }

        drawAndEncodeFrame(frame);
    }

    private void drawAndEncodeFrame(VideoFrame frame) {
        if (recorderEglBase == null) return;

        // Save current EGL state of the thread to prevent breaking the camera thread's context
        android.opengl.EGLContext oldContext = android.opengl.EGL14.eglGetCurrentContext();
        android.opengl.EGLDisplay oldDisplay = android.opengl.EGL14.eglGetCurrentDisplay();
        android.opengl.EGLSurface oldDrawSurface = android.opengl.EGL14.eglGetCurrentSurface(android.opengl.EGL14.EGL_DRAW);
        android.opengl.EGLSurface oldReadSurface = android.opengl.EGL14.eglGetCurrentSurface(android.opengl.EGL14.EGL_READ);

        try {
            recorderEglBase.makeCurrent();
            // Clear OpenGL buffer
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            
            // Draw the WebRTC frame to the MediaCodec input surface
            frameDrawer.drawFrame(frame, drawer, null, 0, 0, width, height);
            
            // Swap buffers and set the timestamp in nanoseconds, normalized to start from 0
            if (firstVideoTimestampNs == -1) {
                firstVideoTimestampNs = frame.getTimestampNs();
            }
            long ptsNs = frame.getTimestampNs() - firstVideoTimestampNs;

            // Cut segment at 60 seconds
            if (ptsNs >= 60000000000L) {
                // Swap buffers for this last frame of the current segment
                recorderEglBase.swapBuffers(ptsNs);
                
                // Restore original EGL context to calling thread BEFORE splitting (split will release EGL base)
                try {
                    if (oldDisplay != android.opengl.EGL14.EGL_NO_DISPLAY && oldContext != android.opengl.EGL14.EGL_NO_CONTEXT) {
                        android.opengl.EGL14.eglMakeCurrent(oldDisplay, oldDrawSurface, oldReadSurface, oldContext);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Error restoring EGL context before split", t);
                }
                
                splitRecording();
                return;
            }

            recorderEglBase.swapBuffers(ptsNs);
        } catch (Throwable t) {
            Log.e(TAG, "Error rendering frame to encoder surface", t);
        } finally {
            // Restore original EGL state to the calling thread
            try {
                if (oldDisplay != android.opengl.EGL14.EGL_NO_DISPLAY && oldContext != android.opengl.EGL14.EGL_NO_CONTEXT) {
                    android.opengl.EGL14.eglMakeCurrent(oldDisplay, oldDrawSurface, oldReadSurface, oldContext);
                } else {
                    // Detach context if none was current
                    android.opengl.EGLDisplay defaultDisplay = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
                    android.opengl.EGL14.eglMakeCurrent(defaultDisplay, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error restoring EGL context", t);
            }
        }
    }

    private synchronized void splitRecording() {
        if (!isRecording) return;
        Log.d(TAG, "60 seconds reached. Splitting recording to next segment.");

        isRecording = false;

        if (encoderThread != null) {
            try {
                encoderThread.join(2000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Encoder thread join interrupted", e);
            }
            encoderThread = null;
        }

        if (audioEncoderThread != null) {
            try {
                audioEncoderThread.join(2000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Audio encoder thread join interrupted", e);
            }
            audioEncoderThread = null;
        }

        releaseCodec();

        segmentIndex++;
        // Keep isStartRequested = true so the next frame restarts recording automatically
    }

    private void drainEncoder() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (isRecording) {
            try {
                int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (muxerLock) {
                        if (videoTrackIndex != -1) {
                            throw new RuntimeException("Video format changed twice");
                        }
                        MediaFormat newFormat = mediaCodec.getOutputFormat();
                        videoTrackIndex = mediaMuxer.addTrack(newFormat);
                        Log.d(TAG, "Video track added: " + videoTrackIndex);
                        checkAndStartMuxer();
                        muxerLock.notifyAll();
                    }
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData = mediaCodec.getOutputBuffer(outputBufferIndex);
                    if (encodedData == null) {
                        throw new RuntimeException("encoderOutputBuffer " + outputBufferIndex + " was null");
                    }

                    if (bufferInfo.size > 0) {
                        synchronized (muxerLock) {
                            while (!isMuxerStarted && isRecording) {
                                try {
                                    muxerLock.wait(10);
                                } catch (InterruptedException e) {
                                    break;
                                }
                            }
                            if (isMuxerStarted) {
                                encodedData.position(bufferInfo.offset);
                                encodedData.limit(bufferInfo.offset + bufferInfo.size);
                                mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                            }
                        }
                    }
                    mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error draining encoder", t);
                break;
            }
        }
    }

    public void onAudioData(byte[] data, int sampleRate, int channelCount) {
        if (!isStartRequested || !recordAudio) {
            return;
        }
        audioQueue.offer(new AudioFrame(data, sampleRate, channelCount));
    }

    private void initAudioCodec(int sampleRate, int channelCount) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioCodec.start();
        isAudioEncoderStarted = true;
        Log.d(TAG, "Audio codec started successfully (" + sampleRate + "Hz, " + channelCount + " channels)");
    }

    private void checkAndStartMuxer() {
        synchronized (muxerLock) {
            if (isMuxerStarted) return;
            boolean videoReady = (videoTrackIndex != -1);
            boolean audioReady = (!recordAudio || audioTrackIndex != -1);
            if (videoReady && audioReady) {
                mediaMuxer.start();
                isMuxerStarted = true;
                Log.d(TAG, "MediaMuxer started with videoTrack=" + videoTrackIndex + ", audioTrack=" + audioTrackIndex);
            }
        }
    }

    private void drainAudioEncoder() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long totalAudioSamples = 0;

        while (isRecording) {
            try {
                AudioFrame frame = audioQueue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (frame != null) {
                    if (audioCodec == null) {
                        initAudioCodec(frame.sampleRate, frame.channelCount);
                    }

                    if (audioCodec != null) {
                        int inputBufferIndex = audioCodec.dequeueInputBuffer(10000);
                        if (inputBufferIndex >= 0) {
                            ByteBuffer inputBuffer = audioCodec.getInputBuffer(inputBufferIndex);
                            if (inputBuffer != null) {
                                inputBuffer.clear();
                                inputBuffer.put(frame.data);

                                long timestampUs = totalAudioSamples * 1000000L / frame.sampleRate;
                                int samplesCount = frame.data.length / (frame.channelCount * 2);
                                totalAudioSamples += samplesCount;

                                audioCodec.queueInputBuffer(inputBufferIndex, 0, frame.data.length, timestampUs, 0);
                            }
                        }
                    }
                }

                if (audioCodec != null) {
                    int outputBufferIndex = audioCodec.dequeueOutputBuffer(bufferInfo, 1000);
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // ignore
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized (muxerLock) {
                            if (audioTrackIndex == -1) {
                                MediaFormat newFormat = audioCodec.getOutputFormat();
                                audioTrackIndex = mediaMuxer.addTrack(newFormat);
                                Log.d(TAG, "Audio track added: " + audioTrackIndex);
                                checkAndStartMuxer();
                                muxerLock.notifyAll();
                            }
                        }
                    } else if (outputBufferIndex >= 0) {
                        ByteBuffer encodedData = audioCodec.getOutputBuffer(outputBufferIndex);
                        if (encodedData != null && bufferInfo.size > 0) {
                            synchronized (muxerLock) {
                                while (!isMuxerStarted && isRecording) {
                                    try {
                                        muxerLock.wait(10);
                                    } catch (InterruptedException e) {
                                        break;
                                    }
                                }
                                if (isMuxerStarted) {
                                    encodedData.position(bufferInfo.offset);
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                                    mediaMuxer.writeSampleData(audioTrackIndex, encodedData, bufferInfo);
                                }
                            }
                        }
                        audioCodec.releaseOutputBuffer(outputBufferIndex, false);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error in audio encoder thread", t);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }
    }

    public synchronized void stop() {
        isStartRequested = false;
        if (!isRecording) {
            return;
        }

        isRecording = false;

        if (encoderThread != null) {
            try {
                encoderThread.join(2000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Encoder thread join interrupted", e);
            }
            encoderThread = null;
        }

        if (audioEncoderThread != null) {
            try {
                audioEncoderThread.join(2000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Audio encoder thread join interrupted", e);
            }
            audioEncoderThread = null;
        }

        releaseCodec();
        Log.d(TAG, "Video recorder stopped for: " + (baseFilePath + "_" + segmentIndex + fileExtension));
    }

    private void releaseCodec() {
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaCodec", e);
            }
            mediaCodec = null;
        }

        if (audioCodec != null) {
            try {
                if (isAudioEncoderStarted) {
                    audioCodec.stop();
                }
                audioCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing audio Codec", e);
            }
            audioCodec = null;
        }

        if (mediaMuxer != null) {
            try {
                if (isMuxerStarted) {
                    mediaMuxer.stop();
                }
                mediaMuxer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaMuxer", e);
            }
            mediaMuxer = null;
        }

        if (recorderEglBase != null) {
            recorderEglBase.release();
            recorderEglBase = null;
        }

        if (drawer != null) {
            drawer.release();
            drawer = null;
        }

        if (frameDrawer != null) {
            frameDrawer.release();
            frameDrawer = null;
        }

        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }

        isMuxerStarted = false;
        isAudioEncoderStarted = false;
        videoTrackIndex = -1;
        audioTrackIndex = -1;
        firstVideoTimestampNs = -1;
        audioQueue.clear();
    }
}
