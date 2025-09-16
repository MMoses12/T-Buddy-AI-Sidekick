package com.example.myaccessibilityapp

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    /**
     * Sends a POST request to the analyze endpoint with a JSON body.
     *
     * @param request The ApiRequest data class containing the text to be analyzed.
     * @return A Retrofit Response object containing the ApiResponse.
     */
    @POST("analyze")
    suspend fun analyzeText(@Body request: ApiRequest): Response<ApiResponse>
}