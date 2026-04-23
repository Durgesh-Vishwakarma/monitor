package com.micmonitor.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL

/**
 * KeepAliveWorker — WorkManager watchdog, runs every 15 minutes.
 *
 * 1. HTTP-pings the Render server to wake it from free-tier sleep BEFORE
 *    the WebSocket reconnect attempt (avoids all retries being wasted while
 *    the server is still cold-starting).
 * 2. Restarts MicService if it was killed.
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "KeepAliveWorker"
    }

    override fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences("micmonitor", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("consent_given", false)) return Result.success()

            // Bug 1.3: Separate the check and add zombie detection
            val wsAlive = MicService.activeWebSocket != null
            val lastAudioAt = MicService.lastAudioChunkSentAtMs
            val wsHealthy = wsAlive && (System.currentTimeMillis() - lastAudioAt) < 60_000
            Log.i(TAG, "Watchdog tick (wsAlive=$wsAlive, wsHealthy=$wsHealthy)")

            // Only skip if WS is truly healthy
            if (!wsHealthy) {
                // ── 1. Wake the Render server with an HTTP ping ────────────────
                wakeServer(prefs)

                // ── 2. Restart MicService (triggers reconnect since WS is dead) ─
                Log.i(TAG, "WS dead or unhealthy — ensuring MicService is running")
                val intent = Intent(applicationContext, MicService::class.java).apply {
                    action = MicService.ACTION_RECONNECT
                    // Bug L3 fix: Include data URI so onStartCommand's reconnect branch matches
                    data = android.net.Uri.parse("timer:reconnect_keepalive")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            } else {
                Log.i(TAG, "WS healthy — skipping server wake and service restart")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "KeepAliveWorker failed: ${e.message}")
            Result.success()
        }
    }

    /** HTTP GET to the server's /health endpoint — wakes Render from free-tier sleep. */
    private fun wakeServer(prefs: android.content.SharedPreferences) {
        try {
            val wsUrl = prefs.getString("server_url", MicService.DEFAULT_SERVER_URL)
                ?: MicService.DEFAULT_SERVER_URL
            val parsed = Uri.parse(wsUrl.trim())
            val scheme = when (parsed.scheme?.lowercase()) {
                "wss" -> "https"
                "ws" -> "http"
                "https" -> "https"
                "http" -> "http"
                else -> "https"
            }
            val host = parsed.host
            val httpBase = if (!host.isNullOrBlank()) {
                val port = if (parsed.port > 0) ":${parsed.port}" else ""
                "$scheme://$host$port"
            } else {
                // Fallback for malformed URLs in prefs.
                wsUrl
                    .replace(Regex("^wss://"), "https://")
                    .replace(Regex("^ws://"), "http://")
                    .substringBefore("/audio")
                    .trimEnd('/')
            }
            val pingUrl = "$httpBase/health"
            Log.i(TAG, "Pinging server: $pingUrl")
            val conn = URL(pingUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout    = 10_000
            conn.requestMethod  = "GET"
            val code = conn.responseCode
            conn.disconnect()
            Log.i(TAG, "Server ping response: $code")
        } catch (e: Exception) {
            Log.w(TAG, "Server ping failed: ${e.message}")
        }
    }

}
