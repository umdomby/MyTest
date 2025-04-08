package com.example.mytest

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit

class WebRTCService : Service() {
    private val binder = LocalBinder()
    private var webSocket: okhttp3.WebSocket? = null
    private var peerConnection: PeerConnection? = null
    private val handler = Handler(Looper.getMainLooper())

    // WebRTC components
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var eglBase: EglBase
    private var videoCapturer: VideoCapturer? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null

    // Configuration
    private val roomName = "room1"
    private val userName = Build.MODEL ?: "AndroidDevice"
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    // WebSocket
    private val pingInterval = 30000L
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectDelay = 5000L

    // Notification
    private val notificationId = 1
    private val channelId = "webrtc_service_channel"

    inner class LocalBinder : Binder() {
        fun getService(): WebRTCService = this@WebRTCService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        Log.d("WebRTCService", "Service onCreate")
        createNotificationChannel()
        startForeground(notificationId, createNotification())

        initializeWebRTC()
        connectWebSocket()
    }

    private fun initializeWebRTC() {
        Log.d("WebRTCService", "Initializing WebRTC")
        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
        )

        val videoEncoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,
            true
        )

        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .createPeerConnectionFactory()

        createLocalTracks()
    }

    private fun createLocalTracks() {
        Log.d("WebRTCService", "Creating local tracks")

        // Audio
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track_$userName", audioSource)

        // Video
        try {
            videoCapturer = createCameraCapturer()
            videoCapturer?.let { capturer ->
                videoSource = peerConnectionFactory.createVideoSource(false)
                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

                capturer.initialize(surfaceTextureHelper, this, videoSource?.capturerObserver)
                capturer.startCapture(1280, 720, 30)

                localVideoTrack = peerConnectionFactory.createVideoTrack("video_track_$userName", videoSource)
                Log.d("WebRTCService", "Video track created and capturer started")
            } ?: run {
                Log.e("WebRTCService", "Failed to create video capturer")
            }
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error initializing camera", e)
            videoCapturer = null
        }
    }

    private fun createCameraCapturer(): VideoCapturer? {
        return try {
            val cameraEnumerator = Camera2Enumerator(this)
            val deviceNames = cameraEnumerator.deviceNames

            deviceNames.find { cameraEnumerator.isFrontFacing(it) }?.let { cameraName ->
                Log.d("WebRTCService", "Using front camera: $cameraName")
                cameraEnumerator.createCapturer(cameraName, null)
            } ?: deviceNames.firstOrNull()?.let { cameraName ->
                Log.d("WebRTCService", "Using first available camera: $cameraName")
                cameraEnumerator.createCapturer(cameraName, null)
            }
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error creating camera capturer", e)
            null
        }
    }

    private fun stopCamera() {
        try {
            videoCapturer?.let {
                try {
                    it.stopCapture()
                    Log.d("WebRTCService", "Camera capture stopped")
                } catch (e: Exception) {
                    Log.e("WebRTCService", "Error stopping camera capture", e)
                }

                try {
                    it.dispose()
                    Log.d("WebRTCService", "Camera capturer disposed")
                } catch (e: Exception) {
                    Log.e("WebRTCService", "Error disposing camera capturer", e)
                }
            }
            videoCapturer = null
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error in stopCamera", e)
        }
    }

    private fun createPeerConnection() {
        Log.d("WebRTCService", "Creating peer connection")

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        peerConnection = peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d("WebRTCService", "New ICE candidate: ${candidate.sdp}")
                    sendIceCandidate(it)
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d("WebRTCService", "Ice connection state changed: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        Log.d("WebRTCService", "ICE Connected")
                        updateNotification("Connected to peer")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        handler.post { scheduleReconnect() }
                    }
                    else -> {}
                }
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onRemoveStream(stream: MediaStream?) {}
        }) ?: run {
            Log.e("WebRTCService", "Failed to create peer connection")
            return
        }

        // Add tracks
        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("stream_$userName"))
            Log.d("WebRTCService", "Audio track added to peer connection")
        }

        localVideoTrack?.let {
            peerConnection?.addTrack(it, listOf("stream_$userName"))
            Log.d("WebRTCService", "Video track added to peer connection")
        }
    }

    private fun connectWebSocket() {
        Log.d("WebRTCService", "Connecting WebSocket")

        val client = OkHttpClient.Builder()
            .pingInterval(pingInterval, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("wss://anybet.site/ws")
            .build()

        webSocket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                Log.d("WebRTCService", "WebSocket connected")
                reconnectAttempts = 0
                joinRoom()
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                try {
                    Log.d("WebRTCService", "Received message: $text")
                    val json = JSONObject(text)

                    when (json.optString("type")) {
                        "offer" -> handleOffer(json)
                        "answer" -> handleAnswer(json)
                        "ice_candidate" -> handleRemoteIceCandidate(json)
                        "joined" -> handleJoinedRoom()
                        else -> if (json.has("ice")) handleRemoteIceCandidate(json)
                    }
                } catch (e: Exception) {
                    Log.e("WebRTCService", "Error processing message", e)
                }
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                Log.d("WebRTCService", "WebSocket closed: $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e("WebRTCService", "WebSocket error", t)
                scheduleReconnect()
            }
        })
    }

    private fun joinRoom() {
        val message = JSONObject().apply {
            put("type", "join")
            put("room", roomName)
            put("username", userName)
        }
        webSocket?.send(message.toString())
        Log.d("WebRTCService", "Joining room: $roomName as $userName")
    }

    private fun handleJoinedRoom() {
        Log.d("WebRTCService", "Joined room successfully")
        handler.post {
            createPeerConnection()
            updateNotification("Waiting for peer in $roomName")
        }
    }

    private fun handleOffer(offer: JSONObject) {
        try {
            Log.d("WebRTCService", "Received offer")
            val sdp = offer.getJSONObject("sdp")
            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
                sdp.getString("sdp")
            )

            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d("WebRTCService", "Remote description set successfully")
                    createAnswer()
                }
                override fun onSetFailure(error: String) {
                    Log.e("WebRTCService", "Set remote description failed: $error")
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(error: String) {}
            }, sessionDescription)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error handling offer", e)
        }
    }

    private fun createAnswer() {
        Log.d("WebRTCService", "Creating answer...")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                Log.d("WebRTCService", "Answer created successfully")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d("WebRTCService", "Local description set successfully")
                        sendSessionDescription(desc)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e("WebRTCService", "Set local description failed: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(error: String) {}
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {
                Log.e("WebRTCService", "Create answer failed: $error")
            }
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
                put("target", "all")
            }
            Log.d("WebRTCService", "Sending ${desc.type}: ${desc.description}")
            webSocket?.send(message.toString())
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error sending session description", e)
        }
    }

    private fun handleAnswer(answer: JSONObject) {
        try {
            Log.d("WebRTCService", "Received answer")
            val sdp = answer.getJSONObject("sdp")
            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
                sdp.getString("sdp")
            )

            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d("WebRTCService", "Remote description set successfully")
                    updateNotification("Streaming active")
                }
                override fun onSetFailure(error: String) {
                    Log.e("WebRTCService", "Set remote description failed: $error")
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
            Log.d("WebRTCService", "Received ICE candidate")
            val ice = if (candidate.has("ice")) candidate.getJSONObject("ice") else candidate
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
                put("target", "all")
            }
            Log.d("WebRTCService", "Sending ICE candidate: ${candidate.sdp}")
            webSocket?.send(message.toString())
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error sending ICE candidate", e)
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.w("WebRTCService", "Max reconnect attempts reached")
            stopSelf()
            return
        }

        reconnectAttempts++
        Log.d("WebRTCService", "Scheduling reconnect attempt $reconnectAttempts in ${reconnectDelay}ms")

        handler.postDelayed({
            if (!isConnected()) {
                webSocket?.close(1000, "Reconnecting")
                webSocket = null
                connectWebSocket()
            }
        }, reconnectDelay)
    }

    private fun isConnected(): Boolean {
        return webSocket != null && peerConnection != null &&
                peerConnection?.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "WebRTC Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC трансляция")
            .setContentText("Ожидание подключения к комнате $roomName")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC трансляция")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(notificationId, notification)
    }

    override fun onDestroy() {
        Log.d("WebRTCService", "Service destroyed")

        try {
            stopCamera()

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            webSocket?.close(1000, "Service destroyed")
            webSocket = null

            peerConnection?.close()
            peerConnection = null

            localAudioTrack?.dispose()
            localAudioTrack = null

            localVideoTrack?.dispose()
            localVideoTrack = null

            videoSource?.dispose()
            videoSource = null

            peerConnectionFactory.dispose()
            eglBase.release()
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error during cleanup", e)
        }

        super.onDestroy()
    }
}