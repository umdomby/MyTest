package com.example.mytest

import android.content.Context
import android.os.Build
import android.util.Log
import org.webrtc.*

class WebRTCClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val localView: SurfaceViewRenderer,
    private val remoteView: SurfaceViewRenderer,
    private val observer: PeerConnection.Observer
) {
    lateinit var peerConnectionFactory: PeerConnectionFactory
    var peerConnection: PeerConnection
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    internal var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    init {
        initializePeerConnectionFactory()
        peerConnection = createPeerConnection()
        createLocalTracks()
    }

    private fun initializePeerConnectionFactory() {
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val videoEncoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,  // enableIntelVp8Encoder
            false  // enableH264HighProfile
        )

        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()
    }


    // Функция для параметров H.264
    private fun getDefaultH264Params(isHighProfile: Boolean): Map<String, String> {
        return mapOf(
            "profile-level-id" to if (isHighProfile) "640c1f" else "42e01f",
            "level-asymmetry-allowed" to "1",
            "packetization-mode" to "1",
            // Устанавливаем низкий битрейт
            "max-bitrate" to "500000", // 500 kbps
            "start-bitrate" to "300000", // 300 kbps
            "min-bitrate" to "200000" // 200 kbps
        )
    }

    private fun createPeerConnection(): PeerConnection {
        val rtcConfig = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("turn:ardua.site:3478")
                .setUsername("user1")
                .setPassword("pass1")
                .createIceServer(),
            PeerConnection.IceServer.builder("stun:ardua.site:3478").createIceServer()
        )).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            keyType = PeerConnection.KeyType.ECDSA

            // Оптимизации для H264
            audioJitterBufferMaxPackets = 50
            audioJitterBufferFastAccelerate = true
            iceConnectionReceivingTimeout = 5000
        }

        return peerConnectionFactory.createPeerConnection(rtcConfig, observer) ?:
        throw IllegalStateException("Failed to create peer connection")
    }

    // В WebRTCClient.kt добавляем обработку переключения камеры
    internal fun switchCamera(useBackCamera: Boolean) {
        try {
            videoCapturer?.let { capturer ->
                if (capturer is CameraVideoCapturer) {
                    if (useBackCamera) {
                        // Switch to back camera
                        val cameraEnumerator = Camera2Enumerator(context)
                        val deviceNames = cameraEnumerator.deviceNames
                        deviceNames.find { !cameraEnumerator.isFrontFacing(it) }?.let { backCameraId ->
                            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                                    Log.d("WebRTCClient", "Switched to back camera")
                                }

                                override fun onCameraSwitchError(error: String) {
                                    Log.e("WebRTCClient", "Error switching to back camera: $error")
                                }
                            })
                        }
                    } else {
                        // Switch to front camera
                        val cameraEnumerator = Camera2Enumerator(context)
                        val deviceNames = cameraEnumerator.deviceNames
                        deviceNames.find { cameraEnumerator.isFrontFacing(it) }?.let { frontCameraId ->
                            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                                    Log.d("WebRTCClient", "Switched to front camera")
                                }

                                override fun onCameraSwitchError(error: String) {
                                    Log.e("WebRTCClient", "Error switching to front camera: $error")
                                }
                            })
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error switching camera", e)
        }
    }

    private fun createLocalTracks() {
        createAudioTrack()
        createVideoTrack()

        val streamId = "ARDAMS"
        val stream = peerConnectionFactory.createLocalMediaStream(streamId)

        localAudioTrack?.let {
            stream.addTrack(it)
            peerConnection.addTrack(it, listOf(streamId))
        }

        localVideoTrack?.let {
            stream.addTrack(it)
            peerConnection.addTrack(it, listOf(streamId))
        }
    }

    private fun createAudioTrack() {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("ARDAMSa0", audioSource)
    }

    private fun createVideoTrack() {
        try {
            videoCapturer = createCameraCapturer()
            videoCapturer?.let { capturer ->
                surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CaptureThread",
                    eglBase.eglBaseContext
                )

                val videoSource = peerConnectionFactory.createVideoSource(false)
                capturer.initialize(
                    surfaceTextureHelper,
                    context,
                    videoSource.capturerObserver
                )

                // Старт с низким разрешением и FPS
                capturer.startCapture(640, 480, 15)

                localVideoTrack = peerConnectionFactory.createVideoTrack("ARDAMSv0", videoSource).apply {
                    addSink(localView)
                }
            } ?: run {
                Log.e("WebRTCClient", "Failed to create video capturer")
            }
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error creating video track", e)
        }
    }

    // Функция для установки битрейта
    fun setVideoEncoderBitrate(minBitrate: Int, currentBitrate: Int, maxBitrate: Int) {
        try {
            val sender = peerConnection.senders.find { it.track()?.kind() == "video" }
            sender?.let { videoSender ->
                val parameters = videoSender.parameters
                if (parameters.encodings.isNotEmpty()) {
                    parameters.encodings[0].minBitrateBps = minBitrate
                    parameters.encodings[0].maxBitrateBps = maxBitrate
                    parameters.encodings[0].bitratePriority = 1.0
                    videoSender.parameters = parameters
                }
            }
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error setting video bitrate", e)
        }
    }



    private fun createCameraCapturer(): VideoCapturer? {
        return Camera2Enumerator(context).run {
            deviceNames.find { isFrontFacing(it) }?.let {
                Log.d("WebRTC", "Using front camera: $it")
                createCapturer(it, null)
            } ?: deviceNames.firstOrNull()?.let {
                Log.d("WebRTC", "Using first available camera: $it")
                createCapturer(it, null)
            }
        }
    }

    fun close() {
        try {
            videoCapturer?.let {
                it.stopCapture()
                it.dispose()
            }
            localVideoTrack?.let {
                it.removeSink(localView)
                it.dispose()
            }
            localAudioTrack?.dispose()
            surfaceTextureHelper?.dispose()
            peerConnection.close()
            peerConnection.dispose()
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error closing resources", e)
        }
    }
}