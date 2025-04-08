// MainActivity.kt
package com.example.mytest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.webrtc.*

class MainActivity : ComponentActivity() {
    // UI State
    private var currentUsername by mutableStateOf("User${(1000..9999).random()}")
    private var currentRoom by mutableStateOf("room1")
    private var isConnected by mutableStateOf(false)
    private var isCallActive by mutableStateOf(false)
    private var usersInRoom by mutableStateOf(emptyList<String>())
    private var errorMessage by mutableStateOf("")
    private var isLoading by mutableStateOf(false)

    // WebRTC Components
    private var webRTCClient: WebRTCClient? = null
    private var webSocketClient: WebSocketClient? = null
    private var eglBase: EglBase? = null
    private var localView: SurfaceViewRenderer? = null
    private var remoteView: SurfaceViewRenderer? = null

    // Permissions
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    // Permission Launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            initializeComponents()
            startWebRTCService()
        } else {
            showToast("Required permissions not granted")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkAllPermissionsGranted()) {
            initializeComponents()
            startWebRTCService()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun startWebRTCService() {
        try {
            val serviceIntent = Intent(this, WebRTCService::class.java).apply {
                putExtra("roomName", currentRoom)
                putExtra("userName", currentUsername)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }

            finish()
        } catch (e: Exception) {
            Log.e("MainActivity", "Service start failed", e)
            showToast("Failed to start service")
        }
    }

    private fun initializeComponents() {
        cleanupResources()

        eglBase = EglBase.create()

        localView = SurfaceViewRenderer(this).apply {
            init(eglBase?.eglBaseContext, null)
            setMirror(true)
            setEnableHardwareScaler(true)
            setZOrderMediaOverlay(true)
        }

        remoteView = SurfaceViewRenderer(this).apply {
            init(eglBase?.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setZOrderMediaOverlay(true)
        }

        webRTCClient = WebRTCClient(
            context = this,
            eglBase = eglBase!!,
            localView = localView!!,
            remoteView = remoteView!!,
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
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        runOnUiThread { endCall() }
                    }
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
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
                runOnUiThread {
                    isConnected = true
                    isLoading = false
                    showToast("Connected to server")
                }
            }
            override fun onDisconnected() {
                runOnUiThread {
                    isConnected = false
                    isCallActive = false
                    isLoading = false
                    showToast("Disconnected from server")
                }
            }
            override fun onError(error: String) {
                runOnUiThread {
                    errorMessage = error
                    isLoading = false
                    showToast("Error: $error")
                }
            }
        })

        setUI()
    }

    @Composable
    fun VideoCallUI() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // Video Views
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                remoteView?.let { rv ->
                    AndroidView(
                        factory = { rv },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                localView?.let { lv ->
                    AndroidView(
                        factory = { lv },
                        modifier = Modifier
                            .size(120.dp)
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Controls
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isConnected) {
                    OutlinedTextField(
                        value = currentUsername,
                        onValueChange = { currentUsername = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = currentRoom,
                        onValueChange = { currentRoom = it },
                        label = { Text("Room") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Button(
                    onClick = {
                        if (isConnected) {
                            disconnectFromRoom()
                        } else {
                            connectToRoom(currentUsername, currentRoom)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White
                        )
                    } else {
                        Text(if (isConnected) "Disconnect" else "Connect")
                    }
                }

                if (isConnected && usersInRoom.size > 1 && !isCallActive) {
                    Button(
                        onClick = { startCall() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start Call")
                    }
                }

                if (isCallActive) {
                    Button(
                        onClick = { endCall() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("End Call")
                    }
                }

                if (usersInRoom.isNotEmpty()) {
                    Text(
                        "Users in room: ${usersInRoom.joinToString()}",
                        color = MaterialTheme.colorScheme.onSurface
                    )
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

    private fun setUI() {
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFBB86FC),
                    secondary = Color(0xFF03DAC6),
                    // ... other colors
                )
            ) {
                VideoCallUI()
            }
        }
    }

    private fun connectToRoom(username: String, room: String) {
        isLoading = true
        currentUsername = username
        currentRoom = room
        errorMessage = ""

        try {
            webSocketClient?.connect("wss://anybet.site/ws")
            val joinMessage = JSONObject().apply {
                put("action", "join")
                put("room", room)
                put("username", username)
            }
            webSocketClient?.send(joinMessage.toString())
        } catch (e: Exception) {
            isLoading = false
            errorMessage = "Connection error: ${e.message}"
        }
    }

    private fun disconnectFromRoom() {
        val leaveMessage = JSONObject().apply {
            put("action", "leave")
            put("room", currentRoom)
            put("username", currentUsername)
        }
        webSocketClient?.send(leaveMessage.toString())
        webSocketClient?.disconnect()

        isConnected = false
        isCallActive = false
        usersInRoom = emptyList()
    }

    private fun startCall() {
        webRTCClient?.let { client ->
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }

            client.peerConnection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    desc?.let {
                        client.peerConnection.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                val offerMessage = JSONObject().apply {
                                    put("type", "offer")
                                    put("sdp", JSONObject().apply {
                                        put("type", desc.type.canonicalForm())
                                        put("sdp", desc.description)
                                    })
                                    put("target", usersInRoom.firstOrNull { it != currentUsername })
                                }
                                webSocketClient?.send(offerMessage.toString())
                                isCallActive = true
                            }
                            override fun onSetFailure(error: String) {
                                errorMessage = "Offer setup failed: $error"
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(error: String) {}
                        }, desc)
                    }
                }
                override fun onCreateFailure(error: String) {
                    errorMessage = "Offer creation failed: $error"
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            }, constraints)
        }
    }

    private fun endCall() {
        val hangupMessage = JSONObject().apply {
            put("type", "hangup")
            put("target", usersInRoom.firstOrNull { it != currentUsername })
        }
        webSocketClient?.send(hangupMessage.toString())

        webRTCClient?.close()
        isCallActive = false
    }

    private fun handleWebSocketMessage(message: JSONObject) {
        when (message.optString("type")) {
            "offer" -> handleOffer(message)
            "answer" -> handleAnswer(message)
            "ice_candidate" -> handleIceCandidate(message)
            "hangup" -> handleHangup(message)
            "room_info" -> {
                val users = mutableListOf<String>()
                message.getJSONObject("data").getJSONArray("users").let {
                    for (i in 0 until it.length()) {
                        users.add(it.getString(i))
                    }
                }
                usersInRoom = users
            }
        }
    }

    private fun handleOffer(message: JSONObject) {
        val sdp = message.getJSONObject("sdp")
        val desc = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
            sdp.getString("sdp")
        )

        webRTCClient?.peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                }

                webRTCClient?.peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        answerDesc?.let {
                            webRTCClient?.peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onSetSuccess() {
                                    val answerMessage = JSONObject().apply {
                                        put("type", "answer")
                                        put("sdp", JSONObject().apply {
                                            put("type", it.type.canonicalForm())
                                            put("sdp", it.description)
                                        })
                                        put("target", message.optString("from"))
                                    }
                                    webSocketClient?.send(answerMessage.toString())
                                    isCallActive = true
                                }
                                override fun onSetFailure(error: String) {
                                    errorMessage = "Answer setup failed: $error"
                                }
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onCreateFailure(error: String) {}
                            }, it)
                        }
                    }
                    override fun onCreateFailure(error: String) {
                        errorMessage = "Answer creation failed: $error"
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String) {}
                }, constraints)
            }
            override fun onSetFailure(error: String) {
                errorMessage = "Remote description failed: $error"
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(error: String) {}
        }, desc)
    }

    private fun handleAnswer(message: JSONObject) {
        val sdp = message.getJSONObject("sdp")
        val desc = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
            sdp.getString("sdp")
        )

        webRTCClient?.peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                isCallActive = true
            }
            override fun onSetFailure(error: String) {
                errorMessage = "Remote description failed: $error"
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(error: String) {}
        }, desc)
    }

    private fun handleIceCandidate(message: JSONObject) {
        val ice = message.getJSONObject("ice")
        val candidate = IceCandidate(
            ice.getString("sdpMid"),
            ice.getInt("sdpMLineIndex"),
            ice.getString("candidate")
        )
        webRTCClient?.peerConnection?.addIceCandidate(candidate)
    }

    private fun handleHangup(message: JSONObject) {
        runOnUiThread {
            endCall()
            showToast("Call ended by remote peer")
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
            put("target", usersInRoom.firstOrNull { it != currentUsername })
        }
        webSocketClient?.send(message.toString())
    }

    private fun cleanupResources() {
        webSocketClient?.disconnect()
        webRTCClient?.close()
        eglBase?.release()
        localView?.release()
        remoteView?.release()

        webSocketClient = null
        webRTCClient = null
        eglBase = null
        localView = null
        remoteView = null
    }

    private fun checkAllPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun showToast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        cleanupResources()
        super.onDestroy()
    }
}