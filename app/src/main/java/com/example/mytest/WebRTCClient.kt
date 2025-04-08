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
        // Инициализация PeerConnectionFactory
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(
                eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        // Создание PeerConnection
        val rtcConfig = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)!!

        // Создание медиапотоков
        createLocalStream()
    }

    private fun createLocalStream() {
        try {
            // Создание аудио трека
            val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            localAudioTrack = peerConnectionFactory.createAudioTrack("AUDIO_TRACK", audioSource)

            // Создание видео трека
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
            videoCapturer?.startCapture(640, 480, 30)

            localVideoTrack = peerConnectionFactory.createVideoTrack("VIDEO_TRACK", videoSource).apply {
                addSink(localView)
            }

            // Добавление треков в peerConnection
            localAudioTrack?.let { audioTrack ->
                peerConnection.addTransceiver(
                    audioTrack,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                )
            }

            localVideoTrack?.let { videoTrack ->
                peerConnection.addTransceiver(
                    videoTrack,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                )
            }

        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error creating local stream", e)
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

    fun createOffer(observer: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection.createOffer(observer, constraints)
    }

    fun createAnswer(observer: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection.createAnswer(observer, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription, observer: SdpObserver) {
        peerConnection.setRemoteDescription(observer, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection.addIceCandidate(candidate)
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