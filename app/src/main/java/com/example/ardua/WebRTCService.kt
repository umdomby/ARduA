package com.example.ardua
import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import org.webrtc.*
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import android.net.NetworkRequest
import android.speech.tts.TextToSpeech
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import java.util.Locale

class WebRTCService : Service() {

    companion object {
        var isRunning = false
            private set
        var currentRoomName = ""
        const val ACTION_SERVICE_STATE = "com.example.ardua.SERVICE_STATE"
        const val EXTRA_IS_RUNNING = "is_running"
        const val ACTION_CONNECTION_LOST = "com.example.ardua.CONNECTION_LOST"
        var sharedRemoteView: SurfaceViewRenderer? = null
        var currentVideoTrack: VideoTrack? = null // Публичное статическое свойство
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SERVICE_STATE) {
                val isRunning = intent.getBooleanExtra(EXTRA_IS_RUNNING, false)
                // Можно обновить UI активности, если она видима
            }
        }
    }

    private fun sendServiceStateUpdate() {
        val intent = Intent(ACTION_SERVICE_STATE).apply {
            putExtra(EXTRA_IS_RUNNING, isRunning)
        }
        sendBroadcast(intent)
    }

    private var isConnected = false // Флаг подключения
    private var isConnecting = false // Флаг процесса подключения

    private var shouldStop = false
    private var isUserStopped = false

    private val binder = LocalBinder()
    private lateinit var webSocketClient: WebSocketClient
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var eglBase: EglBase

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
    private val reconnectDelay = 5000L // 5 секунд

    private lateinit var remoteView: SurfaceViewRenderer

    private var roomName = "room1" // Будет перезаписано при старте
    private val userName = Build.MODEL ?: "AndroidDevice"
    //private val webSocketUrl = "wss://ardua.site/wsgo"
    private val webSocketUrl = "wss://ardua.site:444/wsgo"

    private val notificationId = 1
    private val channelId = "webrtc_service_channel"
    private val handler = Handler(Looper.getMainLooper())

    private var isStateReceiverRegistered = false
    private var isConnectivityReceiverRegistered = false

    private var isEglBaseReleased = false

    private lateinit var cameraManager: CameraManager
    private var flashlightCameraId: String? = null
    private var isFlashlightOn = false


    private var isVideoTrackReceiverRegistered = false
    private var textToSpeech: TextToSpeech? = null

    inner class LocalBinder : Binder() {
        fun getService(): WebRTCService = this@WebRTCService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isInitialized() || !webSocketClient.isConnected()) {
                reconnect()
            }
        }
    }

    private fun isValidSdp(sdp: String, codecName: String): Boolean {
        val hasVideoSection = sdp.contains("m=video")
        val hasCodec = sdp.contains("a=rtpmap:\\d+ $codecName/\\d+".toRegex())
        Log.d("WebRTCService", "SDP validation: hasVideoSection=$hasVideoSection, hasCodec=$hasCodec")
        return hasVideoSection && hasCodec
    }

    private val webSocketListener = object : WebSocketListener() {
        override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
            try {
                val message = JSONObject(text)
                handleWebSocketMessage(message)
            } catch (e: Exception) {
                Log.e("WebRTCService", "WebSocket message parse error", e)
            }
        }

        override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
            Log.d("WebRTCService", "WebSocket connected for room: $roomName")
            isConnected = true
            isConnecting = false
            reconnectAttempts = 0 // Сбрасываем счетчик попыток
            updateNotification("Connected to server")
            joinRoom()
        }

        override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            Log.d("WebRTCService", "WebSocket disconnected, code: $code, reason: $reason")
            isConnected = false
            handler.post {
                sendConnectionLostBroadcast()
                WebRTCService.currentVideoTrack?.let { sendVideoTrackLostBroadcast(it.id()) }
                WebRTCService.currentVideoTrack = null
            }
            if (code != 1000) { // Если это не нормальное закрытие
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
            Log.e("WebRTCService", "WebSocket error: ${t.message}")
            isConnected = false
            isConnecting = false
            handler.post {
                sendConnectionLostBroadcast()
                WebRTCService.currentVideoTrack?.let { sendVideoTrackLostBroadcast(it.id()) }
                WebRTCService.currentVideoTrack = null
            }
            updateNotification("Error: ${t.message?.take(30)}...")
            scheduleReconnect()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            handler.post { reconnect() }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            handler.post { updateNotification("Network lost") }
        }
    }

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            if (!isServiceActive()) {
                reconnect()
            }
            handler.postDelayed(this, 30000) // Проверка каждые 30 секунд
        }
    }

    private val bandwidthEstimationRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                adjustVideoQualityBasedOnStats()
            }
            handler.postDelayed(this, 10000) // Каждые 10 секунд
        }
    }

