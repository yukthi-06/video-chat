package com.example.videochatapp.activities;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.videochatapp.R;
import com.example.videochatapp.webrtc.SignalingClient;
import com.example.videochatapp.webrtc.WebRtcClient;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import org.webrtc.*;

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

    private void initWebRtcAndSignaling() {
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
        signalingClient.disconnect();
        finish();
    }

    @Override
    public void onLocalStreamReady(VideoTrack track) {
        // Local stream is already added to local renderer inside WebRtcClient
    }

    @Override
    public void onRemoteTrackAdded(VideoTrack track) {
        track.setEnabled(true);
        runOnUiThread(() -> track.addSink(remoteVideoView));
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
