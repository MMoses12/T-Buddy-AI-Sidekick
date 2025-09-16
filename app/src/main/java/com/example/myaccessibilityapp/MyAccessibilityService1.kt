package com.example.myaccessibilityapp

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.google.gson.Gson

class MyAccessibilityServiceOld : AccessibilityService() {

    private val TAG = "MyAccessibilityService"
    private val gson = Gson()

    // Retrofit client for your backend
    private val apiService: ApiService by lazy {
        Retrofit.Builder()
            // Emulator = 10.0.2.2, Device = use your PC’s LAN IP
            .baseUrl("http://10.0.2.2:5000/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    enum class WarningType { PERSONAL_DATA, SENTIMENT_NEGATIVE, HARM_NEGATIVE, NONE }

    private var analysisJob: Job? = null

    private var coachView: View? = null
    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 32
            y = 32
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val text = event.text.joinToString(" ")
            if (!text.isNullOrBlank()) {
                // cancel previous debounce
                analysisJob?.cancel()

                analysisJob = CoroutineScope(Dispatchers.Default).launch {
                    delay(500)

                    // 1) Show temporary "checking" bubble
                    withContext(Dispatchers.Main) {
                        showCoachBubble("T-Buddy", "🔎 Checking your message…")
                    }

//                  2) Send text to backend
                    val warningType = analyzeTextWithApi(text)

                    Log.d(text, "Sent to backend.")

//                  3) Update UI based on result
                    withContext(Dispatchers.Main) {
                        when (warningType) {
                            WarningType.PERSONAL_DATA ->
                                showCoachBubble("Warning!", "⚠️ Personal data detected.")
                            WarningType.SENTIMENT_NEGATIVE ->
                                showCoachBubble("Warning!", "⚠️ Negative sentiment detected.")
                            WarningType.HARM_NEGATIVE ->
                                showCoachBubble("Warning!", "⚠️ Harmful language detected.")
                            WarningType.NONE ->
                                hideCoachBubble()
                        }
                    }
                }
            }
        }
    }

    private fun showCoachBubble(title: String, message: String) {
        hideCoachBubble() // remove old one if present

        coachView = LayoutInflater.from(this).inflate(R.layout.view_tbuddy_coach, null)
        coachView!!.findViewById<View>(R.id.tbuddyCoach).visibility = View.VISIBLE
        coachView!!.findViewById<TextView>(R.id.title).text = title
        coachView!!.findViewById<TextView>(R.id.message).text = message
        coachView!!.findViewById<ImageView>(R.id.buddy).setImageResource(R.drawable.robot)

        try {
            windowManager.addView(coachView, params)

            // Fade-in animation
            val fadeIn = AlphaAnimation(0f, 1f).apply {
                duration = 300
                fillAfter = true
            }
            coachView!!.startAnimation(fadeIn)

            Log.d(TAG, "Coach bubble shown: $title - $message")

            // Auto-hide after 4 seconds
            coachView!!.postDelayed({
                hideCoachBubble()
            }, 4000)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show coach bubble: ${e.message}", e)
        }
    }

    private fun hideCoachBubble() {
        coachView?.let { view ->
            try {
                // Fade-out animation
                val fadeOut = AlphaAnimation(1f, 0f).apply {
                    duration = 300
                    fillAfter = true
                }
                view.startAnimation(fadeOut)

                view.postDelayed({
                    try { windowManager.removeView(view) } catch (_: Exception) {}
                }, 300) // remove after fade-out
            } catch (_: Exception) {}
        }
        coachView = null
    }

    private suspend fun analyzeTextWithApi(text: String): WarningType {
        try {
            val request = ApiRequest(text = text)
            val jsonPayload = gson.toJson(request)
            Log.i(TAG, "Sending Payload: $jsonPayload")

            val response = apiService.analyzeText(request)
            if (response.isSuccessful) {
                val apiResponse = response.body()
                if (apiResponse != null) {
                    val verdict = apiResponse.finalVerdict
                    Log.d(TAG, "API Response: $verdict")

                    if (verdict.personalDataProbability > 0.70) return WarningType.PERSONAL_DATA
                    if (verdict.sentimentNegativeProbability > 0.70) return WarningType.SENTIMENT_NEGATIVE
                    // Add harm check if needed
                }
            } else {
                Log.e(TAG, "API failed: ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "API exception: ${e.message}")
        }
        return WarningType.NONE
    }

    override fun onInterrupt() {
        hideCoachBubble()
    }
}
