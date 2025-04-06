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

    init {
        // Initialize PeerConnectionFactory
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
        )

        // Create PeerConnectionFactory with video codecs support
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(
                eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        // Create PeerConnection with ICE servers
        val rtcConfig = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:anybet.site:3478")
                .setUsername("username")
                .setPassword("password")
                .createIceServer()
        )).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 1
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)!!

        // Initialize local stream
        initializeLocalStream()
    }

    private fun initializeLocalStream() {
        try {
            // Initialize views
            localView.init(eglBase.eglBaseContext, null)
            localView.setMirror(true)
            remoteView.init(eglBase.eglBaseContext, null)

            // Audio
            val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            localAudioTrack = peerConnectionFactory.createAudioTrack("AUDIO_TRACK", audioSource)
            peerConnection.addTrack(localAudioTrack!!)

            // Video
            videoCapturer = createCameraCapturer()?.apply {
                val surfaceTextureHelper = SurfaceTextureHelper.create(
                    "VideoCaptureThread",
                    eglBase.eglBaseContext
                )
                val videoSource = peerConnectionFactory.createVideoSource(false)
                initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
                startCapture(640, 480, 30)

                localVideoTrack = peerConnectionFactory.createVideoTrack("VIDEO_TRACK", videoSource).apply {
                    addSink(localView)
                }
                peerConnection.addTrack(localVideoTrack!!)
            }
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error initializing local stream", e)
        }
    }

    private fun createCameraCapturer(): VideoCapturer? {
        return try {
            val cameraEnumerator = Camera2Enumerator(context)
            val deviceNames = cameraEnumerator.deviceNames

            deviceNames.find { cameraEnumerator.isFrontFacing(it) }?.let {
                Log.d("WebRTC", "Using front camera: $it")
                cameraEnumerator.createCapturer(it, null)
            } ?: deviceNames.firstOrNull()?.let {
                Log.d("WebRTC", "Using first available camera: $it")
                cameraEnumerator.createCapturer(it, null)
            }
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error creating camera capturer", e)
            null
        }
    }

    fun setRemoteDescription(sdp: SessionDescription, observer: SdpObserver) {
        peerConnection.setRemoteDescription(observer, sdp)
    }

    fun createAnswer(observer: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection.createAnswer(observer, constraints)
    }

    fun createOffer(observer: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection.createOffer(observer, constraints)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection.addIceCandidate(candidate)
    }

    fun close() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            localVideoTrack?.dispose()
            localAudioTrack?.dispose()
            peerConnection.dispose()
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error closing resources", e)
        }
    }
}