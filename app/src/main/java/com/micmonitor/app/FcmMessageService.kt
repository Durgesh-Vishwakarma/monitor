package com.micmonitor.app

import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FcmMessageService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FcmMessageService"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.i(TAG, "FCM Message received! Layer 4 wake up triggered.")
        
        // This alone wakes the app process from deep sleep.
        // We ensure MicService is restarted to handle any commands.
        val intent = Intent(applicationContext, MicService::class.java)
        
        // Let's pass the action if we have one in data payload
        remoteMessage.data["action"]?.let { action ->
            intent.action = action
        } ?: run {
            intent.action = MicService.ACTION_RECONNECT
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MicService from FCM: ${e.message}")
        }
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "New FCM Token: $token")
        // the server doesn't strictly need it right now because standard FCM API 
        // to a specific token is not fully implemented on backend, but we store it.
        val prefs = getSharedPreferences("micmonitor", MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
        
        // Ensure mic service runs to let it sync any token
        val intent = Intent(applicationContext, MicService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MicService on new token: ${e.message}")
        }
    }
}
