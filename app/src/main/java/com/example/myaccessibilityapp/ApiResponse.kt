package com.example.myaccessibilityapp

import com.google.gson.annotations.SerializedName

// This data class represents the top-level JSON response from your API.
data class ApiResponse(
    @SerializedName("final_verdict")
    val finalVerdict: FinalVerdict
)

// This data class models the "final_verdict" object.
data class FinalVerdict(
    @SerializedName("harm_negative_probability")
    val harmNegativeProbability: Double,
    @SerializedName("personal_data_probability")
    val personalDataProbability: Double,
    @SerializedName("sentiment_negative_probability")
    val sentimentNegativeProbability: Double
)

// This data class models the request body we will send to the API.
data class ApiRequest(
    @SerializedName("text")
    val text: String
)