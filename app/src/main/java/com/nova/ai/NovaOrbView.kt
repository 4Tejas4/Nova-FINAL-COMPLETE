package com.nova.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Nova orb: an animated, glowing multi-ring reactor drawn entirely in code,
 * closely following the reference artwork — concentric orange rings, rotating
 * arc segments, a tick ring, orbiting nodes, a hot radial core and the NOVA
 * wordmark. Animation intensity can be boosted while listening/speaking.
 */
class NovaOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var phase = 0f            // master animation phase (radians)
    private var intensity = 1f        // 0.5 calm … 2.5 excited
    private var showText = true

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        letterSpacing = 0.22f
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
        duration = 16_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    fun setIntensity(value: Float) { intensity = value.coerceIn(0.5f, 2.5f) }
    fun setShowingText(value: Boolean) { showText = value }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val R = min(width, height) * 0.5f * 0.94f
        val pulse = 0.5f + 0.5f * sin(phase * 2f)                 // 0..1 breathing
        val boost = 0.55f + 0.45f * intensity                      // glow strength

        // ---- outer soft glow -------------------------------------------------
        glowPaint.shader = RadialGradient(
            cx, cy, R * 1.25f,
            intArrayOf(
                Color.argb((46 * boost + 14 * pulse * intensity).toInt().coerceIn(0, 120), 255, 130, 40),
                Color.argb((18 * boost).toInt().coerceIn(0, 60), 255, 110, 30),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, R * 1.25f, glowPaint)

        // ---- static faint outer ring ----------------------------------------
        ringPaint.strokeWidth = R * 0.012f
        ringPaint.color = Color.argb(70, 255, 150, 70)
        canvas.drawCircle(cx, cy, R * 0.965f, ringPaint)

        // ---- tick ring -------------------------------------------------------
        val ticks = 72
        tickPaint.strokeWidth = R * 0.008f
        canvas.save()
        canvas.rotate(phase * 8f, cx, cy)
        for (i in 0 until ticks) {
            val major = i % 6 == 0
            val len = if (major) R * 0.045f else R * 0.022f
            tickPaint.color = if (major) Color.argb(190, 255, 190, 120) else Color.argb(90, 255, 160, 80)
            val a = (i.toFloat() / ticks) * 2f * PI.toFloat()
            val cosA = cos(a); val sinA = sin(a)
            canvas.drawLine(
                cx + cosA * (R * 0.915f - len), cy + sinA * (R * 0.915f - len),
                cx + cosA * R * 0.915f, cy + sinA * R * 0.915f, tickPaint
            )
        }
        canvas.restore()

        // ---- bright arc ring A (clockwise, 3 long arcs) ----------------------
        drawArcRing(canvas, cx, cy, R * 0.845f, arcs = 3, sweep = 78f,
            width = R * 0.030f, colorBase = 255, rotDeg = phase * 20f,
            color = Color.rgb(255, 150, 55), halo = true)

        // ---- arc ring B (counter-clockwise, 4 shorter arcs) ------------------
        drawArcRing(canvas, cx, cy, R * 0.755f, arcs = 4, sweep = 52f,
            width = R * 0.022f, colorBase = 235, rotDeg = -phase * 32f,
            color = Color.rgb(255, 185, 104), halo = true)

        // ---- fine dashed ring -------------------------------------------------
        canvas.save()
        canvas.rotate(phase * 13f, cx, cy)
        ringPaint.strokeWidth = R * 0.010f
        ringPaint.color = Color.argb(120, 255, 170, 90)
        rect.set(cx - R * 0.665f, cy - R * 0.665f, cx + R * 0.665f, cy + R * 0.665f)
        var a = 0f
        while (a < 360f) {
            canvas.drawArc(rect, a, 7f, false, ringPaint)
            a += 16f
        }
        canvas.restore()

        // ---- inner thin ring ---------------------------------------------------
        ringPaint.strokeWidth = R * 0.008f
        ringPaint.color = Color.argb(150, 255, 200, 140)
        canvas.drawCircle(cx, cy, R * 0.545f, ringPaint)

        // ---- orbiting nodes ----------------------------------------------------
        nodePaint.color = Color.argb(230, 255, 205, 130)
        for (i in 0 until 3) {
            val a = -phase * 46f * (PI.toFloat() / 180f) + i * (2f * PI.toFloat() / 3f)
            val nx = cx + cos(a) * R * 0.63f
            val ny = cy + sin(a) * R * 0.63f
            canvas.drawCircle(nx, ny, R * 0.020f, nodePaint)
        }

        // ---- hot core ------------------------------------------------------------
        val coreR = R * (0.50f + 0.022f * sin(phase * 3.1f)) * (0.92f + 0.10f * intensity * 0.35f)
        corePaint.shader = RadialGradient(
            cx, cy, coreR,
            intArrayOf(
                Color.argb(235, 255, 244, 226),
                Color.argb(220, 255, 166, 66),
                Color.argb(120, 232, 100, 27),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreR, corePaint)

        // ---- wordmark -------------------------------------------------------------
        if (showText && R > 60f) {
            textPaint.textSize = R * 0.17f
            textPaint.clearShadowLayer()
            val yOffset = (textPaint.ascent() + textPaint.descent()) * 0.5f
            canvas.drawText("NOVA", cx, cy - yOffset, textPaint)
        }
    }

    private fun drawArcRing(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        arcs: Int, sweep: Float, width: Float, colorBase: Int,
        rotDeg: Float, color: Int, halo: Boolean
    ) {
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val gap = 360f / arcs - sweep
        canvas.save()
        canvas.rotate(rotDeg, cx, cy)
        for (i in 0 until arcs) {
            val start = i * (sweep + gap)
            if (halo) {
                arcPaint.strokeWidth = width * 2.1f
                arcPaint.color = Color.argb((colorBase * 0.16f).toInt(), 255, 140, 45)
                canvas.drawArc(rect, start, sweep, false, arcPaint)
            }
            arcPaint.strokeWidth = width
            arcPaint.color = Color.argb(colorBase, (color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
            canvas.drawArc(rect, start, sweep, false, arcPaint)
        }
        canvas.restore()
    }
}
