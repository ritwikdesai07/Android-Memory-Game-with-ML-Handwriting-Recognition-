package com.example.hackerclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.min

enum class ScreenMode {
    IDLE,
    MEMORIZE,
    BLACKOUT,
    DRAW,
    RESULT,
}

data class TerminalRenderState(
    val mode: ScreenMode = ScreenMode.IDLE,
    val idleLines: List<String> = emptyList(),
    val code: String = "",
    val entered: List<Char?> = emptyList(),
    val hintIndices: Set<Int> = emptySet(),
    val correctness: List<Boolean?> = emptyList(),
    val header: String = "",
    val footer: String = "",
    val cursorIndex: Int = 0,
    val cursorVisible: Boolean = true,
    val glitchAmount: Float = 0f,
)

class TerminalScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var scanSweepProgress: Float = -1f
        set(value) {
            field = value
            invalidate()
        }

    var codeGlowIntensity: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var verificationGlowProgress: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var stabilizationProgress: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var renderState: TerminalRenderState = TerminalRenderState()
        set(value) {
            field = value
            invalidate()
        }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A0B180B")
    }

    private val scanlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22000000")
        strokeWidth = 2f
    }

    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#39FF14")
        typeface = Typeface.MONOSPACE
        textSize = sp(14f)
        letterSpacing = 0.08f
    }

    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF41")
        typeface = Typeface.MONOSPACE
        textSize = sp(18f)
    }

    private val dimPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A4D2E")
        typeface = Typeface.MONOSPACE
        textSize = sp(16f)
    }

    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80D3FFA1")
        typeface = Typeface.MONOSPACE
        textSize = sp(16f)
    }

    private val failPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF003C")
        typeface = Typeface.MONOSPACE
        textSize = sp(18f)
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#39FF14")
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val stabilizationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val contentBounds = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        contentBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(contentBounds, dp(18f), dp(18f), glowPaint)

        when (renderState.mode) {
            ScreenMode.IDLE -> drawIdle(canvas)
            ScreenMode.MEMORIZE -> drawMemorize(canvas)
            ScreenMode.BLACKOUT -> drawBlackout(canvas)
            ScreenMode.DRAW -> drawDraw(canvas)
            ScreenMode.RESULT -> drawResult(canvas)
        }

        drawSweep(canvas)
        drawStabilizationFrame(canvas)
        drawScanlines(canvas)
    }

    private fun drawIdle(canvas: Canvas) {
        drawHeader(canvas, renderState.header)
        val originalTextSize = bodyPaint.textSize
        val originalScaleX = bodyPaint.textScaleX
        bodyPaint.textSize = sp(5f)
        bodyPaint.textScaleX = 0.72f

        val footerY = height - dp(18f)
        val bottomPadding = dp(8f)
        val topLimit = dp(44f)
        val lineHeight = max(bodyPaint.fontSpacing, dp(8f))
        val availableHeight = (footerY - bottomPadding - topLimit).coerceAtLeast(0f)
        val maxVisibleLines = max(1, (availableHeight / lineHeight).toInt())
        val visibleLines = renderState.idleLines.takeLast(maxVisibleLines)
        var y = footerY - bottomPadding - (lineHeight * (visibleLines.size - 1))
        visibleLines.forEach { line ->
            canvas.drawText(line, dp(18f), y, bodyPaint)
            y += lineHeight
        }

        bodyPaint.textSize = originalTextSize
        bodyPaint.textScaleX = originalScaleX
        drawFooter(canvas, renderState.footer)
    }

    private fun drawMemorize(canvas: Canvas) {
        drawHeader(canvas, renderState.header)
        val horizontalPadding = dp(24f)
        val availableWidth = (width - (horizontalPadding * 2f)).coerceAtLeast(dp(80f))
        val baseCodeSize = min(width * 0.19f, height * 0.18f).coerceAtLeast(sp(22f))
        bodyPaint.textSize = baseCodeSize
        val rawCodeWidth = bodyPaint.measureText(renderState.code).coerceAtLeast(1f)
        val scaledCodeSize = (baseCodeSize * (availableWidth / rawCodeWidth)).coerceAtMost(baseCodeSize)
        val codeSize = scaledCodeSize.coerceAtLeast(sp(18f))
        bodyPaint.textSize = codeSize
        applyCodeGlow(bodyPaint)
        val codeWidth = bodyPaint.measureText(renderState.code)
        val x = (width - codeWidth) / 2f
        val y = height * 0.5f
        canvas.drawText(renderState.code, x, y, bodyPaint)
        clearCodeGlow(bodyPaint)
        bodyPaint.textSize = sp(18f)
        drawFooter(canvas, renderState.footer)
    }

    private fun drawBlackout(canvas: Canvas) {
        drawHeader(canvas, renderState.header)
        val alpha = (renderState.glitchAmount * 180).toInt().coerceIn(0, 180)
        val glitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, 57, 255, 20)
            strokeWidth = dp(3f)
        }
        for (index in 0..6) {
            val y = height * (0.16f + (index * 0.11f))
            val offset = (renderState.glitchAmount * dp(28f)) * if (index % 2 == 0) 1 else -1
            canvas.drawLine(dp(20f) + offset, y, width - dp(20f) - offset, y, glitchPaint)
        }
        if (renderState.cursorVisible) {
            canvas.drawRect(dp(20f), dp(48f), dp(34f), dp(70f), cursorPaint)
        }
        drawFooter(canvas, renderState.footer)
    }

    private fun drawDraw(canvas: Canvas) {
        drawHeader(canvas, renderState.header)
        drawCodeSlots(canvas, resultMode = false)
        drawFooter(canvas, renderState.footer)
    }

    private fun drawResult(canvas: Canvas) {
        drawHeader(canvas, renderState.header)
        val labelPaint = TextPaint(headerPaint).apply { textSize = sp(12f) }
        canvas.drawText("TRACE", dp(18f), height * 0.34f, labelPaint)
        drawCodeSlots(canvas, resultMode = true)
        drawFooter(canvas, renderState.footer)
    }

    private fun drawCodeSlots(canvas: Canvas, resultMode: Boolean) {
        val code = renderState.code
        if (code.isEmpty()) {
            return
        }

        val slotCount = code.length
        val slotWidth = min((width - dp(36f)) / slotCount.toFloat(), dp(48f))
        val startX = (width - (slotWidth * slotCount)) / 2f + slotWidth / 2f
        val hintY = height * 0.28f
        val charY = height * if (resultMode) 0.52f else 0.52f
        val codeTextSize = min(slotWidth * 1.5f, dp(42f))

        bodyPaint.textSize = codeTextSize
        dimPaint.textSize = codeTextSize
        failPaint.textSize = codeTextSize
        hintPaint.textSize = max(sp(14f), codeTextSize * 0.55f)

        code.forEachIndexed { index, codeChar ->
            val x = startX + (slotWidth * index)

            if (!resultMode && index in renderState.hintIndices && renderState.entered.getOrNull(index) == null) {
                drawCenteredChar(canvas, codeChar.toString(), x, hintY, hintPaint)
            }

            val enteredChar = renderState.entered.getOrNull(index)
            val displayChar = enteredChar ?: '_'
            val paint = when {
                resultMode && renderState.correctness.getOrNull(index) == false -> failPaint
                displayChar == '_' -> dimPaint
                else -> bodyPaint
            }
            if (paint === bodyPaint) {
                applyCodeGlow(bodyPaint)
            } else {
                clearCodeGlow(paint)
            }
            drawCenteredChar(canvas, displayChar.toString(), x, charY, paint)
            if (paint === bodyPaint) clearCodeGlow(bodyPaint)

            if (!resultMode && displayChar == '_' && index == renderState.cursorIndex && renderState.cursorVisible) {
                canvas.drawRect(
                    x - (slotWidth * 0.26f),
                    charY + dp(8f),
                    x + (slotWidth * 0.26f),
                    charY + dp(14f),
                    cursorPaint,
                )
            }
        }

        bodyPaint.textSize = sp(18f)
        dimPaint.textSize = sp(16f)
        failPaint.textSize = sp(18f)
        hintPaint.textSize = sp(16f)
    }

    private fun drawHeader(canvas: Canvas, text: String) {
        val startX = dp(8f)
        var baselineY = dp(10f)
        text.split('\n').forEach { line ->
            canvas.drawText(line, startX, baselineY, headerPaint)
            baselineY += headerPaint.fontSpacing
        }
    }

    private fun drawFooter(canvas: Canvas, text: String) {
        canvas.drawText(text, dp(18f), height - dp(18f), headerPaint)
    }

    private fun drawCenteredChar(canvas: Canvas, text: String, x: Float, baselineY: Float, paint: TextPaint) {
        val width = paint.measureText(text)
        canvas.drawText(text, x - (width / 2f), baselineY, paint)
    }

    private fun drawScanlines(canvas: Canvas) {
        var lineY = 0f
        while (lineY < height) {
            val alpha = (0x22 + (stabilizationProgress * 36f).toInt()).coerceIn(0, 255)
            scanlinePaint.alpha = alpha
            canvas.drawLine(0f, lineY, width.toFloat(), lineY, scanlinePaint)
            lineY += dp(4f)
        }
    }

    private fun drawSweep(canvas: Canvas) {
        if (scanSweepProgress < 0f || scanSweepProgress > 1.2f) return

        val bandHeight = height * 0.24f
        val centerY = (height * scanSweepProgress)
        sweepPaint.shader = LinearGradient(
            0f,
            centerY - (bandHeight / 2f),
            0f,
            centerY + (bandHeight / 2f),
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(70, 132, 255, 160),
                Color.argb(28, 210, 255, 218),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.45f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(contentBounds, dp(18f), dp(18f), sweepPaint)
        sweepPaint.shader = null
    }

    private fun drawStabilizationFrame(canvas: Canvas) {
        if (stabilizationProgress <= 0f) return
        stabilizationPaint.color = Color.argb(
            (90f * stabilizationProgress).toInt().coerceIn(0, 255),
            110,
            255,
            150,
        )
        val inset = dp(6f) + ((1f - stabilizationProgress) * dp(10f))
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            dp(14f),
            dp(14f),
            stabilizationPaint,
        )
    }

    private fun applyCodeGlow(paint: TextPaint) {
        val intensity = (codeGlowIntensity + verificationGlowProgress).coerceIn(0f, 1.6f)
        if (intensity <= 0f) {
            clearCodeGlow(paint)
            return
        }
        val radius = dp(5f) + (dp(8f) * intensity)
        val alpha = (110f + 70f * intensity).toInt().coerceIn(0, 255)
        paint.setShadowLayer(radius, 0f, 0f, Color.argb(alpha, 120, 255, 140))
    }

    private fun clearCodeGlow(paint: TextPaint) {
        paint.clearShadowLayer()
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }
}
