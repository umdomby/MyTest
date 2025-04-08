package com.example.mytest

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

class WebRTCService : Service() {
    private val binder = LocalBinder()
    private var webSocket: WebSocket? = null
    private var peerConnection: PeerConnection? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pingTimer: Timer? = null
    private var lastPongReceived = System.currentTimeMillis()

    // WebRTC components
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var eglBase: EglBase
    private var videoCapturer: VideoCapturer? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // Configuration
    private val roomName = "room1"
    private val userName = Build.MODEL ?: "AndroidDevice"
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    // WebSocket
    private val pingInterval = 25000L // Ping every 25 seconds
    private val pongTimeout = 30000L  // Timeout after 30 seconds
    private val reconnectDelay = 5000L
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    // Notification
    private val notificationId = 1
    private val channelId = "webrtc_service_channel"

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
        startPingTimer()
    }

    private fun startForegroundService() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } catch (e: SecurityException) {
                Log.e("WebRTCService", "Failed to start with media projection", e)
                startForeground(notificationId, notification)
            }
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun initializeWebRTC() {
        Log.d("WebRTCService", "Initializing WebRTC")
        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        createLocalTracks()
    }

    private fun createLocalTracks() {
        Log.d("WebRTCService", "Creating local tracks")

        // Audio track
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track_$userName", audioSource)

        // Video track
        videoCapturer = createCameraCapturer()
        videoCapturer?.let { capturer ->
            val videoSource = peerConnectionFactory.createVideoSource(false)
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            capturer.initialize(surfaceTextureHelper, applicationContext, videoSource.capturerObserver)
            capturer.startCapture(1280, 720, 30)
            localVideoTrack = peerConnectionFactory.createVideoTrack("video_track_$userName", videoSource)
        }
    }

    private fun createCameraCapturer(): VideoCapturer? {
        return Camera2Enumerator(this).run {
            deviceNames.find { isFrontFacing(it) }?.let {
                Log.d("WebRTCService", "Using front-facing camera")
                createCapturer(it, null)
            } ?: deviceNames.firstOrNull()?.let {
                Log.d("WebRTCService", "Using first available camera")
                createCapturer(it, null)
            }
        }
    }

    private fun connectWebSocket() {
        Log.d("WebRTCService", "Connecting to WebSocket")

        val client = OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.MILLISECONDS) // Disable built-in ping
            .build()

        val request = Request.Builder()
            .url("wss://anybet.site/ws")
            .build()

        webSocket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebRTCService", "WebSocket connected")
                reconnectAttempts = 0
                lastPongReceived = System.currentTimeMillis()
                joinRoom()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "pong" -> {
                            lastPongReceived = System.currentTimeMillis()
                            Log.d("WebRTCService", "Pong received")
                        }
                        "offer" -> handleOffer(json)
                        "answer" -> handleAnswer(json)
                        "ice_candidate" -> handleRemoteIceCandidate(json)
                        "joined" -> handleJoinedRoom()
                    }
                } catch (e: Exception) {
                    Log.e("WebRTCService", "Error processing message", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WebRTCService", "WebSocket closed: $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebRTCService", "WebSocket error", t)
                scheduleReconnect()
            }
        })
    }

    private fun startPingTimer() {
        pingTimer?.cancel()
        pingTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    checkConnection()
                }
            }, pingInterval, pingInterval)
        }
    }

    private fun checkConnection() {
        val now = System.currentTimeMillis()
        if (now - lastPongReceived > pongTimeout) {
            Log.w("WebRTCService", "Pong timeout, reconnecting...")
            handler.post { scheduleReconnect() }
        } else {
            sendPing()
        }
    }

    private fun sendPing() {
        try {
            val pingMessage = JSONObject().apply {
                put("type", "ping")
            }
            webSocket?.send(pingMessage.toString())
            Log.d("WebRTCService", "Ping sent")
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error sending ping", e)
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.w("WebRTCService", "Max reconnect attempts reached")
            stopSelf()
            return
        }

        reconnectAttempts++
        Log.d("WebRTCService", "Scheduling reconnect attempt $reconnectAttempts")

        handler.postDelayed({
            webSocket?.close(1000, "Reconnecting")
            connectWebSocket()
        }, reconnectDelay)
    }

    private fun joinRoom() {
        val message = JSONObject().apply {
            put("type", "join")
            put("room", roomName)
            put("username", userName)
        }
        webSocket?.send(message.toString())
        Log.d("WebRTCService", "Joining room: $roomName")
    }

    private fun handleJoinedRoom() {
        Log.d("WebRTCService", "Joined room successfully")
        handler.post {
            createPeerConnection()
            updateNotification("Waiting for peer in $roomName")
        }
    }

    private fun createPeerConnection() {
        Log.d("WebRTCService", "Creating peer connection")

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        peerConnection = peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { sendIceCandidate(it) }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d("WebRTCService", "ICE connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        updateNotification("Streaming active")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        // Handle disconnection
                    }
                    else -> {}
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d("WebRTCService", "Track added")
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                Log.d("WebRTCService", "Transceiver track: ${transceiver?.mediaType}")
            }

            // Other required overrides
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddStream(p0: MediaStream?) {} // Добавлен для совместимости
        })

        // Add tracks
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("stream_$userName")) }
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("stream_$userName")) }
    }

    private fun handleOffer(offer: JSONObject) {
        try {
            val sdp = offer.getJSONObject("sdp")
            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
                sdp.getString("sdp")
            )

            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    createAnswer()
                }
                override fun onSetFailure(error: String) {
                    Log.e("WebRTCService", "Set remote desc failed: $error")
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(error: String) {}
            }, sessionDescription)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error handling offer", e)
        }
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendSessionDescription(desc)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e("WebRTCService", "Set local desc failed: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(error: String) {}
                }, desc)
            }
            override fun onCreateFailure(error: String) {
                Log.e("WebRTCService", "Create answer failed: $error")
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
            webSocket?.send(message.toString())
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error sending SDP", e)
        }
    }

    private fun handleAnswer(answer: JSONObject) {
        try {
            val sdp = answer.getJSONObject("sdp")
            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
                sdp.getString("sdp")
            )

            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d("WebRTCService", "Answer accepted")
                }
                override fun onSetFailure(error: String) {
                    Log.e("WebRTCService", "Set answer failed: $error")
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(error: String) {}
            }, sessionDescription)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error handling answer", e)
        }
    }

    private fun handleRemoteIceCandidate(candidate: JSONObject) {
        try {
            val ice = candidate.getJSONObject("ice")
            val iceCandidate = IceCandidate(
                ice.getString("sdpMid"),
                ice.getInt("sdpMLineIndex"),
                ice.getString("candidate")
            )
            peerConnection?.addIceCandidate(iceCandidate)
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
            webSocket?.send(message.toString())
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
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC Service")
            .setContentText("Waiting for connection in $roomName")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC Service")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, notification)
    }

    override fun onDestroy() {
        Log.d("WebRTCService", "Service destroyed")
        cleanupResources()
        super.onDestroy()
    }

    private fun cleanupResources() {
        try {
            pingTimer?.cancel()
            webSocket?.close(1000, "Service destroyed")
            peerConnection?.close()
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            localAudioTrack?.dispose()
            localVideoTrack?.dispose()
            peerConnectionFactory.dispose()
            eglBase.release()
        } catch (e: Exception) {
            Log.e("WebRTCService", "Cleanup error", e)
        }
    }
}