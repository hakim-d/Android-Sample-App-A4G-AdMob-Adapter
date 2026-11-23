package com.hakim_ma.a4g.ui.pixel

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.hakim_ma.a4g.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.random.Random

/**
 * Pixel-art confetti: square particles snapped to the art-pixel grid, with gravity, drag and a
 * "flip" (width pulses as the piece turns over). Code-drawn effect only, no sprites.
 */
class PixelConfettiView(context: Context, private val originX: Float, private val originY: Float) : View(context) {
    private val u = context.px()
    private val paint = Paint().apply { isAntiAlias = false }
    private val palette = intArrayOf(
        0xFFFF8800.toInt(), // Ad4Game orange
        0xFFFFB45C.toInt(), // light orange
        0xFFF2B134.toInt(), // saffron
        0xFF1F5FA8.toInt(), // zellige blue
        0xFF4FA66A.toInt(), // mint
        0xFFC0392B.toInt(), // red
        0xFFF4ECD8.toInt(), // cream
    )

    private class Piece(var x: Float, var y: Float, var vx: Float, var vy: Float,
                        val size: Int, val color: Int, var phase: Float, val spin: Float)

    private val pieces = List(170) {
        val angle = Math.toRadians(-90.0 + Random.nextDouble(-70.0, 70.0))
        val speed = Random.nextDouble(0.55, 1.45).toFloat() * context.resources.displayMetrics.heightPixels
        Piece(
            originX + Random.nextFloat() * 0.3f * originX - 0.15f * originX, originY,
            (cos(angle) * speed).toFloat(), (kotlin.math.sin(angle) * speed).toFloat(),
            size = Random.nextInt(4, 8), color = palette[Random.nextInt(palette.size)],
            phase = Random.nextFloat() * 6.28f, spin = Random.nextFloat() * 14f + 6f,
        )
    }
    private var alphaAll = 255
    private var last = 0L

    fun start(onEnd: () -> Unit) {
        val gravity = context.resources.displayMetrics.heightPixels * 1.5f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000; interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val now = a.currentPlayTime
                val dt = ((now - last).coerceIn(0, 50)) / 1000f
                last = now
                pieces.forEach { p ->
                    p.vx *= (1f - 1.8f * dt); p.vy = p.vy * (1f - 1.8f * dt) + gravity * dt
                    p.x += p.vx * dt; p.y += p.vy * dt; p.phase += p.spin * dt
                }
                val f = a.animatedFraction
                alphaAll = if (f < 0.75f) 255 else ((1f - (f - 0.75f) / 0.25f) * 255).toInt()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onEnd()
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        pieces.forEach { p ->
            val h = p.size * u
            // Flip: width shrinks to one art pixel and grows back, in whole art pixels.
            val w = maxOf(1f, floor(p.size * abs(cos(p.phase)) + 0.5f)) * u
            val x = floor(p.x / u) * u; val y = floor(p.y / u) * u
            paint.color = p.color; paint.alpha = alphaAll
            canvas.drawRect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, paint)
        }
    }
}

object PixelConfetti {
    /** Bursts confetti over the whole activity, with an optional pixel "+10 COINS" pop. */
    fun burst(activity: Activity, label: String?) {
        val reduce = Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (reduce) return
        val decor = activity.window.decorView as ViewGroup
        val w = decor.width.toFloat(); val h = decor.height.toFloat()
        val layer = FrameLayout(activity).apply { isClickable = false; isFocusable = false }
        val confetti = PixelConfettiView(activity, w / 2, h * 0.5f)
        layer.addView(confetti, FrameLayout.LayoutParams(-1, -1))
        label?.let {
            val pop = TextView(activity).apply {
                text = it; pixelText(40f, bold = true, colorRes = R.color.on_brand); isAllCaps = true
                background = activity.panel(R.color.brand_orange, highlight = R.color.brand_orange_light).apply { shadowUnits = 2 }
                setPadding(activity.dp(20), activity.dp(10), activity.dp(24), activity.dp(14))
                scaleX = 0f; scaleY = 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            layer.addView(pop, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { bottomMargin = (h * 0.16f).toInt() })
            pop.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(2.2f))
                .withEndAction { pop.animate().alpha(0f).translationY(-activity.dp(40).toFloat()).setStartDelay(1300).setDuration(500).start() }
                .start()
        }
        decor.addView(layer, ViewGroup.LayoutParams(-1, -1))
        decor.announceForAccessibility("Reward earned${label?.let { ": $it" } ?: ""}")
        confetti.start { decor.removeView(layer) }
    }
}
