package com.example.myaccessibilityapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var btnEnable: Button

    // Use the raw action string so this compiles on any compileSdk
    private val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    // Observe changes to enabled accessibility services so we can update UI instantly
    private val enabledServicesUri =
        Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.accessibilityStatus)
        btnEnable = findViewById(R.id.btnEnable)

        btnEnable.setOnClickListener { openAccessibilityScreen() }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(enabledServicesUri, false, settingsObserver)
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(settingsObserver)
    }

    private fun updateUi() {
        val enabled = isAccessibilityServiceEnabled(this, MyAccessibilityServiceOld::class.java)
        statusView.text = if (enabled) "Accessibility: ON" else "Accessibility: OFF"
        btnEnable.text = if (enabled) "Manage Accessibility" else "Open Accessibility for T-Buddy"
    }

    private fun openAccessibilityScreen() {
        val pkg = packageName
        val serviceComponent = ComponentName(this, MyAccessibilityServiceOld::class.java)

        // Try the app-specific Accessibility details screen (Android 10+)
        val detailsIntent = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Some OEMs honor this extra to preselect your service
            putExtra(
                "android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME",
                serviceComponent.flattenToString()
            )
        }

        // Fallback: generic Accessibility Settings (all versions)
        val genericIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Try details first, then fallback
        try {
            startActivity(detailsIntent)
        } catch (_: Exception) {
            try {
                startActivity(genericIntent)
            } catch (_: Exception) {
                // As a last resort, open app settings
                val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(appSettings)
            }
        }
    }

    companion object {
        fun isAccessibilityServiceEnabled(
            context: Context,
            service: Class<out android.accessibilityservice.AccessibilityService>
        ): Boolean {
            val expected = ComponentName(context, service).flattenToString()
            val enabledFlag = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0
            ) == 1
            if (!enabledFlag) return false

            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
