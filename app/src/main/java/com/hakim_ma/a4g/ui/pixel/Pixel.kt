package com.hakim_ma.a4g.ui.pixel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.hakim_ma.a4g.R
import kotlin.math.roundToInt

/** One "art pixel" in screen pixels: always a whole number, so every edge is crisp. */
fun Context.px(): Float = (resources.displayMetrics.density * 1.5f).roundToInt().toFloat().coerceAtLeast(2f)
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.color(id: Int): Int = ContextCompat.getColor(this, id)

object PixelFonts {
    private var body: Typeface? = null
    private var display: Typeface? = null
    /** Body copy: Pixel AE (CC0), a bitmap-grid font drawn without anti-aliasing. */
    fun body(c: Context) = body ?: ResourcesCompat.getFont(c, R.font.pixel_ae)!!.also { body = it }
    /** Titles, buttons, stamps: Handjet (OFL, variable: wght + element shape axes). */
    fun display(c: Context) = display ?: ResourcesCompat.getFont(c, R.font.handjet)!!.also { display = it }

    /** Handjet is narrow with a small x-height: same sp reads ~35% smaller than Pixel AE. */
    const val DISPLAY_SCALE = 1.35f
}

/**
 * Pixel-style text. bold = Handjet display face (weight 700), otherwise Pixel AE body face.
 * Pixel AE has no anti-aliasing so its glyph pixels stay hard-edged; Handjet is a vector
 * grid font and needs anti-aliasing to keep its dots even.
 */
fun TextView.pixelText(sizeSp: Float, bold: Boolean = false, colorRes: Int = R.color.on_surface) {
    if (bold) {
        typeface = PixelFonts.display(context)
        if (android.os.Build.VERSION.SDK_INT >= 26) fontVariationSettings = "'wght' 700"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp * PixelFonts.DISPLAY_SCALE)
        letterSpacing = 0.05f // Handjet is tightly fitted; open it up for legibility
        paint.isAntiAlias = true
    } else {
        typeface = PixelFonts.body(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        letterSpacing = 0f
        paint.isAntiAlias = false
    }
    includeFontPadding = false
    setTextColor(context.color(colorRes))
}

fun TextView.monoText(sizeSp: Float = 12f, colorRes: Int = R.color.on_surface) {
    typeface = Typeface.MONOSPACE
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(context.color(colorRes))
}

/**
 * Game-UI panel: rectangle with 1-pixel notched corners, a hard 1px outline, an optional top
 * highlight line and a hard drop shadow (no blur). Everything is drawn on the [u] pixel grid.
 */
class PixelFrameDrawable(
    private val u: Float,
    var fill: Int,
    var outline: Int,
    var shadow: Int? = null,
    var shadowUnits: Int = 1,
    var highlight: Int? = null,
    var dashed: Boolean = false,
    var outlineUnits: Int = 1,
) : Drawable() {
    private val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val strokePaint = Paint().apply {
        isAntiAlias = false; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE
    }
    var pressed = false
        set(v) { field = v; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val so = if (shadow != null) shadowUnits * u else 0f
        val dx = if (pressed) so else 0f
        val r = RectF(b.left + dx, b.top + dx, b.right - so + dx, b.bottom - so + dx)
        if (shadow != null && !pressed) {
            paint.color = shadow!!
            canvas.drawPath(notched(RectF(r.left + so, r.top + so, r.right + so, r.bottom + so), u), paint)
        }
        val ow = outlineUnits * u
        if (dashed) {
            paint.color = fill
            canvas.drawPath(notched(r, u), paint)
            strokePaint.color = outline
            strokePaint.strokeWidth = ow
            strokePaint.pathEffect = DashPathEffect(floatArrayOf(u * 3, u * 2), 0f)
            val h = ow / 2
            canvas.drawPath(notched(RectF(r.left + h, r.top + h, r.right - h, r.bottom - h), u), strokePaint)
            return
        }
        paint.color = outline
        canvas.drawPath(notched(r, u), paint)
        val inner = RectF(r.left + ow, r.top + ow, r.right - ow, r.bottom - ow)
        paint.color = fill
        canvas.drawPath(notched(inner, 0f), paint)
        highlight?.let {
            paint.color = it
            canvas.drawRect(inner.left, inner.top, inner.right, inner.top + u, paint)
        }
    }

    private fun notched(r: RectF, n: Float) = Path().apply {
        moveTo(r.left + n, r.top); lineTo(r.right - n, r.top); lineTo(r.right - n, r.top + n)
        lineTo(r.right, r.top + n); lineTo(r.right, r.bottom - n); lineTo(r.right - n, r.bottom - n)
        lineTo(r.right - n, r.bottom); lineTo(r.left + n, r.bottom); lineTo(r.left + n, r.bottom - n)
        lineTo(r.left, r.bottom - n); lineTo(r.left, r.top + n); lineTo(r.left + n, r.top + n); close()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun Context.panel(fill: Int, outline: Int = R.color.outline, shadow: Boolean = true, highlight: Int? = null, dashed: Boolean = false) =
    PixelFrameDrawable(
        px(), color(fill), color(outline),
        shadow = if (shadow) color(R.color.shadow) else null,
        highlight = highlight?.let { color(it) }, dashed = dashed,
    )

/** Chunky pixel button: hard shadow underneath, drops into the shadow while pressed. */
@SuppressLint("ClickableViewAccessibility")
class PixelButton(context: Context) : AppCompatTextView(context) {
    private val frame = PixelFrameDrawable(
        context.px(), context.color(R.color.brand_orange), context.color(R.color.outline),
        shadow = context.color(R.color.outline), shadowUnits = 2,
        highlight = context.color(R.color.brand_orange_light),
    )

    init {
        background = frame
        gravity = Gravity.CENTER
        pixelText(16f, bold = true, colorRes = R.color.on_brand)
        isAllCaps = true
        minHeight = context.dp(52)
        val p = context.dp(14)
        setPadding(p, 0, p, context.px().toInt() * 2)
        isClickable = true
        isFocusable = true
    }

    fun style(fill: Int, light: Int, textColor: Int = R.color.on_brand) {
        frame.fill = context.color(fill); frame.highlight = context.color(light)
        setTextColor(context.color(textColor)); invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (isEnabled) { frame.pressed = true; translationY = 0f }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> frame.pressed = false
        }
        return super.onTouchEvent(event)
    }
}
