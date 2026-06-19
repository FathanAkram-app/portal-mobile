package com.nexxuz.portal.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.nexxuz.portal.R
import kotlin.math.max

/**
 * Animated "cyber command" backdrop that mirrors the desktop portal:
 *  - a soft radial glow centred on screen
 *  - faint concentric radar rings
 *  - a slowly rotating radar sweep (12s/rev, like the CSS radar-sweep animation)
 *
 * Drawn behind all content; cheap enough to run continuously.
 */
class RadarBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val primary = ContextCompat.getColor(context, R.color.primary)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ColorUtils.setAlphaComponent(primary, 16) // ~6%
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var angle = 0f
    private var cx = 0f
    private var cy = 0f
    private var maxRadius = 0f
    private val ringGap = dp(78f)

    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        maxRadius = max(w, h) * 0.85f

        // Soft central glow.
        glowPaint.shader = RadialGradient(
            cx, cy, maxRadius,
            intArrayOf(
                ColorUtils.setAlphaComponent(primary, 26), // ~10% centre
                ColorUtils.setAlphaComponent(primary, 0)
            ),
            floatArrayOf(0f, 0.7f),
            Shader.TileMode.CLAMP
        )

        // Rotating sweep: transparent everywhere, brightening to a leading edge.
        sweepPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                ColorUtils.setAlphaComponent(primary, 0),
                ColorUtils.setAlphaComponent(primary, 0),
                ColorUtils.setAlphaComponent(primary, 70)
            ),
            floatArrayOf(0f, 0.75f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (maxRadius <= 0f) return

        // 1. Central glow
        canvas.drawCircle(cx, cy, maxRadius, glowPaint)

        // 2. Concentric rings
        var r = ringGap
        while (r < maxRadius) {
            canvas.drawCircle(cx, cy, r, ringPaint)
            r += ringGap
        }

        // 3. Rotating radar sweep
        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawCircle(cx, cy, maxRadius, sweepPaint)
        canvas.restore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 12_000L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
