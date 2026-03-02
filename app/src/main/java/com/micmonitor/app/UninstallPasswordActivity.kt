package com.micmonitor.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * UninstallPasswordActivity — shown over the package installer.
 * Correct password (5099) → let the installer proceed.
 * Wrong password / cancel → send user to Home screen.
 */
class UninstallPasswordActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPasswordDialog()
    }

    private fun showPasswordDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter password"
            setPadding(64, 40, 64, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("Authorization Required")
            .setMessage("Enter the password to continue.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Confirm") { _, _ ->
                if (input.text.toString().trim() == "5099") {
                    UninstallGuardService.passwordVerified = true
                    finish()
                } else {
                    Toast.makeText(this, "Incorrect password", Toast.LENGTH_SHORT).show()
                    goHome()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> goHome() }
            .show()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = goHome()
}
