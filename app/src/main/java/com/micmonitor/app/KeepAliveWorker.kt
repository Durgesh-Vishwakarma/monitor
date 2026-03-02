package com.micmonitor.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * KeepAliveWorker — WorkManager watchdog that runs every 15 minutes.
 * If the MicService has been killed by the OS (battery saver, Doze, etc.),
 * this worker restarts it automatically to maintain 24/7 server connectivity.
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("micmonitor", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("consent_given", false)) return Result.success()

        Log.i("KeepAliveWorker", "Watchdog tick — ensuring MicService is running")
        val intent = Intent(applicationContext, MicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
        return Result.success()
    }
}
