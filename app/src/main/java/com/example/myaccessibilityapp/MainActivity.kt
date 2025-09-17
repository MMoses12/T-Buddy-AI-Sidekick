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
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var btnEnable: Button
    private lateinit var cbBiometrics: CheckBox
    private lateinit var cbPrivacy: CheckBox

    private val prefs by lazy { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    private val KEY_CONSENT_DONE = "consent_done"

    private val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

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
        cbBiometrics = findViewById(R.id.cbBiometrics)
        cbPrivacy = findViewById(R.id.cbPrivacy)

        // Restore previous state
        if (prefs.getBoolean(KEY_CONSENT_DONE, false)) hideConsentSection()

        // Watch checkboxes to enable/disable button
        val watcher = { updateEnableButton() }
        cbBiometrics.setOnCheckedChangeListener { _, _ -> watcher() }
        cbPrivacy.setOnCheckedChangeListener { _, _ -> watcher() }
        updateEnableButton()

        btnEnable.setOnClickListener {
            // Mark consent as done and hide checkboxes permanently
            prefs.edit().putBoolean(KEY_CONSENT_DONE, true).apply()
            hideConsentSection()
            openAccessibilityScreen()
        }

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

    private fun updateEnableButton() {
        val ready = cbBiometrics.isChecked && cbPrivacy.isChecked
        btnEnable.isEnabled = ready

        // Style based on state (using your colors.xml)
        if (ready) {
            btnEnable.setBackgroundColor(getColor(R.color.t_plum))
            btnEnable.setTextColor(getColor(R.color.fg_white))
        } else {
            btnEnable.setBackgroundColor(getColor(R.color.t_plum))
            btnEnable.setTextColor(getColor(R.color.fg_subtle))
        }
    }

    private fun hideConsentSection() {
        cbBiometrics.visibility = View.GONE
        cbPrivacy.visibility = View.GONE
    }

    private fun updateUi() {
        val enabled = isAccessibilityServiceEnabled(this, MyAccessibilityServiceOld::class.java)
        statusView.text = if (enabled) "Accessibility: ON" else "Accessibility: OFF"
        btnEnable.text = if (enabled) "Manage Accessibility" else "Open Accessibility for T-Buddy"
    }

    private fun openAccessibilityScreen() {
        val pkg = packageName
        val serviceComponent = ComponentName(this, MyAccessibilityServiceOld::class.java)

        val detailsIntent = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(
                "android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME",
                serviceComponent.flattenToString()
            )
        }

        val genericIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(detailsIntent)
        } catch (_: Exception) {
            try {
                startActivity(genericIntent)
            } catch (_: Exception) {
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
