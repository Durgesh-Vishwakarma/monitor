package com.micmonitor.app

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * UpdateWorker — Periodic background update checker using WorkManager
 * 
 * Runs every 15 minutes to check for new app versions.
 * If update available and app is Device Owner, installs silently.
 */
class UpdateWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    
    companion object {
        private const val TAG = "UpdateWorker"
        private const val WORK_NAME = "update_check_work"
        
        /**
         * Schedule periodic update checks
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val workRequest = PeriodicWorkRequestBuilder<UpdateWorker>(
                15, TimeUnit.MINUTES,           // Minimum allowed is 15 min (Android limitation)
                5, TimeUnit.MINUTES             // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,  // Replace to apply new interval
                workRequest
            )
            
            Log.i(TAG, "Update check worker scheduled (every 15 min)")
        }
        
        /**
         * Trigger immediate update check
         */
        fun checkNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val workRequest = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(constraints)
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            Log.i(TAG, "Immediate update check triggered")
        }
        
        /**
         * Cancel scheduled update checks
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Update check worker cancelled")
        }
    }
    
    override fun doWork(): Result {
        // user setting: no update automatic, only dashboard trigger (checkNow)
        val isPeriodic = inputData.getBoolean("is_periodic", true)
        if (isPeriodic && !isImmediateTrigger()) {
            Log.i(TAG, "Skipping periodic update check (Dashboard trigger only)")
            return Result.success()
        }
        
        return try {
            Log.i(TAG, "Running update check...")
            runBlocking {
                val versionInfo = UpdateService.checkForUpdate(applicationContext, forceCheck = true)
                
                if (versionInfo != null) {
                    Log.i(TAG, "Update available: ${versionInfo.versionName} (code: ${versionInfo.versionCode})")
                    
                    // Report to server via WebSocket if connected
                    notifyUpdateAvailable(versionInfo)
                    
                    // Start download and install
                    UpdateService.downloadAndInstall(applicationContext, versionInfo)
                } else {
                    Log.d(TAG, "No update available")
                }
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}")
            Result.retry()
        }
    }

    private fun isImmediateTrigger(): Boolean {
        // Check if this was a one-time request (not periodic)
        return tags.contains("one_time") || inputData.getBoolean("force", false)
    }
    
    private fun notifyUpdateAvailable(versionInfo: UpdateService.VersionInfo) {
        // Send notification to dashboard via existing WebSocket connection
        try {
            MicService.activeWebSocket?.let { ws ->
                val json = """{"type":"update_available","version":"${versionInfo.versionName}","code":${versionInfo.versionCode}}"""
                ws.send(json)
                Log.d(TAG, "Notified dashboard of available update")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify dashboard: ${e.message}")
        }
    }
}
