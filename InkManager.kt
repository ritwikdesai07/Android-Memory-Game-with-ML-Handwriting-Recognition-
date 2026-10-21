package com.example.hackerclicker.recognition

import android.os.SystemClock
import android.view.MotionEvent
import com.google.mlkit.vision.digitalink.Ink

/**
 * Captures touch input and converts it into ML Kit ink strokes.
 */
class InkManager {

    private val strokes = mutableListOf<Ink.Stroke>()
    private var currentStrokeBuilder: Ink.Stroke.Builder? = null

    fun onTouchEvent(event: MotionEvent) {
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                currentStrokeBuilder = Ink.Stroke.builder().apply {
                    addPoint(createPoint(event.x, event.y))
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val strokeBuilder = currentStrokeBuilder ?: return
                val historySize = event.historySize
                for (index in 0 until historySize) {
                    strokeBuilder.addPoint(
                        createPoint(
                            event.getHistoricalX(index),
                            event.getHistoricalY(index),
                        )
                    )
                }
                strokeBuilder.addPoint(createPoint(event.x, event.y))
            }

            MotionEvent.ACTION_UP -> {
                val strokeBuilder = currentStrokeBuilder ?: return
                strokeBuilder.addPoint(createPoint(event.x, event.y))
                strokes += strokeBuilder.build()
                currentStrokeBuilder = null
            }

            MotionEvent.ACTION_CANCEL -> {
                currentStrokeBuilder = null
            }
        }
    }

    fun buildInk(): Ink {
        val builder = Ink.builder()
        strokes.forEach(builder::addStroke)
        return builder.build()
    }

    fun hasInk(): Boolean = strokes.isNotEmpty() || currentStrokeBuilder != null

    fun clear() {
        strokes.clear()
        currentStrokeBuilder = null
    }

    private fun createPoint(x: Float, y: Float): Ink.Point {
        return Ink.Point.create(x, y, SystemClock.uptimeMillis())
    }
}
