package com.example.webrtcapp

import android.content.Context
import android.util.Log
import android.widget.Toast
import org.webrtc.*

class WebRTCClient(
    private val context: Context,
    private val observer: PeerConnection.Observer
) {
    private val peerConnectionFactory: PeerConnectionFactory
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:your-turn-server.com")
            .setUsername("username")
            .setPassword("password")
            .createIceServer()
    )
    private val peerConnection: PeerConnection
    val eglBase: EglBase = EglBase.create()

    private lateinit var localVideoTrack: VideoTrack
    private lateinit var localAudioTrack: AudioTrack
    private lateinit var videoCapturer: VideoCapturer
    private lateinit var surfaceTextureHelper: SurfaceTextureHelper

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options().apply {
            disableEncryption = false
            disableNetworkMonitor = false
        }

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 2
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)!!
    }

    fun createLocalStream(localVideoOutput: SurfaceViewRenderer) {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("AUDIO_TRACK_ID", audioSource)

        videoCapturer = createCameraCapturer() ?: run {
            Log.e("WebRTCApp", "Failed to create camera capturer")
            Toast.makeText(context, "Failed to initialize camera", Toast.LENGTH_LONG).show()
            return
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(false)
        videoCapturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        videoCapturer.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("VIDEO_TRACK_ID", videoSource)
        localVideoTrack.addSink(localVideoOutput)

        peerConnection.addTrack(localAudioTrack)
        peerConnection.addTrack(localVideoTrack)
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        if (deviceNames.isNotEmpty()) {
            for (deviceName in deviceNames) {
                if (enumerator.isFrontFacing(deviceName)) {
                    return enumerator.createCapturer(deviceName, null)
                }
            }
            return enumerator.createCapturer(deviceNames[0], null)
        }
        return createEmulatorCameraCapturer()
    }

    private fun createEmulatorCameraCapturer(): VideoCapturer? {
        return try {
            val constructor = Class.forName("org.webrtc.Camera1Enumerator")
                .getDeclaredConstructor()
                .newInstance() as CameraEnumerator
            val deviceNames = constructor.deviceNames
            if (deviceNames.isNotEmpty()) constructor.createCapturer(deviceNames[0], null) else null
        } catch (e: Exception) {
            null
        }
    }

    fun createOffer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection.createOffer(sdpObserver, constraints)
    }

    fun createAnswer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection.createAnswer(sdpObserver, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription, sdpObserver: SdpObserver) {
        peerConnection.setRemoteDescription(sdpObserver, sdp)
    }

    fun addIceCandidate(iceCandidate: IceCandidate) {
        peerConnection.addIceCandidate(iceCandidate)
    }

    fun close() {
        try {
            videoCapturer.stopCapture()
            videoCapturer.dispose()
            surfaceTextureHelper.dispose()
            peerConnection.dispose()
            peerConnectionFactory.dispose()
        } catch (e: Exception) {
            Log.e("WebRTCApp", "Error closing WebRTCClient", e)
        }
    }
}