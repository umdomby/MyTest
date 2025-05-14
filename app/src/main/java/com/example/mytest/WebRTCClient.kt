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
    var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    internal var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    init {
        initializePeerConnectionFactory()
        peerConnection = createPeerConnection()
        if (peerConnection == null) {
            throw IllegalStateException("Failed to create peer connection")
        }
        createLocalTracks()
    }

    private fun initializePeerConnectionFactory(preferredCodec: String = "H264") {
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .setFieldTrials(
                when (preferredCodec) {
                    "H264" -> "WebRTC-H264HighProfile/Enabled/WebRTC-H264PacketizationMode/Enabled/"
                    "VP8" -> "WebRTC-VP8-AutoConstrained/Enabled/"
                    else -> {
                        Log.w("WebRTCClient", "Unknown codec $preferredCodec, defaulting to H264")
                        "WebRTC-H264HighProfile/Enabled/WebRTC-H264PacketizationMode/Enabled/"
                    }
                }
            )
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        // Configure codec-specific encoder/decoder factories
        val videoEncoderFactory: VideoEncoderFactory = when (preferredCodec) {
            "H264" -> DefaultVideoEncoderFactory(eglBase.eglBaseContext, false, true) // Disable VP8, enable H.264
            "VP8" -> DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false) // Enable VP8, disable H.264
            else -> {
                Log.w("WebRTCClient", "Using default H.264 encoder factory")
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, false, true)
            }
        }

        val videoDecoderFactory: VideoDecoderFactory = when (preferredCodec) {
            "H264" -> DefaultVideoDecoderFactory(eglBase.eglBaseContext) // H.264 decoding
            "VP8" -> DefaultVideoDecoderFactory(eglBase.eglBaseContext) // VP8 decoding
            else -> DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        }

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()

        Log.d("WebRTCClient", "PeerConnectionFactory initialized with codec: $preferredCodec")
    }

    private fun createPeerConnection(): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:ardua.site:3478").createIceServer(),
            PeerConnection.IceServer.builder("turn:ardua.site:3478")
                .setUsername("user1")
                .setPassword("pass1")
                .createIceServer()
        )).apply {

            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            keyType = PeerConnection.KeyType.ECDSA
        }

        return peerConnectionFactory.createPeerConnection(rtcConfig, observer)
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

    private fun createLocalTracks(preferredCodec: String = "H264") {
        createAudioTrack()
        createVideoTrack(preferredCodec)

        val streamId = "ARDAMS"
        val stream = peerConnectionFactory.createLocalMediaStream(streamId)

        localAudioTrack?.let {
            stream.addTrack(it)
            peerConnection?.addTrack(it, listOf(streamId))
            Log.d("WebRTCClient", "Added local audio track to stream $streamId")
        }

        localVideoTrack?.let {
            stream.addTrack(it)
            peerConnection?.addTrack(it, listOf(streamId))
            Log.d("WebRTCClient", "Added local video track to stream $streamId with codec $preferredCodec")
        }

        if (localVideoTrack == null) {
            Log.e("WebRTCClient", "Failed to create local video track")
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

    private fun createVideoTrack(preferredCodec: String) {
        try {
            videoCapturer = createCameraCapturer()
            if (videoCapturer == null) {
                Log.e("WebRTCClient", "No camera capturer available")
                return
            }

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

                // Adjust capture settings based on codec
                val (width, height, fps) = when (preferredCodec) {
                    "H264" -> Triple(640, 480, 15)
                    "VP8" -> Triple(640, 480, 20)
                    else -> Triple(640, 480, 15)
                }

                var captureAttempts = 0
                val maxCaptureAttempts = 3
                while (captureAttempts < maxCaptureAttempts) {
                    try {
                        capturer.startCapture(width, height, fps)
                        Log.d("WebRTCClient", "Started video capture: ${width}x$height@$fps for $preferredCodec")
                        break
                    } catch (e: Exception) {
                        captureAttempts++
                        Log.e("WebRTCClient", "Capture attempt $captureAttempts failed: ${e.message}")
                        if (captureAttempts >= maxCaptureAttempts) {
                            Log.e("WebRTCClient", "Max capture attempts reached, giving up")
                            return
                        }
                        Thread.sleep(1000) // Wait before retry
                    }
                }

                localVideoTrack = peerConnectionFactory.createVideoTrack("ARDAMSv0", videoSource).apply {
                    addSink(localView)
                }

                // Set codec-specific bitrate
                val (minBitrate, currBitrate, maxBitrate) = when (preferredCodec) {
                    "H264" -> Triple(300000, 400000, 500000)
                    "VP8" -> Triple(200000, 300000, 400000)
                    else -> Triple(300000, 400000, 500000)
                }
                setVideoEncoderBitrate(minBitrate, currBitrate, maxBitrate)
                Log.d("WebRTCClient", "Set video bitrate: $minBitrate-$maxBitrate bps for $preferredCodec")
            }
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error creating video track for $preferredCodec", e)
        }
    }

    // Функция для установки битрейта
    fun setVideoEncoderBitrate(minBitrate: Int, currentBitrate: Int, maxBitrate: Int) {
        try {
            val sender = peerConnection?.senders?.find { it.track()?.kind() == "video" }
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
            videoCapturer?.let { capturer ->
                try {
                    capturer.stopCapture()
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error stopping capturer", e)
                }
                try {
                    capturer.dispose()
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error disposing capturer", e)
                }
            }

            localVideoTrack?.let { track ->
                try {
                    track.removeSink(localView)
                    track.dispose()
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error disposing video track", e)
                }
            }

            localAudioTrack?.let { track ->
                try {
                    track.dispose()
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error disposing audio track", e)
                }
            }

            surfaceTextureHelper?.let { helper ->
                try {
                    helper.dispose()
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error disposing surface helper", e)
                }
            }

            peerConnection?.let { pc ->
                try {
                    pc.close()
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error closing peer connection", e)
                }
                try {
                    pc.dispose()
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error disposing peer connection", e)
                }
            }
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error in cleanup", e)
        } finally {
            videoCapturer = null
            localVideoTrack = null
            localAudioTrack = null
            surfaceTextureHelper = null
            peerConnection = null
        }
    }
}