//    private val videoTrackCheckRunnable = object : Runnable {
//        override fun run() {
//            if (isConnected && ::webRTCClient.isInitialized && webRTCClient.peerConnection != null) {
//                val hasVideoTrack = currentVideoTrack?.enabled() == true && currentVideoTrack?.state() == MediaStreamTrack.State.LIVE
//                Log.d("WebRTCService", "Video track check: hasVideoTrack=$hasVideoTrack, currentVideoTrack=$currentVideoTrack")
//                if (hasVideoTrack) {
//                    currentVideoTrack?.let { track ->
//                        sharedRemoteView?.clearImage()
//                        try {
//                            track.addSink(sharedRemoteView)
//                            Log.d("WebRTCService", "Reattached video track to sharedRemoteView: ${track.id()}")
//                            sendVideoTrackBroadcast(track.id())
//                        } catch (e: Exception) {
//                            Log.e("WebRTCService", "Error reattaching video track: ${e.message}")
//                        }
//                        sendVideoTrackBroadcast(track.id())
//                    }
//                } else {
//                    currentVideoTrack?.let { track ->
//                        sendVideoTrackLostBroadcast(track.id())
//                    }
//                }
//            }
//            handler.postDelayed(this, 5000)
//        }
//    }

    private fun adjustVideoQualityBasedOnStats() {
        webRTCClient.peerConnection?.getStats { statsReport ->
            try {
                var videoPacketsLost = 0L
                var videoPacketsSent = 0L
                var availableSendBandwidth = 0L
                var roundTripTime = 0.0

                statsReport.statsMap.values.forEach { stats ->
                    when {
                        stats.type == "outbound-rtp" && stats.id.contains("video") -> {
                            videoPacketsLost += stats.members["packetsLost"] as? Long ?: 0L
                            videoPacketsSent += stats.members["packetsSent"] as? Long ?: 1L
                        }
                        stats.type == "candidate-pair" && stats.members["state"] == "succeeded" -> {
                            availableSendBandwidth = stats.members["availableOutgoingBitrate"] as? Long ?: 0L
                            roundTripTime = stats.members["currentRoundTripTime"] as? Double ?: 0.0
                        }
                    }
                }

                if (videoPacketsSent > 0) {
                    val lossRate = videoPacketsLost.toDouble() / videoPacketsSent.toDouble()
                    Log.d("WebRTCService", "Packet loss: $lossRate, Bandwidth: $availableSendBandwidth, RTT: $roundTripTime")
                    handler.post {
                        when {
                            lossRate > 0.05 || roundTripTime > 0.5 -> reduceVideoQuality() // >5% потерь или RTT > 500ms
                            lossRate < 0.02 && availableSendBandwidth > 1000000 -> increaseVideoQuality() // <2% потерь и >1Mbps
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WebRTCService", "Error processing stats", e)
            }
        }
    }

    private fun reduceVideoQuality() {
        try {
            webRTCClient.videoCapturer?.let { capturer ->
                capturer.stopCapture()
                capturer.startCapture(480, 360, 15)
                webRTCClient.setVideoEncoderBitrate(300000, 400000, 500000)
                Log.d("WebRTCService", "Reduced video quality to 480x360@15fps, 200kbps")
            }
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error reducing video quality", e)
        }
    }

    private fun increaseVideoQuality() {
        try {
            webRTCClient.videoCapturer?.let { capturer ->
                capturer.stopCapture()
                capturer.startCapture(640, 360, 15)
                webRTCClient.setVideoEncoderBitrate(600000, 800000, 1000000)
                Log.d("WebRTCService", "Increased video quality to 854x480@20fps, 800kbps")
            }
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error increasing video quality", e)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // Инициализация имени комнаты из статического поля
        roomName = currentRoomName

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, WebRTCService::class.java).apply {
            action = "CHECK_CONNECTION"
        }
        val pendingIntent = PendingIntent.getService(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        handler.post(healthCheckRunnable)

        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            pendingIntent
        )

        Log.d("WebRTCService", "Service created with room: $roomName")
        sendServiceStateUpdate()
        handler.post(bandwidthEstimationRunnable)
//        handler.post(videoTrackCheckRunnable)
        try {
            registerReceiver(connectivityReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            isConnectivityReceiverRegistered = true
            registerReceiver(stateReceiver, IntentFilter(ACTION_SERVICE_STATE))
            isStateReceiverRegistered = true
            registerReceiver(videoTrackReceiver, IntentFilter("com.example.ardua.REQUEST_VIDEO_TRACK"))
            isVideoTrackReceiverRegistered = true
            createNotificationChannel()
            startForegroundService()
            initializeWebRTC()
            connectWebSocket()
            registerNetworkCallback()

            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            try {
                // Ищем камеру с поддержкой фонарика
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val hasFlash = characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    if (hasFlash) {
                        flashlightCameraId = findAvailableFlashlightCamera()
                        Log.d("WebRTCService", "Камера с фонариком найдена: $id")
                        break
                    }
                }
                if (flashlightCameraId == null) {
                    Log.w("WebRTCService", "Фонарик не найден на устройстве")
                }
            } catch (e: CameraAccessException) {
                Log.e("WebRTCService", "Ошибка доступа к CameraManager: ${e.message}")
            }
            textToSpeech = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = textToSpeech?.setLanguage(Locale("ru_RU"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e("WebRTCService", "Русский язык не поддерживается для TextToSpeech, переключаемся на английский")
                        textToSpeech?.setLanguage(Locale.US)
                    } else {
                        Log.d("WebRTCService", "TextToSpeech успешно инициализирован для русского языка")
                        textToSpeech?.let { tts ->
                            // Выводим список доступных голосов для отладки
                            val voices = tts.voices?.filter { it.locale == Locale("ru_RU") } ?: emptyList()
                            Log.d("WebRTCService", "Доступные голоса для ru_RU: ${voices.map { "${it.name} (Качество: ${it.quality}, Пол: ${if (it.name.contains("female", true) || it.name.contains("Standard-A") || it.name.contains("Wavenet-A")) "женский" else "мужской"})" }}")

                            // Выбираем женский голос
                            val preferredVoice = voices.firstOrNull { voice ->
                                voice.name.contains("female", ignoreCase = true) ||
                                        voice.name.contains("ru-RU-Standard-A") || // Женский голос Google TTS
                                        voice.name.contains("ru-RU-Wavenet-A")
                            } ?: voices.firstOrNull() // Если женский голос не найден, берём первый доступный

                            if (preferredVoice != null) {
                                val voiceResult = tts.setVoice(preferredVoice)
                                if (voiceResult == TextToSpeech.SUCCESS) {
                                    Log.d("WebRTCService", "Установлен голос: ${preferredVoice.name}")
                                } else {
                                    Log.e("WebRTCService", "Ошибка установки голоса ${preferredVoice.name}: $voiceResult")
                                }
                                // Устанавливаем скорость воспроизведения
                                val speechRate = 1.5f // Нормальная скорость, можно изменить на 0.8f (медленнее) или 1.2f (быстрее)
                                val rateResult = tts.setSpeechRate(speechRate)
                                if (rateResult == TextToSpeech.SUCCESS) {
                                    Log.d("WebRTCService", "Скорость воспроизведения установлена: $speechRate")
                                } else {
                                    Log.e("WebRTCService", "Ошибка установки скорости воспроизведения: $rateResult")
                                }
                            } else {
                                Log.w("WebRTCService", "Голоса для ru_RU не найдены, используется голос по умолчанию")
                                // Уведомляем UI об ошибке
                                val intent = Intent("com.example.ardua.TTS_ERROR")
                                intent.putExtra("message", "Голоса для ru_RU не найдены")
                                sendBroadcast(intent)
                            }
                        }
                    }
                } else {
                    Log.e("WebRTCService", "Ошибка инициализации TextToSpeech: $status")
                    // Уведомляем UI об ошибке
                    val intent = Intent("com.example.ardua.TTS_ERROR")
                    intent.putExtra("message", "Ошибка инициализации TextToSpeech: $status")
                    sendBroadcast(intent)
                }
            }
        } catch (e: Exception) {
            Log.e("WebRTCService", "Initialization failed", e)
            stopSelf()
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(networkCallback)
        } else {
            val request = NetworkRequest.Builder().build()
            cm.registerNetworkCallback(request, networkCallback)
        }
    }

    private fun isServiceActive(): Boolean {
        return ::webSocketClient.isInitialized && webSocketClient.isConnected()
    }

    private fun startForegroundService() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } catch (e: SecurityException) {
                Log.e("WebRTCService", "SecurityException: ${e.message}")
                startForeground(notificationId, notification)
            }
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun initializeWebRTC() {
        if (isInitializing) {
            Log.w("WebRTCService", "Initialization already in progress, skipping")
            return
        }
        isInitializing = true
        Log.d("WebRTCService", "Initializing new WebRTC connection")
        try {
            cleanupWebRTCResources()
            // Отправляем CONNECTION_LOST перед инициализацией
            sendConnectionLostBroadcast()
            WebRTCService.currentVideoTrack?.let { sendVideoTrackLostBroadcast(it.id()) }
            WebRTCService.currentVideoTrack = null

            eglBase = EglBase.create()
            isEglBaseReleased = false
            val localView = SurfaceViewRenderer(this).apply {
                init(eglBase.eglBaseContext, null)
                setMirror(true)
                setZOrderMediaOverlay(true)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
            sharedRemoteView = SurfaceViewRenderer(this).apply {
                try {
                    init(eglBase.eglBaseContext, null)
                    setZOrderMediaOverlay(true)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                    Log.d("WebRTCService", "sharedRemoteView initialized successfully")
                } catch (e: Exception) {
                    Log.e("WebRTCService", "Failed to initialize sharedRemoteView: ${e.message}")
                    throw e
                }
            }
            webRTCClient = WebRTCClient(
                context = this,
                eglBase = eglBase,
                localView = localView,
                remoteView = sharedRemoteView!!,
                observer = createPeerConnectionObserver()
            )
            webRTCClient.setVideoEncoderBitrate(300000, 400000, 500000)
            Log.d("WebRTCService", "WebRTCClient initialized, peerConnection state: ${webRTCClient.peerConnection?.signalingState()}")
            Log.d("WebRTCService", "Video capturer initialized: ${webRTCClient.videoCapturer != null}")
        } catch (e: Exception) {
            Log.e("WebRTCService", "Failed to initialize WebRTCClient", e)
            throw e
        } finally {
            isInitializing = false
        }
    }

    private fun findAvailableFlashlightCamera(): String? {
        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    Log.d("WebRTCService", "Тыльная камера с фонариком: $id")
                    return id
                }
            }
            Log.w("WebRTCService", "Камера с фонариком не найдена")
            return null
        } catch (e: CameraAccessException) {
            Log.e("WebRTCService", "Ошибка поиска камеры: ${e.message}")
            return null
        }
    }

    private fun isCameraAvailable(cameraId: String): Boolean {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics != null
        } catch (e: CameraAccessException) {
            Log.e("WebRTCService", "Ошибка проверки доступности камеры: ${e.message}")
            false
        }
    }

    private fun toggleFlashlight() {
        flashlightCameraId = findAvailableFlashlightCamera()
        if (flashlightCameraId == null) {
            Log.w("WebRTCService", "Фонарик недоступен")
            return
        }

        val sharedPrefs = getSharedPreferences("WebRTCPrefs", Context.MODE_PRIVATE)
        val useBackCamera = sharedPrefs.getBoolean("useBackCamera", false)
        Log.d("WebRTCService", "Начало переключения фонарика, cameraId: $flashlightCameraId, isCameraAvailable: ${isCameraAvailable(flashlightCameraId!!)}, useBackCamera: $useBackCamera, isFlashlightOn: $isFlashlightOn")

        try {
            // Если включаем фонарик и используем тыльную камеру, останавливаем захват
            if (!isFlashlightOn && useBackCamera) {
                Log.d("WebRTCService", "Остановка захвата видео для тыльной камеры")
                webRTCClient.videoCapturer?.stopCapture()
            }

            // Задержка для освобождения камеры или немедленное действие для фронтальной камеры
            handler.postDelayed({
                try {
                    isFlashlightOn = !isFlashlightOn
                    if (isCameraAvailable(flashlightCameraId!!)) {
                        cameraManager.setTorchMode(flashlightCameraId!!, isFlashlightOn)
                        Log.d("WebRTCService", "Фонарик ${if (isFlashlightOn) "включен" else "выключен"}")
                    } else {
                        Log.w("WebRTCService", "Камера $flashlightCameraId недоступна, повторная попытка")
                        handler.postDelayed({
                            try {
                                cameraManager.setTorchMode(flashlightCameraId!!, isFlashlightOn)
                                Log.d("WebRTCService", "Фонарик ${if (isFlashlightOn) "включен" else "выключен"} после повторной попытки")
                            } catch (e: CameraAccessException) {
                                Log.e("WebRTCService", "Ошибка повторной попытки: ${e.message}, reason: ${e.reason}")
                                isFlashlightOn = !isFlashlightOn
                            }
                        }, 200)
                    }

                    // Возобновляем захват видео только если выключаем фонарик и используем тыльную камеру
                    if (!isFlashlightOn && useBackCamera) {
                        handler.postDelayed({
                            try {
                                webRTCClient.videoCapturer?.startCapture(640, 480, 20)
                                Log.d("WebRTCService", "Возобновление захвата видео")
                            } catch (e: Exception) {
                                Log.e("WebRTCService", "Ошибка возобновления захвата: ${e.message}")
                            }
                        }, 500)
                    }
                } catch (e: CameraAccessException) {
                    Log.e("WebRTCService", "Ошибка фонарика: ${e.message}, reason: ${e.reason}")
                    isFlashlightOn = !isFlashlightOn
                    // Возобновляем захват, если фонарик не включился и используем тыльную камеру
                    if (!isFlashlightOn && useBackCamera) {
                        handler.postDelayed({
                            try {
                                webRTCClient.videoCapturer?.startCapture(640, 480, 20)
                                Log.d("WebRTCService", "Возобновление захвата после ошибки")
                            } catch (startError: Exception) {
                                Log.e("WebRTCService", "Ошибка возобновления захвата: ${startError.message}")
                            }
                        }, 500)
                    }
                }
            }, if (useBackCamera && !isFlashlightOn) 500 else 0) // Задержка только для тыльной камеры при включении
        } catch (e: Exception) {
            Log.e("WebRTCService", "Общая ошибка: ${e.message}")
        } finally {
            Log.d("WebRTCService", "Завершение переключения фонарика, isFlashlightOn: $isFlashlightOn")
        }
    }

    private fun createPeerConnectionObserver() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let {
                Log.d("WebRTCService", "Local ICE candidate: ${it.sdpMid}:${it.sdpMLineIndex} ${it.sdp}")
                sendIceCandidate(it)
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d("WebRTCService", "ICE connection state changed to: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    updateNotification("Connection established")
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    updateNotification("Connection lost")
                    handler.post {
                        sendConnectionLostBroadcast()
                        WebRTCService.currentVideoTrack?.let { sendVideoTrackLostBroadcast(it.id()) }
                        WebRTCService.currentVideoTrack = null
                    }
                    scheduleReconnect()
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    Log.e("WebRTCService", "ICE connection failed")
                    handler.post {
                        sendConnectionLostBroadcast()
                        WebRTCService.currentVideoTrack?.let { sendVideoTrackLostBroadcast(it.id()) }
                        WebRTCService.currentVideoTrack = null
                    }
                    scheduleReconnect()
                }
                else -> {}
            }
        }

        override fun onAddStream(stream: MediaStream?) {
            Log.d("WebRTCService", "onAddStream called, stream=$stream")
            stream?.videoTracks?.forEach { track ->
                Log.d("WebRTCService", "onAddStream: Video track ID=${track.id()}, Enabled=${track.enabled()}, State=${track.state()}")
                handler.post {
                    WebRTCService.currentVideoTrack = track
                    sharedRemoteView?.clearImage()
                    try {
                        track.addSink(sharedRemoteView)
                        Log.d("WebRTCService", "Video track added to sharedRemoteView sink")
                        sendVideoTrackBroadcast(track.id())
                    } catch (e: Exception) {
                        Log.e("WebRTCService", "Error adding video track to sink: ${e.message}")
                        sendVideoTrackLostBroadcast(track.id())
                    }
                }
            }
            stream?.audioTracks?.forEach { track ->
                Log.d("WebRTCService", "onAddStream: Audio track ID=${track.id()}, Enabled=${track.enabled()}, State=${track.state()}")
            }
            if (stream?.videoTracks?.isEmpty() == true) {
                Log.w("WebRTCService", "onAddStream: No video tracks in stream")
                handler.post {
                    WebRTCService.currentVideoTrack?.let { sendVideoTrackLostBroadcast(it.id()) }
                    WebRTCService.currentVideoTrack = null
                }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            transceiver?.receiver?.track()?.let { track ->
                Log.d("WebRTCService", "onTrack called, track kind=${track.kind()}, ID=${track.id()}, Enabled=${track.enabled()}, State=${track.state()}")
                handler.post {
                    if (track.kind() == "video") {
                        WebRTCService.currentVideoTrack = track as VideoTrack
                        sharedRemoteView?.clearImage()
                        try {
                            (track as VideoTrack).addSink(sharedRemoteView)
                            Log.d("WebRTCService", "Video track added to sharedRemoteView sink in onTrack")
                            sendVideoTrackBroadcast(track.id())
                        } catch (e: Exception) {
                            Log.e("WebRTCService", "Error adding video track to sink in onTrack: ${e.message}")
                            sendVideoTrackLostBroadcast(track.id())
                        }
                    } else if (track.kind() == "audio") {
                        Log.d("WebRTCService", "Audio track received, ID=${track.id()}")
                    }
                }
            } ?: run {
                Log.w("WebRTCService", "onTrack: No track received in transceiver")
                handler.post {
                    WebRTCService.currentVideoTrack?.let { sendVideoTrackLostBroadcast(it.id()) }
                    WebRTCService.currentVideoTrack = null
                    sendConnectionLostBroadcast()
                }
            }
        }

        override fun onRemoveStream(stream: MediaStream?) {
            stream?.videoTracks?.forEach { track ->
                Log.d("WebRTCService", "Removing remote video track: ${track.id()}")
                handler.post {
                    if (track == currentVideoTrack) {
                        currentVideoTrack = null
                        remoteView.clearImage()
                        sendVideoTrackLostBroadcast(track.id())
                    }
                }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d("WebRTCService", "Signaling state changed to: $state")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d("WebRTCService", "ICE connection receiving change: $receiving")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d("WebRTCService", "ICE gathering state changed to: $state")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
            Log.d("WebRTCService", "ICE candidates removed: ${candidates?.joinToString()}")
        }

        override fun onDataChannel(channel: DataChannel?) {
            Log.d("WebRTCService", "Data channel created: ${channel?.label()}")
        }

        override fun onRenegotiationNeeded() {
            Log.d("WebRTCService", "Renegotiation needed")
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            Log.d("WebRTCService", "onAddTrack called, receiver=$receiver, streams=$streams")
        }
    }

    private fun sendVideoTrackBroadcast(trackId: String) {
        Log.d("WebRTCService", "Sending REMOTE_VIDEO_AVAILABLE broadcast for track: $trackId")
        val intent = Intent("com.example.ardua.REMOTE_VIDEO_AVAILABLE")
        intent.putExtra("video_track_id", trackId)
        sendBroadcast(intent)
    }

    private fun sendVideoTrackLostBroadcast(trackId: String) {
        Log.d("WebRTCService", "Sending REMOTE_VIDEO_LOST broadcast for track: $trackId")
        val intent = Intent("com.example.ardua.REMOTE_VIDEO_LOST")
        intent.putExtra("video_track_id", trackId)
        sendBroadcast(intent)
    }

    private fun sendConnectionLostBroadcast() {
        Log.d("WebRTCService", "Sending CONNECTION_LOST broadcast")
        val intent = Intent(ACTION_CONNECTION_LOST)
        sendBroadcast(intent)
    }

    private val videoTrackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.example.ardua.REQUEST_VIDEO_TRACK") {
                Log.d("WebRTCService", "Received REQUEST_VIDEO_TRACK broadcast")
                handler.post {
                    WebRTCService.currentVideoTrack?.let { track ->
                        if (track.enabled() && track.state() == MediaStreamTrack.State.LIVE) {
                            sharedRemoteView?.clearImage()
                            try {
                                track.addSink(sharedRemoteView)
                                Log.d("WebRTCService", "Reattached video track: ${track.id()}")
                                sendVideoTrackBroadcast(track.id())
                            } catch (e: Exception) {
                                Log.e("WebRTCService", "Error reattaching video track: ${e.message}")
                                sendVideoTrackLostBroadcast(track.id())
                            }
                        } else {
                            Log.w("WebRTCService", "Video track not active: Enabled=${track.enabled()}, State=${track.state()}")
                            sendVideoTrackLostBroadcast(track.id())
                        }
                    } ?: run {
                        Log.w("WebRTCService", "No current video track to reattach")
                        sendVideoTrackLostBroadcast("unknown")
                        // Пробуем перезапустить захват видео
                        try {
                            webRTCClient.videoCapturer?.let { capturer ->
                                capturer.stopCapture()
                                capturer.startCapture(640, 480, 20)
                                Log.d("WebRTCService", "Restarted video capture on REQUEST_VIDEO_TRACK")
                            }
                        } catch (e: Exception) {
                            Log.e("WebRTCService", "Error restarting video capture: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private var isCleaningUp = false
    private var isInitializing = false


    private fun cleanupWebRTCResources() {
        if (isCleaningUp) {
            Log.w("WebRTCService", "Cleanup already in progress, skipping")
            return
        }
        isCleaningUp = true
        try {
            if (::webRTCClient.isInitialized) {
                webRTCClient.close()
                Log.d("WebRTCService", "WebRTCClient closed")
            }
            if (::eglBase.isInitialized && !isEglBaseReleased) {
                eglBase.release()
                isEglBaseReleased = true
                Log.d("WebRTCService", "EglBase released")
            }
            // Не очищаем sharedRemoteView полностью, только убираем видеопоток
            sharedRemoteView?.clearImage()
            currentVideoTrack = null
            Log.d("WebRTCService", "Cleared current video track")
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error cleaning WebRTC resources", e)
        } finally {
            isCleaningUp = false
        }
    }

    private fun sendMessage(message: JSONObject) {
        try {
            if (::webSocketClient.isInitialized && webSocketClient.isConnected()) {
                webSocketClient.send(message.toString())
                Log.d("WebRTCService", "Sent message: $message")
            } else {
                Log.w("WebRTCService", "Cannot send message: WebSocket not connected")
            }
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error sending message", e)
        }
    }

    private fun connectWebSocket() {
        if (isConnected || isConnecting) {
            Log.d("WebRTCService", "Already connected or connecting, skipping")
            return
        }

        isConnecting = true
        webSocketClient = WebSocketClient(webSocketListener)
        try {
            webSocketClient.connect(webSocketUrl)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error connecting WebSocket", e)
            isConnecting = false
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (isUserStopped) {
            Log.d("WebRTCService", "Service stopped by user, not reconnecting")
            return
        }

        handler.removeCallbacksAndMessages(null)

        reconnectAttempts++
        val delay = when {
            reconnectAttempts < 5 -> 5000L
            reconnectAttempts < 10 -> 15000L
            else -> 60000L
        }

        Log.d("WebRTCService", "Scheduling reconnect in ${delay/1000} seconds (attempt $reconnectAttempts)")
        updateNotification("Reconnecting in ${delay/1000}s...")

        handler.postDelayed({
            reconnect()
        }, delay)
    }

    private fun reconnect() {
        if (isConnected || isConnecting) {
            Log.d("WebRTCService", "Already connected or connecting, skipping manual reconnect")
            return
        }

        Log.d("WebRTCService", "Starting reconnect process, attempt: $reconnectAttempts")
        handler.post {
            try {
                Log.d("WebRTCService", "Starting reconnect process")

                // Отправляем CONNECTION_LOST перед пересозданием
                sendConnectionLostBroadcast()
                WebRTCService.currentVideoTrack?.let { sendVideoTrackLostBroadcast(it.id()) }
                WebRTCService.currentVideoTrack = null

                // Получаем последнее сохраненное имя комнаты
                val sharedPrefs = getSharedPreferences("WebRTCPrefs", Context.MODE_PRIVATE)
                val lastRoomName = sharedPrefs.getString("last_used_room", "")

                // Если имя комнаты пустое, используем дефолтное значение
                roomName = if (lastRoomName.isNullOrEmpty()) {
                    "default_room_${System.currentTimeMillis()}"
                } else {
                    lastRoomName
                }

                // Обновляем текущее имя комнаты
                currentRoomName = roomName
                Log.d("WebRTCService", "Reconnecting to room: $roomName")

                // Очищаем предыдущие соединения
                if (::webSocketClient.isInitialized) {
                    webSocketClient.disconnect()
                }

                // Инициализируем заново
                initializeWebRTC()
                connectWebSocket()

            } catch (e: Exception) {
                Log.e("WebRTCService", "Reconnection error", e)
                isConnecting = false
                scheduleReconnect()
            }
        }
    }

    private fun joinRoom() {
        try {
            val message = JSONObject().apply {
                put("action", "join")
                put("room", roomName)
                put("username", userName)
                put("isLeader", true)
                put("preferredCodec", "VP8")
            }
            webSocketClient.send(message.toString())
            Log.d("WebRTCService", "Sent join request for room: $roomName")
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error joining room: $roomName", e)
        }
    }

    private fun handleBandwidthEstimation(estimation: Long) {
        handler.post {
            try {
                // Адаптируем качество видео в зависимости от доступной полосы
                val width = when {
                    estimation > 1500000 -> 1280 // 1.5 Mbps+
                    estimation > 500000 -> 854  // 0.5-1.5 Mbps
                    else -> 640                // <0.5 Mbps
                }

                val height = (width * 9 / 16)

                webRTCClient.videoCapturer?.let { capturer ->
                    capturer.stopCapture()
                    capturer.startCapture(width, height, 24)
                    Log.d("WebRTCService", "Adjusted video to ${width}x${height} @24fps")
                }
            } catch (e: Exception) {
                Log.e("WebRTCService", "Error adjusting video quality", e)
            }
        }
    }

    private fun handleWebSocketMessage(message: JSONObject) {
        Log.d("WebRTCService", "Received: $message")
        try {
            when (message.optString("type")) {
                "rejoin_and_offer" -> {
                    Log.d("WebRTCService", "Received rejoin_and_offer with codec: VP8")
                    handler.post {
                        cleanupWebRTCResources()
                        initializeWebRTC()
                        createOffer("VP8")
                    }
                }
                "create_offer_for_new_follower" -> {
                    Log.d("WebRTCService", "Received request to create offer for new follower")
                    handler.post {
                        createOffer("VP8")
                    }
                }
                "bandwidth_estimation" -> {
                    val estimation = message.optLong("estimation", 1000000)
                    handleBandwidthEstimation(estimation)
                }
                "offer" -> {
                    // Удаляем проверку isLeader
                    handleOffer(message)
                }
                "answer" -> handleAnswer(message)
                "ice_candidate" -> handleIceCandidate(message)
                "room_info" -> {}
                "track_received" -> {
                    val data = message.getJSONObject("data")
                    val trackId = data.getString("trackId")
                    val kind = data.getString("kind")
                    Log.d("WebRTCService", "Track received: trackId=$trackId, kind=$kind")
                    if (kind == "video") {
                        sendVideoTrackBroadcast(trackId)
                    }
                }
                "switch_camera" -> {
                    val useBackCamera = message.optBoolean("useBackCamera", false)
                    Log.d("WebRTCService", "Received switch camera command: useBackCamera=$useBackCamera")
                    handler.post {
                        try {
                            if (!::webRTCClient.isInitialized) {
                                Log.e("WebRTCService", "WebRTCClient not initialized, cannot switch camera")
                                sendCameraSwitchAck(useBackCamera, success = false)
                                return@post
                            }
                            webRTCClient.switchCamera(useBackCamera)
                            // Перезапускаем захват видео
                            webRTCClient.videoCapturer?.let { capturer ->
                                capturer.stopCapture()
                                capturer.startCapture(640, 480, 20)
                                Log.d("WebRTCService", "Video capture restarted after camera switch")
                            }
                            Log.d("WebRTCService", "Switch camera command executed for useBackCamera=$useBackCamera")
                            sendCameraSwitchAck(useBackCamera)
                            // Отправляем запрос на обновление видеопотока
                            handler.postDelayed({
                                currentVideoTrack?.let {
                                    if (it.enabled() && it.state() == MediaStreamTrack.State.LIVE) {
                                        sendVideoTrackBroadcast(it.id())
                                    } else {
                                        sendVideoTrackLostBroadcast(it.id())
                                    }
                                }
                            }, 1000)
                        } catch (e: Exception) {
                            Log.e("WebRTCService", "Error switching camera: ${e.message}")
                            sendCameraSwitchAck(useBackCamera, success = false)
                        }
                    }
                }
                "toggle_flashlight" -> {
                    Log.d("WebRTCService", "Received toggle_flashlight command")
                    handler.post { toggleFlashlight() }
                }
                "transcript" -> {
                    val transcript = message.optString("data", "")
                    val room = message.optString("room", "")
                    Log.d("WebRTCService", "Received transcript: text='$transcript', room='$room'")
                    handler.post {
                        // Отправляем текст в UI через BroadcastReceiver
                        val intent = Intent("com.example.TRANSCRIPT_RECEIVED")
                        intent.putExtra("transcript", transcript)
                        intent.putExtra("room", room)
                        sendBroadcast(intent)
                        Log.d("WebRTCService", "Broadcasted transcript: $transcript")
                        if (transcript.isNotEmpty()) {
                            textToSpeech?.let { tts ->
                                if (tts.isSpeaking) {
                                    tts.stop()
                                }
                                tts.speak(transcript, TextToSpeech.QUEUE_FLUSH, null, "transcript_${System.currentTimeMillis()}")
                                Log.d("WebRTCService", "Синтезирован текст: $transcript")
                            } ?: Log.w("WebRTCService", "TextToSpeech не инициализирован, текст не синтезирован")
                        }
                    }
                }
                else -> Log.w("WebRTCService", "Unknown message type")
            }
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error handling message", e)
        }
    }

    private fun normalizeSdpForCodec(sdp: String, targetCodec: String, targetBitrateAs: Int = 300): String {
        Log.d("WebRTCService", "Normalizing SDP for codec: $targetCodec")
        val lines = sdp.split("\r\n").toMutableList()
        val videoSectionIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (videoSectionIndex == -1) {
            Log.e("WebRTCService", "No video section found in SDP")
            return sdp
        }
        val videoLineParts = lines[videoSectionIndex].split(" ")
        if (videoLineParts.size < 4) {
            Log.e("WebRTCService", "Invalid video section: ${lines[videoSectionIndex]}")
            return sdp
        }
        val payloadTypes = videoLineParts.drop(3)
        var targetPayloadType: String? = null
        for (i in lines.indices) {
            if (lines[i].startsWith("a=rtpmap:") && lines[i].contains(targetCodec, ignoreCase = true)) {
                val parts = lines[i].split(" ")
                if (parts.size >= 2) {
                    targetPayloadType = parts[0].substringAfter("a=rtpmap:").substringBefore(" ")
                    break
                }
            }
        }
        if (targetPayloadType == null) {
            Log.w("WebRTCService", "$targetCodec not found in SDP, leaving SDP unchanged")
            return sdp
        }
        val newPayloadTypes = mutableListOf(targetPayloadType).apply {
            addAll(payloadTypes.filter { it != targetPayloadType })
        }
        lines[videoSectionIndex] = "${videoLineParts.take(3).joinToString(" ")} ${newPayloadTypes.joinToString(" ")}"
        if (targetBitrateAs > 0) {
            val bLine = "b=AS:$targetBitrateAs"
            if (!lines.contains(bLine)) {
                lines.add(videoSectionIndex + 1, bLine)
                Log.d("WebRTCService", "Added bitrate constraint: $bLine")
            }
        }
        // Проверяем наличие rtcp-fb
        val rtcpFb = lines.filter { it.startsWith("a=rtcp-fb:$targetPayloadType") }
        if (rtcpFb.isEmpty()) {
            lines.add(videoSectionIndex + 2, "a=rtcp-fb:$targetPayloadType ccm fir")
            lines.add(videoSectionIndex + 3, "a=rtcp-fb:$targetPayloadType nack")
            lines.add(videoSectionIndex + 4, "a=rtcp-fb:$targetPayloadType nack pli")
            Log.d("WebRTCService", "Added rtcp-fb for $targetPayloadType")
        }
        val modifiedSdp = lines.joinToString("\r\n")
        Log.d("WebRTCService", "Modified SDP:\n$modifiedSdp")
        return modifiedSdp
    }

    private fun createOffer(preferredCodec: String = "VP8") {
        try {
            if (!::webRTCClient.isInitialized || !isConnected || webRTCClient.peerConnection == null) {
                Log.e("WebRTCService", "Cannot create offer - not initialized, not connected, or PeerConnection is null")
                return
            }

            Log.d("WebRTCService", "Creating offer with preferred codec: VP8, PeerConnection state: ${webRTCClient.peerConnection?.signalingState()}")
            if (webRTCClient.peerConnection?.signalingState() == PeerConnection.SignalingState.CLOSED) {
                Log.e("WebRTCService", "PeerConnection is closed, reinitializing")
                cleanupWebRTCResources()
                initializeWebRTC()
            }

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googCpuOveruseDetection", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googScreencastMinBitrate", "300"))
            }

            webRTCClient.peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) {
                        Log.e("WebRTCService", "Created SessionDescription is NULL")
                        return
                    }

                    Log.d("WebRTCService", "Original Local Offer SDP:\n${desc.description}")
                    val modifiedSdp = normalizeSdpForCodec(desc.description, "VP8", 300)
                    Log.d("WebRTCService", "Modified Local Offer SDP:\n$modifiedSdp")

                    if (!isValidSdp(modifiedSdp, "VP8")) {
                        Log.e("WebRTCService", "Invalid modified SDP, falling back to original")
                        setLocalDescription(desc)
                        return
                    }

                    val modifiedDesc = SessionDescription(desc.type, modifiedSdp)
                    setLocalDescription(modifiedDesc)
                }

                override fun onCreateFailure(error: String?) {
                    Log.e("WebRTCService", "Error creating offer: $error")
                }

                override fun onSetSuccess() {
                    Log.d("WebRTCService", "Offer created successfully")
                }

                override fun onSetFailure(error: String?) {
                    Log.e("WebRTCService", "Error setting offer: $error")
                }
            }, constraints)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error in createOffer", e)
        }
    }

    private fun setLocalDescription(desc: SessionDescription) {
        webRTCClient.peerConnection?.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d("WebRTCService", "Successfully set local description")
                sendSessionDescription(desc)
            }

            override fun onSetFailure(error: String?) {
                Log.e("WebRTCService", "Error setting local description: $error")
                // Пробуем реинициализацию
                handler.postDelayed({
                    cleanupWebRTCResources()
                    initializeWebRTC()
                    createOffer()
                }, 2000)
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, desc)
    }

    private fun sendCameraSwitchAck(useBackCamera: Boolean, success: Boolean = true) {
        try {
            val message = JSONObject().apply {
                put("type", "switch_camera_ack")
                put("useBackCamera", useBackCamera)
                put("success", success)
                put("room", roomName)
                put("username", userName)
            }
            webSocketClient.send(message.toString())
            Log.d("WebRTCService", "Sent camera switch ack: success=$success, useBackCamera=$useBackCamera")
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error sending camera switch ack: ${e.message}")
        }
    }

    private fun handleOffer(offer: JSONObject) {
        try {
            val sdp = offer.getJSONObject("sdp")
            val sdpString = sdp.getString("sdp")
            if (!sdpString.contains("m=video")) {
                Log.e("WebRTCService", "Offer SDP lacks video section: $sdpString")
                return
            }
            val sessionDescription = SessionDescription(
                SessionDescription.Type.OFFER,
                sdpString
            )
            val preferredCodec = "VP8"

            Log.d("WebRTCService", "Received offer: ${sessionDescription.description}")
            Log.d("WebRTCService", "PeerConnection state before setting offer: ${webRTCClient.peerConnection?.signalingState()}")

            if (!sessionDescription.description.contains("m=audio")) {
                Log.w("WebRTCService", "Offer SDP does not contain audio section")
            }

            if (webRTCClient.peerConnection == null || webRTCClient.peerConnection?.signalingState() == PeerConnection.SignalingState.CLOSED) {
                Log.e("WebRTCService", "PeerConnection is null or closed, reinitializing")
                cleanupWebRTCResources()
                initializeWebRTC()
            }

            webRTCClient.peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d("WebRTCService", "Remote description set successfully for offer")
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                    }
                    createAnswer(constraints, preferredCodec)
                }

                override fun onSetFailure(error: String) {
                    Log.e("WebRTCService", "Error setting remote description: $error")
                    handler.postDelayed({
                        cleanupWebRTCResources()
                        initializeWebRTC()
                        createOffer("VP8")
                    }, 2000)
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(error: String) {}
            }, sessionDescription)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error handling offer", e)
            handler.postDelayed({
                cleanupWebRTCResources()
                initializeWebRTC()
                createOffer("VP8")
            }, 2000)
        }
    }

    private fun createAnswer(constraints: MediaConstraints, preferredCodec: String = "VP8") {
        try {
            if (webRTCClient.peerConnection == null) {
                Log.e("WebRTCService", "Cannot create answer: PeerConnection is null")
                return
            }
            Log.d("WebRTCService", "Creating answer with codec: $preferredCodec")
            webRTCClient.peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) {
                        Log.e("WebRTCService", "Created SessionDescription is NULL")
                        return
                    }
                    Log.d("WebRTCService", "Original Local Answer SDP:\n${desc.description}")
                    val modifiedSdp = normalizeSdpForCodec(desc.description, "VP8", 300)
                    Log.d("WebRTCService", "Modified Local Answer SDP:\n$modifiedSdp")
                    if (!isValidSdp(modifiedSdp, "VP8") || !modifiedSdp.contains("m=audio")) {
                        Log.e("WebRTCService", "Invalid modified SDP or no audio section, falling back to original")
                        setLocalDescription(desc)
                        return
                    }
                    val modifiedDesc = SessionDescription(desc.type, modifiedSdp)
                    setLocalDescription(modifiedDesc)
                    // Отправляем answer клиенту
                    val answerJson = JSONObject().apply {
                        put("type", "answer")
                        put("sdp", mapOf("type" to "answer", "sdp" to modifiedSdp))
                    }
                    sendMessage(answerJson) // Предполагается, что метод sendMessage отправляет JSON на сервер
                }
                override fun onCreateFailure(error: String?) {
                    Log.e("WebRTCService", "Error creating answer: $error")
                    handler.postDelayed({
                        cleanupWebRTCResources()
                        initializeWebRTC()
                        createOffer("VP8")
                    }, 2000)
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, constraints)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error creating answer", e)
            handler.postDelayed({
                cleanupWebRTCResources()
                initializeWebRTC()
                createOffer("VP8")
            }, 2000)
        }
    }

    private fun sendSessionDescription(desc: SessionDescription) {
        Log.d("WebRTCService", "Sending SDP: ${desc.type} \n${desc.description}")
        try {
            val codec = when {
                desc.description.contains("a=rtpmap:.*H264") -> "H264"
                desc.description.contains("a=rtpmap:.*VP8") -> "VP8"
                else -> "Unknown"
            }

            val message = JSONObject().apply {
                put("type", desc.type.canonicalForm())
                put("sdp", JSONObject().apply {
                    put("type", desc.type.canonicalForm())
                    put("sdp", desc.description)
                })
                put("codec", codec)
                put("room", roomName)
                put("username", userName)
                put("target", "browser")
            }
            Log.d("WebRTCService", "Sending JSON: $message")
            webSocketClient.send(message.toString())
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error sending SDP", e)
        }
    }

    private fun handleAnswer(answer: JSONObject) {
        try {
            val sdp = answer.getJSONObject("sdp")
            val sessionDescription = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdp.getString("type")),
                sdp.getString("sdp")
            )
            Log.d("WebRTCService", "Received answer SDP:\n${sessionDescription.description}")
            webRTCClient.peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d("WebRTCService", "Answer accepted, connection should be established")
                }
                override fun onSetFailure(error: String) {
                    Log.e("WebRTCService", "Error setting answer: $error")
                    handler.postDelayed({ createOffer() }, 2000)
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(error: String) {}
            }, sessionDescription)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error handling answer", e)
        }
    }

    private fun handleIceCandidate(candidate: JSONObject) {
        try {
            val ice = candidate.getJSONObject("ice")
            val iceCandidate = IceCandidate(
                ice.getString("sdpMid"),
                ice.getInt("sdpMLineIndex"),
                ice.getString("candidate")
            )
            webRTCClient.peerConnection?.addIceCandidate(iceCandidate)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error handling ICE candidate", e)
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        try {
            val message = JSONObject().apply {
                put("type", "ice_candidate")
                put("ice", JSONObject().apply {
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                })
                put("room", roomName)
                put("username", userName)
            }
            webSocketClient.send(message.toString())
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error sending ICE candidate", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "WebRTC Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WebRTC streaming service"
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC Service")
            .setContentText("Active in room: $roomName")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("WebRTC Service")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, notification)
    }

    override fun onDestroy() {
        if (!isUserStopped) {
            if (isConnectivityReceiverRegistered) {
                unregisterReceiver(connectivityReceiver)
            }
            if (isStateReceiverRegistered) {
                unregisterReceiver(stateReceiver)
            }
            scheduleRestartWithWorkManager()
        }
        if (isVideoTrackReceiverRegistered) {
            unregisterReceiver(videoTrackReceiver)
            isVideoTrackReceiverRegistered = false
        }

        if (isFlashlightOn) {
            try {
                flashlightCameraId?.let { cameraManager.setTorchMode(it, false) }
                isFlashlightOn = false
                Log.d("WebRTCService", "Фонарик выключен при завершении сервиса")
            } catch (e: CameraAccessException) {
                Log.e("WebRTCService", "Ошибка выключения фонарика: ${e.message}")
            }
        }

        textToSpeech?.let {
            it.stop()
            it.shutdown()
            textToSpeech = null
            Log.d("WebRTCService", "TextToSpeech остановлен и очищен")
        }
        super.onDestroy()
    }

    private fun cleanupAllResources() {
        handler.removeCallbacksAndMessages(null)
//        handler.removeCallbacks(videoTrackCheckRunnable) // Добавьте эту строку
        cleanupWebRTCResources()
        if (::webSocketClient.isInitialized) {
            webSocketClient.disconnect()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                isUserStopped = true
                isConnected = false
                isConnecting = false
                stopEverything()
                return START_NOT_STICKY
            }
            else -> {
                isUserStopped = false

                // Получаем последнее сохраненное имя комнаты
                val sharedPrefs = getSharedPreferences("WebRTCPrefs", Context.MODE_PRIVATE)
                val lastRoomName = sharedPrefs.getString("last_used_room", "")

                roomName = if (lastRoomName.isNullOrEmpty()) {
                    "default_room_${System.currentTimeMillis()}"
                } else {
                    lastRoomName
                }

                currentRoomName = roomName

                Log.d("WebRTCService", "Starting service with room: $roomName")

                if (!isConnected && !isConnecting) {
                    initializeWebRTC()
                    connectWebSocket()
                }

                isRunning = true
                return START_STICKY
            }
        }
    }

    private fun stopEverything() {
        isRunning = false
        isConnected = false
        isConnecting = false

        try {
            handler.removeCallbacksAndMessages(null)
            unregisterReceiver(connectivityReceiver)
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error during cleanup", e)
        }

        cleanupAllResources()

        if (isUserStopped) {
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun scheduleRestartWithWorkManager() {
        val workRequest = OneTimeWorkRequestBuilder<WebRTCWorker>()
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "WebRTCServiceRestart",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun isInitialized(): Boolean {
        return ::webSocketClient.isInitialized &&
                ::webRTCClient.isInitialized &&
                ::eglBase.isInitialized
    }
}