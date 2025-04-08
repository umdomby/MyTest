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
    private var webSocket: WebSocket? = null
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
        Log.d("WebRTCService", "Service onCreate")
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(notificationId, createNotification())
        }

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
        Log.d("WebRTCService", "Creating local tracks")

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
                candidate?.let {
                    Log.d("WebRTCService", "New ICE candidate: ${it.sdp}")
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
                Log.d("WebRTCService", "Ice gathering state: $state")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d("WebRTCService", "Signaling state: $state")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d("WebRTCService", "Track added: ${receiver?.track()?.id()}")
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                Log.d("WebRTCService", "Transceiver track: ${transceiver?.mediaType}")
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onRemoveStream(stream: MediaStream?) {}
        })

        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("stream_$userName"))
            Log.d("WebRTCService", "Audio track added")
        }

        localVideoTrack?.let {
            peerConnection?.addTrack(it, listOf("stream_$userName"))
            Log.d("WebRTCService", "Video track added")
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
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebRTCService", "WebSocket connected")
                reconnectAttempts = 0
                joinRoom()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    Log.d("WebRTCService", "Received: $text")
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
            updateNotification("Waiting for peer")
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
                    Log.d("WebRTCService", "Remote description set")
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
        Log.d("WebRTCService", "Creating answer")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                Log.d("WebRTCService", "Answer created")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d("WebRTCService", "Local description set")
                        sendSessionDescription(desc)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e("WebRTCService", "Set local desc failed: $error")
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
            Log.d("WebRTCService", "Sending ${desc.type}")
            webSocket?.send(message.toString())
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error sending SDP", e)
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
                    Log.d("WebRTCService", "Remote description set")
                    updateNotification("Streaming active")
                }
                override fun onSetFailure(error: String) {
                    Log.e("WebRTCService", "Set remote desc failed: $error")
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
            Log.e("WebRTCService", "Error handling ICE", e)
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
            Log.d("WebRTCService", "Sending ICE candidate")
            webSocket?.send(message.toString())
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error sending ICE", e)
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.w("WebRTCService", "Max reconnect attempts")
            stopSelf()
            return
        }

        reconnectAttempts++
        Log.d("WebRTCService", "Reconnect attempt $reconnectAttempts")

        handler.postDelayed({
            webSocket?.close(1000, "Reconnecting")
            connectWebSocket()
        }, reconnectDelay)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "WebRTC Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC Service")
            .setContentText("Streaming in room $roomName")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC Service")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(notificationId, notification)
    }

    override fun onDestroy() {
        Log.d("WebRTCService", "Service destroyed")

        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            webSocket?.close(1000, "Service destroyed")
            peerConnection?.close()
            localAudioTrack?.dispose()
            localVideoTrack?.dispose()
            peerConnectionFactory.dispose()
            eglBase.release()
        } catch (e: Exception) {
            Log.e("WebRTCService", "Cleanup error", e)
        }

        super.onDestroy()
    }
}