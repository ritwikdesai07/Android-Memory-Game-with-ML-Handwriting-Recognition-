package com.example.hackerclicker

import android.content.Context
import com.google.mlkit.vision.digitalink.RecognitionResult

/**
 * Ultra-lenient character recognition helper.
 * Tries multiple strategies to extract a character from messy handwriting.
 */
object RecognitionHelper {
    
    /**
     * Extracts a character from ML Kit recognition result using ultra-lenient matching.
     * Returns null only if absolutely nothing can be extracted.
     */
    fun extractCharacter(result: RecognitionResult): Char? {
        return try {
            // PASS 1: Try top candidates with lenient normalization
            for (candidate in result.candidates.take(5)) {
                val normalized = normalizeLenient(candidate.text)
                if (normalized != null) {
                    return normalized
                }
            }
            
            // PASS 2: Try ALL candidates
            for (candidate in result.candidates) {
                val normalized = normalizeUltraLenient(candidate.text)
                if (normalized != null) {
                    return normalized
                }
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Lenient normalization - accepts letters and digits with common mappings.
     */
    private fun normalizeLenient(text: String): Char? {
        if (text.isNullOrBlank()) return null
        
        val cleaned = text.uppercase().trim()
        
        for (char in cleaned) {
            when {
                char in 'A'..'Z' -> return char
                char in '0'..'9' -> return char
                // Common symbol mappings
                char == '@' -> return 'A'
                char == '$' -> return 'S'
                char == '!' -> return 'I'
                char == '&' -> return 'A'
                char == '*' -> return 'X'
                char == '#' -> return 'H'
                char == '%' -> return 'P'
                char == '+' -> return 'T'
                char == '<' || char == '>' || char == '[' || char == ']' || 
                     char == '{' || char == '}' -> return 'C'
                char == '(' || char == ')' -> return 'O'
                char == '.' -> return 'O'
                char == ',' -> return 'C'
                char == '-' -> return 'T'
                char == '/' -> return 'Y'
                char == '\\' -> return 'X'
                char == '|' -> return 'I'
            }
        }
        
        return null
    }
    
    /**
     * Ultra-lenient normalization - will accept ANYTHING and turn it into a character.
     */
    private fun normalizeUltraLenient(text: String): Char? {
        if (text.isNullOrBlank()) return null
        
        val cleaned = text.uppercase().trim()
        
        // First pass: look for any alphanumeric
        for (char in cleaned) {
            if (char.isLetterOrDigit()) {
                return char
            }
        }
        
        // Second pass: map any symbol to a letter
        for (char in cleaned) {
            return when (char) {
                in 'A'..'Z', in '0'..'9' -> char
                '@', '&', 'a' -> 'A'
                'b' -> 'B'
                'c', '<', '>', '[', ']', '{', '}', ',', '.' -> 'C'
                'd' -> 'D'
                'e' -> 'E'
                'f' -> 'F'
                'g', '9' -> 'G'
                'h', '#' -> 'H'
                'i', '!', '|', 'l' -> 'I'
                'j' -> 'J'
                'k' -> 'K'
                'l', '1' -> 'L'
                'm' -> 'M'
                'n' -> 'N'
                'o', '0', '(', ')' -> 'O'
                'p', '%' -> 'P'
                'q' -> 'Q'
                'r' -> 'R'
                's', '$', '5' -> 'S'
                't', '+', '-' -> 'T'
                'u' -> 'U'
                'v' -> 'V'
                'w' -> 'W'
                'x', '*', '\\' -> 'X'
                'y', '/' -> 'Y'
                'z', '2' -> 'Z'
                else -> 'A'  // Absolute fallback
            }
        }
        
        return null
    }
    
    /**
     * Analyzes simple stroke patterns for gesture-based recognition.
     * Used as a fallback when ML Kit fails.
     */
    data class StrokeInfo(val startX: Float, val startY: Float, val endX: Float, val endY: Float)
    
    fun analyzeSimpleGesture(strokes: List<StrokeInfo>): Char? {
        if (strokes.isEmpty()) return null
        
        val totalStrokes = strokes.size
        val first = strokes.firstOrNull() ?: return null
        
        val width = kotlin.math.abs(first.endX - first.startX)
        val height = kotlin.math.abs(first.endY - first.startY)
        
        return when {
            totalStrokes == 1 -> {
                when {
                    height > width * 2 -> 'I'  // Vertical
                    width > height * 2 -> 'T'  // Horizontal
                    first.endX < first.startX && first.endY < first.startY -> '7'  // Diagonal up-left
                    first.endX > first.startX && first.endY > first.startY -> 'L'  // Diagonal down-right
                    else -> 'C'  // Default curve
                }
            }
            totalStrokes == 2 -> 'H'
            totalStrokes == 3 -> 'E'
            totalStrokes >= 4 -> 'A'
            else -> null
        }
    }
}
