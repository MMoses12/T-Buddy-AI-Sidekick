// models.kt
package com.example.myaccessibilityapp.safetyAlerts

data class Suggestion(
        val text: String,
        val iconRes: Int,      // e.g. R.drawable.ic_block
        val hasAction: Boolean = false
)

data class Alert(
    val title: String,
    val message: String,
    val severity: String,   // e.g. "High", "Medium", "Low"
    val suggestions: String
)
