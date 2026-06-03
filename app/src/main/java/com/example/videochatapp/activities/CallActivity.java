package com.example.videochatapp.activities;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.videochatapp.R;
import com.example.videochatapp.webrtc.SignalingClient;
import com.example.videochatapp.webrtc.WebRtcClient;
import com.example.videochatapp.webrtc.WebRtcVideoRecorder;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        isCreator = getIntent().getBooleanExtra(EXTRA_IS_CREATOR, false);

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

        localRecorder = new WebRtcVideoRecorder(localPath, 480, 640, eglBase.getEglBaseContext());
        remoteRecorder = new WebRtcVideoRecorder(remotePath, 480, 640, eglBase.getEglBaseContext());

        localRecorder.start();
        remoteRecorder.start();

        webRtcClient = new WebRtcClient(getApplicationContext(), this);
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
        if (localRecorder != null) {
            localRecorder.stop();
        }
        if (remoteRecorder != null) {
            remoteRecorder.stop();
        }
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
    public void onRemoteTrackAdded(VideoTrack track) {
        track.setEnabled(true);
        runOnUiThread(() -> track.addSink(remoteVideoView));
        if (remoteRecorder != null) {
            track.addSink(remoteRecorder);
        }
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
        runOnUiThread(() -> webRtcClient.handleOffer(sdp));
    }

    @Override
    public void onAnswerReceived(SessionDescription sdp) {
        runOnUiThread(() -> webRtcClient.handleAnswer(sdp));
    }

    @Override
    public void onIceCandidateReceived(IceCandidate candidate) {
        runOnUiThread(() -> webRtcClient.addIceCandidate(candidate));
    }

    @Override
    public void onCallEnded() {
        runOnUiThread(() -> {
            Toast.makeText(CallActivity.this, "Call Ended by Remote User", Toast.LENGTH_SHORT).show();
            finish();
        });
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
    }
}
