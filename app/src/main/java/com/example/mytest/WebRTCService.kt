// file: src/main/java/com/example/mytest/WebRTCService.kt
package com.example.mytest

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.webrtc.*

class WebRTCService : Service() {
    private val binder = LocalBinder()
    private lateinit var webSocketClient: WebSocketClient
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var eglBase: EglBase
    private var localView: SurfaceViewRenderer? = null
    private var remoteView: SurfaceViewRenderer? = null

    // Configuration
    private val roomName = "room1"
    private val userName = Build.MODEL ?: "AndroidDevice"
    private val webSocketUrl = "wss://anybet.site/ws"

    // Constants
    private companion object {
        const val VIDEO_WIDTH = 640
        const val VIDEO_HEIGHT = 480
        const val VIDEO_FPS = 30
        const val ICE_CONNECTION_TIMEOUT_MS = 10000L
    }

    // Notification
    private val notificationId = 1
    private val channelId = "webrtc_service_channel"
    private val handler = Handler(Looper.getMainLooper())

    inner class LocalBinder : Binder() {
        fun getService(): WebRTCService = this@WebRTCService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d("WebRTCService", "Service onCreate")
        createNotificationChannel()
        startForegroundService()
        initializeWebRTC()
        connectWebSocket()
    }

    private fun startForegroundService() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun initializeWebRTC() {
        Log.d("WebRTCService", "Initializing WebRTC")
        cleanupWebRTCResources()

        eglBase = EglBase.create()

        localView = SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
            setMirror(true)
            setEnableHardwareScaler(true)
            setZOrderMediaOverlay(true)
        }

