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

        private var isWhatsAppCallActive = false
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
        // This logic is now more robust to handle interruptions like notifications or screen lock.
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        if (packageName != "com.whatsapp") {
            // If a window from another app appears while a WhatsApp call was active,
            // Loophole Fix: Do NOT immediately stop recording if the user goes to the home screen
            // or opens another app while on a WhatsApp call (e.g., PIP mode or speakerphone).
            // The call might still be active in the background. We rely on WhatsApp's in-app
            // navigation to detect actual call hang-ups.
            return
        }

        // We are in WhatsApp
        val className = event.className?.toString() ?: ""
        val isCallScreen = className.contains("VoipActivity", ignoreCase = true) || className.contains("CallActivity", ignoreCase = true)

        if (isCallScreen && !isWhatsAppCallActive) {
            // Transitioned TO a call screen
            isWhatsAppCallActive = true
            if (!CallRecorder.isRecording) {
                Log.i(TAG, "WhatsApp call detected, starting recording.")
                sendBroadcast(Intent(MicService.ACTION_WHATSAPP_CALL_START))
                // Give MicService a moment to release the mic before we try to acquire it.
                android.os.Handler(mainLooper).postDelayed({
                    CallRecorder.startRecording(this, "WhatsApp")
                }, 300)
            }
        } else if (!isCallScreen && isWhatsAppCallActive && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Transitioned FROM a call screen to another screen WITHIN WhatsApp
            Log.d(TAG, "Left WhatsApp call screen, checking if call actually ended...")
            // Loophole Fix: Delaying stop slightly to check if call actually ended or user just went to chat list (PIP/Speaker)
            android.os.Handler(mainLooper).postDelayed({
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                val mode = am?.mode ?: android.media.AudioManager.MODE_NORMAL
                if (mode == android.media.AudioManager.MODE_IN_COMMUNICATION || mode == android.media.AudioManager.MODE_IN_CALL) {
                    Log.d(TAG, "Call is still active in background (PIP/Speaker), ignoring window change.")
                } else {
                    isWhatsAppCallActive = false
                    if (CallRecorder.isRecording) {
                        CallRecorder.stopRecording("WhatsApp")
                        sendBroadcast(Intent(MicService.ACTION_WHATSAPP_CALL_END))
                    }
                }
            }, 1500)
        }
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