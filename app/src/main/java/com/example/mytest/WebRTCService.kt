// WebRTCService.kt
package com.example.mytest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.webrtc.*

class WebRTCService : Service() {
    private var webRTCClient: WebRTCClient? = null
    private var webSocketClient: WebSocketClient? = null
    private var eglBase: EglBase? = null

    // Конфигурация сервера
    private val websocketUrl = "wss://anybet.site/ws"
    private val roomName = "service_room"
    private val username = "service_user_${(1000..9999).random()}"

    // Флаги состояния
    private var isConnected = false
    private var isCallActive = false

    // Интервал переподключения (5 секунд)
    private val reconnectInterval = 5000L

    // Обработчик для переподключения
    private val reconnectHandler = android.os.Handler()
    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (!isConnected) {
                connectToServer()
            }
            reconnectHandler.postDelayed(this, reconnectInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())

        // Инициализация WebRTC
        initializeWebRTC()

        // Запуск переподключения
        reconnectHandler.post(reconnectRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "webrtc_channel",
                "WebRTC Connection",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "webrtc_channel")
            .setContentTitle("WebRTC Service")
            .setContentText("Waiting for connection...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun initializeWebRTC() {
        eglBase = EglBase.create()

        // Мы не используем SurfaceViewRenderer в сервисе, так как нет UI
        webRTCClient = WebRTCClient(
            context = this,
            eglBase = eglBase!!,
            localView = object : SurfaceViewRenderer(this) {}, // Заглушка
            remoteView = object : SurfaceViewRenderer(this) {}, // Заглушка
            observer = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { sendIceCandidate(it) }
                }
                override fun onAddStream(stream: MediaStream?) {}
                override fun onTrack(transceiver: RtpTransceiver?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        isCallActive = false
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )
    }

    private fun connectToServer() {
        if (webSocketClient == null) {
            webSocketClient = WebSocketClient(object : WebSocketListener {
                override fun onMessage(message: JSONObject) = handleWebSocketMessage(message)
                override fun onConnected() {
                    isConnected = true
                    joinRoom()
                    Log.d("WebRTCService", "Connected to server")
                }
                override fun onDisconnected() {
                    isConnected = false
                    isCallActive = false
                    Log.d("WebRTCService", "Disconnected from server")
                }
                override fun onError(error: String) {
                    Log.e("WebRTCService", "Error: $error")
                }
            })
        }

        try {
            webSocketClient?.connect(websocketUrl)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Connection error: ${e.message}")
        }
    }

    private fun joinRoom() {
        val joinMessage = JSONObject().apply {
            put("action", "join")
            put("room", roomName)
            put("username", username)
        }
        webSocketClient?.sendRaw(joinMessage.toString())
    }

    private fun handleWebSocketMessage(message: JSONObject) {
        Log.d("WebRTCService", "Received: $message")

        when {
            message.has("type") -> when (message.getString("type")) {
                "offer" -> handleOffer(message)
                "ice_candidate" -> handleIceCandidate(message)
                "hangup" -> {
                    isCallActive = false
                    Log.d("WebRTCService", "Call ended by remote peer")
                }
            }
        }
    }

    private fun handleOffer(message: JSONObject) {
        webRTCClient?.let { client ->
            val sdp = message.getJSONObject("sdp")
            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
                sdp.getString("sdp")
            )

            client.peerConnection.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    }

                    client.peerConnection.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            desc?.let {
                                client.peerConnection.setLocalDescription(object : SdpObserver {
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onSetSuccess() {}
                                    override fun onCreateFailure(p0: String?) {}
                                    override fun onSetFailure(p0: String?) {}
                                }, it)

                                val answerMessage = JSONObject().apply {
                                    put("type", "answer")
                                    put("sdp", JSONObject().apply {
                                        put("type", it.type.canonicalForm())
                                        put("sdp", it.description)
                                    })
                                    put("target", message.optString("from"))
                                }
                                webSocketClient?.sendRaw(answerMessage.toString())
                                isCallActive = true
                            }
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(error: String?) {
                            Log.e("WebRTCService", "Create answer error: $error")
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e("WebRTCService", "Set answer error: $error")
                        }
                    }, constraints)
                }
                override fun onCreateFailure(error: String?) {
                    Log.e("WebRTCService", "Create remote description error: $error")
                }
                override fun onSetFailure(error: String?) {
                    Log.e("WebRTCService", "Set remote description error: $error")
                }
            }, sessionDescription)
        }
    }

    private fun handleIceCandidate(message: JSONObject) {
        webRTCClient?.let { client ->
            val ice = message.getJSONObject("ice")
            val candidate = IceCandidate(
                ice.getString("sdpMid"),
                ice.getInt("sdpMLineIndex"),
                ice.getString("candidate")
            )
            client.peerConnection.addIceCandidate(candidate)
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val message = JSONObject().apply {
            put("type", "ice_candidate")
            put("ice", JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
            put("target", "any") // Отправляем любому участнику комнаты
        }
        webSocketClient?.sendRaw(message.toString())
    }

    override fun onDestroy() {
        super.onDestroy()
        // Очистка ресурсов
        reconnectHandler.removeCallbacks(reconnectRunnable)
        webSocketClient?.disconnect()
        webRTCClient?.close()
        eglBase?.release()
    }
}