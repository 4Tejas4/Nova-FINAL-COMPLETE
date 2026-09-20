package com.nova.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/** Tiny animated equalizer bars shown next to the status line. */
class NovaEqView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var phase = 0f
    private var active = false

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF9330.toInt() }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            if (active) postInvalidateOnAnimation()
        }
    }

    fun setActive(value: Boolean) {
        active = value
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = 4
        val gap = width * 0.14f
        val barW = (width - gap * (n - 1)) / n
        val mid = height * 0.5f
        for (i in 0 until n) {
            val h = if (active) {
                val s = abs(sin((phase * 2f * Math.PI + i * 0.9).toFloat()))
                height * (0.30f + 0.62f * s)
            } else {
                height * 0.34f
            }
            val left = i * (barW + gap)
            canvas.drawRoundRect(left, mid - h * 0.5f, left + barW, mid + h * 0.5f, barW * 0.5f, barW * 0.5f, barPaint)
        }
    }
}
