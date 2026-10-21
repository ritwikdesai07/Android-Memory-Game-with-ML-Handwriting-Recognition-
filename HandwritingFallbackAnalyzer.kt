package com.example.hackerclicker

import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionResult
import kotlin.math.abs

class HandwritingFallbackAnalyzer(
    private val dp: (Float) -> Float,
) {
    fun extractCharacter(result: RecognitionResult, strokes: List<InkCaptureView.StrokeInfo>): Char? {
        return try {
            for (candidate in result.candidates.take(5)) {
                val text = candidate.text
                if (text.isNotBlank()) {
                    val normalized = normalizeCandidateUltraLenient(text)
                    if (normalized != null) return normalized
                }
            }

            if (strokes.isNotEmpty()) {
                val patternChar = analyzeStrokePattern(strokes)
                if (patternChar != null) return patternChar
            }

            for (candidate in result.candidates) {
                val text = candidate.text
                if (text.isNotBlank()) {
                    for (char in text) {
                        if (char.isLetterOrDigit()) return char.uppercaseChar()
                    }
                    for (char in text) {
                        return mapLooseSymbol(char)
                    }
                }
            }

            if (strokes.isNotEmpty()) {
                return analyzeGestureFallback(strokes)
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    fun resolveFallbackCharacter(
        result: com.example.hackerclicker.recognition.RecognitionResult,
        strokes: List<InkCaptureView.StrokeInfo>,
    ): Char? {
        if (result.isSuccess) {
            result.candidates.firstNotNullOfOrNull { normalizeCandidateUltraLenient(it) }?.let { return it }
        }
        if (strokes.isEmpty()) return null
        return analyzeStrokePattern(strokes) ?: analyzeGestureFallback(strokes)
    }

    private fun analyzeStrokePattern(strokes: List<InkCaptureView.StrokeInfo>): Char? {
        if (strokes.isEmpty()) return null

        val totalStrokes = strokes.size
        val firstStroke = strokes.firstOrNull() ?: return null
        val horizontalDist = abs(firstStroke.endX - firstStroke.startX)
        val verticalDist = abs(firstStroke.endY - firstStroke.startY)

        return when {
            totalStrokes == 1 -> {
                when {
                    verticalDist > horizontalDist * 2 -> if (verticalDist > dp(40f)) 'I' else 'l'
                    horizontalDist > verticalDist * 2 -> 'T'
                    verticalDist > horizontalDist -> if (firstStroke.endX < firstStroke.startX) '7' else '/'
                    else -> if (firstStroke.endY < firstStroke.startY) '7' else 'L'
                }
            }

            totalStrokes == 2 -> {
                val secondStroke = strokes.getOrNull(1)
                if (secondStroke != null &&
                    abs(secondStroke.startX - firstStroke.startX) < dp(20f) &&
                    abs(secondStroke.startY - firstStroke.startY) < dp(20f)
                ) {
                    return 't'
                }
                'H'
            }

            totalStrokes >= 3 -> when (totalStrokes) {
                3 -> 'E'
                4 -> 'A'
                else -> 'M'
            }

            else -> null
        }
    }

    private fun analyzeGestureFallback(strokes: List<InkCaptureView.StrokeInfo>): Char? {
        if (strokes.isEmpty()) return null

        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for (stroke in strokes) {
            minX = minOf(minX, stroke.startX, stroke.endX)
            maxX = maxOf(maxX, stroke.startX, stroke.endX)
            minY = minOf(minY, stroke.startY, stroke.endY)
            maxY = maxOf(maxY, stroke.startY, stroke.endY)
        }

        val width = maxX - minX
        val height = maxY - minY
        val aspectRatio = width / height.coerceAtLeast(1f)

        return when {
            aspectRatio < 0.5 -> when (strokes.size) {
                1 -> 'I'
                2 -> 'H'
                else -> 'M'
            }

            aspectRatio > 2.0 -> when (strokes.size) {
                1 -> '-'
                2 -> '='
                else -> 'Z'
            }

            aspectRatio in 0.8f..1.2f -> when (strokes.size) {
                1 -> 'O'
                2 -> 'X'
                3 -> 'E'
                4 -> 'A'
                else -> 'B'
            }

            else -> when (strokes.size) {
                1 -> 'C'
                2 -> 'H'
                3 -> 'E'
                4 -> 'A'
                else -> 'B'
            }
        }
    }

    private fun normalizeCandidateUltraLenient(candidate: String): Char? {
        if (candidate.isBlank()) return null
        val cleaned = candidate.uppercase().trim()

        for (char in cleaned) {
            when {
                char in 'A'..'Z' -> return char
                char in '0'..'9' -> return char
                char in listOf('@', '$', '!', '&', '*', '#', '%', '+', '<', '>', '[', ']', '{', '}', '(', ')') -> {
                    return mapLooseSymbol(char)
                }
            }
        }

        val upper = cleaned.uppercase()
        return when {
            upper.contains("ZERO") || upper.contains("OH") || upper.contains("O") -> 'O'
            upper.contains("ONE") || upper.contains("L") || upper.contains("I") -> 'I'
            upper.contains("TWO") || upper.contains("TO") -> 'T'
            upper.contains("THREE") -> 'E'
            upper.contains("FOUR") -> 'F'
            upper.contains("FIVE") -> 'V'
            upper.contains("SIX") -> 'X'
            upper.contains("SEVEN") -> 'S'
            upper.contains("EIGHT") -> 'E'
            upper.contains("NINE") -> 'N'
            upper.contains("TEN") -> 'T'
            upper.length == 1 && upper[0] in 'A'..'Z' -> upper[0]
            cleaned.any { it.isLetterOrDigit() } -> cleaned.first { it.isLetterOrDigit() }.uppercaseChar()
            else -> null
        }
    }

    private fun mapLooseSymbol(char: Char): Char {
        return when (char) {
            '@', '&' -> 'A'
            '$' -> 'S'
            '!' , '|' -> 'I'
            '*' , '\\' -> 'X'
            '#' -> 'H'
            '%' -> 'P'
            '+' , '-' -> 'T'
            '<', '>', '[', ']', '{', '}', ',' -> 'C'
            '(', ')', '.' -> 'O'
            '/' -> 'Y'
            else -> char.uppercaseChar()
        }
    }
}
