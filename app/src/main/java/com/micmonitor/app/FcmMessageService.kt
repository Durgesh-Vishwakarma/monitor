package com.micmonitor.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class FcmMessageService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FcmMessageService"
        private val tokenSyncClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.i(TAG, "FCM Message received! Layer 4 wake up triggered.")
        
        // This alone wakes the app process from deep sleep.
        // We ensure MicService is restarted to handle any commands.
        val intent = Intent(applicationContext, MicService::class.java)
        
        // Map FCM payload into MicService intent.action.
        // Backend retries typically send action="force_reconnect", which should map
        // to the MicService reconnect behavior.
        val action = remoteMessage.data["action"] ?: remoteMessage.data["type"]
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
        val httpUrl = resolveHttpBaseUrl(serverUrl)
        
        val url = "$httpUrl/api/fcm-token"
        Log.i(TAG, "Syncing FCM token to: $url")

        val json = JSONObject().apply {
            put("deviceId", deviceId)
            put("token", token)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        tokenSyncClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to sync FCM token: ${e.message}")
                tokenSyncClient.connectionPool.evictAll()
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.i(TAG, "FCM token synced successfully to backend")
                } else {
                    Log.e(TAG, "FCM token sync failed with code: ${response.code}")
                }
                response.close()
                tokenSyncClient.connectionPool.evictAll()
            }
        })
    }

    private fun resolveHttpBaseUrl(rawServerUrl: String): String {
        val normalized = rawServerUrl.trim()
            .replace(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replace(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")

        return try {
            val parsed = Uri.parse(normalized)
            val scheme = when (parsed.scheme?.lowercase()) {
                "https", "http" -> parsed.scheme!!.lowercase()
                else -> "https"
            }
            val host = parsed.host ?: return normalized.trimEnd('/').replace(Regex("/audio(/.*)?$"), "")
            val port = if (parsed.port > 0) ":${parsed.port}" else ""
            "$scheme://$host$port"
        } catch (_: Exception) {
            normalized.trimEnd('/').replace(Regex("/audio(/.*)?$"), "")
        }
    }
}
