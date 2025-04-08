// file: app/src/main/java/com/example/mytest/WebRTCClient.kt
package com.example.mytest

import android.content.Context
import android.util.Log
import org.webrtc.*

class WebRTCClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val localView: SurfaceViewRenderer,
    private val remoteView: SurfaceViewRenderer,
    private val observer: PeerConnection.Observer
) {
    val peerConnectionFactory: PeerConnectionFactory
    val peerConnection: PeerConnection
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    init {
        // Initialize PeerConnectionFactory with H.264 and VP8 support
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val videoEncoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,  // enableIntelVp8Encoder
            true   // enableH264HighProfile
        )

        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .createPeerConnectionFactory()

        // Enhanced configuration for browser compatibility
        val rtcConfig = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
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

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)!!
        createLocalTracks()
    }

    private fun createLocalTracks() {
        try {
            // Audio track with echo cancellation
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }

            val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory.createAudioTrack("AUDIO_TRACK", audioSource)
            peerConnection.addTrack(localAudioTrack!!, listOf("stream"))

            // Video track with camera capture
            videoCapturer = createCameraCapturer()
            surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread",
                eglBase.eglBaseContext
            )

            val videoSource = peerConnectionFactory.createVideoSource(false)
            videoCapturer?.initialize(
                surfaceTextureHelper,
                context,
                videoSource.capturerObserver
            )
            videoCapturer?.startCapture(640, 480, 30) // Reduced resolution for better compatibility

            localVideoTrack = peerConnectionFactory.createVideoTrack("VIDEO_TRACK", videoSource).apply {
                addSink(localView)
            }
            peerConnection.addTrack(localVideoTrack!!, listOf("stream"))

        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error creating local tracks", e)
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
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            localVideoTrack?.dispose()
            surfaceTextureHelper?.dispose()
            peerConnection.dispose()
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error closing resources", e)
        }
    }
}