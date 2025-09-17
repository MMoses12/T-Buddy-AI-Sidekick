// app/src/main/java/com/example/myaccessibilityapp/safetyAlerts/SafetyAlertsActivity.kt
package com.example.myaccessibilityapp.safetyAlerts

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myaccessibilityapp.R

class SafetyAlertsActivity : AppCompatActivity() {

    private lateinit var alertsRecycler: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.suggestions_layout)

        alertsRecycler = findViewById(R.id.alertsRecycler)
        alertsRecycler.layoutManager = LinearLayoutManager(this)

        // Read ONLY the values passed via Intent
        val recommendation   = intent.getStringExtra("recommendation")   // e.g. "Your message is friendly and safe..."
        val suggestedRewrite = intent.getStringExtra("suggested_rewrite")// only for outgoing
        val verdict          = intent.getStringExtra("verdict")          // e.g. "SAFE"
        val confidence       = intent.getStringExtra("confidence")       // e.g. "100"

        // Build the single alert strictly from the intent values
        val suggestionsParagraph = buildString {
            if (!recommendation.isNullOrBlank()) {
                append("• ").append(recommendation.trim()).append('\n')
            }
            if (!suggestedRewrite.isNullOrBlank()) {
                append("• Suggested rewrite: ").append(suggestedRewrite.trim())
            }
        }.trim()

        val hasAny =
            !recommendation.isNullOrBlank() ||
                    !suggestedRewrite.isNullOrBlank() ||
                    !verdict.isNullOrBlank() ||
                    !confidence.isNullOrBlank()

        if (!hasAny) {
            Toast.makeText(this, "No analysis data available.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val alert = Alert(
            title = "Analysis Result",
            message = buildVerdictMessage(verdict, confidence),
            severity = mapVerdictToSeverity(verdict),
            suggestions = suggestionsParagraph.ifBlank { "• No additional suggestions." }
        )

        alertsRecycler.adapter = AlertAdapter(listOf(alert))
    }

    private fun buildVerdictMessage(verdict: String?, confidence: String?): String {
        val v = verdict?.trim().orEmpty()
        val c = confidence?.trim().orEmpty()
        return when {
            v.isNotEmpty() && c.isNotEmpty() -> "Verdict: $v (confidence: $c%)"
            v.isNotEmpty()                   -> "Verdict: $v"
            c.isNotEmpty()                   -> "Confidence: $c%"
            else                             -> "Latest analysis from assistant."
        }
    }

    private fun mapVerdictToSeverity(verdict: String?): String {
        return when (verdict?.uppercase()?.trim()) {
            "SAFE"            -> "Info"
            "WARNING", "RISK" -> "Medium"
            "DANGER", "BLOCK" -> "High"
            else              -> "Info"
        }
    }
}
