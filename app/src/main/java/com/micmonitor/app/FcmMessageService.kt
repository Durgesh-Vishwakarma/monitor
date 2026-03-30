package com.micmonitor.app

import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class FcmMessageService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FcmMessageService"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.i(TAG, "FCM Message received! Layer 4 wake up triggered.")
        
        // This alone wakes the app process from deep sleep.
        // We ensure MicService is restarted to handle any commands.
        val intent = Intent(applicationContext, MicService::class.java)
        
        // Map FCM payload into MicService intent.action.
        // Backend retries typically send action="force_reconnect", which should map
        // to the MicService reconnect behavior.
        val action = remoteMessage.data["action"]
        intent.action = when (action) {
            "force_reconnect" -> MicService.ACTION_RECONNECT
            null -> MicService.ACTION_RECONNECT
            else -> action
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
        val prefs = getSharedPreferences("micmonitor", MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
        
        // Immediate sync with backend
        val deviceId = prefs.getString("device_id", null) ?: "unknown"
        val serverUrl = prefs.getString("server_url", MicService.DEFAULT_SERVER_URL) ?: MicService.DEFAULT_SERVER_URL
        sendTokenToBackend(deviceId, token, serverUrl)
    }

    private fun sendTokenToBackend(deviceId: String, token: String, serverUrl: String) {
        val httpUrl = serverUrl.replace("wss://", "https://")
            .replace("ws://", "http://")
            .trimEnd('/')
            .substringBeforeLast("/") // Remove current deviceId if present in WS URL
        
        val url = "$httpUrl/api/fcm-token"
        Log.i(TAG, "Syncing FCM token to: $url")

        val client = OkHttpClient()
        val json = JSONObject().apply {
            put("deviceId", deviceId)
            put("token", token)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to sync FCM token: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.i(TAG, "FCM token synced successfully to backend")
                } else {
                    Log.e(TAG, "FCM token sync failed with code: ${response.code}")
                }
                response.close()
            }
        })
    }
}
