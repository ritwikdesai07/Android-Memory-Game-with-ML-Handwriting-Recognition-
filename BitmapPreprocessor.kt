package com.example.hackerclicker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max

internal object BitmapPreprocessor {
    private const val MODEL_SIZE = 28
    private const val MIN_INK_ALPHA = 16

    data class PreprocessedCharacter(
        val input: FloatArray,
        val originalInkBounds: Rect?,
    )

    /**
     * Produces a 28x28 grayscale float tensor in the same spirit as the Android digit-classifier codelab:
     * crop to ink, center into a square canvas with padding, resize, normalize to [0,1].
     */
    fun preprocess(characterBitmap: Bitmap): PreprocessedCharacter {
        val tightBounds = findInkBounds(characterBitmap)
        val cropped = cropToBounds(characterBitmap, tightBounds)
        val squared = centerIntoSquare(cropped)
        val resized = Bitmap.createScaledBitmap(squared, MODEL_SIZE, MODEL_SIZE, true)

        val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
        resized.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)

        val output = FloatArray(MODEL_SIZE * MODEL_SIZE)
        for (index in pixels.indices) {
            val color = pixels[index]
            val gray = (0.299f * Color.red(color)) + (0.587f * Color.green(color)) + (0.114f * Color.blue(color))
            output[index] = (gray / 255f).coerceIn(0f, 1f)
        }

        val result = PreprocessedCharacter(
            input = output,
            originalInkBounds = tightBounds,
        )
        if (cropped !== characterBitmap && !cropped.isRecycled) {
            cropped.recycle()
        }
        if (squared !== cropped && !squared.isRecycled) {
            squared.recycle()
        }
        if (resized !== squared && !resized.isRecycled) {
            resized.recycle()
        }
        return result
    }

    fun toModelInput(characterCrop: Bitmap): FloatArray = preprocess(characterCrop).input

    private fun findInkBounds(bitmap: Bitmap): Rect? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var left = width
        var top = height
        var right = -1
        var bottom = -1

        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = pixels[(y * width) + x]
                if (!isInkPixel(color)) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }

        if (right < left || bottom < top) {
            return null
        }

        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun cropToBounds(bitmap: Bitmap, bounds: Rect?): Bitmap {
        if (bounds == null) return bitmap
        val safeLeft = bounds.left.coerceIn(0, bitmap.width - 1)
        val safeTop = bounds.top.coerceIn(0, bitmap.height - 1)
        val safeRight = bounds.right.coerceIn(safeLeft + 1, bitmap.width)
        val safeBottom = bounds.bottom.coerceIn(safeTop + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)
    }

    private fun centerIntoSquare(bitmap: Bitmap): Bitmap {
        val side = max(bitmap.width, bitmap.height).coerceAtLeast(1)
        val paddedSide = (side * 1.2f).toInt().coerceAtLeast(side + 2)
        val square = Bitmap.createBitmap(paddedSide, paddedSide, Bitmap.Config.ARGB_8888)

        Canvas(square).apply {
            drawColor(Color.BLACK)
            val left = ((paddedSide - bitmap.width) / 2f)
            val top = ((paddedSide - bitmap.height) / 2f)
            drawBitmap(bitmap, left, top, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }

        return square
    }

    private fun isInkPixel(color: Int): Boolean {
        if (Color.alpha(color) < MIN_INK_ALPHA) return false
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val brightness = max(red, max(green, blue))
        return brightness > 25
    }
}
