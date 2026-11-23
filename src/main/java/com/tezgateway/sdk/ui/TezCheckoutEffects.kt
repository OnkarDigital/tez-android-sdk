package com.tezgateway.sdk.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * Lightweight checkout animations — plain Canvas + ValueAnimator, no extra libraries.
 * Every effect respects the system "remove animations" setting (animationsEnabled()).
 */

/** False when the user turned animations off (Developer options / Accessibility). */
internal fun animationsEnabled(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
    else Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

internal fun hapticSuccess(v: View) {
    v.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    )
}

internal fun hapticReject(v: View) {
    v.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    )
}

/** Short horizontal "no" shake. */
internal fun shake(v: View) {
    if (!animationsEnabled(v.context)) return
    val d = v.resources.displayMetrics.density
    ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, -12 * d, 12 * d, -8 * d, 8 * d, -4 * d, 4 * d, 0f).apply {
        duration = 450
        start()
    }
}

/** Endless gentle breathing scale — returns the animator so the caller can cancel it. */
internal fun pulse(v: View, to: Float = 1.12f, periodMs: Long = 700): Animator? {
    if (!animationsEnabled(v.context)) return null
    return ObjectAnimator.ofPropertyValuesHolder(
        v,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, to),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, to)
    ).apply {
        duration = periodMs
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        start()
    }
}

internal fun stopPulse(anim: Animator?, v: View?) {
    anim?.cancel()
    v?.scaleX = 1f
    v?.scaleY = 1f
}

// ─────────────────────────────────────────────────────────────────────────────
// W7 — countdown ring around the checking logo
// ─────────────────────────────────────────────────────────────────────────────

/** Circular "time left" ring: full at the start, empties clockwise from the top. */
internal class TezCountdownRingView(context: Context) : View(context) {

    private val stroke = 5f * resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private var fraction = 1f
    private var animator: ValueAnimator? = null

    fun setColors(arcColor: Int, trackColor: Int) {
        arcPaint.color = arcColor
        trackPaint.color = trackColor
        invalidate()
    }

    fun setArcColor(color: Int) {
        arcPaint.color = color
        invalidate()
    }

    /** Smoothly moves the ring to [target] (0..1) over [durationMs]. */
    fun animateTo(target: Float, durationMs: Long) {
        animator?.cancel()
        val t = target.coerceIn(0f, 1f)
        if (!animationsEnabled(context)) {
            fraction = t; invalidate(); return
        }
        animator = ValueAnimator.ofFloat(fraction, t).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun reset() {
        animator?.cancel()
        fraction = 1f
        invalidate()
    }

    fun stop() { animator?.cancel() }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val left = (width - size) / 2f + stroke / 2f
        val top = (height - size) / 2f + stroke / 2f
        rect.set(left, top, left + size - stroke, top + size - stroke)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        if (fraction > 0f) canvas.drawArc(rect, -90f, 360f * fraction, false, arcPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// W8 / W9 — animated result icon (circle pops, then ✓ or ✗ draws itself)
// ─────────────────────────────────────────────────────────────────────────────

internal class TezResultIconView(context: Context) : View(context) {

    enum class Kind { SUCCESS, FAILURE }

    var kind = Kind.SUCCESS
        set(value) { field = value; buildPath(); invalidate() }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private var hasBorder = false

    private var circleScale = 1f
    private var markProgress = 1f

    private val checkPath = Path()
    private val segment = Path()
    private var measure: PathMeasure? = null
    private var animSet: AnimatorSet? = null

    fun setColors(circleColor: Int, markColor: Int, borderColor: Int? = null) {
        circlePaint.color = circleColor
        markPaint.color = markColor
        hasBorder = borderColor != null
        if (borderColor != null) borderPaint.color = borderColor
        invalidate()
    }

    /** Pop the circle, then draw the mark; [onMarkStart] fires as the ✓/✗ begins. */
    fun play(onMarkStart: () -> Unit) {
        animSet?.cancel()
        if (!animationsEnabled(context)) {
            circleScale = 1f; markProgress = 1f; invalidate()
            onMarkStart()
            return
        }
        circleScale = 0f; markProgress = 0f; invalidate()

        val pop = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            interpolator = OvershootInterpolator(2.2f)
            addUpdateListener { circleScale = it.animatedValue as Float; invalidate() }
        }
        val draw = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { markProgress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) = onMarkStart()
            })
        }
        animSet = AnimatorSet().apply {
            playSequentially(pop, draw)
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildPath()
    }

