//package com.example.mytest
//
//
//class WebRTCService : Service() {
//    private val binder = LocalBinder()
//    private var webSocket: WebSocket? = null
//    private var peerConnection: PeerConnection? = null
//    private val handler = Handler(Looper.getMainLooper())
//    private var reconnectAttempts = 0
//    private val maxReconnectAttempts = 5
//    private val reconnectDelay = 5000L // 5 сек
//
//    // Настройки WebRTC
//    private val iceServers = listOf(
//        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
//    )
//
//    // Пинг каждые 30 сек
//    private val pingInterval = 30000L
//    private val pingRunnable = object : Runnable {
//        override fun run() {
//            sendPing()
//            handler.postDelayed(this, pingInterval)
//        }
//    }
//
//    inner class LocalBinder : Binder() {
//        fun getService(): WebRTCService = this@WebRTCService
//    }
//
//    override fun onBind(intent: Intent?): IBinder = binder
//
//    override fun onCreate() {
//        super.onCreate()
//        initWebRTC()
//        connectWebSocket()
//        startPing()
//    }
//
//    private fun initWebRTC() {
//        val config = PeerConnection.RTCConfiguration(iceServers)
//        peerConnection = PeerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
//            override fun onIceConnectionChange(state: IceConnectionState?) {
//                when (state) {
//                    IceConnectionState.DISCONNECTED, IceConnectionState.FAILED -> {
//                        Log.d("WebRTCService", "Соединение потеряно, переподключаемся...")
//                        reconnect()
//                    }
//                    else -> Unit
//                }
//            }
//            // ... другие методы
//        })
//    }
//
//    private fun connectWebSocket() {
//        val client = OkHttpClient.Builder()
//            .pingInterval(pingInterval, TimeUnit.MILLISECONDS)
//            .build()
//
//        val request = Request.Builder()
//            .url("ws://ваш-сервер:8080/ws")
//            .build()
//
//        client.newWebSocket(request, object : WebSocketListener() {
//            override fun onOpen(webSocket: WebSocket, response: Response) {
//                this@WebRTCService.webSocket = webSocket
//                reconnectAttempts = 0
//                Log.d("WebRTCService", "WebSocket подключен")
//
//                // Отправляем данные для инициализации
//                val initData = JSONObject().apply {
//                    put("room", "default_room")
//                    put("username", "android_client_${Random.nextInt(1000)}")
//                }
//                webSocket.send(initData.toString())
//            }
//
//            override fun onMessage(webSocket: WebSocket, text: String) {
//                if (text == "pong") {
//                    Log.d("WebRTCService", "Получен pong от сервера")
//                    return
//                }
//
//                // Обработка WebRTC сообщений
//                processWebRTCMessage(text)
//            }
//
//            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
//                Log.d("WebRTCService", "WebSocket закрыт: $reason")
//                reconnect()
//            }
//
//            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
//                Log.e("WebRTCService", "Ошибка WebSocket", t)
//                reconnect()
//            }
//        })
//    }
//
//    private fun sendPing() {
//        webSocket?.send("ping") ?: run {
//            Log.d("WebRTCService", "WebSocket недоступен, переподключаемся...")
//            reconnect()
//        }
//    }
//
//    private fun startPing() {
//        handler.postDelayed(pingRunnable, pingInterval)
//    }
//
//    private fun stopPing() {
//        handler.removeCallbacks(pingRunnable)
//    }
//
//    private fun reconnect() {
//        if (reconnectAttempts >= maxReconnectAttempts) {
//            Log.w("WebRTCService", "Достигнуто максимальное число попыток")
//            return
//        }
//
//        reconnectAttempts++
//        Log.d("WebRTCService", "Попытка переподключения ($reconnectAttempts/$maxReconnectAttempts)")
//
//        stopPing()
//        webSocket?.close(1000, "Переподключение")
//        webSocket = null
//
//        handler.postDelayed({
//            connectWebSocket()
//            startPing()
//        }, reconnectDelay)
//    }
//
//    private fun processWebRTCMessage(message: String) {
//        // Обработка сообщений WebRTC
//    }
//
//    override fun onDestroy() {
//        stopPing()
//        webSocket?.close(1000, "Сервис остановлен")
//        peerConnection?.close()
//        super.onDestroy()
//    }
//}