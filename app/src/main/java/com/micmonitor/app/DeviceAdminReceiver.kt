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
 *   adb shell dpm set-device-owner com.device.services.app/.DeviceAdminReceiver
 * 
 * To remove Device Owner:
 *   adb shell dpm remove-active-admin com.device.services.app/.DeviceAdminReceiver
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {
    
    companion object {
        private const val TAG = "DeviceAdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin disabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Profile provisioning complete")
    }
}
