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


class MainActivity : ComponentActivity() {
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var webSocketClient: WebSocketClient
    private var remoteVideoView: SurfaceViewRenderer? = null
    private val eglBase = EglBase.create()

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS
    )



    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            initializeWebRTC()
            setUI()
        } else {
            Toast.makeText(
                this,
                "Для работы приложения необходимы все разрешения",
                Toast.LENGTH_LONG
            ).show()
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
            mytestTheme {
                mytestUI()
            }
        }
    }

    @Composable
    fun mytestUI() {
        var username by remember { mutableStateOf("User${(1000..9999).random()}") }
        var room by remember { mutableStateOf("room1") }
        var isConnected by remember { mutableStateOf(false) }
        var isCallActive by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var connectionState by remember { mutableStateOf("Initializing...") }
        val context = LocalContext.current

        if (!::webRTCClient.isInitialized || !::webSocketClient.isInitialized) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Status: $connectionState",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = room,
                onValueChange = { room = it },
                label = { Text("Room") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    connectToRoom(username, room)
                    isConnected = true
                    connectionState = "Connecting..."
                },
                enabled = !isConnected,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isConnected) "Connected" else "Connect")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    startCall()
                    isCallActive = true
                    connectionState = "Starting call..."
                },
                enabled = isConnected && !isCallActive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isCallActive) "Call Active" else "Start Call")
            }

            if (error.isNotEmpty()) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(8.dp)
            ) {
                AndroidView(
                    factory = { context ->
                        SurfaceViewRenderer(context).also { view ->
                            view.init(eglBase.eglBaseContext, null)
                            view.setMirror(true)
                            view.setEnableHardwareScaler(true)
                            view.setZOrderMediaOverlay(true)
                            // Убедимся, что локальный поток создан
                            webRTCClient.createLocalStream(view)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                AndroidView(
                    factory = { context ->
                        SurfaceViewRenderer(context).also { view ->
                            view.init(eglBase.eglBaseContext, null)
                            view.setEnableHardwareScaler(true)
                            view.setZOrderMediaOverlay(true)
                            remoteVideoView = view
                            // Убедимся, что удалённый поток добавлен
                            webRTCClient.setObserver(object : PeerConnection.Observer {
                                override fun onAddStream(stream: MediaStream?) {
                                    stream?.videoTracks?.forEach { track ->
                                        track.addSink(view)
                                    }
                                }
                                // Остальные методы оставляем пустыми
                                override fun onIceCandidate(candidate: IceCandidate?) {}
                                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                                override fun onRemoveStream(stream: MediaStream?) {}
                                override fun onDataChannel(channel: DataChannel?) {}
                                override fun onRenegotiationNeeded() {}
                                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
                            })
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    private fun checkAllPermissionsGranted(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun initializeWebRTC() {
        webRTCClient = WebRTCClient(
            context = this,
            observer = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        val message = JSONObject().apply {
                            put("type", "ice_candidate")
                            put("ice", JSONObject().apply {
                                put("sdpMid", it.sdpMid)
                                put("sdpMLineIndex", it.sdpMLineIndex)
                                put("sdp", it.sdp)
                            })
                        }
                        webSocketClient.send(message)
                    }
                }

                override fun onAddStream(stream: MediaStream?) {
                    Log.d("mytest", "onAddStream: ${stream?.id}")
                    runOnUiThread {
                        stream?.videoTracks?.forEach { track ->
                            remoteVideoView?.let { track.addSink(it) }
                        }
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d("mytest", "IceConnectionState: $state")
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )

        webSocketClient = WebSocketClient(object : WebSocketListener {
            override fun onMessage(message: JSONObject) {
                runOnUiThread {
                    try {
                        if (message.has("type")) {
                            when (message.getString("type")) {
                                "offer" -> handleOffer(message)
                                "answer" -> handleAnswer(message)
                                "ice_candidate" -> handleIceCandidate(message)
                                "join" -> Log.d("mytest", "User joined the room")
                                "leave" -> Log.d("mytest", "User left the room")
                                else -> Log.w("mytest", "Unknown message type: ${message.getString("type")}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("mytest", "Error processing message", e)
                    }
                }
            }

            override fun onConnected() {
                Log.d("mytest", "WebSocket connected")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "WebSocket connected", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onDisconnected() {
                Log.d("mytest", "WebSocket disconnected")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "WebSocket disconnected", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(error: String) {
                Log.e("mytest", "WebSocket error: $error")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "WebSocket error: $error", Toast.LENGTH_SHORT).show()
                }
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
                        Log.e("mytest", "Failed to create answer: $error")
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e("mytest", "Failed to set answer: $error")
                    }
                })
            }
            override fun onCreateFailure(error: String?) {
                Log.e("mytest", "Failed to create offer: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("mytest", "Failed to set offer: $error")
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
                Log.e("mytest", "Failed to create answer: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("mytest", "Failed to set answer: $error")
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
                Log.e("mytest", "Failed to create offer: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("mytest", "Failed to set offer: $error")
            }
        })
    }

    override fun onDestroy() {
        webSocketClient.disconnect()
        webRTCClient.close()
        eglBase.release()
        remoteVideoView?.release()
        super.onDestroy()
    }
}

@Composable
fun mytestTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = lightColorScheme(
        primary = Color(0xFF6200EE),
        secondary = Color(0xFF03DAC6),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}