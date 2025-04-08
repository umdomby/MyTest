package com.example.mytest

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
import android.os.Build
import android.annotation.SuppressLint

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
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(notificationId, createNotification())
        }

        initializeWebRTC()
        connectWebSocket()
    }

    private fun initializeWebRTC() {
        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options().apply {
            disableEncryption = false
            disableNetworkMonitor = false
        }

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()

        createLocalTracks()
    }

    private fun createLocalTracks() {
        // Audio
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track_$userName", audioSource)

        // Video
        videoCapturer = createCameraCapturer()
        videoCapturer?.let { capturer ->
            val videoSource = peerConnectionFactory.createVideoSource(false)
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            capturer.initialize(surfaceTextureHelper, applicationContext, videoSource.capturerObserver)
            capturer.startCapture(1280, 720, 30)

            localVideoTrack = peerConnectionFactory.createVideoTrack("video_track_$userName", videoSource)
            Log.d("WebRTCService", "Video track created and capturer started")
        } ?: run {
            Log.e("WebRTCService", "Failed to create video capturer")
        }
    }

    private fun createPeerConnection() {
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
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        handler.post { scheduleReconnect() }
                    }
                    else -> {}
                }
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d("WebRTCService", "Ice gathering state changed: $state")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d("WebRTCService", "Signaling state changed: $state")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d("WebRTCService", "Add track: ${receiver?.track()?.id()}")
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                Log.d("WebRTCService", "Track added: ${transceiver?.mediaType}")
            }

            // Остальные методы Observer оставляем пустыми
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onRemoveStream(stream: MediaStream?) {}
        })

        // Добавляем треки
        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("stream_$userName"))
            Log.d("WebRTCService", "Audio track added to peer connection")
        }

        localVideoTrack?.let {
            peerConnection?.addTrack(it, listOf("stream_$userName"))
            Log.d("WebRTCService", "Video track added to peer connection")
        }
    }

    private fun createCameraCapturer(): VideoCapturer? {
        return Camera2Enumerator(this).run {
            deviceNames.find { isFrontFacing(it) }?.let { createCapturer(it, null) }
                ?: deviceNames.firstOrNull()?.let { createCapturer(it, null) }
        }
    }

    private fun connectWebSocket() {
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

                    if (!json.has("type")) {
                        if (json.has("ice")) {
                            handleRemoteIceCandidate(json)
                        }
                        return
                    }

                    when (json.getString("type")) {
                        "offer" -> handleOffer(json)
                        "answer" -> handleAnswer(json)
                        "ice_candidate" -> handleRemoteIceCandidate(json)
                        "joined" -> handleJoinedRoom()
                        "room_info" -> Log.d("WebRTCService", "Room info updated: ${json.getJSONObject("data")}")
                        else -> Log.w("WebRTCService", "Unknown message type: ${json.getString("type")}")
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
        createPeerConnection()
        updateNotification("Waiting for peer in $roomName")
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
            val ice = if (candidate.has("ice")) {
                candidate.getJSONObject("ice")
            } else {
                candidate
            }
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
            return
        }

        reconnectAttempts++
        Log.d("WebRTCService", "Scheduling reconnect attempt $reconnectAttempts in ${reconnectDelay}ms")
        handler.postDelayed({
            connectWebSocket()
        }, reconnectDelay)
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

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    override fun onDestroy() {
        Log.d("WebRTCService", "Service destroyed")
        webSocket?.close(1000, "Service destroyed")
        peerConnection?.close()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        localAudioTrack?.dispose()
        localVideoTrack?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnectionFactory.dispose()
        eglBase.release()
        super.onDestroy()
    }
}