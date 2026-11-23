package com.hakim_ma.a4g.ui.stage

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hakim_ma.a4g.R
import com.hakim_ma.a4g.ui.pixel.PixelButton
import com.hakim_ma.a4g.ui.pixel.color
import com.hakim_ma.a4g.ui.pixel.dp
import com.hakim_ma.a4g.ui.pixel.panel
import com.hakim_ma.a4g.ui.pixel.pixelText

/**
 * The "Ad Stage": a pixel-art phone frame with faux app content, so the ad reads in a real
 * placement. Banners dock at the bottom; fullscreen formats launch through a 3-2-1 curtain.
 */
class AdStageView(context: Context) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val device = FrameLayout(context)
    private val slot = FrameLayout(context)
    private val placeholder = TextView(context)
    private val readyCard = LinearLayout(context)
    private val readyTitle = TextView(context)
    private val readySub = TextView(context)
    private val curtain = LinearLayout(context)
    private val countdown = TextView(context)
    private val curtainLabel = TextView(context)
    private val showNow = PixelButton(context)
    private val cancel = TextView(context)
    private var ticks: Runnable? = null

    var onShowNow: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    init {
        clipChildren = false
        device.background = context.panel(R.color.surface_container_high).apply { outlineUnits = 2; shadowUnits = 2 }
        device.setPadding(context.dp(8), context.dp(8), context.dp(10), context.dp(10))
        device.clipToPadding = true
        addView(device, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(240)).apply {
            leftMargin = context.dp(8); rightMargin = context.dp(8)
        })

        // Faux app content: pixel status bar + skeleton feed rows.
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val status = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        status.addView(block(36, 6)); status.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        repeat(3) { status.addView(block(6, 6).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = context.dp(3) }) }
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(10) })
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(block(28, 28, R.color.brand_orange_light))
        header.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(block(120, 8)); addView(block(80, 6).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = context.dp(5) })
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(8) })
        content.addView(header)
        listOf(1f, 0.85f, 0.95f, 0.6f).forEach { w ->
            content.addView(View(context).apply { setBackgroundColor(context.color(R.color.skeleton)) },
                LinearLayout.LayoutParams(-1, context.dp(8)).apply {
                    topMargin = context.dp(9); rightMargin = ((1 - w) * context.dp(200)).toInt()
                })
        }
        device.addView(content, LayoutParams(-1, -2))

        // Ad slot docked to the bottom of the screen.
        placeholder.text = context.getString(R.string.stage_placeholder)
        placeholder.pixelText(15f, colorRes = R.color.on_surface_variant)
        placeholder.isAllCaps = true
        placeholder.gravity = Gravity.CENTER
        placeholder.background = context.panel(R.color.surface, R.color.outline_variant, shadow = false, dashed = true)
        slot.addView(placeholder, LayoutParams(-1, context.dp(56)))
        // Ad WebViews grab focus, which makes the ScrollView jump to them.
        slot.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        device.addView(slot, LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        // Fullscreen "ready" card.
        readyCard.orientation = LinearLayout.VERTICAL
        readyCard.gravity = Gravity.CENTER
        readyCard.background = context.panel(R.color.brand_orange, highlight = R.color.brand_orange_light)
        readyCard.setPadding(context.dp(16), context.dp(14), context.dp(18), context.dp(16))
        readyTitle.pixelText(16f, bold = true, colorRes = R.color.on_brand); readyTitle.isAllCaps = true
        readySub.pixelText(15f, colorRes = R.color.on_brand)
        readyCard.addView(readyTitle); readyCard.addView(readySub)
        readyCard.visibility = GONE
        device.addView(readyCard, LayoutParams(-2, -2, Gravity.CENTER))

        // Curtain: scrim + countdown, covers the device screen.
        curtain.orientation = LinearLayout.VERTICAL
        curtain.gravity = Gravity.CENTER
        curtain.setBackgroundColor(context.color(R.color.scrim))
        countdown.pixelText(72f, bold = true, colorRes = R.color.brand_orange)
        countdown.gravity = Gravity.CENTER
        curtainLabel.pixelText(16f, colorRes = R.color.surface); curtainLabel.gravity = Gravity.CENTER
        showNow.text = context.getString(R.string.show_now)
        cancel.text = context.getString(R.string.cancel)
        cancel.pixelText(16f, colorRes = R.color.brand_orange_light); cancel.isAllCaps = true
        cancel.setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
        curtain.addView(countdown)
        curtain.addView(curtainLabel, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = context.dp(16) })
        curtain.addView(showNow, LinearLayout.LayoutParams(context.dp(180), -2))
        curtain.addView(cancel)
        curtain.visibility = GONE
        device.addView(curtain, LayoutParams(-1, -1))
        showNow.setOnClickListener { stopTicks(); onShowNow?.invoke() }
        cancel.setOnClickListener { stopTicks(); liftCurtain(); onCancel?.invoke() }
    }

    private fun block(w: Int, h: Int, colorRes: Int = R.color.skeleton) = View(context).apply {
        setBackgroundColor(context.color(colorRes))
        layoutParams = LinearLayout.LayoutParams(context.dp(w), context.dp(h))
    }

    fun reset() {
        stopTicks()
        slot.removeAllViews()
        slot.addView(placeholder, LayoutParams(-1, context.dp(56)))
        readyCard.visibility = GONE
        curtain.visibility = GONE
    }

    fun setPlaceholderText(text: String) { placeholder.text = text }

    fun showBanner(view: View) {
        slot.removeAllViews()
        (view.parent as? ViewGroup)?.removeView(view)
        slot.addView(view, LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        view.alpha = 0f; view.translationY = context.dp(24).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(350).setInterpolator(DecelerateInterpolator()).start()
    }

    fun showReadyCard(title: String, sub: String) {
        readyTitle.text = title; readySub.text = sub
        readyCard.visibility = VISIBLE
        readyCard.scaleX = 0.8f; readyCard.scaleY = 0.8f; readyCard.alpha = 0f
        readyCard.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).setInterpolator(OvershootInterpolator()).start()
    }

    fun hideReadyCard() { readyCard.visibility = GONE }

    /** Slides the scrim down, counts 3-2-1 naming the source, then calls [onShowNow]. */
    fun runCurtain(label: String, seconds: Int, reduceMotion: Boolean) {
        curtainLabel.text = label
        readyCard.visibility = GONE
        curtain.visibility = VISIBLE
        if (reduceMotion) {
            curtain.alpha = 0f; curtain.animate().alpha(1f).setDuration(150).withEndAction { onShowNow?.invoke() }.start()
            countdown.text = ""
            return
        }
        curtain.alpha = 1f
        curtain.translationY = -device.height.toFloat()
        curtain.animate().translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        curtain.announceForAccessibility("Showing ad in $seconds seconds. Show now, or cancel.")
        var left = seconds
        val tick = object : Runnable {
            override fun run() {
                if (left == 0) { ticks = null; onShowNow?.invoke(); return }
                countdown.text = "$left"
                countdown.scaleX = 1.6f; countdown.scaleY = 1.6f
                countdown.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
                left--
                main.postDelayed(this, 800)
            }
        }
        ticks = tick
        main.postDelayed(tick, 300)
    }

    fun liftCurtain() {
        stopTicks()
        if (curtain.visibility != VISIBLE) return
        curtain.animate().translationY(-device.height.toFloat()).setDuration(300)
            .withEndAction { curtain.visibility = GONE; curtain.translationY = 0f }.start()
    }

    private fun stopTicks() { ticks?.let { main.removeCallbacks(it) }; ticks = null }
}
