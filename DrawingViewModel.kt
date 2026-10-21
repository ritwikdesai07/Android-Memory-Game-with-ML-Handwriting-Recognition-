package com.example.hackerclicker.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.hackerclicker.recognition.InkManager
import com.example.hackerclicker.recognition.InkRecognizer
import com.example.hackerclicker.recognition.RecognitionResult

/**
 * Keeps drawing recognition state isolated from UI classes.
 */
class DrawingViewModel : ViewModel() {

    private val _recognitionResult = MutableLiveData<RecognitionResult>()
    val recognitionResult: LiveData<RecognitionResult> = _recognitionResult
    var lastCandidates: List<String> = emptyList() // DEBUG cache

    val inkManager: InkManager
    val inkRecognizer: InkRecognizer

    init {
        inkManager = InkManager()
        inkRecognizer = InkRecognizer(this)
    }

    fun postResult(result: RecognitionResult) {
        if (result.isSuccess) {
            lastCandidates = result.candidates // DEBUG cache update
        }
        _recognitionResult.postValue(result)
    }

    fun recognizeCurrentDrawing() {
        if (!inkManager.hasInk()) {
            postResult(
                RecognitionResult(
                    candidates = emptyList(),
                    confidence = -1f,
                    isSuccess = false,
                    errorMessage = "No strokes captured",
                )
            )
            return
        }
        inkRecognizer.recognize(inkManager.buildInk())
        inkManager.clear()
    }

    override fun onCleared() {
        inkRecognizer.close()
        inkManager.clear()
        super.onCleared()
    }
}
