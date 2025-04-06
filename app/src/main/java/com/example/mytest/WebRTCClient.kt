package com.example.mytest

import android.content.Context
import android.util.Log
import android.widget.Toast
import org.webrtc.*

class WebRTCClient(
    private val context: Context,
    private var observer: PeerConnection.Observer
) {
    private val peerConnectionFactory: PeerConnectionFactory
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )
    private val peerConnection: PeerConnection
    private val eglBase: EglBase = EglBase.create()

    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    init {
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBase.eglBaseContext,
                    true,  // enableIntelVp8Encoder
                    true   // enableH264HighProfile
                )
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 1
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)!!
    }

    fun setObserver(observer: PeerConnection.Observer) {
        this.observer = observer
    }

    fun createLocalStream(localVideoOutput: SurfaceViewRenderer) {
        try {
            // Audio
            val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            localAudioTrack = peerConnectionFactory.createAudioTrack("AUDIO_TRACK_ID", audioSource)

            // Video
            videoCapturer = createCameraCapturer()
            if (videoCapturer == null) {
                Log.e("WebRTCClient", "Failed to create video capturer")
                return
            }

            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            val videoSource = peerConnectionFactory.createVideoSource(false)
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            videoCapturer?.startCapture(640, 480, 30)

            localVideoTrack = peerConnectionFactory.createVideoTrack("VIDEO_TRACK_ID", videoSource)
            localVideoTrack?.addSink(localVideoOutput)

            // Add tracks to peer connection
            localAudioTrack?.let { peerConnection.addTrack(it) }
            localVideoTrack?.let { peerConnection.addTrack(it) }

            Log.d("WebRTCClient", "Local stream created successfully")
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error creating local stream", e)
        }
    }

    private fun createCameraCapturer(): VideoCapturer? {
        return try {
            val enumerator = Camera2Enumerator(context)
            val deviceNames = enumerator.deviceNames

            for (deviceName in deviceNames) {
                if (enumerator.isFrontFacing(deviceName)) {
                    Log.d("WebRTCClient", "Using front-facing camera: $deviceName")
                    return enumerator.createCapturer(deviceName, null)
                }
            }

            if (deviceNames.isNotEmpty()) {
                Log.d("WebRTCClient", "Using first available camera: ${deviceNames[0]}")
                enumerator.createCapturer(deviceNames[0], null)
            } else {
                Log.e("WebRTCClient", "No camera devices found")
                null
            }
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Failed to create camera capturer", e)
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
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            localVideoTrack?.dispose()
            localAudioTrack?.dispose()
            peerConnection.dispose()
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error closing resources", e)
        }
    }
}