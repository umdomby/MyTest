// file: src/main/java/com/example/mytest/WebRTCService.kt
package com.example.mytest

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import org.webrtc.*
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType

class WebRTCService : Service() {

    companion object {
        var isRunning = false
            private set
        var currentRoomName = ""
        const val ACTION_SERVICE_STATE = "com.example.mytest.SERVICE_STATE"
        const val EXTRA_IS_RUNNING = "is_running"
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SERVICE_STATE) {
                val isRunning = intent.getBooleanExtra(EXTRA_IS_RUNNING, false)
                // Можно обновить UI активности, если она видима
            }
        }
    }

    private fun sendServiceStateUpdate() {
        val intent = Intent(ACTION_SERVICE_STATE).apply {
            putExtra(EXTRA_IS_RUNNING, isRunning)
        }
        sendBroadcast(intent)
    }

    private var isConnected = false // Флаг подключения
    private var isConnecting = false // Флаг процесса подключения

    private var shouldStop = false
    private var isUserStopped = false

    private val binder = LocalBinder()
    private lateinit var webSocketClient: WebSocketClient
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var eglBase: EglBase

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
    private val reconnectDelay = 5000L // 5 секунд

    private lateinit var remoteView: SurfaceViewRenderer

    private var roomName = "room1" // Будет перезаписано при старте
    private val userName = Build.MODEL ?: "AndroidDevice"
    private val webSocketUrl = "wss://ardua.site/wsgo"

    private val notificationId = 1
    private val channelId = "webrtc_service_channel"
    private val handler = Handler(Looper.getMainLooper())

    private var isStateReceiverRegistered = false
    private var isConnectivityReceiverRegistered = false

    inner class LocalBinder : Binder() {
        fun getService(): WebRTCService = this@WebRTCService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isInitialized() || !webSocketClient.isConnected()) {
                reconnect()
            }
        }
    }

    private val webSocketListener = object : WebSocketListener() {
        override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
            try {
                val message = JSONObject(text)
                handleWebSocketMessage(message)
            } catch (e: Exception) {
                Log.e("WebRTCService", "WebSocket message parse error", e)
            }
        }

        override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
            Log.d("WebRTCService", "WebSocket connected for room: $roomName")
            isConnected = true
            isConnecting = false
            reconnectAttempts = 0 // Сбрасываем счетчик попыток
            updateNotification("Connected to server")
            joinRoom()
        }

        override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            Log.d("WebRTCService", "WebSocket disconnected, code: $code, reason: $reason")
            isConnected = false
            if (code != 1000) { // Если это не нормальное закрытие
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
            Log.e("WebRTCService", "WebSocket error: ${t.message}")
            isConnected = false
            isConnecting = false
            updateNotification("Error: ${t.message?.take(30)}...")
            scheduleReconnect()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            handler.post { reconnect() }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            handler.post { updateNotification("Network lost") }
        }
    }

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            if (!isServiceActive()) {
                reconnect()
            }
            handler.postDelayed(this, 30000) // Проверка каждые 30 секунд
        }
    }

    // Добавляем в класс WebRTCService
    private val bandwidthEstimationRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                adjustVideoQualityBasedOnStats()
            }
            handler.postDelayed(this, 8000) // Каждые 8 секунд
        }
    }

    private fun getNetworkType(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Для Android 6.0+ используем новый API
            val network = cm.activeNetwork ?: return "unknown"
            val caps = cm.getNetworkCapabilities(network) ?: return "unknown"

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "unknown"
            }
        } else {
            // Для старых версий Android используем старый API
            @Suppress("DEPRECATION")
            when (cm.activeNetworkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "mobile"
                else -> "unknown"
            }
        }
    }

    private fun adjustVideoQualityBasedOnStats() {
        val networkType = getNetworkType()
        val isMobile = networkType == "mobile"

        webRTCClient.peerConnection?.getStats { statsReport ->
            try {
                var videoPacketsLost = 0L
                var videoPacketsSent = 0L
                var availableSendBandwidth = 0L
                var currentBitrate = 0L

                statsReport.statsMap.values.forEach { stats ->
                    when {
                        stats.type == "outbound-rtp" && stats.id.contains("video") -> {
                            videoPacketsLost += stats.members["packetsLost"] as? Long ?: 0L
                            videoPacketsSent += stats.members["packetsSent"] as? Long ?: 1L
                            currentBitrate = (stats.members["bytesSent"] as? Long ?: 0L) * 8 / 1000 // kbps
                        }
                        stats.type == "candidate-pair" && stats.members["state"] == "succeeded" -> {
                            availableSendBandwidth = (stats.members["availableOutgoingBitrate"] as? Long ?: 0L) / 1000 // kbps
                        }
                    }
                }

                if (videoPacketsSent > 0) {
                    val lossRate = videoPacketsLost.toDouble() / videoPacketsSent.toDouble()
                    handler.post {
                        when {
                            // Более агрессивные пороги для мобильных сетей
                            isMobile && (lossRate > 0.05 || currentBitrate > (availableSendBandwidth * 0.8)) ->
                                reduceVideoQuality(true, availableSendBandwidth)

                            isMobile && lossRate < 0.02 && availableSendBandwidth > 500 ->
                                increaseVideoQuality(true, availableSendBandwidth)

                            // Более либеральные пороги для WiFi
                            !isMobile && (lossRate > 0.05 || currentBitrate > (availableSendBandwidth * 0.8)) ->
                                reduceVideoQuality(false, availableSendBandwidth)

                            !isMobile && lossRate < 0.02 && availableSendBandwidth > 500 ->
                                increaseVideoQuality(false, availableSendBandwidth)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WebRTCService", "Error processing stats", e)
            }
        }
    }
    private fun reduceVideoQuality(isMobile: Boolean, availableBandwidth: Long) {
        try {
            webRTCClient.videoCapturer?.let { capturer ->
                val targetBitrate = if (isMobile) {
                    // Для мобильных сетей используем более низкие значения
                    (availableBandwidth * 0.5).toInt().coerceAtMost(200) // Макс 200 кбит/с
                } else {
                    // Для WiFi можно оставить больше
                    (availableBandwidth * 0.5).toInt().coerceAtMost(200) // Макс 300 кбит/с
                }

                capturer.stopCapture()

                // Устанавливаем разрешение в зависимости от типа сети
                if (isMobile) {
                    capturer.startCapture(480, 360, 12) // Низкое разрешение для мобильных сетей
                } else {
                    capturer.startCapture(480, 360, 12) // Среднее разрешение для WiFi
                }

                webRTCClient.setVideoEncoderBitrate(
                    50000, 100000, 150000
//                    (targetBitrate * 0.7).toInt(), // min
//                    targetBitrate,                 // current
//                    (targetBitrate * 1.3).toInt()  // max
                )

                Log.d("WebRTCService", "Reduced video quality to ${targetBitrate}kbps (${if(isMobile) "mobile" else "wifi"})")
            }
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error reducing video quality", e)
        }
    }

    private fun increaseVideoQuality(isMobile: Boolean, availableBandwidth: Long) {
        try {
            webRTCClient.videoCapturer?.let { capturer ->
                val targetBitrate = if (isMobile) {
                    // Для мобильных сетей ограничиваем максимальный битрейт
                    (availableBandwidth * 0.6).toInt().coerceAtMost(350) // Макс 350 кбит/с
                } else {
                    // Для WiFi можно больше
                    (availableBandwidth * 0.7).toInt().coerceAtMost(350) // Макс 500 кбит/с
                }

                capturer.stopCapture()

                // Устанавливаем разрешение в зависимости от типа сети
                if (isMobile) {
                    capturer.startCapture(640, 480, 15) // Среднее разрешение для мобильных сетей
                } else {
                    capturer.startCapture(640, 480, 15) // Более высокое разрешение для WiFi
                }

                webRTCClient.setVideoEncoderBitrate(
                    50000, 100000, 150000
//                    (targetBitrate * 0.8).toInt(), // min
//                    targetBitrate,                 // current
//                    (targetBitrate * 1.5).toInt()  // max
                )

                Log.d("WebRTCService", "Increased video quality to ${targetBitrate}kbps (${if(isMobile) "mobile" else "wifi"})")
            }
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error increasing video quality", e)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // Инициализация имени комнаты из статического поля
        roomName = currentRoomName

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, WebRTCService::class.java).apply {
            action = "CHECK_CONNECTION"
        }
        val pendingIntent = PendingIntent.getService(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        handler.post(healthCheckRunnable)

        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            pendingIntent
        )

        Log.d("WebRTCService", "Service created with room: $roomName")
        sendServiceStateUpdate()
        handler.post(bandwidthEstimationRunnable)
        try {
            registerReceiver(connectivityReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            isConnectivityReceiverRegistered = true
            registerReceiver(stateReceiver, IntentFilter(ACTION_SERVICE_STATE))
            isStateReceiverRegistered = true
            createNotificationChannel()
            startForegroundService()
            initializeWebRTC()
            connectWebSocket()
            registerNetworkCallback() // Добавлен вызов регистрации коллбэка сети
        } catch (e: Exception) {
            Log.e("WebRTCService", "Initialization failed", e)
            stopSelf()
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(networkCallback)
        } else {
            val request = NetworkRequest.Builder().build()
            cm.registerNetworkCallback(request, networkCallback)
        }
    }

    private fun isServiceActive(): Boolean {
        return ::webSocketClient.isInitialized && webSocketClient.isConnected()
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
                Log.e("WebRTCService", "SecurityException: ${e.message}")
                startForeground(notificationId, notification)
            }
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun initializeWebRTC(preferredCodec: String = "H264") {
        Log.d("WebRTCService", "Initializing new WebRTC connection with codec: $preferredCodec")

        // Очистка предыдущих ресурсов
        cleanupWebRTCResources()

        // Создание нового EglBase
        eglBase = EglBase.create()

        // Инициализация SurfaceViewRenderer
        val localView = SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
            setMirror(true)
        }

        remoteView = SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
        }

        // Инициализация WebRTCClient
        webRTCClient = WebRTCClient(
            context = this,
            eglBase = eglBase,
            localView = localView,
            remoteView = remoteView,
            observer = createPeerConnectionObserver(),
            preferredCodec = preferredCodec
        )

        // Установка начального битрейта
        webRTCClient.setVideoEncoderBitrate(50000, 100000, 150000)
    }


    private fun createPeerConnectionObserver() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let {
                Log.d("WebRTCService", "Local ICE candidate: ${it.sdpMid}:${it.sdpMLineIndex} ${it.sdp}")
                sendIceCandidate(it)
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d("WebRTCService", "ICE connection state: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED ->
                    updateNotification("Connection established")
                PeerConnection.IceConnectionState.DISCONNECTED ->
                    updateNotification("Connection lost")
                else -> {}
            }
        }

        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {
            stream?.videoTracks?.forEach { track ->
                Log.d("WebRTCService", "Adding remote video track from stream")
                track.addSink(remoteView)
            }
        }
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        override fun onTrack(transceiver: RtpTransceiver?) {
            transceiver?.receiver?.track()?.let { track ->
                handler.post {
                    when (track.kind()) {
                        "video" -> {
                            Log.d("WebRTCService", "Video track received")
                            (track as VideoTrack).addSink(remoteView)
                        }
                        "audio" -> {
                            Log.d("WebRTCService", "Audio track received")
                        }
                    }
                }
            }
        }
    }

    fun cleanupWebRTCResources() {
        try {
            if (::webRTCClient.isInitialized) {
                // Закрываем и очищаем PeerConnection
                webRTCClient.peerConnection?.let { pc ->
                    pc.close()
                    pc.dispose()
                    webRTCClient.peerConnection = null
                }
                // Предполагаем, что webRTCClient управляет другими ресурсами (EglBase, VideoCapturer, etc.)
                // Если есть метод cleanup в webRTCClient, вызываем его
                // webRTCClient.cleanup() // Раскомментируй, если такой метод существует
                Log.d("WebRTCService", "WebRTC resources cleaned up")
            } else {
                Log.w("WebRTCService", "webRTCClient not initialized, skipping cleanup")
            }
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error cleaning WebRTC resources", e)
        }
    }

    private fun connectWebSocket() {
        if (isConnected || isConnecting) {
            Log.d("WebRTCService", "Already connected or connecting, skipping")
            return
        }

        isConnecting = true
        webSocketClient = WebSocketClient(webSocketListener)
        webSocketClient.connect(webSocketUrl)
    }

    private fun scheduleReconnect() {
        if (isUserStopped) {
            Log.d("WebRTCService", "Service stopped by user, not reconnecting")
            return
        }

        handler.removeCallbacksAndMessages(null)

        reconnectAttempts++
        val delay = when {
            reconnectAttempts < 5 -> 5000L
            reconnectAttempts < 10 -> 15000L
            else -> 60000L
        }

        Log.d("WebRTCService", "Scheduling reconnect in ${delay/1000} seconds (attempt $reconnectAttempts)")
        updateNotification("Reconnecting in ${delay/1000}s...")

        handler.postDelayed({
            if (!isConnected && !isConnecting) {
                Log.d("WebRTCService", "Executing reconnect attempt $reconnectAttempts")
                reconnect()
            } else {
                Log.d("WebRTCService", "Already connected or connecting, skipping scheduled reconnect")
            }
        }, delay)
    }

    private fun reconnect() {
        if (isConnected || isConnecting) {
            Log.d("WebRTCService", "Already connected or connecting, skipping manual reconnect")
            return
        }

        handler.post {
            try {
                Log.d("WebRTCService", "Starting reconnect process")

                // Получаем последнее сохраненное имя комнаты
                val sharedPrefs = getSharedPreferences("WebRTCPrefs", Context.MODE_PRIVATE)
                val lastRoomName = sharedPrefs.getString("last_used_room", "")

                // Если имя комнаты пустое, используем дефолтное значение
                roomName = if (lastRoomName.isNullOrEmpty()) {
                    "default_room_${System.currentTimeMillis()}"
                } else {
                    lastRoomName
                }

                // Обновляем текущее имя комнаты
                currentRoomName = roomName
                Log.d("WebRTCService", "Reconnecting to room: $roomName")

                // Очищаем предыдущие соединения
                if (::webSocketClient.isInitialized) {
                    webSocketClient.disconnect()
                }

                // Инициализируем заново
                initializeWebRTC()
                connectWebSocket()

            } catch (e: Exception) {
                Log.e("WebRTCService", "Reconnection error", e)
                isConnecting = false
                scheduleReconnect()
            }
        }
    }

    private fun joinRoom() {
        try {
            val message = JSONObject().apply {
                put("action", "join")
                put("room", roomName)
                put("username", userName)
                put("isLeader", true)
            }
            webSocketClient.send(message.toString())
            Log.d("WebRTCService", "Sent join request for room: $roomName")
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error joining room: $roomName", e)
        }
    }

    private fun handleBandwidthEstimation(estimation: Long) {
        handler.post {
            try {
                // Адаптируем качество видео в зависимости от доступной полосы
                val width = when {
                    estimation > 1500000 -> 1280 // 1.5 Mbps+
                    estimation > 500000 -> 854  // 0.5-1.5 Mbps
                    else -> 640                // <0.5 Mbps
                }

                val height = (width * 9 / 16)

                webRTCClient.videoCapturer?.let { capturer ->
                    capturer.stopCapture()
                    capturer.startCapture(width, height, 24)
                    Log.d("WebRTCService", "Adjusted video to ${width}x${height} @24fps")
                }
            } catch (e: Exception) {
                Log.e("WebRTCService", "Error adjusting video quality", e)
            }
        }
    }

    private fun handleWebSocketMessage(message: JSONObject) {
        Log.d("WebRTCService", "Received WebSocket message: $message")

        try {
            val isLeader = message.optBoolean("isLeader", false)

            when (message.optString("type")) {
                "rejoin_and_offer" -> {
                    val preferredCodec = message.optString("preferredCodec", "VP8")
                    Log.d("WebRTCService", "Received rejoin_and_offer with codec: $preferredCodec")
                    handler.post {
                        try {
                            Log.d("WebRTCService", "Cleaning resources for rejoin")
                            cleanupWebRTCResources()
                            // Добавляем задержку для освобождения камеры
                            Thread.sleep(2000) // Увеличено с 1000 до 2000 мс
                            if (!isConnected) {
                                Log.w("WebRTCService", "WebSocket not connected, aborting rejoin")
                                connectWebSocket()
                                return@post
                            }
                            Log.d("WebRTCService", "Reinitializing WebRTC with codec: $preferredCodec")
                            initializeWebRTC(preferredCodec)
                            // Проверяем доступность камеры
                            if (!isCameraAvailable()) {
                                Log.e("WebRTCService", "Camera not available after rejoin")
                                return@post
                            }
                            Log.d("WebRTCService", "Creating new offer with codec: $preferredCodec")
                            createOffer(preferredCodec)
                        } catch (e: Exception) {
                            Log.e("WebRTCService", "Error processing rejoin_and_offer", e)
                            scheduleReconnect()
                        }
                    }
                }
                "create_offer_for_new_follower" -> {
                    val preferredCodec = message.optString("preferredCodec", "H264")
                    Log.d("WebRTCService", "Creating offer for new follower with codec: $preferredCodec")
                    handler.post {
                        createOffer(preferredCodec)
                    }
                }
                "bandwidth_estimation" -> {
                    val estimation = message.optLong("estimation", 1000000)
                    handleBandwidthEstimation(estimation)
                }
                "offer" -> {
                    if (!isLeader) {
                        Log.w("WebRTCService", "Received offer from non-leader, ignoring")
                        return
                    }
                    handleOffer(message)
                }
                "answer" -> handleAnswer(message)
                "ice_candidate" -> handleIceCandidate(message)
                "room_info" -> Log.d("WebRTCService", "Received room_info: $message")
                "switch_camera" -> {
                    // Обрабатываем команду переключения камеры
                    val useBackCamera = message.optBoolean("useBackCamera", false)
                    Log.d("WebRTCService", "Received switch camera command: useBackCamera=$useBackCamera")
                    handler.post {
                        webRTCClient.switchCamera(useBackCamera)
                        // Отправляем подтверждение
                        sendCameraSwitchAck(useBackCamera)
                    }
                }
                else -> Log.w("WebRTCService", "Unknown message type: ${message.optString("type")}")
            }
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error handling message", e)
        }
    }

    private fun isCameraAvailable(): Boolean {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraIds = cameraManager.cameraIdList
            return cameraIds.isNotEmpty()
        } catch (e: CameraAccessException) {
            Log.e("WebRTCService", "Camera access error", e)
            return false
        }
    }

    // Вспомогательная функция для модификации SDP на Android
    private fun forceCodecAndNormalizeSdp(sdp: String, preferredCodec: String, bitrateKbps: Int): String {
        Log.d("WebRTCService", "Normalizing SDP with preferredCodec: $preferredCodec")
        val sdpLines = sdp.split("\r\n").toMutableList()
        var videoSection = false
        val videoPayloads = mutableListOf<String>()
        var vp8PayloadType: String? = null
        var rtxPayloadType: String? = null
        var redPayloadType: String? = null
        var ulpfecPayloadType: String? = null

        // Парсим SDP
        for (i in sdpLines.indices) {
            val line = sdpLines[i]
            if (line.startsWith("m=video")) {
                videoSection = true
                val parts = line.split(" ")
                if (parts.size > 3) {
                    videoPayloads.addAll(parts.subList(3, parts.size))
                }
            } else if (videoSection && line.startsWith("m=")) {
                videoSection = false
            }
            if (videoSection) {
                if (line.matches(Regex("a=rtpmap:(\\d+) VP8/90000"))) {
                    vp8PayloadType = line.split(":")[1].split(" ")[0]
                } else if (line.matches(Regex("a=rtpmap:(\\d+) rtx/90000"))) {
                    rtxPayloadType = line.split(":")[1].split(" ")[0]
                } else if (line.matches(Regex("a=rtpmap:(\\d+) red/90000"))) {
                    redPayloadType = line.split(":")[1].split(" ")[0]
                } else if (line.matches(Regex("a=rtpmap:(\\d+) ulpfec/90000"))) {
                    ulpfecPayloadType = line.split(":")[1].split(" ")[0]
                }
            }
        }

        if (vp8PayloadType == null) {
            Log.e("WebRTCService", "VP8 codec not found in SDP")
            return sdp // Возвращаем исходный SDP
        }

        Log.d("WebRTCService", "Found VP8 payload type: $vp8PayloadType, RTX payload type: $rtxPayloadType")

        // Формируем новый список payload types
        val newVideoPayloads = mutableListOf<String>()
        vp8PayloadType?.let { newVideoPayloads.add(it) }
        rtxPayloadType?.let { newVideoPayloads.add(it) }
        redPayloadType?.let { newVideoPayloads.add(it) }
        ulpfecPayloadType?.let { newVideoPayloads.add(it) }

        // Обновляем m=video
        for (i in sdpLines.indices) {
            if (sdpLines[i].startsWith("m=video")) {
                sdpLines[i] = "m=video 9 UDP/TLS/RTP/SAVPF ${newVideoPayloads.joinToString(" ")}"
                break
            }
        }

        // Устанавливаем направление и битрейт
        for (i in sdpLines.indices) {
            if (sdpLines[i].startsWith("m=video")) {
                // Удаляем старые направления и битрейт
                var j = i + 1
                while (j < sdpLines.size && (sdpLines[j].startsWith("a=sendrecv") || sdpLines[j].startsWith("a=sendonly") || sdpLines[j].startsWith("a=recvonly") || sdpLines[j].startsWith("b=AS"))) {
                    sdpLines.removeAt(j)
                }
                // Добавляем новые
                sdpLines.add(i + 1, "b=AS:$bitrateKbps")
                sdpLines.add(i + 1, "a=sendonly")
                break
            }
        }

        val normalizedSdp = sdpLines.joinToString("\r\n")
        Log.d("WebRTCService", "Normalized SDP:\n$normalizedSdp")
        return normalizedSdp
    }

    // Модифицируем createOffer для принудительного создания нового оффера
    private fun createOffer(preferredCodec: String = "VP8") {

        try {
            if (!::webRTCClient.isInitialized || !isConnected) {
                Log.w("WebRTCService", "Cannot create offer - not initialized or connected")
                return
            }

            Log.d("WebRTCService", "Creating offer with codec: $preferredCodec")

            // Проверяем наличие PeerConnection
            if (webRTCClient.peerConnection == null) {
                Log.e("WebRTCService", "PeerConnection is null, cannot create offer")
                return
            }

            // Настраиваем кодеки для видеотрансляторов
            webRTCClient.peerConnection?.transceivers?.filter {
                it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && it.sender != null
            }?.forEach { transceiver ->
                try {
                    val sender = transceiver.sender
                    val parameters = sender.parameters
                    if (parameters != null) {
                        val targetCodecs = parameters.codecs.filter { codecInfo ->
                            codecInfo.name.equals(preferredCodec, ignoreCase = true)
                        }
                        if (targetCodecs.isNotEmpty()) {
                            parameters.codecs = ArrayList(targetCodecs)
                            val result = sender.setParameters(parameters)
                            if (result) {
                                Log.d("WebRTCService", "Set $preferredCodec as preferred codec for video sender")
                            } else {
                                Log.w("WebRTCService", "Failed to set $preferredCodec for video sender")
                            }
                        } else {
                            Log.w("WebRTCService", "$preferredCodec codec not found in sender parameters")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WebRTCService", "Error setting codec preferences", e)
                }
            }

            // Создаём медиа-констрейнты
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googCpuOveruseDetection", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googScreencastMinBitrate", "300"))
            }

            // Создаём оффер
            webRTCClient.peerConnection?.createOffer(object : SdpObserver {

                override fun onCreateSuccess(desc: SessionDescription) {
                    Log.d("WebRTCService", "Original Offer SDP:\n${desc.description}")

                    // Нормализуем SDP
                    val modifiedSdp = forceCodecAndNormalizeSdp(desc.description, preferredCodec, 300)
                    if (modifiedSdp.isBlank()) {
                        Log.e("WebRTCService", "Failed to normalize SDP, cannot set local description")
                        return
                    }

                    Log.d("WebRTCService", "Modified Offer SDP:\n$modifiedSdp")
                    val modifiedDesc = SessionDescription(desc.type, modifiedSdp)

                    // Устанавливаем локальное описание
                    webRTCClient.peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d("WebRTCService", "Set local description successfully")
                            sendSessionDescription(modifiedDesc)
                        }

                        override fun onSetFailure(error: String) {
                            Log.e("WebRTCService", "Error setting local description: $error")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    }, modifiedDesc)
                }

                override fun onCreateFailure(error: String) {
                    Log.e("WebRTCService", "Error creating offer: $error")
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, constraints)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error in createOffer", e)
        }
    }

    // Метод для отправки подтверждения переключения камеры
    private fun sendCameraSwitchAck(useBackCamera: Boolean) {
        try {
            val message = JSONObject().apply {
                put("type", "switch_camera_ack")
                put("useBackCamera", useBackCamera)
                put("success", true)
                put("room", roomName)
                put("username", userName)
            }
            webSocketClient.send(message.toString())
            Log.d("WebRTCService", "Sent camera switch ack")
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error sending camera switch ack", e)
        }
    }

    private fun handleOffer(offer: JSONObject) {
        try {
            val sdp = offer.getJSONObject("sdp")
            val sessionDescription = SessionDescription(
                SessionDescription.Type.OFFER,
                sdp.getString("sdp")
            )

            webRTCClient.peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                    }
                    createAnswer(constraints)
                }

                override fun onSetFailure(error: String) {
                    Log.e("WebRTCService", "Error setting remote description: $error")
                }

                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(error: String) {}
            }, sessionDescription)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error handling offer", e)
        }
    }

    private fun createAnswer(constraints: MediaConstraints) {
        try {
            webRTCClient.peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    Log.d("WebRTCService", "Created answer: ${desc.description}")
                    webRTCClient.peerConnection!!.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            sendSessionDescription(desc)
                        }

                        override fun onSetFailure(error: String) {
                            Log.e("WebRTCService", "Error setting local description: $error")
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(error: String) {}
                    }, desc)
                }

                override fun onCreateFailure(error: String) {
                    Log.e("WebRTCService", "Error creating answer: $error")
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            }, constraints)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error creating answer", e)
        }
    }

    private fun sendSessionDescription(desc: SessionDescription) {
        Log.d("WebRTCService", "Sending SDP: ${desc.type} \n${desc.description}")
        try {
            val message = JSONObject().apply {
                put("type", desc.type.canonicalForm())
                put("sdp", JSONObject().apply {
                    put("type", desc.type.canonicalForm())
                    put("sdp", desc.description)
                })
                put("room", roomName)
                put("username", userName)
                put("target", "browser")
            }
            webSocketClient.send(message.toString())
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

            webRTCClient.peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d("WebRTCService", "Answer accepted, connection should be established")
                }

                override fun onSetFailure(error: String) {
                    Log.e("WebRTCService", "Error setting answer: $error")
                    // При ошибке запрашиваем новый оффер
                    handler.postDelayed({ createOffer() }, 2000)
                }

                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(error: String) {}
            }, sessionDescription)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error handling answer", e)
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
            webRTCClient.peerConnection?.addIceCandidate(iceCandidate)
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
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC Service")
            .setContentText("Active in room: $roomName")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Измените на HIGH
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
        if (!isUserStopped) {
            if (isConnectivityReceiverRegistered) {
                unregisterReceiver(connectivityReceiver)
            }
            if (isStateReceiverRegistered) {
                unregisterReceiver(stateReceiver)
            }
            // Автоматический перезапуск только если не было явной остановки
            scheduleRestartWithWorkManager()
        }
        super.onDestroy()
    }

    private fun cleanupAllResources() {
        handler.removeCallbacksAndMessages(null)
        cleanupWebRTCResources()
        if (::webSocketClient.isInitialized) {
            webSocketClient.disconnect()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                isUserStopped = true
                isConnected = false
                isConnecting = false
                stopEverything()
                return START_NOT_STICKY
            }
            else -> {
                isUserStopped = false

                // Получаем последнее сохраненное имя комнаты
                val sharedPrefs = getSharedPreferences("WebRTCPrefs", Context.MODE_PRIVATE)
                val lastRoomName = sharedPrefs.getString("last_used_room", "")

                roomName = if (lastRoomName.isNullOrEmpty()) {
                    "default_room_${System.currentTimeMillis()}"
                } else {
                    lastRoomName
                }

                currentRoomName = roomName

                Log.d("WebRTCService", "Starting service with room: $roomName")

                if (!isConnected && !isConnecting) {
                    initializeWebRTC()
                    connectWebSocket()
                }

                isRunning = true
                return START_STICKY
            }
        }
    }

    private fun stopEverything() {
        isRunning = false
        isConnected = false
        isConnecting = false

        try {
            handler.removeCallbacksAndMessages(null)
            unregisterReceiver(connectivityReceiver)
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error during cleanup", e)
        }

        cleanupAllResources()

        if (isUserStopped) {
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun scheduleRestartWithWorkManager() {
        // Убедитесь, что используете ApplicationContext
        val workRequest = OneTimeWorkRequestBuilder<WebRTCWorker>()
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED) // Только при наличии сети
                    .build()
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "WebRTCServiceRestart",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun isInitialized(): Boolean {
        return ::webSocketClient.isInitialized &&
                ::webRTCClient.isInitialized &&
                ::eglBase.isInitialized
    }
}