    private fun buildPath() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        markPaint.strokeWidth = min(w, h) * 0.085f
        borderPaint.strokeWidth = min(w, h) * 0.045f
        checkPath.reset()
        checkPath.moveTo(w * 0.29f, h * 0.52f)
        checkPath.lineTo(w * 0.44f, h * 0.67f)
        checkPath.lineTo(w * 0.72f, h * 0.37f)
        measure = PathMeasure(checkPath, false)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f * circleScale
        val inset = if (hasBorder) borderPaint.strokeWidth / 2f else 0f
        if (r > 0f) {
            canvas.drawCircle(cx, cy, (r - inset).coerceAtLeast(0f), circlePaint)
            if (hasBorder) canvas.drawCircle(cx, cy, (r - inset).coerceAtLeast(0f), borderPaint)
        }
        if (markProgress <= 0f) return

        when (kind) {
            Kind.SUCCESS -> {
                val pm = measure ?: return
                segment.reset()
                pm.getSegment(0f, pm.length * markProgress, segment, true)
                canvas.drawPath(segment, markPaint)
            }
            Kind.FAILURE -> {
                // Two strokes: "\" first, then "/"
                val p1 = (markProgress * 2f).coerceIn(0f, 1f)
                val p2 = ((markProgress - 0.5f) * 2f).coerceIn(0f, 1f)
                val a = 0.34f; val b = 0.66f
                canvas.drawLine(w * a, h * a, w * (a + (b - a) * p1), h * (a + (b - a) * p1), markPaint)
                if (p2 > 0f) {
                    canvas.drawLine(w * b, h * a, w * (b - (b - a) * p2), h * (a + (b - a) * p2), markPaint)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        animSet?.cancel()
        super.onDetachedFromWindow()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// W8 — confetti burst, drawn on a view's overlay (no layout changes needed)
// ─────────────────────────────────────────────────────────────────────────────

internal class ConfettiDrawable private constructor(
    private val originX: Float,
    private val originY: Float,
    colors: IntArray,
    private val density: Float
) : Drawable() {

    private class Piece(
        val vx: Float, val vy: Float,
        val rot: Float, val spin: Float,
        val w: Float, val h: Float,
        val color: Int, val round: Boolean
    )

    private val pieces: List<Piece>
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f                      // seconds since burst
    private val life = 1.5f                 // seconds
    private val gravity = 1500f * density   // px / s²

    init {
        val rnd = Random(System.nanoTime())
        pieces = List(56) {
            // Fan out upwards (−165°..−15°), then gravity pulls them down
            val angle = Math.toRadians(-165.0 + rnd.nextDouble() * 150.0)
            val speed = (380f + rnd.nextFloat() * 620f) * density
            Piece(
                vx = (cos(angle) * speed).toFloat(),
                vy = (sin(angle) * speed).toFloat(),
                rot = rnd.nextFloat() * 360f,
                spin = (rnd.nextFloat() - 0.5f) * 720f,
                w = (4f + rnd.nextFloat() * 4f) * density,
                h = (7f + rnd.nextFloat() * 6f) * density,
                color = colors[rnd.nextInt(colors.size)],
                round = rnd.nextInt(4) == 0
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val alpha = (255 * (1f - (t / life) * (t / life))).toInt().coerceIn(0, 255)
        for (p in pieces) {
            val x = originX + p.vx * t
            val y = originY + p.vy * t + 0.5f * gravity * t * t
            paint.color = p.color
            paint.alpha = alpha
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(p.rot + p.spin * t)
            if (p.round) canvas.drawCircle(0f, 0f, p.w / 2f, paint)
            else canvas.drawRect(-p.w / 2f, -p.h / 2f, p.w / 2f, p.h / 2f, paint)
            canvas.restore()
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Bursts confetti from ([x], [y]) — coordinates relative to [host]. */
        fun burst(host: View, x: Float, y: Float, colors: IntArray) {
            if (!animationsEnabled(host.context) || host.width == 0) return
            val d = ConfettiDrawable(x, y, colors, host.resources.displayMetrics.density)
            d.setBounds(0, 0, host.width, host.height)
            host.overlay.add(d)
            ValueAnimator.ofFloat(0f, d.life).apply {
                duration = (d.life * 1000).toLong()
                interpolator = LinearInterpolator()
                addUpdateListener { d.t = it.animatedValue as Float; d.invalidateSelf() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { host.overlay.remove(d) }
                    override fun onAnimationCancel(animation: Animator) { host.overlay.remove(d) }
                })
                start()
            }
        }
    }
}
