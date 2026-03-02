package com.micmonitor.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * AdminReceiver — makes this app a Device Administrator.
 * While active, Android disables the Uninstall button in App Info,
 * forcing the user to deactivate admin first — which triggers our
 * accessibility-service password prompt.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "⚠ Authorization required. Enter password 5099 to confirm."
}
