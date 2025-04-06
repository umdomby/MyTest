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
    private var videoCapturer: VideoCapturer? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(
                eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)!!
        createLocalStream()
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

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection.addIceCandidate(candidate)
    }

    fun createOffer(observer: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection.createOffer(observer, constraints)
    }

    private fun createLocalStream() {
        try {
            // Аудио
            val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            val localAudioTrack = peerConnectionFactory.createAudioTrack("AUDIO_TRACK", audioSource)
            peerConnection.addTrack(localAudioTrack)

            // Видео
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
            Log.e("WebRTCClient", "Ошибка создания потока", e)
        }
    }

    private fun createCameraCapturer(): VideoCapturer? {
        return Camera2Enumerator(context).run {
            deviceNames.firstOrNull { isFrontFacing(it) }?.let {
                Log.d("WebRTC", "Используется фронтальная камера: $it")
                createCapturer(it, null)
            } ?: deviceNames.firstOrNull()?.let {
                Log.d("WebRTC", "Используется первая доступная камера: $it")
                createCapturer(it, null)
            }
        }
    }

    fun close() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        localVideoTrack?.dispose()
        peerConnection.dispose()
    }
}