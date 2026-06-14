package com.vypeensoft.videochatapp.webrtc;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import java.util.UUID;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * SignalingClient connects to a public WebSocket channel (Itty-Sockets)
 * to exchange SDP offers/answers and ICE candidates in real-time.
 */
public class SignalingClient {

    private static final String TAG = "SignalingClient";
    //private static final String WS_BASE_URL = "wss://connect.ittysockets.com/videochatapp-";
    private static final String WS_BASE_URL = "ws://192.168.1.68:8765/videochatapp-";

    private static SignalingClient instance;
    private SignalingListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    private final String senderId = UUID.randomUUID().toString();
    private final OkHttpClient client = new OkHttpClient();
    private WebSocket webSocket;
    private String currentRoomId;

    public interface SignalingListener {
        void onSignalingConnected();
        void onPeerJoined();
        void onOfferReceived(SessionDescription sdp);
        void onAnswerReceived(SessionDescription sdp);
        void onIceCandidateReceived(IceCandidate candidate);
        void onCallEnded();
        void onAdminAnnouncement(String senderId);
        void onVideoFileStart(String senderId, String fileName, long fileSize);
        void onVideoFileChunk(String senderId, String fileName, int chunkIndex, int totalChunks, String data);
        void onVideoFileEnd(String senderId, String fileName);
    }

    private SignalingClient() {
        // Private constructor for singleton
    }

    public static synchronized SignalingClient getInstance() {
        if (instance == null) {
            instance = new SignalingClient();
        }
        return instance;
    }

    public void setListener(SignalingListener listener) {
        this.listener = listener;
    }

