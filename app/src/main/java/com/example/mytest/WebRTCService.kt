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

    // Конфигурация
    private val roomName = "room1"
    private val userName = Build.MODEL ?: "AndroidDevice"
    private val webSocketUrl = "wss://ardua.site/ws"
    private val isLeader = true

    // Управление уведомлениями
    private val notificationId = 1
    private val channelId = "webrtc_service_channel"

    // Обработчики
    private val handler = Handler(Looper.getMainLooper())
    private val reconnectHandler = Handler(Looper.getMainLooper())

    // Переподключение
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
    private val baseReconnectDelay = 5000L
    private val maxReconnectDelay = 30000L

    // Мониторинг сети
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
        startForegroundService()
        initializeWebRTC()
        connectWebSocketWithRetry()
    }

    private fun initializeWebRTC() {
        Log.d("WebRTCService", "Initializing WebRTC")
        cleanupWebRTCResources()

        eglBase = EglBase.create()
        webRTCClient = WebRTCClient(
            context = this,
            eglBase = eglBase,
            observer = createPeerConnectionObserver()
        )

        // Установка обработчика ICE кандидатов
        webRTCClient.setIceCandidateListener(object : WebRTCClient.IceCandidateListener {
            override fun onIceCandidate(candidate: IceCandidate) {
                sendIceCandidate(candidate)
            }
        })
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
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.FAILED -> {
                    updateNotification("Connection lost, reconnecting...")
                    reconnect()
                }
                PeerConnection.IceConnectionState.CONNECTED -> {
                    updateNotification("Connection established")
                    reconnectAttempts = 0
                }
                else -> {}
            }
        }

        // Остальные методы Observer оставляем пустыми
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

    private fun connectWebSocketWithRetry() {
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
                connectWebSocket()
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to WebSocket", e)
                connectWebSocketWithRetry()
            }
        }, delay)
    }

    private fun calculateReconnectDelay(): Long {
        return minOf(baseReconnectDelay * (1 shl (reconnectAttempts - 1)), maxReconnectDelay)
    }

    private fun connectWebSocket() {
        webSocketClient = WebSocketClient(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                updateNotification("Connected to server")
                reconnectAttempts = 0
                joinRoom()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    handleWebSocketMessage(message)
                } catch (e: Exception) {
                    Log.e(TAG, "WebSocket message parse error", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket disconnected: $reason")
                updateNotification("Disconnected from server")
                connectWebSocketWithRetry()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                updateNotification("Connection error")
                connectWebSocketWithRetry()
            }
        })

        webSocketClient.connect(webSocketUrl)
    }

    private fun joinRoom() {
        try {
            val message = JSONObject().apply {
                put("action", "join")
                put("room", roomName)
                put("username", userName)
                put("isLeader", isLeader)
                put("reconnecting", reconnectAttempts > 1)
            }
            webSocketClient.send(message.toString())

            if (isLeader) {
                handler.postDelayed({
                    if (isConnected()) {
                        createAndSendOffer()
                    } else {
                        Log.w(TAG, "Not connected, skipping offer creation")
                    }
                }, 1000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error joining room", e)
        }
    }

    private fun handleWebSocketMessage(message: JSONObject) {
        Log.d(TAG, "Received: ${message.toString().take(200)}...")

        try {
            when (message.optString("type")) {
                "offer" -> handleOffer(message)
                "answer" -> handleAnswer(message)
                "ice_candidate" -> handleIceCandidate(message)
                "room_info" -> handleRoomInfo(message)
                else -> Log.w(TAG, "Unknown message type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    private fun handleOffer(offer: JSONObject) {
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
            val hasSlave = data.optBoolean("hasSlave", false)

            if (isLeader && !hasSlave) {
                handler.postDelayed({
                    if (isConnected()) {
                        createAndSendOffer()
                    }
                }, 1000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling room info", e)
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

            webRTCClient.peerConnection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    Log.d(TAG, "Offer created successfully")
                    webRTCClient.peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set successfully")
                            sendSessionDescription(desc)
                        }
                        override fun onSetFailure(error: String) {
                            Log.e(TAG, "Error setting local description: $error")
                            handler.postDelayed({ createAndSendOffer() }, 1000)
                        }
                        override fun onCreateSuccess(desc: SessionDescription?) {}
                        override fun onCreateFailure(error: String) {}
                    }, desc)
                }
                override fun onCreateFailure(error: String) {
                    Log.e(TAG, "Error creating offer: $error")
                    handler.postDelayed({ createAndSendOffer() }, 1000)
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            }, constraints)
        } catch (e: Exception) {
            Log.e(TAG, "Error in createAndSendOffer", e)
            handler.postDelayed({ createAndSendOffer() }, 1000)
        }
    }

    private fun canCreateOffer(): Boolean {
        return ::webRTCClient.isInitialized &&
                ::webSocketClient.isInitialized &&
                webSocketClient.isConnected() &&
                isLeader
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

    fun reconnect() {
        handler.post {
            try {
                updateNotification("Reconnecting...")
                cleanupAllResources()
                initializeWebRTC()
                connectWebSocket()

                if (isLeader) {
                    handler.postDelayed({
                        if (isConnected()) {
                            createAndSendOffer()
                        }
                    }, 2000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reconnection error", e)
                handler.postDelayed({
                    reconnect()
                }, 5000)
            }
        }
    }

    private fun cleanupAllResources() {
        handler.removeCallbacksAndMessages(null)
        cleanupWebRTCResources()
        if (::webSocketClient.isInitialized) {
            webSocketClient.disconnect()
        }
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

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork: NetworkInfo? = connectivityManager.activeNetworkInfo
        return activeNetwork?.isConnectedOrConnecting == true
    }

    fun isConnected(): Boolean {
        return ::webSocketClient.isInitialized && webSocketClient.isConnected()
    }

    fun isInitialized(): Boolean {
        return ::webSocketClient.isInitialized &&
                ::webRTCClient.isInitialized &&
                ::eglBase.isInitialized
    }

    // Управление уведомлениями
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

    private fun startForegroundService() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException: ${e.message}")
                startForeground(notificationId, notification)
            }
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC Service")
            .setContentText("Active in room: $roomName")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC Service")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, notification)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        unregisterReceiver(networkReceiver)
        cleanupAllResources()
        reconnectHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let {
            if (it == "RECONNECT") {
                reconnect()
            }
        }
        return START_STICKY
    }

    companion object {
        private const val TAG = "WebRTCService"
    }
}