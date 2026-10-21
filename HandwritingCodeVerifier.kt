package com.example.hackerclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.util.Locale

/**
 * Self-contained on-device handwriting verifier for codes made of `0-9` and `A-Z`.
 *
 * It supports two flows:
 * - bitmap verification: segment a finished canvas bitmap left-to-right, classify each segment, compare to expected code
 * - pause-based session verification: feed already-segmented characters from a drawing view, one by one
 */
class HandwritingCodeVerifier(
    context: Context,
    modelAssetPath: String = DEFAULT_MODEL_ASSET_PATH,
    labelsAssetPath: String? = DEFAULT_LABELS_ASSET_PATH,
) : Closeable {

    interface VerificationCallback {
        fun onVerified(
            matched: Boolean,
            predicted: String,
            perChar: List<CharPrediction>,
        )

        fun onError(error: Throwable)
    }

    data class CharPrediction(
        val index: Int,
        val char: Char,
        val confidence: Float,
        val bounds: Rect? = null,
    )

    data class SegmentedChar(
        val bitmap: Bitmap,
        val bounds: Rect? = null,
    )

    companion object {
        @JvmField
        var DEBUG_MODE: Boolean = false

        const val DEFAULT_MODEL_ASSET_PATH = "alphanum_28x28.tflite"
        const val DEFAULT_LABELS_ASSET_PATH = "labels_alphanum_36.txt"

        private const val TAG = "HandwritingCodeVerifier"
    }

    private data class VerificationSession(
        val expectedCode: String,
        val callback: VerificationCallback,
        val predictions: MutableList<CharPrediction> = mutableListOf(),
    )

    private val classifier = TFLiteCharClassifier(
        appContext = context.applicationContext,
        modelAssetPath = modelAssetPath,
        labelsAssetPath = labelsAssetPath,
    )
    private val segmenter = BitmapCharacterSegmenter()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeSession: VerificationSession? = null

    override fun close() {
        classifier.close()
    }

    fun startSession(expectedCode: String, callback: VerificationCallback) {
        val normalizedExpected = normalizeExpectedCode(expectedCode)
        activeSession = VerificationSession(
            expectedCode = normalizedExpected,
            callback = callback,
        )
    }

    fun resetSession() {
        activeSession = null
    }

    fun classifyCharacter(characterBitmap: Bitmap, bounds: Rect? = null): CharPrediction {
        val preprocessed = BitmapPreprocessor.preprocess(characterBitmap)
        val output = classifier.classify(preprocessed.input)
        debugLogPrediction(index = 0, output.char, output.confidence, bounds)
        return CharPrediction(
            index = 0,
            char = output.char,
            confidence = output.confidence,
            bounds = bounds ?: preprocessed.originalInkBounds,
        )
    }

    /**
     * Pushes a pause-segmented character into the active session.
     * When the session receives as many characters as the expected code length,
     * the final callback is fired automatically.
     */
    fun submitSegment(segment: SegmentedChar): CharPrediction {
        val session = activeSession
            ?: error("No active verification session. Call startSession() before submitSegment().")

        val nextIndex = session.predictions.size
        if (nextIndex >= session.expectedCode.length) {
            error("Received more character segments than expected for code '${session.expectedCode}'.")
        }

        val preprocessed = BitmapPreprocessor.preprocess(segment.bitmap)
        val output = classifier.classify(preprocessed.input)
        val prediction = CharPrediction(
            index = nextIndex,
            char = output.char,
            confidence = output.confidence,
            bounds = segment.bounds ?: preprocessed.originalInkBounds,
        )

        session.predictions += prediction
        debugLogPrediction(nextIndex, output.char, output.confidence, prediction.bounds)

        if (session.predictions.size == session.expectedCode.length) {
            val predictedString = session.predictions.joinToString(separator = "") { it.char.toString() }
            val matched = predictedString == session.expectedCode
            activeSession = null
            mainHandler.post {
                session.callback.onVerified(
                    matched = matched,
                    predicted = predictedString,
                    perChar = session.predictions.toList(),
                )
            }
        }

        return prediction
    }

    /**
     * Verifies a completed bitmap using image-based segmentation as a fallback.
     */
    fun verifyCode(
        canvasBitmap: Bitmap,
        expectedCode: String,
        callback: VerificationCallback,
    ) {
        try {
            val normalizedExpected = normalizeExpectedCode(expectedCode)
            val segments = segmenter.segment(canvasBitmap)
            verifyCodeFromSegments(segments, normalizedExpected, callback)
        } catch (t: Throwable) {
            mainHandler.post { callback.onError(t) }
        }
    }

    fun verifyCodeFromSegments(
        segmentsLeftToRight: List<SegmentedChar>,
        expectedCode: String,
        callback: VerificationCallback,
    ) {
        try {
            val normalizedExpected = normalizeExpectedCode(expectedCode)
            val predictions = segmentsLeftToRight.mapIndexed { index, segment ->
                val preprocessed = BitmapPreprocessor.preprocess(segment.bitmap)
                val output = classifier.classify(preprocessed.input)
                val prediction = CharPrediction(
                    index = index,
                    char = output.char,
                    confidence = output.confidence,
                    bounds = segment.bounds ?: preprocessed.originalInkBounds,
                )
                debugLogPrediction(index, output.char, output.confidence, prediction.bounds)
                prediction
            }

            val predictedString = predictions.joinToString(separator = "") { it.char.toString() }
            mainHandler.post {
                callback.onVerified(
                    matched = predictedString == normalizedExpected,
                    predicted = predictedString,
                    perChar = predictions,
                )
            }
        } catch (t: Throwable) {
            mainHandler.post { callback.onError(t) }
        }
    }

    private fun normalizeExpectedCode(expectedCode: String): String {
        return expectedCode.trim().uppercase(Locale.US)
    }

    private fun debugLogPrediction(index: Int, char: Char, confidence: Float, bounds: Rect?) {
        if (!DEBUG_MODE) return
        val confidencePercent = confidence * 100f
        Log.d(
            TAG,
            "char[$index]=$char confidence=${"%.1f".format(Locale.US, confidencePercent)}% bounds=$bounds",
        )
    }
}

