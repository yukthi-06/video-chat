package com.example.videochatapp.webrtc;

import android.content.Context;
import android.util.Log;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import java.util.ArrayList;
import java.util.List;

public class WebRtcClient {

    private final Context context;
    private final WebRtcListener listener;
    
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private VideoCapturer videoCapturer;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private SurfaceTextureHelper surfaceTextureHelper;

    private final String STUN_SERVER = "stun:stun.l.google.com:19302";

    public interface WebRtcListener {
        void onLocalStreamReady(VideoTrack track);
        void onRemoteTrackAdded(VideoTrack track);
        void onPeerDisconnected();
        void onIceCandidateGenerated(IceCandidate candidate);
        void onSdpGenerated(SessionDescription sdp);
    }

    public WebRtcClient(Context context, WebRtcListener listener) {
        this.context = context;
        this.listener = listener;
        initWebRtc();
    }

    private void initWebRtc() {
        // Initialize PeerConnectionFactory
        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        // Create JavaAudioDeviceModule for proper audio playout/record
        JavaAudioDeviceModule audioDeviceModule;
        try {
            audioDeviceModule = JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule();
        } catch (Throwable t) {
            Log.w("WebRtcClient", "Failed to create JavaAudioDeviceModule with hardware effects, falling back to default", t);
            try {
                audioDeviceModule = JavaAudioDeviceModule.builder(context)
                        .createAudioDeviceModule();
            } catch (Throwable t2) {
                Log.e("WebRtcClient", "Failed to create any JavaAudioDeviceModule", t2);
                audioDeviceModule = null;
            }
        }

        // Create PeerConnectionFactory
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory defaultVideoEncoderFactory = new DefaultVideoEncoderFactory(
                null, true, true);
        DefaultVideoDecoderFactory defaultVideoDecoderFactory = new DefaultVideoDecoderFactory(null);

        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory();
    }

    public void startLocalVideoCapture(SurfaceViewRenderer localRenderer, EglBase.Context eglContext) {
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglContext);
        videoCapturer = createVideoCapturer();
        
        if (videoCapturer != null) {
            videoSource = factory.createVideoSource(videoCapturer.isScreencast());
            videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
            videoCapturer.startCapture(1280, 720, 30);

            localVideoTrack = factory.createVideoTrack("ARDMSV0", videoSource);
            localVideoTrack.setEnabled(true);
            localVideoTrack.addSink(localRenderer);
            
            listener.onLocalStreamReady(localVideoTrack);
        }

        // Setup audio
        audioSource = factory.createAudioSource(new MediaConstraints());
        localAudioTrack = factory.createAudioTrack("ARDMAS0", audioSource);
        localAudioTrack.setEnabled(true);
    }

    private VideoCapturer createVideoCapturer() {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(context) ?
                new Camera2Enumerator(context) : new Camera1Enumerator(true);

        String[] deviceNames = enumerator.getDeviceNames();

        // First try to find front facing camera
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    return capturer;
                }
            }
        }

        // Fallback to any camera
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                VideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    return capturer;
                }
            }
        }

        return null;
    }

    public void initPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder(STUN_SERVER).createIceServer());

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {}

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED ||
                        iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                    listener.onPeerDisconnected();
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {}

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                listener.onIceCandidateGenerated(iceCandidate);
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}

            @Override
            public void onAddStream(MediaStream mediaStream) {}

            @Override
            public void onRemoveStream(MediaStream mediaStream) {}

            @Override
            public void onDataChannel(DataChannel dataChannel) {}

            @Override
            public void onRenegotiationNeeded() {}

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                MediaStreamTrack track = rtpReceiver.track();
                if (track instanceof VideoTrack) {
                    listener.onRemoteTrackAdded((VideoTrack) track);
                }
            }
        });

        // Add local tracks
        if (peerConnection != null) {
            if (localVideoTrack != null) {
                peerConnection.addTrack(localVideoTrack);
            }
            if (localAudioTrack != null) {
                peerConnection.addTrack(localAudioTrack);
            }
        }
    }

    public void createOffer() {
        if (peerConnection == null) return;
        
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                listener.onSdpGenerated(sessionDescription);
            }
        }, constraints);
    }

    public void handleAnswer(SessionDescription sdp) {
        if (peerConnection != null) {
            peerConnection.setRemoteDescription(new SimpleSdpObserver(), sdp);
        }
    }

    public void handleOffer(SessionDescription sdp) {
        if (peerConnection == null) return;
        
        peerConnection.setRemoteDescription(new SimpleSdpObserver(), sdp);
        
        MediaConstraints constraints = new MediaConstraints();
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                listener.onSdpGenerated(sessionDescription);
            }
        }, constraints);
    }

    public void addIceCandidate(IceCandidate candidate) {
        if (peerConnection != null) {
            peerConnection.addIceCandidate(candidate);
        }
    }

    public void toggleMic(boolean enabled) {
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(enabled);
        }
    }

    public void toggleVideo(boolean enabled) {
        if (localVideoTrack != null) {
            localVideoTrack.setEnabled(enabled);
        }
    }

    public void close() {
        try {
            if (videoCapturer != null) {
                videoCapturer.stopCapture();
                videoCapturer.dispose();
            }
            if (surfaceTextureHelper != null) {
                surfaceTextureHelper.dispose();
            }
            if (peerConnection != null) {
                peerConnection.close();
            }
            if (factory != null) {
                factory.dispose();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {}

        @Override
        public void onSetSuccess() {}

        @Override
        public void onCreateFailure(String s) {}

        @Override
        public void onSetFailure(String s) {}
    }
}
