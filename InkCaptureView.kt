package com.example.hackerclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.digitalink.Ink

class InkCaptureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF41")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 24f
        alpha = 230
    }

    private val committedPaths = mutableListOf<Path>()
    private var currentPath: Path? = null
    private var currentStrokeBuilder: Ink.Stroke.Builder? = null
    private var inkBuilder = Ink.builder()
    private var strokeCount = 0

    // --- Pause-based segmentation state (for TFLite verifier) ---
    private val segmentPaths = mutableListOf<Path>()
    private var segmentBounds: RectF? = null
    private val segmentedBoxes = mutableListOf<Rect>()
    private val pauseHandler = Handler(Looper.getMainLooper())
    private val pauseRunnable = Runnable { finalizeSegmentIfAny() }
    private var lastTouchUptimeMs: Long = 0L

    var pauseThresholdMs: Long = 500L
    var debugShowSegmentBoxes: Boolean = false

    /**
     * Emits a cropped character bitmap when a pause is detected (~[pauseThresholdMs] after touch ends).
     * Bitmap is a tight crop around the character.
     */
    var onCharacterSegmented: ((HandwritingCodeVerifier.SegmentedChar) -> Unit)? = null
    
    // Store basic stroke info for gesture analysis
    data class StrokeInfo(val startX: Float, val startY: Float, val endX: Float, val endY: Float)
    private val allStrokes = mutableListOf<StrokeInfo>()

    var drawingEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                clearInk()
            }
        }

    // DEBUG: forwards raw touch events to external recognition infrastructure.
    var onTouchEventForwarded: ((MotionEvent) -> Unit)? = null
    var onInkStateChanged: ((Boolean) -> Unit)? = null
    // DEBUG: keeps external ink capture in sync when the canvas is reset.
    var onInkCleared: (() -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled) {
            return false
        }

        onTouchEventForwarded?.invoke(event) // DEBUG

        parent?.requestDisallowInterceptTouchEvent(true)

        val action = event.actionMasked
        val x = event.x
        val y = event.y
        val timestamp = System.currentTimeMillis()
        lastTouchUptimeMs = android.os.SystemClock.uptimeMillis()
        // Any new touch cancels a pending pause-finalization.
        pauseHandler.removeCallbacks(pauseRunnable)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path().apply { moveTo(x, y) }
                segmentPaths.add(currentPath!!)
                updateSegmentBounds(x, y)
                currentStrokeBuilder = Ink.Stroke.builder().also {
                    it.addPoint(Ink.Point.create(x, y, timestamp))
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                currentPath?.lineTo(x, y)
                updateSegmentBounds(x, y)
                currentStrokeBuilder?.addPoint(Ink.Point.create(x, y, timestamp))
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentPath?.lineTo(x, y)
                updateSegmentBounds(x, y)
                currentStrokeBuilder?.addPoint(Ink.Point.create(x, y, timestamp))
                currentPath?.let(committedPaths::add)
                currentStrokeBuilder?.build()?.let { builtStroke ->
                    inkBuilder.addStroke(builtStroke)
                    // Store stroke info for gesture analysis
                    if (builtStroke.points.isNotEmpty()) {
                        val firstPoint = builtStroke.points.first()
                        val lastPoint = builtStroke.points.last()
                        allStrokes.add(StrokeInfo(
                            startX = firstPoint.x,
                            startY = firstPoint.y,
                            endX = lastPoint.x,
                            endY = lastPoint.y
                        ))
                    }
                    strokeCount += 1
                }
                currentPath = null
                currentStrokeBuilder = null
                invalidate()
                onInkStateChanged?.invoke(hasInk())
                // Schedule pause-based character finalization.
                pauseHandler.postDelayed(pauseRunnable, pauseThresholdMs)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        committedPaths.forEach { canvas.drawPath(it, strokePaint) }
        currentPath?.let { canvas.drawPath(it, strokePaint) }

        if (debugShowSegmentBoxes) {
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 255, 0, 60)
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            segmentedBoxes.forEach { r ->
                canvas.drawRect(r, boxPaint)
            }
        }
    }

    fun hasInk(): Boolean = strokeCount > 0

    fun buildInk(): Ink = inkBuilder.build()
    
    fun getAllStrokes(): List<StrokeInfo> = allStrokes.toList()

    fun writingArea(): Pair<Float, Float> = Pair(width.coerceAtLeast(1).toFloat(), height.coerceAtLeast(1).toFloat())

    fun clearInk() {
        committedPaths.clear()
        currentPath = null
        currentStrokeBuilder = null
        inkBuilder = Ink.builder()
        allStrokes.clear()
        segmentPaths.clear()
        segmentBounds = null
        segmentedBoxes.clear()
        strokeCount = 0
        pauseHandler.removeCallbacks(pauseRunnable)
        invalidate()
        onInkStateChanged?.invoke(false)
        onInkCleared?.invoke() // DEBUG
    }

    private fun updateSegmentBounds(x: Float, y: Float) {
        val b = segmentBounds
        if (b == null) {
            segmentBounds = RectF(x, y, x, y)
        } else {
            if (x < b.left) b.left = x
            if (x > b.right) b.right = x
            if (y < b.top) b.top = y
            if (y > b.bottom) b.bottom = y
        }
    }

    private fun finalizeSegmentIfAny() {
        // Ensure we really had a pause (avoid firing if another touch just happened).
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTouchUptimeMs < pauseThresholdMs) return

        val bounds = segmentBounds ?: return
        if (segmentPaths.isEmpty()) return

        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)

        // Render just the segment's paths to a bitmap, then crop.
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(full).apply {
            drawColor(Color.BLACK)
            segmentPaths.forEach { drawPath(it, strokePaint) }
        }

        val pad = strokePaint.strokeWidth.toInt().coerceAtLeast(12)
        val l = (bounds.left.toInt() - pad).coerceIn(0, w - 1)
        val t = (bounds.top.toInt() - pad).coerceIn(0, h - 1)
        val r = (bounds.right.toInt() + pad).coerceIn(l + 1, w)
        val b = (bounds.bottom.toInt() + pad).coerceIn(t + 1, h)

        val cropRect = Rect(l, t, r, b)
        val crop = Bitmap.createBitmap(full, l, t, r - l, b - t)
        full.recycle()

        if (debugShowSegmentBoxes) {
            segmentedBoxes.add(Rect(cropRect))
        }

        onCharacterSegmented?.invoke(HandwritingCodeVerifier.SegmentedChar(crop, cropRect))

        // Reset segment state for next character.
        segmentPaths.clear()
        segmentBounds = null
        invalidate()
    }
}
