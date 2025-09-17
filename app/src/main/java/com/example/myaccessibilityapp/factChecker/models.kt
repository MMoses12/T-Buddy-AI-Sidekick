// models/FactCheckModels.kt
package com.example.myaccessibilityapp.factChecker

import com.google.gson.annotations.SerializedName

// POST body: { "text": "<headline>" }
data class FactCheckRequest(
    @SerializedName("text") val text: String
)

// Verdict enum (Gson supports @SerializedName on enum constants)
enum class Verdict {
    @SerializedName("REAL") REAL,
    @SerializedName("FAKE") FAKE,
    @SerializedName("MISLEADING") MISLEADING,
    @SerializedName("UNVERIFIED") UNVERIFIED
}

// Response payload from backend
data class FactCheckResponse(
    val headline: String,
    val verdict: Verdict,
    val confidence: Int,
    val reasons: List<String>,
    val recommendation: String
)
