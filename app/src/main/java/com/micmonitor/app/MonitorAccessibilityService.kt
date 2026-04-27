package com.micmonitor.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

class MonitorAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MonitorAccessibility"
        var instance: MonitorAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Connected")
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Accessibility Service Unbound")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Required by AccessibilityService but not used for screenshots
    }

    override fun onInterrupt() {
        // Required by AccessibilityService but not used for screenshots
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreen(callback: (Bitmap?) -> Unit) {
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        // Copy to a software bitmap so we can compress it safely after closing the buffer
                        val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBitmap?.recycle()
                        hardwareBuffer.close()
                        callback(softwareBitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to wrap/copy hardware buffer to bitmap", e)
                        callback(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    callback(null)
                }
            }
        )
    }
}