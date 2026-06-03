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

    private final String filePath;
    private final EglBase.Context sharedContext;
    private int width;
    private int height;

    private MediaCodec mediaCodec;
    private MediaMuxer mediaMuxer;
    private Surface inputSurface;
    private EglBase recorderEglBase;
    private GlRectDrawer drawer;
    private VideoFrameDrawer frameDrawer;

    private int videoTrackIndex = -1;
    private boolean isStartRequested = false;
    private boolean isRecording = false;
    private boolean isMuxerStarted = false;
    private Thread encoderThread;

    public WebRtcVideoRecorder(String filePath, EglBase.Context sharedContext) {
        this.filePath = filePath;
        this.sharedContext = sharedContext;
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
        File file = new File(filePath);
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
            // Configure MediaCodec
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
            mediaMuxer = new MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

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

            Log.d(TAG, "Video recorder started dynamically for: " + filePath + " (" + width + "x" + height + ")");
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
            
            // Swap buffers and set the timestamp in nanoseconds
            recorderEglBase.swapBuffers(frame.getTimestampNs());
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
                    if (isMuxerStarted) {
                        throw new RuntimeException("Format changed twice");
                    }
                    MediaFormat newFormat = mediaCodec.getOutputFormat();
                    videoTrackIndex = mediaMuxer.addTrack(newFormat);
                    mediaMuxer.start();
                    isMuxerStarted = true;
                    Log.d(TAG, "MediaMuxer started");
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData = mediaCodec.getOutputBuffer(outputBufferIndex);
                    if (encodedData == null) {
                        throw new RuntimeException("encoderOutputBuffer " + outputBufferIndex + " was null");
                    }

                    if (bufferInfo.size > 0) {
                        if (!isMuxerStarted) {
                            throw new RuntimeException("Muxer not started before output");
                        }
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                    }
                    mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error draining encoder", t);
                break;
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

        releaseCodec();
        Log.d(TAG, "Video recorder stopped for: " + filePath);
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
        videoTrackIndex = -1;
    }
}
