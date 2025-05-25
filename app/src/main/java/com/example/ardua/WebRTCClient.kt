package com.example.ardua

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
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    internal var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    init {
        initializePeerConnectionFactory()
        peerConnection = createPeerConnection()
        if (peerConnection == null) {
            Log.e("WebRTCClient", "Failed to create peer connection")
            throw IllegalStateException("Failed to create peer connection")
        }
        createLocalTracks()
    }

    private fun initializePeerConnectionFactory() {
        // 1. Инициализация WebRTC
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(true)
                    .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                    .createInitializationOptions()
            )
            Log.d("WebRTCClient", "WebRTC library initialized successfully")
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Failed to initialize WebRTC library", e)
            throw e
        }

        // 2. Создание фабрик кодеков
        val videoEncoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,  // enableIntelVp8Encoder
            true   // enableH264HighProfile
        )
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        // 3. Настройка опций
        val options = PeerConnectionFactory.Options().apply {
            disableEncryption = false
            disableNetworkMonitor = false
        }

        // 4. Создание PeerConnectionFactory
        try {
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(videoEncoderFactory)
                .setVideoDecoderFactory(videoDecoderFactory)
                .createPeerConnectionFactory()
            Log.d("WebRTCClient", "PeerConnectionFactory created successfully")
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Failed to create PeerConnectionFactory", e)
            throw e
        }
    }

    private fun createPeerConnection(): PeerConnection? {
        try {
            val rtcConfig = PeerConnection.RTCConfiguration(
                listOf(
                    //PeerConnection.IceServer.builder("stun:ardua.site:3478").createIceServer(),
                    PeerConnection.IceServer.builder("turn:ardua.site:3478")
                        .setUsername("user1")
                        .setPassword("pass1")
                        .createIceServer()
                )
            ).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
                keyType = PeerConnection.KeyType.ECDSA
            }
            val peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
            if (peerConnection == null) {
                Log.e("WebRTCClient", "Failed to create PeerConnection")
            }
            return peerConnection
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error creating PeerConnection", e)
            return null
        }
    }

    internal fun switchCamera(useBackCamera: Boolean) {
        try {
            videoCapturer?.let { capturer ->
                if (capturer is CameraVideoCapturer) {
                    val enumerator = Camera2Enumerator(context)
                    val targetCamera = enumerator.deviceNames.find {
                        if (useBackCamera) !enumerator.isFrontFacing(it) else enumerator.isFrontFacing(it)
                    }
                    if (targetCamera != null) {
                        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                                Log.d("WebRTCClient", "Switched to ${if (isFrontCamera) "front" else "back"} camera")
                            }

                            override fun onCameraSwitchError(error: String) {
                                Log.e("WebRTCClient", "Error switching camera: $error")
                            }
                        }, targetCamera)
                    } else {
                        Log.e("WebRTCClient", "No ${if (useBackCamera) "back" else "front"} camera found")
                    }
                } else {
                    Log.w("WebRTCClient", "Video capturer is not a CameraVideoCapturer")
                }
            } ?: Log.w("WebRTCClient", "Video capturer is null")
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
            peerConnection?.addTrack(it, listOf(streamId))
        }

        localVideoTrack?.let {
            stream.addTrack(it)
            peerConnection?.addTrack(it, listOf(streamId))
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
            if (videoCapturer == null) {
                Log.e("WebRTCClient", "Failed to create video capturer")
                throw IllegalStateException("Video capturer is null")
            }

            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            if (surfaceTextureHelper == null) {
                Log.e("WebRTCClient", "Failed to create SurfaceTextureHelper")
                throw IllegalStateException("SurfaceTextureHelper is null")
            }

            val videoSource = peerConnectionFactory.createVideoSource(false)
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)

            val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            videoCapturer?.startCapture(
                if (isSamsung) 480 else 640,
                if (isSamsung) 360 else 480,
                if (isSamsung) 15 else 20
            )

            localVideoTrack = peerConnectionFactory.createVideoTrack("ARDAMSv0", videoSource).apply {
                addSink(localView)
            }

            setVideoEncoderBitrate(
                if (isSamsung) 150000 else 300000,
                if (isSamsung) 200000 else 400000,
                if (isSamsung) 300000 else 500000
            )
            Log.d("WebRTCClient", "Video track created successfully")
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error creating video track", e)
            throw e
        }
    }

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
                    Log.d("WebRTCClient", "Set video bitrate: min=$minBitrate, max=$maxBitrate")
                }
            } ?: Log.w("WebRTCClient", "No video sender found")
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error setting video bitrate", e)
        }
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        return enumerator.deviceNames.find { enumerator.isFrontFacing(it) }?.let {
            Log.d("WebRTCClient", "Using front camera: $it")
            enumerator.createCapturer(it, null)
        } ?: enumerator.deviceNames.firstOrNull()?.let {
            Log.d("WebRTCClient", "Using first available camera: $it")
            enumerator.createCapturer(it, null)
        } ?: run {
            Log.e("WebRTCClient", "No cameras available")
            null
        }
    }

    fun close() {
        try {
            videoCapturer?.let { capturer ->
                try {
                    capturer.stopCapture()
                    Log.d("WebRTCClient", "Video capturer stopped")
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error stopping capturer", e)
                }
                try {
                    capturer.dispose()
                    Log.d("WebRTCClient", "Video capturer disposed")
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error disposing capturer", e)
                }
            }

            localVideoTrack?.let { track ->
                try {
                    track.removeSink(localView)
                    track.dispose()
                    Log.d("WebRTCClient", "Local video track disposed")
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error disposing video track", e)
                }
            }

            localAudioTrack?.let { track ->
                try {
                    track.dispose()
                    Log.d("WebRTCClient", "Local audio track disposed")
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error disposing audio track", e)
                }
            }

            surfaceTextureHelper?.let { helper ->
                try {
                    helper.dispose()
                    Log.d("WebRTCClient", "SurfaceTextureHelper disposed")
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error disposing surface helper", e)
                }
            }

            peerConnection?.let { pc ->
                try {
                    pc.close()
                    Log.d("WebRTCClient", "Peer connection closed")
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error closing peer connection", e)
                }
                try {
                    pc.dispose()
                    Log.d("WebRTCClient", "Peer connection disposed")
                } catch (e: Exception) {
                    Log.e("WebRTCClient", "Error disposing peer connection", e)
                }
            }

            try {
                peerConnectionFactory.dispose()
                Log.d("WebRTCClient", "PeerConnectionFactory disposed")
            } catch (e: Exception) {
                Log.e("WebRTCClient", "Error disposing PeerConnectionFactory", e)
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