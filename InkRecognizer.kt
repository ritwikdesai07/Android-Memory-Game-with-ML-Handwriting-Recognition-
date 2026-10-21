package com.example.hackerclicker.recognition

import com.example.hackerclicker.viewmodel.DrawingViewModel
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.RecognitionResult
import com.google.mlkit.vision.digitalink.WritingArea
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.RecognitionCandidate

/**
 * Owns the ML Kit recognizer lifecycle and posts recognition results to the ViewModel.
 */
class InkRecognizer(
    private val drawingViewModel: DrawingViewModel,
) {

    private val remoteModelManager = RemoteModelManager.getInstance()
    private val modelIdentifier: DigitalInkRecognitionModelIdentifier? = try {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(LANGUAGE_TAG)
    } catch (_: MlKitException) {
        null
    }
    private val model: DigitalInkRecognitionModel? = modelIdentifier?.let {
        DigitalInkRecognitionModel.builder(it).build()
    }
    private val recognizer: DigitalInkRecognizer? = model?.let {
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(it).build())
    }

    @Volatile
    private var isModelReady = false

    init {
        val recognitionModel = model
        if (recognitionModel != null) {
            remoteModelManager.isModelDownloaded(recognitionModel)
                .addOnSuccessListener { downloaded ->
                    if (downloaded) {
                        isModelReady = true
                    } else {
                        remoteModelManager.download(recognitionModel, DownloadConditions.Builder().build())
                            .addOnSuccessListener {
                                isModelReady = true
                            }
                            .addOnFailureListener {
                                isModelReady = false
                            }
                    }
                }
        }
    }

    fun recognize(ink: Ink) {
        val digitalInkRecognizer = recognizer
        if (!isModelReady || digitalInkRecognizer == null) {
            drawingViewModel.postResult(
                RecognitionResult(
                    candidates = emptyList(),
                    confidence = -1f,
                    isSuccess = false,
                    errorMessage = "Model not ready",
                )
            )
            return
        }

        digitalInkRecognizer.recognize(ink)
            .addOnSuccessListener { result ->
                val topCandidates = result.candidates.take(MAX_CANDIDATES)
                val candidateTexts = topCandidates.map(RecognitionCandidate::getText)
                val topConfidence = topCandidates.firstOrNull()?.score
                    ?.takeIf { it in 0f..1f }
                    ?: -1f

                drawingViewModel.postResult(
                    RecognitionResult(
                        candidates = candidateTexts,
                        confidence = topConfidence,
                        isSuccess = candidateTexts.isNotEmpty(),
                        errorMessage = if (candidateTexts.isEmpty()) "No recognition candidates" else null,
                    )
                )
            }
            .addOnFailureListener { error ->
                drawingViewModel.postResult(
                    RecognitionResult(
                        candidates = emptyList(),
                        confidence = -1f,
                        isSuccess = false,
                        errorMessage = error.message ?: "Recognition failed",
                    )
                )
            }
    }

    fun close() {
        recognizer?.close()
    }

    private companion object {
        const val LANGUAGE_TAG = "en-US"
        const val MAX_CANDIDATES = 3
    }
}
