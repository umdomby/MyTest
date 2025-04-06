package com.example.mytest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.webrtc.*
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import org.webrtc.MediaConstraints

class MainActivity : ComponentActivity() {
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var webSocketClient: WebSocketClient
    private val eglBase = EglBase.create()

    private val localView by lazy {
        SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
            setMirror(true)
            setEnableHardwareScaler(true)
            setZOrderMediaOverlay(true)
        }
    }

    private val remoteView by lazy {
        SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setZOrderMediaOverlay(true)
        }
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            initializeWebRTC()
            setUI()
        } else {
            Toast.makeText(this, "All permissions are required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkAllPermissionsGranted()) {
            initializeWebRTC()
            setUI()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun setUI() {
        setContent {
            MyAppTheme {
                VideoCallUI()
            }
        }
    }


    @Composable
    fun VideoCallUI() {
        var username by remember { mutableStateOf("User${(1000..9999).random()}") }
        var room by remember { mutableStateOf("room1") }
        var isConnected by remember { mutableStateOf(false) }
        var isCallActive by remember { mutableStateOf(false) }
        val context = LocalContext.current

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // UI элементы управления
            ControlsSection(
                username = username,
                room = room,
                isConnected = isConnected,
                isCallActive = isCallActive,
                onUsernameChange = { username = it },
                onRoomChange = { room = it },
                onConnect = {
                    connectToRoom(username, room)
                    isConnected = true
                },
                onCall = {
                    startCall()
                    isCallActive = true
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Видео элементы
            VideoSection()
        }
    }

    @Composable
    private fun ControlsSection(
        username: String,
        room: String,
        isConnected: Boolean,
        isCallActive: Boolean,
        onUsernameChange: (String) -> Unit,
        onRoomChange: (String) -> Unit,
        onConnect: () -> Unit,
        onCall: () -> Unit
    ) {
        Column {
            TextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = room,
                onValueChange = onRoomChange,
                label = { Text("Room") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onConnect,
                enabled = !isConnected,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isConnected) "Connected" else "Connect")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onCall,
                enabled = isConnected && !isCallActive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isCallActive) "Call Active" else "Start Call")
            }
        }
    }

    @Composable
    private fun VideoSection() {
        Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
            // Удаленное видео (основной экран)
            AndroidView(
                factory = { remoteView },
                modifier = Modifier.fillMaxSize()
            )

            // Локальное видео (маленькое окошко)
            AndroidView(
                factory = { localView },
                modifier = Modifier
                    .size(120.dp)
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )
        }
    }
    private fun sendIceCandidate(candidate: IceCandidate) {
        val message = JSONObject().apply {
            put("type", "ice_candidate")
            put("ice", JSONObject().apply {
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("sdp", candidate.sdp)
            })
        }
        webSocketClient.send(message)
    }

    private fun connectToRoom(username: String, room: String) {
        webSocketClient.connect("wss://anybet.site/ws")
        val joinMessage = JSONObject().apply {
            put("type", "join")
            put("username", username)
            put("room", room)
        }
        webSocketClient.send(joinMessage)
    }

    private fun startCall() {
        webRTCClient.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    val offerMessage = JSONObject().apply {
                        put("type", "offer")
                        put("sdp", JSONObject().apply {
                            put("type", it.type.canonicalForm())
                            put("sdp", it.description)
                        })
                    }
                    webSocketClient.send(offerMessage)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e("WebRTC", "Failed to create offer: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("WebRTC", "Failed to set offer: $error")
            }
        })
    }

    private fun handleOffer(message: JSONObject) {
        val sdp = SessionDescription(
            SessionDescription.Type.OFFER,
            message.getJSONObject("sdp").getString("sdp")
        )
        webRTCClient.setRemoteDescription(sdp, object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                webRTCClient.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        desc?.let {
                            val answerMessage = JSONObject().apply {
                                put("type", "answer")
                                put("sdp", JSONObject().apply {
                                    put("type", it.type.canonicalForm())
                                    put("sdp", it.description)
                                })
                            }
                            webSocketClient.send(answerMessage)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {
                        Log.e("WebRTC", "Failed to create answer: $error")
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e("WebRTC", "Failed to set answer: $error")
                    }
                })
            }
            override fun onCreateFailure(error: String?) {
                Log.e("WebRTC", "Failed to create offer: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("WebRTC", "Failed to set offer: $error")
            }
        })
    }

    private fun handleAnswer(message: JSONObject) {
        val sdp = SessionDescription(
            SessionDescription.Type.ANSWER,
            message.getJSONObject("sdp").getString("sdp")
        )
        webRTCClient.setRemoteDescription(sdp, object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e("WebRTC", "Failed to create answer: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("WebRTC", "Failed to set answer: $error")
            }
        })
    }

    private fun handleIceCandidate(message: JSONObject) {
        val ice = message.getJSONObject("ice")
        val candidate = IceCandidate(
            ice.getString("sdpMid"),
            ice.getInt("sdpMLineIndex"),
            ice.getString("sdp")
        )
        webRTCClient.addIceCandidate(candidate)
    }



    private fun checkAllPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun initializeWebRTC() {
        webRTCClient = WebRTCClient(
            context = this,
            eglBase = eglBase,
            localView = localView,
            remoteView = remoteView,
            observer = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { sendIceCandidate(it) }
                }
                override fun onAddStream(stream: MediaStream?) {
                    stream?.videoTracks?.firstOrNull()?.addSink(remoteView)
                }
                override fun onTrack(transceiver: RtpTransceiver?) {
                    transceiver?.receiver?.track()?.let { track ->
                        if (track.kind() == "video") {
                            (track as VideoTrack).addSink(remoteView)
                        }
                    }
                }
                // Остальные обязательные методы Observer
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )

        webSocketClient = WebSocketClient(object : WebSocketListener {
            override fun onMessage(message: JSONObject) = handleWebSocketMessage(message)
            override fun onConnected() = showToast("WebSocket connected")
            override fun onDisconnected() = showToast("WebSocket disconnected")
            override fun onError(error: String) = showToast("Error: $error")
        })
    }

    private fun handleWebSocketMessage(message: JSONObject) {
        when (message.getString("type")) {
            "offer" -> handleOffer(message)
            "answer" -> handleAnswer(message)
            "ice_candidate" -> handleIceCandidate(message)
        }
    }

    private fun showToast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        webSocketClient.disconnect()
        webRTCClient.close()
        eglBase.release()
        localView.release()
        remoteView.release()
        super.onDestroy()
    }
}

@Composable
fun MyAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF6200EE),
            secondary = Color(0xFF03DAC6)
        ),
        content = content
    )
}