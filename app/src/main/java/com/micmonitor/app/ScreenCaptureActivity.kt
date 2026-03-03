package com.micmonitor.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Transparent Activity that shows the system screen-capture permission dialog.
 * On approval it passes the result Intent to MicService and immediately finishes.
 */
class ScreenCaptureActivity : Activity() {

    companion object {
        private const val TAG       = "ScreenCaptureActivity"
        private const val RC_SCREEN = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), RC_SCREEN)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SCREEN) {
            Log.d(TAG, "Screen capture result: $resultCode")
            val svcIntent = Intent(this, MicService::class.java).apply {
                action = MicService.ACTION_SCREEN_RESULT
                putExtra("result_code", resultCode)
                if (data != null) putExtra("result_data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        }
        finish()
    }
}
