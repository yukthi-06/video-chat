package com.vypeensoft.videochatapp.activities;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.vypeensoft.videochatapp.R;
import com.vypeensoft.videochatapp.webrtc.SignalingClient;
import com.vypeensoft.videochatapp.webrtc.WebRtcClient;
import com.vypeensoft.videochatapp.webrtc.WebRtcVideoRecorder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import org.webrtc.*;
import android.util.Log;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CallActivity extends AppCompatActivity implements WebRtcClient.WebRtcListener, SignalingClient.SignalingListener {

    public static final String EXTRA_ROOM_ID = "extra_room_id";
    public static final String EXTRA_IS_CREATOR = "extra_is_creator";

    private SurfaceViewRenderer localVideoView;
    private SurfaceViewRenderer remoteVideoView;
    private FloatingActionButton btnToggleMic;
    private FloatingActionButton btnToggleVideo;
    private FloatingActionButton btnEndCall;
    private TextView tvRoomInfo;

    private WebRtcClient webRtcClient;
    private SignalingClient signalingClient;
    private EglBase eglBase;
    private AudioManager audioManager;
    private WebRtcVideoRecorder localRecorder;
    private WebRtcVideoRecorder remoteRecorder;

    private boolean isMuted = false;
    private boolean isVideoDisabled = false;
    private boolean isCreator = false;
    private String roomId;

    private boolean isAdmin = false;
    private String adminId = null;
    private final java.util.Map<String, java.io.FileOutputStream> activeReceivers = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger activeUploadsCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private boolean isDisconnectPending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        isCreator = getIntent().getBooleanExtra(EXTRA_IS_CREATOR, false);

        android.content.SharedPreferences prefs = getSharedPreferences("video_chat_settings", MODE_PRIVATE);
        isAdmin = prefs.getBoolean("key_is_admin", false);

        // Configure Audio Manager for Call routing
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
        }

        initViews();
        setupRenderers();
        initWebRtcAndSignaling();
        setupControls();
    }

    private void initViews() {
        localVideoView = findViewById(R.id.local_video_view);
        remoteVideoView = findViewById(R.id.remote_video_view);
        btnToggleMic = findViewById(R.id.btn_toggle_mic);
        btnToggleVideo = findViewById(R.id.btn_toggle_video);
        btnEndCall = findViewById(R.id.btn_end_call);
        tvRoomInfo = findViewById(R.id.tv_room_info);

        tvRoomInfo.setText("Room: " + roomId);
    }

    private void setupRenderers() {
        eglBase = EglBase.create();
        
        // Initialize local video view
        localVideoView.init(eglBase.getEglBaseContext(), null);
        localVideoView.setMirror(true);
        localVideoView.setEnableHardwareScaler(true);

        // Initialize remote video view
        remoteVideoView.init(eglBase.getEglBaseContext(), null);
        remoteVideoView.setMirror(false);
        remoteVideoView.setEnableHardwareScaler(true);
    }

    private String getRecordingsDirectory() {
        File dir = null;
        boolean isWritable = false;

        // Try external public SD card directory first
        try {
            dir = new File("/sdcard/Vypeensoft/Video_Caller/recordings");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (dir.exists()) {
                // Perform a test write to verify write permissions (for Scoped Storage compatibility)
                File testFile = new File(dir, "test_write_" + System.currentTimeMillis() + ".tmp");
                if (testFile.createNewFile()) {
                    testFile.delete();
                    isWritable = true;
                }
            }
        } catch (Throwable t) {
            Log.e("CallActivity", "Failed to write to external SD card directory", t);
        }

        // Try app external sandbox directory next
        if (!isWritable) {
            try {
                Log.w("CallActivity", "External SD card directory not writable. Trying sandbox external files dir.");
                File externalDir = getExternalFilesDir(null);
                if (externalDir != null) {
                    dir = new File(externalDir, "Vypeensoft/Video_Caller/recordings");
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    if (dir.exists()) {
                        isWritable = true;
                    }
                }
            } catch (Throwable t) {
                Log.e("CallActivity", "Failed to write to external sandbox directory", t);
            }
        }

        // Fallback to internal storage (guaranteed to be writable)
        if (!isWritable) {
            try {
                Log.w("CallActivity", "External storage not available. Falling back to internal storage.");
                dir = new File(getFilesDir(), "Vypeensoft/Video_Caller/recordings");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
            } catch (Throwable t) {
                Log.e("CallActivity", "Failed to write to internal storage directory", t);
            }
        }

        return dir != null ? dir.getAbsolutePath() : getFilesDir().getAbsolutePath();
    }

    private void initWebRtcAndSignaling() {
        // Initialize video stream recorders
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String recDir = getRecordingsDirectory();
        Log.d("CallActivity", "Saving MP4 files to: " + recDir);
        Toast.makeText(this, "Recording path: " + recDir, Toast.LENGTH_LONG).show();

        String localPath = recDir + "/" + timestamp + "_local.mp4";
        String remotePath = recDir + "/" + timestamp + "_remote.mp4";

        localRecorder = new WebRtcVideoRecorder(localPath, eglBase.getEglBaseContext(), true);
        localRecorder.setListener(new WebRtcVideoRecorder.RecorderListener() {
            @Override
            public void onSegmentCompleted(String filePath) {
                if (adminId != null) {
                    new Thread(() -> {
                        sendVideoFile(new File(filePath));
                    }).start();
                }
            }
        });
        remoteRecorder = new WebRtcVideoRecorder(remotePath, eglBase.getEglBaseContext(), true);

        webRtcClient = new WebRtcClient(getApplicationContext(), this, eglBase.getEglBaseContext());
        webRtcClient.startLocalVideoCapture(localVideoView, eglBase.getEglBaseContext());
        webRtcClient.initPeerConnection();

        signalingClient = SignalingClient.getInstance();
        signalingClient.setListener(this);
        signalingClient.connect(roomId, isCreator);
    }

    private void setupControls() {
        btnToggleMic.setOnClickListener(v -> {
            isMuted = !isMuted;
            webRtcClient.toggleMic(!isMuted);
            btnToggleMic.setImageResource(isMuted ? 
                    android.R.drawable.ic_lock_silent_mode : android.R.drawable.ic_btn_speak_now);
            Toast.makeText(this, isMuted ? "Microphone Muted" : "Microphone Active", Toast.LENGTH_SHORT).show();
        });

        btnToggleVideo.setOnClickListener(v -> {
            isVideoDisabled = !isVideoDisabled;
            webRtcClient.toggleVideo(!isVideoDisabled);
            btnToggleVideo.setImageResource(isVideoDisabled ? 
                    android.R.drawable.presence_video_busy : android.R.drawable.presence_video_online);
            Toast.makeText(this, isVideoDisabled ? "Camera Disabled" : "Camera Enabled", Toast.LENGTH_SHORT).show();
        });

        btnEndCall.setOnClickListener(v -> endCall());
    }

    private void endCall() {
        if (isDisconnectPending) return;
        isDisconnectPending = true;

        if (localRecorder != null) {
            localRecorder.stop();
        }
        if (remoteRecorder != null) {
            remoteRecorder.stop();
        }

        new Thread(() -> {
            try {
                // Wait briefly for final segment to start uploading
                Thread.sleep(500);

                while (activeUploadsCount.get() > 0 || activeReceivers.size() > 0) {
                    runOnUiThread(() -> Toast.makeText(CallActivity.this, "Uploading final video segments... Please wait.", Toast.LENGTH_SHORT).show());
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {}

            runOnUiThread(this::performActualDisconnect);
        }).start();
    }

    private void performActualDisconnect() {
        signalingClient.disconnect();
        finish();
    }

    @Override
    public void onLocalStreamReady(VideoTrack track) {
        if (localRecorder != null) {
            track.addSink(localRecorder);
        }
    }

    @Override
    public void onLocalAudioSample(byte[] data, int sampleRate, int channelCount) {
        if (localRecorder != null) {
            localRecorder.onAudioData(data, sampleRate, channelCount);
        }
    }

    @Override
    public void onRemoteTrackAdded(VideoTrack track) {
        track.setEnabled(true);
        runOnUiThread(() -> {
            track.addSink(remoteVideoView);
            Toast.makeText(CallActivity.this, "Call connected. Recording started.", Toast.LENGTH_SHORT).show();
        });

        try {
            if (localRecorder != null) {
                localRecorder.start();
            }
        } catch (Throwable t) {
            Log.e("CallActivity", "Error starting local recorder on remote track added", t);
        }

        try {
            if (remoteRecorder != null) {
                remoteRecorder.start();
                track.addSink(remoteRecorder);
            }
        } catch (Throwable t) {
            Log.e("CallActivity", "Error starting remote recorder on remote track added", t);
        }

        if (isAdmin) {
            signalingClient.sendAdminAnnouncement();
        }
        startRemoteAudioInterception();
    }

    private void startRemoteAudioInterception() {
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(new Runnable() {
            private int attempts = 0;
            @Override
            public void run() {
                if (isDestroyed() || isFinishing()) return;
                
                if (webRtcClient != null) {
                    boolean success = webRtcClient.attachRemoteAudioInterceptor(new com.vypeensoft.videochatapp.webrtc.AudioTrackInterceptor.AudioDebugCallback() {
                        @Override
                        public void onWebRtcAudioPlayoutSamplesReady(byte[] data, int sampleRate, int channelCount) {
                            if (remoteRecorder != null) {
                                remoteRecorder.onAudioData(data, sampleRate, channelCount);
                            }
                        }
                    });
                    if (success) {
                        Log.d("CallActivity", "Successfully attached remote audio interceptor");
                        return; // Done!
                    }
                }
                
                attempts++;
                if (attempts < 30) { // Try for 30 seconds
                    handler.postDelayed(this, 1000);
                } else {
                    Log.w("CallActivity", "Failed to attach remote audio interceptor after 30 attempts");
                }
            }
        });
    }

    @Override
    public void onPeerDisconnected() {
        runOnUiThread(() -> {
            Toast.makeText(CallActivity.this, "Call disconnected by peer", Toast.LENGTH_SHORT).show();
            endCall();
        });
    }

    @Override
    public void onIceCandidateGenerated(IceCandidate candidate) {
        signalingClient.sendIceCandidate(candidate);
    }

    @Override
    public void onSdpGenerated(SessionDescription sdp) {
        signalingClient.sendSdp(sdp);
    }

    @Override
    public void onSignalingConnected() {
        runOnUiThread(() -> {
            Toast.makeText(CallActivity.this, "Connected to Signaling Server", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onPeerJoined() {
        runOnUiThread(() -> {
            Toast.makeText(CallActivity.this, "Peer joined. Connecting...", Toast.LENGTH_SHORT).show();
            if (isCreator) {
                webRtcClient.createOffer();
            }
        });
    }

    @Override
    public void onOfferReceived(SessionDescription sdp) {
        runOnUiThread(() -> {
            try {
                webRtcClient.handleOffer(sdp);
            } catch (Throwable t) {
                Log.e("CallActivity", "Error handling offer", t);
            }
        });
    }

    @Override
    public void onAnswerReceived(SessionDescription sdp) {
        runOnUiThread(() -> {
            try {
                webRtcClient.handleAnswer(sdp);
            } catch (Throwable t) {
                Log.e("CallActivity", "Error handling answer", t);
            }
        });
    }

    @Override
    public void onIceCandidateReceived(IceCandidate candidate) {
        runOnUiThread(() -> {
            try {
                webRtcClient.addIceCandidate(candidate);
            } catch (Throwable t) {
                Log.e("CallActivity", "Error handling ice candidate", t);
            }
        });
    }

    @Override
    public void onCallEnded() {
        runOnUiThread(() -> {
            Toast.makeText(CallActivity.this, "Call Ended by Remote User", Toast.LENGTH_SHORT).show();
            endCall();
        });
    }

    @Override
    public void onAdminAnnouncement(final String adminSenderId) {
        if (isAdmin) return;
        adminId = adminSenderId;
        runOnUiThread(() -> Toast.makeText(CallActivity.this, "Admin detected. Uploading completed video segments...", Toast.LENGTH_SHORT).show());
        new Thread(() -> {
            try {
                String recDir = getRecordingsDirectory();
                File directory = new File(recDir);
                File[] files = directory.listFiles((dir, name) -> name.endsWith(".mp4") && name.contains("_local_"));
                if (files == null) return;
                java.util.Arrays.sort(files, (f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified()));
                for (File file : files) {
                    if (System.currentTimeMillis() - file.lastModified() < 3000) {
                        continue;
                    }
                    sendVideoFile(file);
                }
            } catch (Exception e) {
                Log.e("CallActivity", "Error loading/sending existing video segments", e);
            }
        }).start();
    }

    private void sendVideoFile(File file) {
        activeUploadsCount.incrementAndGet();
        String fileName = file.getName();
        long fileSize = file.length();
        signalingClient.sendVideoFileStart(fileName, fileSize);
        
        byte[] buffer = new byte[65536];
        int bytesRead;
        int chunkIndex = 0;
        int totalChunks = (int) Math.ceil((double) fileSize / buffer.length);
        
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            while ((bytesRead = fis.read(buffer)) != -1) {
                byte[] chunkData;
                if (bytesRead < buffer.length) {
                    chunkData = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunkData, 0, bytesRead);
                } else {
                    chunkData = buffer;
                }
                String base64Data = android.util.Base64.encodeToString(chunkData, android.util.Base64.NO_WRAP);
                signalingClient.sendVideoFileChunk(fileName, chunkIndex, totalChunks, base64Data);
                chunkIndex++;
                Thread.sleep(50);
            }
            signalingClient.sendVideoFileEnd(fileName);
        } catch (Exception e) {
            Log.e("CallActivity", "Error uploading file " + fileName, e);
        } finally {
            activeUploadsCount.decrementAndGet();
        }
    }

    @Override
    public void onVideoFileStart(String senderId, String fileName, long fileSize) {
        if (!isAdmin) return;
        String recDir = getRecordingsDirectory();
        String safeFileName = "received_" + senderId + "_" + fileName;
        File file = new File(recDir, safeFileName);
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            activeReceivers.put(senderId + "_" + fileName, fos);
            Log.d("CallActivity", "Started saving remote video: " + safeFileName);
        } catch (Exception e) {
            Log.e("CallActivity", "Error starting output stream for file " + safeFileName, e);
        }
    }

    @Override
    public void onVideoFileChunk(String senderId, String fileName, int chunkIndex, int totalChunks, String data) {
        if (!isAdmin) return;
        java.io.FileOutputStream fos = activeReceivers.get(senderId + "_" + fileName);
        if (fos != null) {
            try {
                byte[] decoded = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                fos.write(decoded);
            } catch (Exception e) {
                Log.e("CallActivity", "Error writing chunk " + chunkIndex + " for file " + fileName, e);
            }
        }
    }

    @Override
    public void onVideoFileEnd(String senderId, String fileName) {
        if (!isAdmin) return;
        String key = senderId + "_" + fileName;
        java.io.FileOutputStream fos = activeReceivers.remove(key);
        if (fos != null) {
            try {
                fos.flush();
                fos.close();
                Log.d("CallActivity", "Saved remote video: received_" + senderId + "_" + fileName);
                runOnUiThread(() -> Toast.makeText(CallActivity.this, "Saved remote video: received_" + senderId + "_" + fileName, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e("CallActivity", "Error closing received video file " + fileName, e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (localRecorder != null) {
            localRecorder.stop();
        }
        if (remoteRecorder != null) {
            remoteRecorder.stop();
        }
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(false);
        }
        if (webRtcClient != null) {
            webRtcClient.close();
        }
        if (localVideoView != null) {
            localVideoView.release();
        }
        if (remoteVideoView != null) {
            remoteVideoView.release();
        }

        for (java.io.FileOutputStream fos : activeReceivers.values()) {
            try {
                fos.close();
            } catch (Exception ignored) {}
        }
        activeReceivers.clear();
    }
}
