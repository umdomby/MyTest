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
            showToast("Необходимы все разрешения")
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
        val context = LocalContext.current

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            ControlsSection(
                username = username,
                room = room,
                isConnected = isConnected,
                isCallActive = isCallActive,
                usersInRoom = usersInRoom,
                onUsernameChange = { username = it },
                onRoomChange = { room = it },
                onConnect = {
                    connectToRoom(username, room)
                },
                onCall = {
                    startCall()
                },
                onEndCall = {
                    endCall()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            VideoSection()
        }
    }

    @Composable
    private fun ControlsSection(
        username: String,
        room: String,
        isConnected: Boolean,
        isCallActive: Boolean,
        usersInRoom: List<String>,
        onUsernameChange: (String) -> Unit,
        onRoomChange: (String) -> Unit,
        onConnect: () -> Unit,
        onCall: () -> Unit,
        onEndCall: () -> Unit
    ) {
        Column {
            TextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Имя пользователя") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = room,
                onValueChange = onRoomChange,
                label = { Text("Комната") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onConnect,
                enabled = !isConnected,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isConnected) "Подключено" else "Подключиться")
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isConnected) {
                if (!isCallActive) {
                    Button(
                        onClick = onCall,
                        enabled = usersInRoom.size > 1,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Начать звонок")
                    }
                } else {
                    Button(
                        onClick = onEndCall,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Завершить звонок")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (usersInRoom.isNotEmpty()) {
                Text("Участники в комнате (${usersInRoom.size}):", style = MaterialTheme.typography.bodyMedium)
                usersInRoom.forEach { user ->
                    Text("- $user", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable
    private fun VideoSection() {
        Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
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
    }

    private fun connectToRoom(username: String, room: String) {
        currentUsername = username
        currentRoom = room

        webSocketClient.connect("wss://anybet.site/ws")
        val joinMessage = JSONObject().apply {
            put("room", room)
            put("username", username)
        }
        webSocketClient.sendRaw(joinMessage.toString())
    }

    private fun startCall() {
        webRTCClient.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    val offerMessage = JSONObject().apply {
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
                Log.e("WebRTC", "Ошибка создания offer: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("WebRTC", "Ошибка установки offer: $error")
            }
        })
        isCallActive = true
    }

    private fun endCall() {
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
                        Log.e("WebRTC", "Ошибка создания answer: $error")
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e("WebRTC", "Ошибка установки answer: $error")
                    }
                })
            }
            override fun onCreateFailure(error: String?) {
                Log.e("WebRTC", "Ошибка создания remote description: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("WebRTC", "Ошибка установки remote description: $error")
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
                Log.e("WebRTC", "Ошибка создания remote description: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("WebRTC", "Ошибка установки remote description: $error")
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
            put("ice", JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        }
        webSocketClient.sendRaw(message.toString())
    }

    private fun handleWebSocketMessage(message: JSONObject) {
        Log.d("WebSocket", "Получено сообщение: $message")

        when {
            message.has("type") -> {
                when (message.getString("type")) {
                    "offer" -> handleOffer(message)
                    "answer" -> handleAnswer(message)
                    "ice_candidate" -> handleIceCandidate(message)
                }
            }
            message.has("sdp") -> {
                if (message.getJSONObject("sdp").getString("type") == "offer") {
                    handleOffer(message)
                } else {
                    handleAnswer(message)
                }
            }
            message.has("ice") -> {
                handleIceCandidate(message)
            }
            message.has("data") && message.getJSONObject("data").has("users") -> {
                val users = mutableListOf<String>()
                val usersArray = message.getJSONObject("data").getJSONArray("users")
                for (i in 0 until usersArray.length()) {
                    users.add(usersArray.getString(i))
                }
                usersInRoom = users
            }
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
            override fun onConnected() {
                isConnected = true
                showToast("Подключено к серверу")
            }
            override fun onDisconnected() {
                isConnected = false
                showToast("Отключено от сервера")
            }
            override fun onError(error: String) {
                showToast("Ошибка: $error")
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