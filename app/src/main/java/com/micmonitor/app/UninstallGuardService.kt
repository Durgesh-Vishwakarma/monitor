package com.micmonitor.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * UninstallGuardService — Accessibility Service.
 * Monitors for any package-installer window and immediately
 * launches the password prompt, blocking uninstallation.
 */
class UninstallGuardService : AccessibilityService() {

    companion object {
        /** Set to true once the correct password is entered so we don't re-launch the prompt. */
        @Volatile var passwordVerified = false
    }

    // Package names of the system uninstall/installer screens across all major OEMs
    private val installerPackages = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.huawei.systemmanager",
        "com.oppo.packageinstaller",
        "com.vivo.packageinstaller",
        "com.oneplus.packageinstaller"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg in installerPackages) {
            if (!passwordVerified) {
                val i = Intent(this, UninstallPasswordActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(i)
            }
        } else {
            // Reset when user navigates away from installer
            passwordVerified = false
        }
    }

    override fun onInterrupt() {}
}
