// WebRTCService.kt
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

    private val roomName = "room1"
    private val userName = Build.MODEL ?: "AndroidDevice"
    private val webSocketUrl = "wss://anybet.site/ws"

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
        }

        remoteView = SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
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
                        PeerConnection.IceConnectionState.CONNECTED ->
                            updateNotification("Call connected")
                        PeerConnection.IceConnectionState.DISCONNECTED ->
                            updateNotification("Call disconnected")
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
            }

            override fun onError(error: String) {
                Log.e("WebRTCService", "WebSocket error: $error")
                updateNotification("Error: ${error.take(30)}...")
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
                Thread.sleep(1000)
                initializeWebRTC()
                connectWebSocket()
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

            webRTCClient.peerConnection.setRemoteDescription(object : SdpObserver {
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

        webRTCClient.peerConnection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                // Модифицируем SDP для лучшей совместимости
                val modifiedSdp = preferCodecs(desc.description)
                val modifiedDesc = SessionDescription(desc.type, modifiedSdp)

                webRTCClient.peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendSessionDescription(modifiedDesc)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e("WebRTCService", "Set local desc failed: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(error: String) {}
                }, modifiedDesc)
            }
            override fun onCreateFailure(error: String) {
                Log.e("WebRTCService", "Create answer failed: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    private fun preferCodecs(sdp: String): String {
        // Устанавливаем приоритет кодеков: VP8 > H264 > VP9
        return sdp.replaceFirst(
            "a=rtpmap:(\\d+) VP8/90000".toRegex(),
            "a=rtpmap:\$1 VP8/90000\r\na=fmtp:\$1 x-google-start-bitrate=800\r\na=rtcp-fb:\$1 goog-remb\r\na=rtcp-fb:\$1 transport-cc\r\na=rtcp-fb:\$1 ccm fir\r\na=rtcp-fb:\$1 nack\r\na=rtcp-fb:\$1 nack pli"
        )
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
        }
    }

    private fun handleAnswer(answer: JSONObject) {
        try {
            val sdp = answer.getJSONObject("sdp")
            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
                sdp.getString("sdp")
            )

            webRTCClient.peerConnection.setRemoteDescription(object : SdpObserver {
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