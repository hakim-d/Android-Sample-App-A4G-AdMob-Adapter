package com.hakim_ma.a4g.ui.flow

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hakim_ma.a4g.R
import com.hakim_ma.a4g.engine.AdFormat
import com.hakim_ma.a4g.engine.AdapterAttempt
import com.hakim_ma.a4g.engine.LoadOutcome
import com.hakim_ma.a4g.ui.pixel.PixelFrameDrawable
import com.hakim_ma.a4g.ui.pixel.color
import com.hakim_ma.a4g.ui.pixel.dp
import com.hakim_ma.a4g.ui.pixel.monoText
import com.hakim_ma.a4g.ui.pixel.panel
import com.hakim_ma.a4g.ui.pixel.pixelText
import com.hakim_ma.a4g.ui.pixel.px
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "How this impression was won": App ID -> ad unit -> mediation group -> the sources AdMob
 * actually tried -> winner -> live impression / reward.
 *
 * Honesty rules (spec 4.8): neither SDK streams per-source events, so while loading we only show
 * "Requesting..." with real elapsed time. Rungs come from ResponseInfo AFTER the load returns and
 * are REPLAYED, timed in proportion to real latencies. No prices. Sources the SDK didn't report
 * are never drawn; a missing Ad4Game gets a dashed "not called" ghost. Impression and reward light
 * only when the SDK callbacks really fire.
 */