    /**
     * Connects to the room channel using Itty-Sockets pub/sub channel.
     */
    public void connect(String roomId, boolean isCreate) {
        this.currentRoomId = roomId;
        String connectionUrl = WS_BASE_URL + roomId.toLowerCase().trim();
        Log.d(TAG, "Connecting to WebSocket room at: " + connectionUrl);

        Request request = new Request.Builder()
                .url(connectionUrl)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Log.d(TAG, "WebSocket Connection Opened");
                handler.post(() -> {
                    if (listener != null) {
                        listener.onSignalingConnected();
                    }
                });
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                Log.d(TAG, "Received message: " + text);
                try {
                    JSONObject json = new JSONObject(text);
                    
                    // Filter out messages that we sent ourselves
                    String msgSenderId = json.optString("senderId");
                    if (senderId.equals(msgSenderId)) {
                        return;
                    }

                    String type = json.optString("type");
                    if ("peer_joined".equals(type)) {
                        handler.post(() -> {
                            if (listener != null) {
                                listener.onPeerJoined();
                            }
                        });
                    } else if ("offer".equals(type)) {
                        String sdpDescription = json.getString("sdp");
                        SessionDescription sdp = new SessionDescription(SessionDescription.Type.OFFER, sdpDescription);
                        handler.post(() -> {
                            if (listener != null) {
                                listener.onOfferReceived(sdp);
                            }
                        });
                    } else if ("answer".equals(type)) {
                        String sdpDescription = json.getString("sdp");
                        SessionDescription sdp = new SessionDescription(SessionDescription.Type.ANSWER, sdpDescription);
                        handler.post(() -> {
                            if (listener != null) {
                                listener.onAnswerReceived(sdp);
                            }
                        });
                    } else if ("candidate".equals(type)) {
                        String sdpMid = json.getString("sdpMid");
                        int sdpMLineIndex = json.getInt("sdpMLineIndex");
                        String sdp = json.getString("candidate");
                        IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
                        handler.post(() -> {
                            if (listener != null) {
                                listener.onIceCandidateReceived(candidate);
                            }
                        });
                    } else if ("end_call".equals(type)) {
                        handler.post(() -> {
                            if (listener != null) {
                                listener.onCallEnded();
                            }
                        });
                    } else if ("admin_announcement".equals(type)) {
                        handler.post(() -> {
                            if (listener != null) {
                                listener.onAdminAnnouncement(msgSenderId);
                            }
                        });
                    } else if ("video_file_start".equals(type)) {
                        String fileName = json.getString("fileName");
                        long fileSize = json.getLong("fileSize");
                        handler.post(() -> {
                            if (listener != null) {
                                listener.onVideoFileStart(msgSenderId, fileName, fileSize);
                            }
                        });
                    } else if ("video_file_chunk".equals(type)) {
                        String fileName = json.getString("fileName");
                        int chunkIndex = json.getInt("chunkIndex");
                        int totalChunks = json.getInt("totalChunks");
                        String data = json.getString("data");
                        handler.post(() -> {
                            if (listener != null) {
                                listener.onVideoFileChunk(msgSenderId, fileName, chunkIndex, totalChunks, data);
                            }
                        });
                    } else if ("video_file_end".equals(type)) {
                        String fileName = json.getString("fileName");
                        handler.post(() -> {
                            if (listener != null) {
                                listener.onVideoFileEnd(msgSenderId, fileName);
                            }
                        });
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse signaling message JSON", e);
                }
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                Log.e(TAG, "WebSocket Connection Failure", t);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                Log.d(TAG, "WebSocket Connection Closed. Reason: " + reason);
            }
        });
    }

    public void sendAdminAnnouncement() {
        if (webSocket == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("senderId", senderId);
            json.put("type", "admin_announcement");
            webSocket.send(json.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to construct admin announcement JSON", e);
        }
    }

    public void sendVideoFileStart(String fileName, long fileSize) {
        if (webSocket == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("senderId", senderId);
            json.put("type", "video_file_start");
            json.put("fileName", fileName);
            json.put("fileSize", fileSize);
            webSocket.send(json.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to construct video file start JSON", e);
        }
    }

    public void sendVideoFileChunk(String fileName, int chunkIndex, int totalChunks, String data) {
        if (webSocket == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("senderId", senderId);
            json.put("type", "video_file_chunk");
            json.put("fileName", fileName);
            json.put("chunkIndex", chunkIndex);
            json.put("totalChunks", totalChunks);
            json.put("data", data);
            webSocket.send(json.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to construct video file chunk JSON", e);
        }
    }

    public void sendVideoFileEnd(String fileName) {
        if (webSocket == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("senderId", senderId);
            json.put("type", "video_file_end");
            json.put("fileName", fileName);
            webSocket.send(json.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to construct video file end JSON", e);
        }
    }

    /**
     * Send SDP (Offer/Answer) to the peer.
     */
    public void sendSdp(SessionDescription sdp) {
        if (webSocket == null) {
            Log.w(TAG, "Cannot send SDP. WebSocket is not connected.");
            return;
        }

        try {
            JSONObject json = new JSONObject();
            json.put("senderId", senderId);
            json.put("type", sdp.type.canonicalForm());
            json.put("sdp", sdp.description);

            String message = json.toString();
            Log.d(TAG, "Sending SDP: " + message);
            webSocket.send(message);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to construct SDP JSON", e);
        }
    }

    /**
     * Send ICE candidate to the peer.
     */
    public void sendIceCandidate(IceCandidate candidate) {
        if (webSocket == null) {
            Log.w(TAG, "Cannot send ICE candidate. WebSocket is not connected.");
            return;
        }

        try {
            JSONObject json = new JSONObject();
            json.put("senderId", senderId);
            json.put("type", "candidate");
            json.put("sdpMid", candidate.sdpMid);
            json.put("sdpMLineIndex", candidate.sdpMLineIndex);
            json.put("candidate", candidate.sdp);

            String message = json.toString();
            Log.d(TAG, "Sending ICE candidate: " + message);
            webSocket.send(message);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to construct ICE candidate JSON", e);
        }
    }

    /**
     * Disconnects from the signaling server and informs the peer.
     */
    public void disconnect() {
        if (webSocket != null) {
            try {
                // Send end_call message so the other peer automatically disconnects
                JSONObject json = new JSONObject();
                json.put("senderId", senderId);
                json.put("type", "end_call");
                webSocket.send(json.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Failed to construct end_call JSON", e);
            }
            webSocket.close(1000, "Call Ended");
            webSocket = null;
        }
        currentRoomId = null;
    }
}