internal class BitmapCharacterSegmenter {
    fun segment(canvas: Bitmap): List<HandwritingCodeVerifier.SegmentedChar> {
        val width = canvas.width.coerceAtLeast(1)
        val height = canvas.height.coerceAtLeast(1)
        val scale = 0.5f
        val downsampledWidth = (width * scale).toInt().coerceAtLeast(1)
        val downsampledHeight = (height * scale).toInt().coerceAtLeast(1)
        val downsampled = Bitmap.createScaledBitmap(canvas, downsampledWidth, downsampledHeight, true)

        val pixels = IntArray(downsampledWidth * downsampledHeight)
        downsampled.getPixels(pixels, 0, downsampledWidth, 0, 0, downsampledWidth, downsampledHeight)
        val inkMask = BooleanArray(pixels.size)

        for (index in pixels.indices) {
            val color = pixels[index]
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            inkMask[index] = (green > 80 && green > red + 30 && green > blue + 30) || (red + green + blue > 420)
        }

        val mergedBoxes = ConnectedComponents.findComponents(inkMask, downsampledWidth, downsampledHeight)
            .mapNotNull { component ->
                component.expand(2, 2, downsampledWidth, downsampledHeight).takeIf { box ->
                    box.width() > 4 && box.height() > 6
                }
            }
            .let(::mergeIntoCharacterBlobs)
            .sortedBy { it.left }

        val segments = mergedBoxes.map { box ->
            val left = (box.left / scale).toInt().coerceIn(0, width - 1)
            val top = (box.top / scale).toInt().coerceIn(0, height - 1)
            val right = (box.right / scale).toInt().coerceIn(left + 1, width)
            val bottom = (box.bottom / scale).toInt().coerceIn(top + 1, height)

            val padX = ((right - left) * 0.20f).toInt().coerceAtLeast(8)
            val padY = ((bottom - top) * 0.20f).toInt().coerceAtLeast(8)
            val cropLeft = (left - padX).coerceAtLeast(0)
            val cropTop = (top - padY).coerceAtLeast(0)
            val cropRight = (right + padX).coerceAtMost(width)
            val cropBottom = (bottom + padY).coerceAtMost(height)

            HandwritingCodeVerifier.SegmentedChar(
                bitmap = Bitmap.createBitmap(canvas, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop),
                bounds = Rect(cropLeft, cropTop, cropRight, cropBottom),
            )
        }
        if (downsampled !== canvas && !downsampled.isRecycled) {
            downsampled.recycle()
        }
        return segments
    }

    private fun mergeIntoCharacterBlobs(boxes: List<Rect>): List<Rect> {
        if (boxes.isEmpty()) return emptyList()

        val sorted = boxes.sortedWith(compareBy<Rect> { it.left }.thenBy { it.top })
        val merged = mutableListOf<Rect>()

        for (box in sorted) {
            val last = merged.lastOrNull()
            if (last == null) {
                merged += Rect(box)
                continue
            }

            val gap = box.left - last.right
            val verticalOverlap = minOf(last.bottom, box.bottom) - maxOf(last.top, box.top)
            val canMerge = gap <= 14 && verticalOverlap > 0

            if (canMerge) {
                last.union(box)
            } else {
                merged += Rect(box)
            }
        }

        return merged
    }
}

internal object ConnectedComponents {
    fun findComponents(mask: BooleanArray, width: Int, height: Int): List<Rect> {
        val visited = BooleanArray(mask.size)
        val components = mutableListOf<Rect>()
        val queueX = IntArray(mask.size)
        val queueY = IntArray(mask.size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val startIndex = y * width + x
                if (!mask[startIndex] || visited[startIndex]) continue

                var left = x
                var right = x + 1
                var top = y
                var bottom = y + 1
                var head = 0
                var tail = 0

                visited[startIndex] = true
                queueX[tail] = x
                queueY[tail] = y
                tail += 1

                while (head < tail) {
                    val currentX = queueX[head]
                    val currentY = queueY[head]
                    head += 1

                    if (currentX < left) left = currentX
                    if (currentX + 1 > right) right = currentX + 1
                    if (currentY < top) top = currentY
                    if (currentY + 1 > bottom) bottom = currentY + 1

                    val neighbors = intArrayOf(
                        currentX - 1, currentY,
                        currentX + 1, currentY,
                        currentX, currentY - 1,
                        currentX, currentY + 1,
                    )

                    var offset = 0
                    while (offset < neighbors.size) {
                        val neighborX = neighbors[offset]
                        val neighborY = neighbors[offset + 1]
                        offset += 2

                        if (neighborX !in 0 until width || neighborY !in 0 until height) continue
                        val neighborIndex = neighborY * width + neighborX
                        if (!mask[neighborIndex] || visited[neighborIndex]) continue

                        visited[neighborIndex] = true
                        queueX[tail] = neighborX
                        queueY[tail] = neighborY
                        tail += 1
                    }
                }

                components += Rect(left, top, right, bottom)
            }
        }

        return components
    }
}

private fun Rect.expand(padX: Int, padY: Int, maxWidth: Int, maxHeight: Int): Rect {
    return Rect(
        (left - padX).coerceAtLeast(0),
        (top - padY).coerceAtLeast(0),
        (right + padX).coerceAtMost(maxWidth),
        (bottom + padY).coerceAtMost(maxHeight),
    )
}