class WaterfallFlowView(context: Context) : LinearLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("flow", Context.MODE_PRIVATE)
    private val u = context.px()
    private val emphasized: TimeInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val standard: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val replayChip = TextView(context)
    private val replayBtn = ImageView(context)
    private val speedButtons = mutableListOf<TextView>()
    private val ladder = Ladder(context)
    private val footnote = TextView(context)

    /** 1 = normal, 2 = double, 0 = skip animation. Persisted. */
    var speed: Int = prefs.getInt("speed", 1)
        private set

    private var model: Model? = null
    private var anim: AnimatorSet? = null
    private var requestStart = 0L
    private var ticker: Runnable? = null

    // Rows built for the current result.
    private var nodeApp: NodeRow? = null
    private var nodeUnit: NodeRow? = null
    private var nodeGroup: NodeRow? = null
    private val rungs = mutableListOf<RungRow>()
    private var ghost: View? = null
    private var terminal: NodeRow? = null
    private var impression: NodeRow? = null
    private var reward: NodeRow? = null
    private var requestingText: TextView? = null
    private var impressionAt: Long? = null
    private var rewardText: String? = null

    /** [demo]: the adapter is called directly (no AdMob mediation). [ownUnit]: with the publisher's own Ad4Game Unit ID. */
    data class Model(val appId: String, val outcome: LoadOutcome, val testUnits: Boolean, val loadedAt: Long, val demo: Boolean = false,
                     val ownUnit: Boolean = false)

    init {
        orientation = VERTICAL
        background = context.panel(R.color.surface_container_high)
        setPadding(context.dp(14), context.dp(12), context.dp(16), context.dp(16))

        val title = TextView(context).apply {
            text = context.getString(R.string.flow_title); pixelText(14f, bold = true); isAllCaps = true
        }
        addView(title)

        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        replayChip.text = context.getString(R.string.flow_replay_chip)
        replayChip.pixelText(14f, colorRes = R.color.on_primary_container)
        replayChip.background = context.panel(R.color.primary_container, shadow = false)
        replayChip.setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(5))
        controls.addView(replayChip)
        replayBtn.setImageResource(R.drawable.ic_replay)
        replayBtn.setColorFilter(context.color(R.color.on_surface))
        replayBtn.contentDescription = context.getString(R.string.flow_replay)
        replayBtn.setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
        replayBtn.setOnClickListener { replay() }
        controls.addView(replayBtn, LayoutParams(context.dp(44), context.dp(44)))
        controls.addView(View(context), LayoutParams(0, 1, 1f))
        listOf(1 to "1×", 2 to "2×", 0 to "SKIP").forEach { (s, label) ->
            val b = TextView(context).apply {
                text = label; pixelText(12f, bold = true); gravity = Gravity.CENTER
                setPadding(context.dp(9), context.dp(7), context.dp(9), context.dp(8))
                contentDescription = when (s) { 1 -> "Normal speed"; 2 -> "Double speed"; else -> "Skip animation" }
                tag = s
                setOnClickListener { setSpeed(s) }
            }
            speedButtons += b
            controls.addView(b, LayoutParams(-2, -2).apply { leftMargin = context.dp(4) })
        }
        addView(controls, LayoutParams(-1, -2).apply { topMargin = context.dp(6); bottomMargin = context.dp(6) })
        addView(ladder, LayoutParams(-1, -2))
        footnote.text = context.getString(R.string.flow_footnote)
        footnote.pixelText(14f, colorRes = R.color.on_surface_variant)
        footnote.setLineSpacing(0f, 1.2f)
        addView(footnote, LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
        setSpeed(speed)
        setReplayControlsVisible(false)
    }

    private fun setSpeed(s: Int) {
        speed = s
        prefs.edit().putInt("speed", s).apply()
        speedButtons.forEach { b ->
            val on = b.tag == s
            b.background = context.panel(if (on) R.color.brand_orange else R.color.surface, shadow = false)
            b.setTextColor(context.color(if (on) R.color.on_brand else R.color.on_surface_variant))
            b.isSelected = on
        }
        if (s == 0) jumpToEnd()
    }

    private fun setReplayControlsVisible(v: Boolean) {
        replayChip.visibility = if (v) VISIBLE else INVISIBLE
        replayBtn.visibility = if (v) VISIBLE else INVISIBLE
    }

    private val reduceMotion: Boolean
        get() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    // ---------------------------------------------------------------- states

    private fun unitLabel(demo: Boolean, ownUnit: Boolean) = when {
        ownUnit -> "Ad4Game Unit ID"
        demo -> "Ad4Game parameter"
        else -> "Ad unit"
    }

    fun showIdle(appId: String, adUnit: String, demo: Boolean = false, ownUnit: Boolean = false) {
        stopAll(); model = null
        ladder.reset()
        nodeApp = ladder.node("App ID", appId)
        nodeUnit = ladder.node(unitLabel(demo, ownUnit), adUnit)
        nodeGroup = ladder.node("AdMob mediation", when {
            ownUnit -> "Bypassed: Ad4Game is called directly"
            demo -> "Bypassed: the demo calls Ad4Game directly"
            else -> "Waiting for a request"
        })
        ladder.addView(hint(context.getString(R.string.flow_idle)))
        setReplayControlsVisible(false)
        ladder.progressTo(null)
    }

    fun showRequesting(appId: String, adUnit: String, demo: Boolean = false, ownUnit: Boolean = false) {
        stopAll(); model = null
        ladder.reset()
        nodeApp = ladder.node("App ID", appId).also { it.lit(R.color.brand_orange) }
        nodeUnit = ladder.node(unitLabel(demo, ownUnit), adUnit).also { it.lit(R.color.brand_orange) }
        nodeGroup = ladder.node(if (demo) "Direct adapter call" else "AdMob mediation", "Request sent").also { it.lit(R.color.brand_orange) }
        val t = hint("Requesting… 0.0 s")
        t.pixelText(14f, bold = true)
        requestingText = t
        ladder.addView(t)
        ladder.addView(hint(context.getString(R.string.flow_requesting_hint)))
        setReplayControlsVisible(false)
        ladder.post { ladder.progressTo(nodeGroup!!.dot); ladder.startShimmer(nodeGroup!!.dot) }
        requestStart = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                val s = (SystemClock.elapsedRealtime() - requestStart) / 1000f
                requestingText?.text = String.format(Locale.US, "Requesting… %.1f s", s)
                main.postDelayed(this, 100)
            }
        }
        ticker = tick; main.post(tick)
    }

    /** Builds the ladder from the real response, then replays it (or renders it final). */
    fun showResult(m: Model, animate: Boolean, live: Pair<Long?, String?> = null to null) {
        stopAll()
        model = m
        impressionAt = live.first; rewardText = live.second
        buildRows(m)
        footnote.text = if (m.ownUnit) "AdMob mediation is bypassed and the Ad4Game adapter loads your Ad4Game Unit ID directly. " +
                "In your app, AdMob, AppLovin MAX or Unity LevelPlay requests it from your mediation setup."
            else if (m.demo) "Demo: AdMob mediation is bypassed and Ad4Game is called directly with an Ad Manager demo unit. " +
                "In a real app, AdMob calls Ad4Game from your mediation group, in the order you set there."
            else context.getString(R.string.flow_footnote)
        setReplayControlsVisible(true)
        if (animate && speed != 0 && !reduceMotion) ladder.post { play() } else ladder.post { jumpToEnd(announce = animate) }
    }

    fun replay() {
        if (model == null) return
        if (speed == 0 || reduceMotion) { jumpToEnd(announce = true); return }
        stopAll(); buildRows(model!!); ladder.post { play() }
    }

    fun markImpression(at: Long) {
        impressionAt = at
        val row = impression ?: return
        if (anim?.isRunning == true) return // applied at the end of the replay
        row.lit(R.color.status_success); row.value.text = clock.format(Date(at))
        ladder.progressTo(row.dot, animateMs = 300)
    }

    fun markReward(text: String) {
        rewardText = text
        val row = reward ?: return
        if (anim?.isRunning == true) return
        row.lit(R.color.brand_orange); row.value.text = text
        ladder.progressTo(row.dot, animateMs = 300)
    }

    private fun stopAll() {
        ticker?.let { main.removeCallbacks(it) }; ticker = null
        anim?.removeAllListeners(); anim?.cancel(); anim = null
        ladder.stopShimmer()
    }

    // ---------------------------------------------------------------- build

    private fun buildRows(m: Model) {
        val o = m.outcome
        val s = o.summary
        ladder.reset(); rungs.clear(); ghost = null
        nodeApp = ladder.node("App ID", m.appId)
        nodeUnit = ladder.node(unitLabel(m.demo, m.ownUnit), o.adUnitId)
        nodeGroup = if (m.demo) ladder.node("Direct adapter call", if (m.ownUnit) "AdMob mediation bypassed" else "AdMob mediation bypassed (demo)") else ladder.node(
            "Mediation group",
            s?.mediationGroupName?.let { "\"$it\"" } ?: if (s == null) "No response" else "Name not reported"
        )
        val attempts = s?.attempts.orEmpty()
        val ratios = ReplayTimeline.barRatios(attempts.map { it.latencyMs })
        attempts.forEachIndexed { i, a ->
            rungs += ladder.rung(a, ratios[i], i, attempts.size, isLast = i == attempts.lastIndex && !o.success)
        }
        if (s == null && !o.success) ladder.addView(hint("No waterfall data: the request failed before mediation ran."))
        if (attempts.none { it.isAd4Game }) {
            val caption = when {
                m.testUnits -> "Google test units never route to Ad4Game."
                o.success -> "A source above filled first."
                else -> "Not reported by the SDK. Shown for reference."
            }
            ghost = ladder.ghost(caption)
        }
        terminal = if (o.success) ladder.node("Ad ready", clock.format(Date(m.loadedAt))) else
            ladder.node("No ad", o.error?.let { "${it.kind.name.lowercase().replace('_', ' ')} (${it.code})" } ?: "")
        if (o.success) {
            // A banner only logs its impression once it is on screen, and the screen has scrolled down to this ladder
            impression = ladder.node("Impression",
                if (o.format == AdFormat.BANNER) "waiting: scroll up for the impression to log" else "waiting")
            reward = if (o.format == AdFormat.REWARDED) ladder.node("Reward", "waiting") else null
        } else { impression = null; reward = null }
    }

    private fun initialState() {
        listOf(nodeApp, nodeUnit, nodeGroup, terminal).forEach { it?.hollow() }
        nodeApp?.lit(R.color.brand_orange)
        rungs.forEach { it.hidden() }
        ghost?.alpha = 0f
        terminal?.root?.alpha = 0f
        applyLive(final = false)
        ladder.progressTo(nodeApp?.dot)
    }

    private fun applyLive(final: Boolean) {
        impression?.let { r ->
            impressionAt?.let { r.lit(R.color.status_success); r.value.text = clock.format(Date(it)) } ?: r.hollow()
        }
        reward?.let { r -> rewardText?.let { r.lit(R.color.brand_orange); r.value.text = it } ?: r.hollow() }
        if (final) {
            val last = listOfNotNull(
                reward?.takeIf { rewardText != null }?.dot,
                impression?.takeIf { impressionAt != null }?.dot,
                terminal?.dot,
            ).firstOrNull()
            ladder.progressTo(last)
        }
    }

    private fun jumpToEnd(announce: Boolean = false) {
        val m = model ?: return
        anim?.removeAllListeners(); anim?.cancel(); anim = null
        listOf(nodeApp, nodeUnit, nodeGroup).forEach { it?.lit(R.color.brand_orange) }
        rungs.forEach { it.final() }
        ghost?.alpha = 1f
        terminal?.root?.alpha = 1f
        terminal?.lit(if (m.outcome.success) R.color.status_success else R.color.status_error)
        applyLive(final = true)
        ladder.pulseY = null
        if (announce) announceSummary(m)
    }

    // ---------------------------------------------------------------- replay

    private fun play() {
        val m = model ?: return
        initialState()
        val k = if (speed == 2) 2f else 1f
        fun d(ms: Long) = (ms / k).toLong()
        val steps = mutableListOf<Animator>()
        fun step(ms: Long, interp: TimeInterpolator, onStart: () -> Unit = {}, onEnd: () -> Unit = {}, update: (Float) -> Unit = {}) {
            steps += ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ms; interpolator = interp
                addUpdateListener { update(it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(a: Animator) = onStart()
                    override fun onAnimationEnd(a: Animator) = onEnd()
                })
            }
        }
        fun travel(to: View, ms: Long, onEnd: () -> Unit = {}) {
            var from = 0f; var target = 0f
            step(ms, emphasized, onStart = { from = ladder.progressY; target = ladder.centerY(to) },
                onEnd = { ladder.pulseY = null; onEnd() }) { f ->
                val y = from + (target - from) * f
                ladder.progressY = y; ladder.pulseY = y; ladder.invalidate()
            }
        }

        travel(nodeUnit!!.dot, d(200)) { nodeUnit?.pop(R.color.brand_orange) }
        travel(nodeGroup!!.dot, d(250)) { nodeGroup?.pop(R.color.brand_orange) }

        val durations = ReplayTimeline.barDurations(rungs.map { it.attempt.latencyMs }, k)
        rungs.forEachIndexed { i, r ->
            travel(r.dot, d(120))
            step(d(200), emphasized, onStart = { r.card.visibility = VISIBLE }) { f ->
                r.card.alpha = f; r.card.translationY = -context.dp(8) * (1 - f)
            }
            step(durations[i], LinearInterpolator()) { f ->
                r.fill.scaleX = r.ratio * f
                r.ms.text = "${(r.attempt.latencyMs * f).toLong()} ms"
            }
            step(d(150), standard, onStart = { r.stamp.visibility = VISIBLE }) { f ->
                r.stamp.alpha = f; r.stamp.scaleX = 0.9f + 0.1f * f; r.stamp.scaleY = r.stamp.scaleX
            }
            if (r.isLastFailure) step(d(120), LinearInterpolator()) { f ->
                r.card.translationX = (if (f < 0.5f) f else 1 - f) * 2 * context.dp(4) * (if ((f * 4).toInt() % 2 == 0) 1 else -1)
            }
            if (r.attempt.isWinner) step(d(500), standard, onStart = { r.win() }) { f ->
                val s = 1f + 0.04f * (if (f < 0.5f) f * 2 else (1 - f) * 2)
                r.card.scaleX = s; r.card.scaleY = s
            }
        }
        ghost?.let { g ->
            travel(g, d(200))
            step(d(300), emphasized) { f -> g.alpha = f }
        }
        travel(terminal!!.dot, d(200)) {
            terminal?.root?.alpha = 1f
            terminal?.pop(if (m.outcome.success) R.color.status_success else R.color.status_error)
        }

        anim = AnimatorSet().apply {
            playSequentially(steps)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (anim !== this@apply) return
                    anim = null
                    applyLive(final = true)
                    announceSummary(m)
                }
            })
            start()
        }
    }

    private fun announceSummary(m: Model) {
        val n = m.outcome.summary?.attempts?.size ?: 0
        val w = m.outcome.summary?.winner
        val msg = when {
            w != null -> "Replay finished. $n sources tried. ${w.sourceName} won in ${w.latencyMs} milliseconds."
            m.outcome.success -> "Replay finished. Ad loaded."
            else -> "No ad. $n sources tried."
        }
        announceForAccessibility(msg)
    }

    private fun hint(text: String) = TextView(context).apply {
        this.text = text; pixelText(15f, colorRes = R.color.on_surface_variant)
        setPadding(ladder.gutter, context.dp(6), 0, context.dp(6))
    }

    // ---------------------------------------------------------------- rows

    inner class NodeRow(val root: LinearLayout, val dot: View, val value: TextView, private val frame: PixelFrameDrawable) {
        fun hollow() { frame.fill = context.color(R.color.surface); frame.invalidateSelf() }
        fun lit(colorRes: Int) { frame.fill = context.color(colorRes); frame.invalidateSelf() }
        fun pop(colorRes: Int) {
            lit(colorRes)
            dot.scaleX = 1.6f; dot.scaleY = 1.6f
            dot.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }
    }

    inner class RungRow(
        val attempt: AdapterAttempt, val ratio: Float, val isLastFailure: Boolean,
        val root: View, val dot: View, val card: LinearLayout, val frame: PixelFrameDrawable,
        val fill: View, val ms: TextView, val stamp: TextView,
    ) {
        fun hidden() {
            card.visibility = INVISIBLE; card.alpha = 0f; fill.scaleX = 0f; ms.text = "0 ms"
            stamp.visibility = INVISIBLE; card.scaleX = 1f; card.scaleY = 1f; card.translationX = 0f
            frame.fill = context.color(R.color.surface); frame.invalidateSelf()
        }
        fun final() {
            card.visibility = VISIBLE; card.alpha = 1f; card.translationY = 0f; card.translationX = 0f
            card.scaleX = 1f; card.scaleY = 1f
            fill.scaleX = ratio; ms.text = "${attempt.latencyMs} ms"
            stamp.visibility = VISIBLE; stamp.alpha = 1f; stamp.scaleX = 1f; stamp.scaleY = 1f
            if (attempt.isWinner) winColors() else { frame.fill = context.color(R.color.surface); frame.invalidateSelf() }
        }
        fun win() {
            val to = if (attempt.isAd4Game) context.color(R.color.brand_orange_light) else context.color(R.color.status_success_container)
            ValueAnimator.ofObject(ArgbEvaluator(), context.color(R.color.surface), to).apply {
                duration = 300
                addUpdateListener { frame.fill = it.animatedValue as Int; frame.invalidateSelf() }
            }.start()
        }
        private fun winColors() {
            frame.fill = if (attempt.isAd4Game) context.color(R.color.brand_orange_light) else context.color(R.color.status_success_container)
            frame.invalidateSelf()
        }
    }

    /** The ladder column: draws the pixel spine behind its rows. */
    inner class Ladder(context: Context) : LinearLayout(context) {
        val gutter = context.dp(30)
        private val spineX = context.dp(12).toFloat()
        private val paint = Paint().apply { isAntiAlias = false }
        var progressY = 0f
        var pulseY: Float? = null
        private var shimmerFrom: View? = null
        private var shimmerPhase = 0f
        private var shimmerAnim: ValueAnimator? = null
        private var progressAnim: ValueAnimator? = null
        private val stops = mutableListOf<View>()

        init { orientation = VERTICAL; setWillNotDraw(false); clipChildren = false }

        fun reset() { removeAllViews(); stops.clear(); progressY = 0f; pulseY = null; stopShimmer() }

        fun centerY(v: View): Float {
            var y = v.height / 2f; var cur: View = v
            while (cur !== this) { y += cur.top; cur = cur.parent as? View ?: break }
            return y
        }

        fun progressTo(v: View?, animateMs: Long = 0) {
            progressAnim?.cancel()
            post {
                val target = v?.let { centerY(it) } ?: 0f
                if (animateMs == 0L) { progressY = target; invalidate(); return@post }
                progressAnim = ValueAnimator.ofFloat(progressY, target).apply {
                    duration = animateMs
                    addUpdateListener { progressY = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }

        fun startShimmer(from: View) {
            shimmerFrom = from
            shimmerAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { shimmerPhase = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stopShimmer() { shimmerAnim?.cancel(); shimmerAnim = null; shimmerFrom = null }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (stops.isEmpty()) return
            val top = centerY(stops.first()); val bottom = centerY(stops.last())
            val w = u
            // Base track: dotted pixel line through every stop.
            paint.color = context.color(R.color.outline_variant)
            var y = top
            while (y < bottom) { canvas.drawRect(spineX - w, y, spineX + w, minOf(y + 2 * u, bottom), paint); y += 4 * u }
            // Progress: solid outline-colored line.
            if (progressY > top) {
                paint.color = context.color(R.color.outline)
                canvas.drawRect(spineX - w, top, spineX + w, progressY, paint)
            }
            // "Requesting" shimmer: marching pixel dashes below the mediation node.
            shimmerFrom?.let { f ->
                val start = centerY(f); val len = context.dp(56).toFloat(); val period = 6 * u
                paint.color = context.color(R.color.brand_orange)
                var yy = start + shimmerPhase * period
                while (yy < start + len) { canvas.drawRect(spineX - w, yy, spineX + w, yy + 3 * u, paint); yy += period }
            }
            pulseY?.let { p ->
                paint.color = context.color(R.color.pulse)
                canvas.drawRect(spineX - 3 * u, p - 3 * u, spineX + 3 * u, p + 3 * u, paint)
            }
        }

        private fun dotView(sizeDp: Int): Pair<View, PixelFrameDrawable> {
            val f = PixelFrameDrawable(u, context.color(R.color.surface), context.color(R.color.outline))
            return View(context).apply { background = f } to f
        }

        fun node(label: String, value: String): NodeRow {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val g = FrameLayout(context)
            val (dot, frame) = dotView(14)
            g.addView(dot, FrameLayout.LayoutParams(context.dp(14), context.dp(14)).apply {
                leftMargin = (spineX - context.dp(7)).toInt(); gravity = Gravity.CENTER_VERTICAL
            })
            row.addView(g, LayoutParams(gutter, context.dp(36)))
            val col = LinearLayout(context).apply { orientation = VERTICAL }
            col.addView(TextView(context).apply { text = label; pixelText(14f, colorRes = R.color.on_surface_variant); isAllCaps = true })
            val v = TextView(context).apply { text = value; monoText(14f) }
            col.addView(v)
            row.addView(col, LayoutParams(0, -2, 1f))
            row.contentDescription = "$label, $value"
            addView(row, LayoutParams(-1, -2).apply { topMargin = context.dp(4); bottomMargin = context.dp(4) })
            stops += dot
            return NodeRow(row, dot, v, frame)
        }

        fun rung(a: AdapterAttempt, ratio: Float, i: Int, n: Int, isLast: Boolean): RungRow {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val g = FrameLayout(context)
            val tick = View(context).apply { setBackgroundColor(context.color(R.color.outline)) }
            g.addView(tick, FrameLayout.LayoutParams(gutter - spineX.toInt(), (2 * u).toInt()).apply {
                leftMargin = spineX.toInt(); gravity = Gravity.CENTER_VERTICAL
            })
            row.addView(g, LayoutParams(gutter, -1))

            val frame = PixelFrameDrawable(
                u, context.color(R.color.surface),
                context.color(if (a.isAd4Game) R.color.brand_orange_dark else R.color.outline),
                shadow = context.color(R.color.shadow), outlineUnits = if (a.isAd4Game) 2 else 1,
            )
            val card = LinearLayout(context).apply {
                orientation = VERTICAL; background = frame
                setPadding(context.dp(10), context.dp(8), context.dp(12), context.dp(10))
            }
            val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(context).apply { text = a.sourceName; pixelText(14f, bold = true) },
                LayoutParams(0, -2, 1f))
            if (a.isAd4Game) top.addView(TextView(context).apply {
                text = "CUSTOM EVENT"; pixelText(13f, colorRes = R.color.on_brand)
                background = context.panel(R.color.brand_orange, shadow = false)
                setPadding(context.dp(6), context.dp(3), context.dp(6), context.dp(4))
            }, LayoutParams(-2, -2).apply { rightMargin = context.dp(6) })
            val (stampText, stampFill, stampColor) = when {
                a.isWinner -> Triple("★ WON", if (a.isAd4Game) R.color.brand_orange else R.color.status_success_container, R.color.on_surface)
                a.error == null -> Triple("NOT SELECTED", R.color.status_neutral_container, R.color.status_neutral)
                a.error.code == 3 || a.error.code == 9 -> Triple("NO FILL", R.color.status_warning_container, R.color.status_warning)
                a.error.code == 408 && a.isAd4Game -> Triple("TIMEOUT", R.color.status_error_container, R.color.status_error)
                else -> Triple("ERROR ${a.error.code}", R.color.status_error_container, R.color.status_error)
            }
            val stamp = TextView(context).apply {
                text = stampText; pixelText(12f, bold = true, colorRes = stampColor)
                background = context.panel(stampFill, shadow = false)
                setPadding(context.dp(7), context.dp(3), context.dp(7), context.dp(4))
            }
            top.addView(stamp)
            card.addView(top)
            a.instanceName?.let { card.addView(TextView(context).apply { text = it; monoText(13f, R.color.on_surface_variant) }) }
            val bar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val track = FrameLayout(context).apply { setBackgroundColor(context.color(R.color.skeleton)) }
            val fill = View(context).apply {
                setBackgroundColor(context.color(if (a.isAd4Game) R.color.brand_orange else R.color.outline))
                pivotX = 0f
            }
            track.addView(fill, FrameLayout.LayoutParams(-1, -1))
            bar.addView(track, LayoutParams(0, (3 * u).toInt(), 1f))
            val ms = TextView(context).apply { monoText(13f); gravity = Gravity.END }
            bar.addView(ms, LayoutParams(context.dp(64), -2))
            card.addView(bar, LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
            a.error?.let { e ->
                card.addView(TextView(context).apply {
                    text = e.message.take(90); pixelText(14f, colorRes = R.color.on_surface_variant)
                }, LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
            }
            row.addView(card, LayoutParams(0, -2, 1f))
            row.contentDescription = "Source ${i + 1} of $n. ${a.sourceName}${if (a.isAd4Game) ", custom event" else ""}. " +
                    "${a.latencyMs} milliseconds. ${stampText.replace("★ ", "")}."
            addView(row, LayoutParams(-1, -2).apply { topMargin = context.dp(5); bottomMargin = context.dp(5) })
            stops += tick
            return RungRow(a, ratio, isLast, row, tick, card, frame, fill, ms, stamp)
        }

        fun ghost(caption: String): View {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(View(context), LayoutParams(gutter, 1))
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                background = context.panel(R.color.surface, R.color.brand_orange_dark, shadow = false, dashed = true)
                setPadding(context.dp(10), context.dp(8), context.dp(12), context.dp(10))
            }
            val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(context).apply { text = "Ad4Game"; pixelText(14f, bold = true, colorRes = R.color.on_surface_variant) },
                LayoutParams(0, -2, 1f))
            top.addView(TextView(context).apply { text = "NOT CALLED"; pixelText(12f, bold = true, colorRes = R.color.on_surface_variant) })
            card.addView(top)
            card.addView(TextView(context).apply { text = caption; pixelText(14f, colorRes = R.color.on_surface_variant) })
            row.addView(card, LayoutParams(0, -2, 1f))
            row.contentDescription = "Ad4Game was not called. $caption"
            addView(row, LayoutParams(-1, -2).apply { topMargin = context.dp(5); bottomMargin = context.dp(5) })
            return row
        }
    }
}
