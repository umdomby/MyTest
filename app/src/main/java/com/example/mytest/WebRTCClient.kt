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

        // Create PeerConnectionFactory with video codecs support
        peerConnectionFactory = createPeerConnectionFactory()

        // Create PeerConnection with ICE servers
        peerConnection = createPeerConnection()

        // Initialize local media streams
        initializeLocalStream()
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(
                eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection(): PeerConnection {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 1
        }

        return peerConnectionFactory.createPeerConnection(rtcConfig, observer) ?:
        throw IllegalStateException("Failed to create PeerConnection")
    }

    private fun initializeLocalStream() {
        try {
            // Initialize views if not already initialized
            try {
                localView.init(eglBase.eglBaseContext, null)
                localView.setMirror(true)
            } catch (e: Exception) {
                Log.d("WebRTC", "Local view already initialized")
            }

            try {
                remoteView.init(eglBase.eglBaseContext, null)
            } catch (e: Exception) {
                Log.d("WebRTC", "Remote view already initialized")
            }

            // Create and add audio track
            val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            localAudioTrack = peerConnectionFactory.createAudioTrack("AUDIO_TRACK", audioSource)
            peerConnection.addTrack(localAudioTrack!!)

            // Create and add video track
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
            throw e
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