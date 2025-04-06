package com.example.mytest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.registerForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.webrtc.*


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

    private var currentUsername = ""
    private var currentRoom = ""
    private var isConnected by mutableStateOf(false)
    private var isCallActive by mutableStateOf(false)
    private var usersInRoom by mutableStateOf(emptyList<String>())
    private var errorMessage by mutableStateOf("")

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
            showToast("Camera and microphone permissions required")
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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFBB86FC),
                    secondary = Color(0xFF03DAC6),
                    surface = Color(0xFF121212),
                    onSurface = Color.White
                )
            ) {
                VideoCallUI()
            }
        }
    }

    @Composable
    fun VideoCallUI() {
        var username by remember { mutableStateOf("User${(1000..9999).random()}") }
        var room by remember { mutableStateOf("room1") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // Video container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { remoteView },
                    modifier = Modifier.fillMaxSize()
                )

                AndroidView(
                    factory = { localView },
                    modifier = Modifier
                        .size(120.dp)
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Controls
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Используем OutlinedTextField с правильными цветами
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.Gray
                    )
                )

                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("Room") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.Gray
                    )
                )

                Button(
                    onClick = { connectToRoom(username, room) },
                    enabled = !isConnected,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black
                    )
                ) {
                    Text(if (isConnected) "Connected" else "Connect")
                }

                if (isConnected) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isCallActive) {
                            Button(
                                onClick = { startCall() },
                                enabled = usersInRoom.size > 1,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text("Start Call")
                            }
                        } else {
                            Button(
                                onClick = { endCall() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = Color.White
                                )
                            ) {
                                Text("End Call")
                            }
                        }
                    }
                }

                if (usersInRoom.isNotEmpty()) {
                    Text(
                        "Users in room (${usersInRoom.size}):",
                        color = Color.White
                    )
                    Column {
                        usersInRoom.forEach { user ->
                            Text(
                                "- $user",
                                color = Color.White
                            )
                        }
                    }
                }

                if (errorMessage.isNotEmpty()) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    private fun connectToRoom(username: String, room: String) {
        currentUsername = username
        currentRoom = room
        errorMessage = ""

        try {
            webSocketClient.connect("wss://anybet.site/ws")
            val joinMessage = JSONObject().apply {
                put("room", room)
                put("username", username)
            }
            webSocketClient.sendRaw(joinMessage.toString())
        } catch (e: Exception) {
            errorMessage = "Connection error: ${e.message}"
        }
    }

    private fun startCall() {
        errorMessage = ""
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
                    webSocketClient.sendRaw(offerMessage.toString())
                    webRTCClient.peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, it)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                errorMessage = "Offer creation failed: $error"
                Log.e("WebRTC", "Create offer error: $error")
            }
            override fun onSetFailure(error: String?) {
                errorMessage = "Offer setup failed: $error"
                Log.e("WebRTC", "Set offer error: $error")
            }
        })
        isCallActive = true
    }

    private fun endCall() {
        errorMessage = ""
        webRTCClient.close()
        initializeWebRTC()
        isCallActive = false
    }

    private fun handleOffer(message: JSONObject) {
        val sdp = message.getJSONObject("sdp")
        val sessionDescription = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
            sdp.getString("sdp")
        )

        webRTCClient.peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
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
                            webSocketClient.sendRaw(answerMessage.toString())
                            webRTCClient.peerConnection.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() {}
                                override fun onCreateFailure(p0: String?) {}
                                override fun onSetFailure(p0: String?) {}
                            }, it)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {
                        errorMessage = "Answer creation failed: $error"
                        Log.e("WebRTC", "Create answer error: $error")
                    }
                    override fun onSetFailure(error: String?) {
                        errorMessage = "Answer setup failed: $error"
                        Log.e("WebRTC", "Set answer error: $error")
                    }
                })
            }
            override fun onCreateFailure(error: String?) {
                errorMessage = "Remote description failed: $error"
                Log.e("WebRTC", "Create remote description error: $error")
            }
            override fun onSetFailure(error: String?) {
                errorMessage = "Remote setup failed: $error"
                Log.e("WebRTC", "Set remote description error: $error")
            }
        }, sessionDescription)
    }

    private fun handleAnswer(message: JSONObject) {
        val sdp = message.getJSONObject("sdp")
        val sessionDescription = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
            sdp.getString("sdp")
        )

        webRTCClient.peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                errorMessage = "Remote description failed: $error"
                Log.e("WebRTC", "Create remote description error: $error")
            }
            override fun onSetFailure(error: String?) {
                errorMessage = "Remote setup failed: $error"
                Log.e("WebRTC", "Set remote description error: $error")
            }
        }, sessionDescription)
    }

    private fun handleIceCandidate(message: JSONObject) {
        val ice = message.getJSONObject("ice")
        val candidate = IceCandidate(
            ice.getString("sdpMid"),
            ice.getInt("sdpMLineIndex"),
            ice.getString("candidate")
        )
        webRTCClient.peerConnection.addIceCandidate(candidate)
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val message = JSONObject().apply {
            put("type", "ice_candidate")
            put("ice", JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        }
        webSocketClient.sendRaw(message.toString())
    }

    private fun handleWebSocketMessage(message: JSONObject) {
        Log.d("WebSocket", "Received: $message")

        when {
            message.has("type") -> when (message.getString("type")) {
                "offer" -> handleOffer(message)
                "answer" -> handleAnswer(message)
                "ice_candidate" -> handleIceCandidate(message)
                "error" -> errorMessage = message.getString("message")
                "room_info" -> {
                    val users = mutableListOf<String>()
                    message.getJSONObject("data").getJSONArray("users").let { usersArray ->
                        for (i in 0 until usersArray.length()) {
                            users.add(usersArray.getString(i))
                        }
                    }
                    usersInRoom = users
                }
            }
            message.has("sdp") -> {
                if (message.getJSONObject("sdp").getString("type") == "offer") {
                    handleOffer(message)
                } else {
                    handleAnswer(message)
                }
            }
            message.has("ice") -> handleIceCandidate(message)
        }
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
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        runOnUiThread { endCall() }
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

        webSocketClient = WebSocketClient(object : WebSocketListener {
            override fun onMessage(message: JSONObject) = handleWebSocketMessage(message)
            override fun onConnected() {
                isConnected = true
                showToast("Connected to server")
            }
            override fun onDisconnected() {
                isConnected = false
                showToast("Disconnected from server")
                runOnUiThread { endCall() }
            }
            override fun onError(error: String) {
                errorMessage = error
                showToast("Error: $error")
            }
        })
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