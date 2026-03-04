package com.micmonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BootReceiver — Automatically restarts MicService after reboot.
 * Handles multiple boot broadcast actions for all Android brands:
 *   - Standard Android: BOOT_COMPLETED, LOCKED_BOOT_COMPLETED
 *   - MIUI / Xiaomi: BOOT_COMPLETED (auto-launch must be enabled in settings)
 *   - HTC / Some brands: QUICKBOOT_POWERON
 *   - App update: MY_PACKAGE_REPLACED
 *
 * No mic permission needed on boot — permission persists from first grant.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,                    // Standard boot (after screen unlock)
            "android.intent.action.LOCKED_BOOT_COMPLETED",  // Boot before first unlock (Android 7+)
            "android.intent.action.QUICKBOOT_POWERON",      // HTC / some Chinese ROMs
            "com.htc.intent.action.QUICKBOOT_POWERON",      // HTC specific
            Intent.ACTION_MY_PACKAGE_REPLACED               // App was updated
        )

        if (action !in validActions) return

        Log.i("BootReceiver", "Boot action received: $action — checking consent")

        val prefs = context.getSharedPreferences("micmonitor", Context.MODE_PRIVATE)

        // Only auto-start if user previously gave consent — no permission dialog shown
        if (!prefs.getBoolean("consent_given", false)) {
            Log.w("BootReceiver", "Consent not given — skipping auto-start")
            return
        }

        Log.i("BootReceiver", "Consent found — starting MicService silently")
        try {
            val serviceIntent = Intent(context, MicService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start MicService on boot: ${e.message}")
        }
    }
}
