// file: src/main/java/com/example/mytest/WebRTCService.kt
package com.example.mytest

import android.app.Service
import android.content.Intent
import android.os.*
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.util.*
import java.util.concurrent.TimeUnit

class WebRTCService : Service() {
    private val binder = LocalBinder()
    private var webSocket: WebSocket? = null
    private var peerConnection: PeerConnection? = null
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectDelay = 5000L // 5 sec

    // WebRTC components
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var eglBase: EglBase

    // WebRTC settings
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    // Ping every 30 sec
    private val pingInterval = 30000L
    private val pingRunnable = object : Runnable {
        override fun run() {
            sendPing()
            handler.postDelayed(this, pingInterval)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): WebRTCService = this@WebRTCService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        initializeWebRTC()
        connectWebSocket()
        startPing()
    }

    private fun initializeWebRTC() {
        eglBase = EglBase.create()

        val options = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { sendIceCandidate(it) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        Log.d("WebRTCService", "Connection lost, reconnecting...")
                        reconnect()
                    }
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
        })
    }

    private fun connectWebSocket() {
        val client = OkHttpClient.Builder()
            .pingInterval(pingInterval, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("wss://your-server.com/ws")  // Replace with your actual server URL
            .build()

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@WebRTCService.webSocket = webSocket
                reconnectAttempts = 0
                Log.d("WebRTCService", "WebSocket connected")

                // Send initialization data
                val initData = JSONObject().apply {
                    put("type", "join")
                    put("room", "default_room")
                    put("username", "android_client_${Random().nextInt(1000)}")
                }
                webSocket.send(initData.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pong") {
                    Log.d("WebRTCService", "Received pong from server")
                    return
                }

                processWebRTCMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WebRTCService", "WebSocket closed: $reason")
                reconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebRTCService", "WebSocket error", t)
                reconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        })
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val message = JSONObject().apply {
            put("type", "ice_candidate")
            put("candidate", JSONObject().apply {
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            })
        }
        webSocket?.send(message.toString())
    }

    private fun sendPing() {
        webSocket?.send("ping") ?: run {
            Log.d("WebRTCService", "WebSocket not available, reconnecting...")
            reconnect()
        }
    }

    private fun startPing() {
        handler.postDelayed(pingRunnable, pingInterval)
    }

    private fun stopPing() {
        handler.removeCallbacks(pingRunnable)
    }

    private fun reconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.w("WebRTCService", "Reached maximum reconnection attempts")
            return
        }

        reconnectAttempts++
        Log.d("WebRTCService", "Reconnection attempt ($reconnectAttempts/$maxReconnectAttempts)")

        stopPing()
        webSocket?.close(1000, "Reconnecting")
        webSocket = null

        handler.postDelayed({
            connectWebSocket()
            startPing()
        }, reconnectDelay)
    }

    private fun processWebRTCMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.getString("type")) {
                "offer" -> handleOffer(json)
                "answer" -> handleAnswer(json)
                "ice_candidate" -> handleRemoteIceCandidate(json)
                // Add other message types as needed
            }
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error processing message", e)
        }
    }

    private fun handleOffer(offer: JSONObject) {
        // Implement offer handling
    }

    private fun handleAnswer(answer: JSONObject) {
        // Implement answer handling
    }

    private fun handleRemoteIceCandidate(candidate: JSONObject) {
        // Implement ICE candidate handling
    }

    override fun onDestroy() {
        stopPing()
        webSocket?.close(1000, "Service stopped")
        peerConnection?.close()
        peerConnectionFactory.dispose()
        eglBase.release()
        super.onDestroy()
    }
}