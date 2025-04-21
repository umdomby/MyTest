package com.example.mytest

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.*

class WebRTCService : Service() {
    private val binder = LocalBinder()
    private lateinit var webSocketClient: WebSocketClient
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var eglBase: EglBase

    private val roomName = "room1"
    private val userName = Build.MODEL ?: "AndroidDevice"
    private val webSocketUrl = "wss://ardua.site/ws"
    private val isLeader = true

    private val notificationId = 1
    private val channelId = "webrtc_service_channel"

    private val handler = Handler(Looper.getMainLooper())
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val pingHandler = Handler(Looper.getMainLooper())

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
    private val baseReconnectDelay = 5000L
    private val maxReconnectDelay = 30000L
    private var lastPongTime = 0L

    // Состояние подключения
    private var isOfferCreated = false
    private var isInRoom = false
    private var hasSlave = false
    private var isConnectionEstablished = false
    private var shouldReconnect = false

    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isNetworkAvailable() && !isConnected()) {
                reconnect()
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): WebRTCService = this@WebRTCService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        registerReceiver(networkReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        createNotificationChannel()
        startForeground(notificationId, createNotification())
        initializeWebRTC()
        connectWebSocket()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork: NetworkInfo? = connectivityManager.activeNetworkInfo
        return activeNetwork?.isConnectedOrConnecting == true
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

    private fun createNotification(text: String = "Active in room: $roomName"): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC Service")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, createNotification(text))
    }

    private fun initializeWebRTC() {
        Log.d(TAG, "Initializing WebRTC")
        cleanupWebRTCResources()

        eglBase = EglBase.create()
        webRTCClient = WebRTCClient(
            context = this,
            eglBase = eglBase,
            observer = createPeerConnectionObserver()
        )

        webRTCClient.setIceCandidateListener(object : WebRTCClient.IceCandidateListener {
            override fun onIceCandidate(candidate: IceCandidate) {
                sendIceCandidate(candidate)
            }
        })
    }

    private fun cleanupWebRTCResources() {
        try {
            if (::webRTCClient.isInitialized) {
                webRTCClient.close()
            }
            if (::eglBase.isInitialized) {
                eglBase.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning WebRTC resources", e)
        }
    }

    private fun createPeerConnectionObserver() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let {
                Log.d(TAG, "Local ICE candidate: ${it.sdpMid}:${it.sdpMLineIndex} ${it.sdp}")
                sendIceCandidate(it)
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ICE connection state: $state")
            when (state) {
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    updateNotification("Connection lost, reconnecting...")
                    handler.postDelayed({ checkAndReconnect() }, 2000)
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    updateNotification("Connection failed, reconnecting...")
                    handler.postDelayed({ checkAndReconnect() }, 2000)
                }
                PeerConnection.IceConnectionState.CONNECTED -> {
                    updateNotification("Connection established")
                    reconnectAttempts = 0
                    isConnectionEstablished = true
                }
                PeerConnection.IceConnectionState.COMPLETED -> {
                    isConnectionEstablished = true
                }
                else -> {}
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        override fun onTrack(transceiver: RtpTransceiver?) {}
    }

    private fun checkAndReconnect() {
        if (!isConnectionEstablished && !shouldReconnect) {
            shouldReconnect = true
            reconnect()
        }
    }

    private fun connectWebSocket() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.e(TAG, "Max reconnect attempts reached")
            stopSelf()
            return
        }

        reconnectAttempts++
        val delay = calculateReconnectDelay()
        Log.d(TAG, "Attempting to connect to WebSocket (attempt $reconnectAttempts), delay: ${delay}ms")

        reconnectHandler.postDelayed({
            try {
                if (::webSocketClient.isInitialized) {
                    webSocketClient.disconnect()
                }
                createWebSocketConnection()
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to WebSocket", e)
                connectWebSocket()
            }
        }, delay)
    }

    private fun calculateReconnectDelay(): Long {
        return minOf(baseReconnectDelay * (1 shl (reconnectAttempts - 1)), maxReconnectDelay)
    }

    private fun createWebSocketConnection() {
        webSocketClient = WebSocketClient(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                updateNotification("Connected to server")
                reconnectAttempts = 0
                isInRoom = false
                hasSlave = false
                isOfferCreated = false
                isConnectionEstablished = false
                shouldReconnect = false
                joinRoom()
                startPingPong()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (text == "pong") {
                        lastPongTime = System.currentTimeMillis()
                        return
                    }

                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "room_info" -> handleRoomInfo(message)
                        "error" -> {
                            val error = message.optString("data")
                            Log.e(TAG, "WebSocket error: $error")
                            if (error.contains("already has leader") || error.contains("Room is full")) {
                                handler.postDelayed({ reconnect() }, 1000)
                            }
                        }
                        else -> handleWebSocketMessage(message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WebSocket message parse error", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket disconnected: $reason")
                updateNotification("Disconnected from server")
                stopPingPong()
                if (code != 1000) {
                    connectWebSocket()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                updateNotification("Connection error")
                stopPingPong()
                connectWebSocket()
            }
        })

        webSocketClient.connect(webSocketUrl)
    }

    private fun startPingPong() {
        lastPongTime = System.currentTimeMillis()
        pingHandler.postDelayed(object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() - lastPongTime > 30000) {
                    Log.w(TAG, "Ping timeout, reconnecting...")
                    reconnect()
                    return
                }

                if (isConnected()) {
                    webSocketClient.send("ping")
                }
                pingHandler.postDelayed(this, 15000)
            }
        }, 15000)
    }

    private fun stopPingPong() {
        pingHandler.removeCallbacksAndMessages(null)
    }

    private fun joinRoom() {
        try {
            val message = JSONObject().apply {
                put("action", "join")
                put("room", roomName)
                put("username", userName)
                put("isLeader", isLeader)
            }
            webSocketClient.send(message.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error joining room", e)
        }
    }

    private fun isConnected(): Boolean {
        return ::webSocketClient.isInitialized && webSocketClient.isConnected()
    }

    private fun canCreateOffer(): Boolean {
        return ::webRTCClient.isInitialized &&
                ::webSocketClient.isInitialized &&
                webSocketClient.isConnected() &&
                isLeader &&
                isInRoom &&
                !hasSlave &&
                !isOfferCreated
    }

    private fun handleWebSocketMessage(message: JSONObject) {
        Log.d(TAG, "Received: ${message.toString().take(200)}...")

        try {
            when (message.optString("type")) {
                "offer" -> handleOffer(message)
                "answer" -> handleAnswer(message)
                "ice_candidate" -> handleIceCandidate(message)
                "room_info" -> handleRoomInfo(message)
                "error" -> handleError(message)
                else -> Log.w(TAG, "Unknown message type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    private fun handleOffer(offer: JSONObject) {
        // Ведущий не должен обрабатывать офферы
        if (!isLeader) {
            try {
                val sdp = offer.getJSONObject("sdp")
                val sessionDescription = SessionDescription(
                    SessionDescription.Type.OFFER,
                    sdp.getString("sdp")
                )

                webRTCClient.peerConnection.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val constraints = MediaConstraints().apply {
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                        }
                        createAnswer(constraints)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Error setting remote description: $error")
                    }
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onCreateFailure(error: String) {}
                }, sessionDescription)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling offer", e)
            }
        }
    }

    private fun createAnswer(constraints: MediaConstraints) {
        try {
            webRTCClient.peerConnection.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    webRTCClient.peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            sendSessionDescription(desc)
                        }
                        override fun onSetFailure(error: String) {
                            Log.e(TAG, "Error setting local description: $error")
                        }
                        override fun onCreateSuccess(desc: SessionDescription?) {}
                        override fun onCreateFailure(error: String) {}
                    }, desc)
                }
                override fun onCreateFailure(error: String) {
                    Log.e(TAG, "Error creating answer: $error")
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            }, constraints)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating answer", e)
        }
    }

    private fun handleAnswer(answer: JSONObject) {
        if (isLeader) {
            try {
                val sdp = answer.getJSONObject("sdp")
                val sessionDescription = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
                    sdp.getString("sdp")
                )

                webRTCClient.peerConnection.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Answer accepted")
                        hasSlave = true
                        isConnectionEstablished = true
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Error setting answer: $error")
                    }
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onCreateFailure(error: String) {}
                }, sessionDescription)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling answer", e)
            }
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
            Log.e(TAG, "Error handling ICE candidate", e)
        }
    }

    private fun handleRoomInfo(message: JSONObject) {
        try {
            val data = message.getJSONObject("data")
            hasSlave = data.optBoolean("hasSlave", false)
            val users = data.optJSONArray("users")?.let {
                (0 until it.length()).map { i -> it.getString(i) }
            } ?: emptyList()

            isInRoom = users.contains(userName)
            Log.d(TAG, "Room info: hasSlave=$hasSlave, users=$users, isInRoom=$isInRoom")

            if (isLeader && isInRoom) {
                if (users.size > 2) {
                    // Если в комнате больше 2 пользователей - переподключаемся
                    Log.w(TAG, "Room has too many users, reconnecting...")
                    reconnect()
                } else if (!hasSlave && !isOfferCreated) {
                    // Если нет ведомого и оффер еще не создан - создаем оффер
                    handler.postDelayed({
                        if (canCreateOffer()) {
                            createAndSendOffer()
                        }
                    }, 1000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling room info", e)
        }
    }

    private fun handleError(message: JSONObject) {
        val error = message.optString("data", "Unknown error")
        Log.e(TAG, "Server error: $error")

        if (error.contains("already has leader") ||
            error.contains("Room is full") ||
            error.contains("Room already has")) {
            reconnect()
        }
    }

    private fun createAndSendOffer() {
        if (!canCreateOffer()) {
            Log.w(TAG, "Cannot create offer - not ready")
            return
        }

        try {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }

            Log.d(TAG, "Creating offer...")
            webRTCClient.peerConnection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    Log.d(TAG, "Offer created successfully")
                    isOfferCreated = true
                    webRTCClient.peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set successfully")
                            sendSessionDescription(desc)
                        }
                        override fun onSetFailure(error: String) {
                            Log.e(TAG, "Error setting local description: $error")
                            isOfferCreated = false
                        }
                        override fun onCreateSuccess(desc: SessionDescription?) {}
                        override fun onCreateFailure(error: String) {}
                    }, desc)
                }
                override fun onCreateFailure(error: String) {
                    Log.e(TAG, "Error creating offer: $error")
                    isOfferCreated = false
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            }, constraints)
        } catch (e: Exception) {
            Log.e(TAG, "Error in createAndSendOffer", e)
            isOfferCreated = false
        }
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
            Log.e(TAG, "Error sending SDP", e)
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
            Log.e(TAG, "Error sending ICE candidate", e)
        }
    }

    private fun reconnect() {
        if (shouldReconnect) {
            handler.post {
                try {
                    updateNotification("Reconnecting...")
                    isOfferCreated = false
                    isInRoom = false
                    hasSlave = false
                    isConnectionEstablished = false
                    cleanupAllResources()
                    initializeWebRTC()
                    connectWebSocket()
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnection error", e)
                    handler.postDelayed({
                        reconnect()
                    }, 5000)
                }
            }
        }
    }

    private fun cleanupAllResources() {
        handler.removeCallbacksAndMessages(null)
        stopPingPong()
        cleanupWebRTCResources()
        if (::webSocketClient.isInitialized) {
            webSocketClient.disconnect()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        unregisterReceiver(networkReceiver)
        cleanupAllResources()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WebRTCService"
    }
}