        remoteView = SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setZOrderMediaOverlay(true)
        }

        webRTCClient = WebRTCClient(
            context = this,
            eglBase = eglBase,
            localView = localView!!,
            remoteView = remoteView!!,
            observer = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { sendIceCandidate(it) }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d("WebRTCService", "ICE connection state: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CHECKING -> {
                            handler.postDelayed({
                                if (webRTCClient.peerConnection.iceConnectionState() ==
                                    PeerConnection.IceConnectionState.CHECKING) {
                                    reconnect()
                                }
                            }, ICE_CONNECTION_TIMEOUT_MS)
                        }
                        PeerConnection.IceConnectionState.CONNECTED ->
                            updateNotification("Call connected")
                        PeerConnection.IceConnectionState.DISCONNECTED ->
                            reconnect()
                        PeerConnection.IceConnectionState.FAILED ->
                            reconnect()
                        else -> {}
                    }
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
                override fun onTrack(transceiver: RtpTransceiver?) {
                    transceiver?.receiver?.track()?.let { track ->
                        if (track.kind() == "video") {
                            (track as VideoTrack).addSink(remoteView)
                            updateNotification("Video stream received")
                        } else if (track.kind() == "audio") {
                            updateNotification("Audio stream received")
                        }
                    }
                }
            }
        )
    }

    private fun cleanupWebRTCResources() {
        try {
            webRTCClient?.close()

            localView?.let {
                it.clearImage()
                it.release()
                localView = null
            }

            remoteView?.let {
                it.clearImage()
                it.release()
                remoteView = null
            }

            eglBase?.release()
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error cleaning up WebRTC resources", e)
        }
    }

    private fun connectWebSocket() {
        webSocketClient = WebSocketClient(object : WebSocketListener {
            override fun onMessage(message: JSONObject) = handleWebSocketMessage(message)

            override fun onConnected() {
                Log.d("WebRTCService", "WebSocket connected")
                updateNotification("Connected to server")
                joinRoom()
            }

            override fun onDisconnected() {
                Log.d("WebRTCService", "WebSocket disconnected")
                updateNotification("Disconnected from server")
                reconnect()
            }

            override fun onError(error: String) {
                Log.e("WebRTCService", "WebSocket error: $error")
                updateNotification("Error: ${error.take(30)}...")
                reconnect()
            }
        })

        webSocketClient.connect(webSocketUrl)
    }

    fun reconnect() {
        handler.post {
            try {
                updateNotification("Reconnecting...")
                webSocketClient.disconnect()
                cleanupWebRTCResources()
                handler.postDelayed({
                    initializeWebRTC()
                    connectWebSocket()
                }, 1000)
            } catch (e: Exception) {
                Log.e("WebRTCService", "Reconnect error", e)
                updateNotification("Reconnect failed")
            }
        }
    }

    private fun joinRoom() {
        val message = JSONObject().apply {
            put("action", "join")
            put("room", roomName)
            put("username", userName)
        }
        webSocketClient.send(message.toString())
    }

    private fun handleWebSocketMessage(message: JSONObject) {
        Log.d("WebRTCService", "Received: $message")

        when (message.optString("type")) {
            "offer" -> handleOffer(message)
            "answer" -> handleAnswer(message)
            "ice_candidate" -> handleIceCandidate(message)
            "room_info" -> {}
            else -> Log.w("WebRTCService", "Unknown message type")
        }
    }

    private fun handleOffer(offer: JSONObject) {
        try {
            val sdp = offer.getJSONObject("sdp")
            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
                sdp.getString("sdp")
            )

            // Modify SDP for better browser compatibility
            val modifiedSdp = sessionDescription.description
                .replace("profile-level-id=42e01f", "profile-level-id=42e01f;packetization-mode=1")
                .replace("a=fmtp:126", "a=fmtp:126 profile-level-id=42e01f;packetization-mode=1")

            val modifiedDesc = SessionDescription(sessionDescription.type, modifiedSdp)

            webRTCClient.peerConnection.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    createAnswer()
                }
                override fun onSetFailure(error: String) {
                    Log.e("WebRTCService", "Set remote desc failed: $error")
                    reconnect()
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(error: String) {}
            }, modifiedDesc)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error handling offer", e)
            reconnect()
        }
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            // Prioritize VP8 for better browser compatibility
            optional.add(MediaConstraints.KeyValuePair("googCodecPreferences", "VP8,H264"))
        }

        webRTCClient.peerConnection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                // Modify SDP for better compatibility
                val modifiedSdp = desc.description
                    .replace("profile-level-id=42e01f", "profile-level-id=42e01f;packetization-mode=1")
                    .replace("a=fmtp:126", "a=fmtp:126 profile-level-id=42e01f;packetization-mode=1")

                val modifiedDesc = SessionDescription(desc.type, modifiedSdp)

                webRTCClient.peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendSessionDescription(modifiedDesc)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e("WebRTCService", "Set local desc failed: $error")
                        reconnect()
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(error: String) {}
                }, modifiedDesc)
            }
            override fun onCreateFailure(error: String) {
                Log.e("WebRTCService", "Create answer failed: $error")
                reconnect()
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    private fun sendSessionDescription(desc: SessionDescription) {
        try {
            val message = JSONObject().apply {
                put("type", desc.type.canonicalForm())
                put("sdp", JSONObject().apply {
                    put("type", desc.type.canonicalForm())
                    put("sdp", desc.description)
                })
                put("room", roomName)
                put("username", userName)
            }
            webSocketClient.send(message.toString())
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error sending SDP", e)
            reconnect()
        }
    }

    private fun handleAnswer(answer: JSONObject) {
        try {
            val sdp = answer.getJSONObject("sdp")
            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
                sdp.getString("sdp")
            )

            // Modify SDP for better compatibility
            val modifiedSdp = sessionDescription.description
                .replace("profile-level-id=42e01f", "profile-level-id=42e01f;packetization-mode=1")
                .replace("a=fmtp:126", "a=fmtp:126 profile-level-id=42e01f;packetization-mode=1")

            val modifiedDesc = SessionDescription(sessionDescription.type, modifiedSdp)

            webRTCClient.peerConnection.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d("WebRTCService", "Answer accepted")
                }
                override fun onSetFailure(error: String) {
                    Log.e("WebRTCService", "Set answer failed: $error")
                    reconnect()
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(error: String) {}
            }, modifiedDesc)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error handling answer", e)
            reconnect()
        }
    }

    private fun handleIceCandidate(candidate: JSONObject) {
        try {
            val ice = candidate.getJSONObject("ice")
            val iceCandidate = IceCandidate(
                ice.getString("sdpMid"),
                ice.getInt("sdpMLineIndex"),
                ice.getString("candidate")
            )
            webRTCClient.peerConnection.addIceCandidate(iceCandidate)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error handling ICE candidate", e)
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        try {
            val message = JSONObject().apply {
                put("type", "ice_candidate")
                put("ice", JSONObject().apply {
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                })
                put("room", roomName)
                put("username", userName)
            }
            webSocketClient.send(message.toString())
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error sending ICE candidate", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "WebRTC Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WebRTC streaming service"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC Service")
            .setContentText("Active in room: $roomName")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC Service")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, notification)
    }

    override fun onDestroy() {
        Log.d("WebRTCService", "Service destroyed")
        cleanupAllResources()
        super.onDestroy()
    }

    private fun cleanupAllResources() {
        cleanupWebRTCResources()
        webSocketClient.disconnect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let {
            if (it == "RECONNECT") {
                reconnect()
            }
        }
        return START_STICKY
    }
}