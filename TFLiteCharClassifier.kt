package com.example.hackerclicker

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

internal class TFLiteCharClassifier(
    private val appContext: Context,
    private val modelAssetPath: String,
    private val labelsAssetPath: String?,
) : Closeable {
    data class Output(val char: Char, val confidence: Float)

    private val labels: List<Char> = loadLabelsOrDefault()
    private val interpreter: Interpreter = Interpreter(TFLiteUtils.loadModelFile(appContext, modelAssetPath))
    private val outputSize: Int

    init {
        val outShape = interpreter.getOutputTensor(0).shape()
        outputSize = outShape.lastOrNull() ?: error("Unexpected model output shape: ${outShape.contentToString()}")
        require(outputSize == labels.size) {
            "Model output size ($outputSize) does not match label count (${labels.size})."
        }
        Log.d("TFLiteCharClassifier", "model=$modelAssetPath outputShape=${outShape.contentToString()} labels=${labels.size}")
    }

    override fun close() {
        interpreter.close()
    }

    fun classify(input28x28: FloatArray): Output {
        require(input28x28.size == 28 * 28) { "Expected 28*28 floats, got ${input28x28.size}" }

        val input = ByteBuffer.allocateDirect(4 * 28 * 28).order(ByteOrder.nativeOrder())
        for (v in input28x28) input.putFloat(v)
        input.rewind()

        val output = Array(1) { FloatArray(outputSize) }
        interpreter.run(input, output)

        val probs = normalizeScores(output[0])
        var bestIdx = 0
        var best = probs[0]
        for (i in 1 until probs.size) {
            if (probs[i] > best) {
                best = probs[i]
                bestIdx = i
            }
        }
        val c = labels.getOrNull(bestIdx) ?: '?'
        return Output(char = c, confidence = best.coerceIn(0f, 1f))
    }

    private fun normalizeScores(rawScores: FloatArray): FloatArray {
        val alreadyProbabilities = rawScores.all { it in 0f..1f }
        if (alreadyProbabilities) {
            val sum = rawScores.sum()
            if (sum in 0.98f..1.02f) return rawScores
        }

        val maxScore = rawScores.maxOrNull() ?: 0f
        val expScores = FloatArray(rawScores.size)
        var sum = 0.0
        for (index in rawScores.indices) {
            val value = exp((rawScores[index] - maxScore).toDouble())
            expScores[index] = value.toFloat()
            sum += value
        }
        if (sum <= 0.0) return rawScores

        for (index in expScores.indices) {
            expScores[index] = (expScores[index] / sum.toFloat()).coerceIn(0f, 1f)
        }
        return expScores
    }

    private fun loadLabelsOrDefault(): List<Char> {
        val fromFile = labelsAssetPath?.let { path ->
            runCatching {
                appContext.assets.open(path).bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        val t = line.trim()
                        if (t.isEmpty()) null else t[0]
                    }.toList()
                }
            }.getOrNull()
        }

        if (fromFile != null && fromFile.size >= 36) {
            return fromFile.take(36)
        }

        // Default mapping: 0-9 then A-Z.
        val labels = ArrayList<Char>(36)
        for (d in '0'..'9') labels.add(d)
        for (c in 'A'..'Z') labels.add(c)
        return labels
    }
}
