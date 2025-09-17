// FactCheckActivity.kt
package com.example.myaccessibilityapp.factChecker

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myaccessibilityapp.ApiService
import com.example.myaccessibilityapp.R
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class FactCheckActivity : AppCompatActivity() {

    private val TAG = "FactCheckActivity"

    private lateinit var inputHeadline: TextInputEditText
    private lateinit var btnCheck: Button
    private lateinit var resultCard: View
    private lateinit var tvHeadline: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var tvReasons: TextView
    private lateinit var tvRecommendation: TextView

    // IMPORTANT: if you run the backend on your HOST while using the Android emulator,
    // point to 10.0.2.2 (emulator-to-host loopback). Keep ports aligned with your server.
    private val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl("http://127.0.0.1:5000/") // <— change from 127.0.0.1 if using emulator
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fact_checker_layout)

        inputHeadline = findViewById(R.id.inputHeadline)
        btnCheck = findViewById(R.id.btnCheck)
        resultCard = findViewById(R.id.resultCard)
        tvHeadline = findViewById(R.id.tvHeadline)
        tvVerdict = findViewById(R.id.tvVerdict)
        tvConfidence = findViewById(R.id.tvConfidence)
        tvReasons = findViewById(R.id.tvReasons)
        tvRecommendation = findViewById(R.id.tvRecommendation)

        resultCard.visibility = View.GONE

        btnCheck.setOnClickListener {
            val headline = inputHeadline.text?.toString()?.trim().orEmpty()
            if (headline.isEmpty()) {
                inputHeadline.error = "Please paste a headline"
                return@setOnClickListener
            }
            sendHeadline(headline)
        }
    }

    private fun sendHeadline(headline: String) {
        btnCheck.isEnabled = false
        resultCard.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val result = analyzeHeadline(headline)

                resultCard.visibility = View.VISIBLE
                tvHeadline.text = result.headline
                tvVerdict.text = "Verdict: ${result.verdict}"
                tvConfidence.text = "Confidence: ${result.confidence}%"
                tvReasons.text =
                    if (result.reasons.isNullOrEmpty()) "• —"
                    else result.reasons.joinToString("\n") { "• $it" }
                tvRecommendation.text = "Recommendation: ${result.recommendation}"
            } catch (e: Exception) {
                Log.e(TAG, "Fact-check failed", e)
                Toast.makeText(
                    this@FactCheckActivity,
                    e.message ?: "Network error",
                    Toast.LENGTH_SHORT
                ).show()

                resultCard.visibility = View.VISIBLE
                tvHeadline.text = "Couldn’t check right now."
                tvVerdict.text = ""
                tvConfidence.text = ""
                tvReasons.text = e.message ?: "Unknown error"
                tvRecommendation.text = ""
            } finally {
                btnCheck.isEnabled = true
            }
        }
    }

    private suspend fun analyzeHeadline(text: String): FactCheckResponse =
        withContext(Dispatchers.IO) {
            val response = apiService.checkNews(FactCheckRequest(text))
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) return@withContext body
            }
            throw IllegalStateException(
                "API error: ${response.code()} ${response.message()}"
            )
        }
}
