package com.example.hackerclicker.recognition

/**
 * Holds a simplified recognition outcome for the drawing pipeline.
 */
data class RecognitionResult(
    val candidates: List<String>,
    val confidence: Float,
    val isSuccess: Boolean,
    val errorMessage: String?,
)
