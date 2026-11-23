package com.hakim_ma.a4g

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.hakim_ma.a4g.data.GoogleTest
import com.hakim_ma.a4g.data.TestDevice
import com.hakim_ma.a4g.data.UnitMode
import com.hakim_ma.a4g.data.Validator
import com.hakim_ma.a4g.domain.Status
import com.hakim_ma.a4g.domain.VerdictEngine
import com.hakim_ma.a4g.engine.AdFormat
import com.hakim_ma.a4g.engine.LoadOutcome
import com.hakim_ma.a4g.engine.ShowEvent
import com.hakim_ma.a4g.ui.flow.WaterfallFlowView
import com.hakim_ma.a4g.ui.pixel.PixelButton
import com.hakim_ma.a4g.ui.pixel.PixelConfetti
import com.hakim_ma.a4g.ui.pixel.PixelFonts
import com.hakim_ma.a4g.ui.pixel.color
import com.hakim_ma.a4g.ui.pixel.dp
import com.hakim_ma.a4g.ui.pixel.panel
import com.hakim_ma.a4g.ui.pixel.pixelText
import com.hakim_ma.a4g.ui.pixel.px
import com.hakim_ma.a4g.ui.setup.SetupView
import com.hakim_ma.a4g.ui.stage.AdStageView
import java.util.Locale

/** Two tabs: TEST (Ad Stage + Waterfall Flow + verdict) and SETUP (mode, App ID, test device, ad units). */
class MainActivity : AppCompatActivity() {

    private enum class Phase { IDLE, LOADING, LOADED, FAILED, SHOWN }

    private class FormatState {
        var phase = Phase.IDLE
        var outcome: LoadOutcome? = null
        var usedMode = UnitMode.AD4GAME_DEMO   // mode at the time of the request
        var bannerView: View? = null
        var impressionAt: Long? = null
        var rewardText: String? = null
        var shownAt = 0L
        var loadedAt = 0L
        var shownSummary: String? = null
        var cooldownUntil = 0L
    }

    private val main = Handler(Looper.getMainLooper())
    private val app get() = application as A4GApplication
    private val engine get() = app.engine
    private val config get() = app.config
    private val hashedId by lazy { TestDevice.hashedId(this) }
    private val states = AdFormat.entries.associateWith { FormatState() }
    private var current = AdFormat.BANNER
    private var sdkReady = false
    private var showingSetup = false
    private var cooldownTick: Runnable? = null

    private val myUnits get() = config.mode == UnitMode.MY_UNITS
    private val demo get() = config.mode == UnitMode.AD4GAME_DEMO
    private val isTestDevice get() = hashedId in config.testDeviceIds || TestDevice.isEmulator
    private fun unitFor(f: AdFormat) = when (config.mode) {
        UnitMode.AD4GAME_DEMO -> GoogleTest.ad4gameDemoParams.getValue(f)
        UnitMode.TEST_UNITS -> GoogleTest.units.getValue(f)
        UnitMode.MY_UNITS -> config.unit(f)
    }

    private lateinit var envMode: TextView
    private lateinit var envDevice: TextView
    private lateinit var envAdapter: TextView
    private val tabs = mutableMapOf<AdFormat, PixelButton>()
    private lateinit var liveBanner: TextView
    private lateinit var stage: AdStageView
    private lateinit var resultChip: TextView
    private lateinit var primary: PixelButton
    private lateinit var secondary: TextView
    private lateinit var flow: WaterfallFlowView
    private lateinit var scroll: ScrollView
    private lateinit var testPage: LinearLayout
    private lateinit var setupPage: SetupView
    private lateinit var navTest: PixelButton
    private lateinit var navSetup: PixelButton
    private lateinit var verdictCard: LinearLayout
    private lateinit var verdictIcon: ImageView
    private lateinit var verdictTitle: TextView
    private lateinit var verdictBody: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildUi())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingSetup) showPage(setup = false) else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        app.whenInitialized {
            sdkReady = true
            updateEnv()
            render(animate = false)
        }
        updateEnv()
        render(animate = false)
        showPage(setup = false)
    }

    override fun onDestroy() {
        cooldownTick?.let { main.removeCallbacks(it) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.surface)) }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.brand_orange))
        }
        // Title row: app name on the left, the white A4G cube on the right.
        val title = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        title.addView(TextView(this).apply {
            text = getString(R.string.toolbar_title); pixelText(20f, bold = true, colorRes = R.color.on_brand); isAllCaps = true
        }, LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(ImageView(this).apply {
            setImageResource(R.drawable.a4g_logo_white)
            adjustViewBounds = true
            contentDescription = "Ad4Game"
        }, LinearLayout.LayoutParams(-2, dp(30)))
        toolbar.addView(title)
        toolbar.addView(View(this).apply { setBackgroundColor(color(R.color.outline)) }, LinearLayout.LayoutParams(-1, (px() * 2).toInt()))
        root.addView(toolbar)

        // Environment strip: which SDK, which adapter, which mode, test device. Always visible.
        val env = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.surface_container))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { showPage(setup = true) }
        }
        val i = engine.info
        val row1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row1.addView(TextView(this).apply {
            text = i.flavorTag; pixelText(14f, bold = true, colorRes = R.color.on_brand); gravity = Gravity.CENTER
            background = panel(R.color.brand_orange, highlight = R.color.brand_orange_light)
            setPadding(dp(8), dp(4), dp(10), dp(6))
        })
        row1.addView(TextView(this).apply {
            text = "${i.sdkName} ${i.sdkVersion} · Adapter ${i.adapterVersion}"
            pixelText(16f) // data, not a heading: readable body face
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        env.addView(row1)
        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        envMode = chip("", R.color.status_neutral_container)
        envDevice = chip("", R.color.status_neutral_container)
        envAdapter = chip(getString(R.string.sdk_starting), R.color.status_neutral_container)
        row2.addView(envMode)
        row2.addView(envDevice, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        row2.addView(envAdapter, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        env.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(row2)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(env)
        root.addView(View(this).apply { setBackgroundColor(color(R.color.outline)) }, LinearLayout.LayoutParams(-1, px().toInt()))

        // Pages: TEST and SETUP share the space between the strip and the bottom bar.
        val pages = FrameLayout(this)
        testPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val tabRow = LinearLayout(this).apply { setPadding(dp(12), dp(12), dp(12), dp(4)) }
        AdFormat.entries.forEach { f ->
            val b = PixelButton(this).apply {
                text = f.label; textSize = 13f * PixelFonts.DISPLAY_SCALE; minHeight = dp(44)
                setOnClickListener { selectFormat(f) }
            }
            tabs[f] = b
            tabRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        testPage.addView(tabRow)

        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        liveBanner = TextView(this).apply {
            text = "Live ads without test mode. Don't click ads. Tap to fix."
            pixelText(15f, colorRes = R.color.status_error)
            background = panel(R.color.status_error_container, shadow = false)
            setPadding(dp(12), dp(10), dp(12), dp(11))
            setCompoundDrawablesRelative(tinted(R.drawable.ic_error, R.color.status_error), null, null, null)
            compoundDrawablePadding = dp(8)
            setOnClickListener { showPage(setup = true) }
        }
        content.addView(liveBanner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        stage = AdStageView(this)
        content.addView(stage)
        resultChip = TextView(this).apply {
            pixelText(13f, bold = true); gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(8)
            setPadding(dp(10), dp(7), dp(12), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(resultChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16); gravity = Gravity.CENTER_HORIZONTAL })
        primary = PixelButton(this).apply { textSize = 18f * PixelFonts.DISPLAY_SCALE; minHeight = dp(58); setOnClickListener { onPrimary() } }
        content.addView(primary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        secondary = TextView(this).apply {
            pixelText(16f, colorRes = R.color.primary); isAllCaps = true; gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onSecondary() }
        }
        content.addView(secondary, LinearLayout.LayoutParams(-1, -2))

        verdictCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(14), dp(14))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        verdictIcon = ImageView(this)
        verdictCard.addView(verdictIcon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
        val vcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        verdictTitle = TextView(this).apply { pixelText(16f, bold = true) }
        verdictBody = TextView(this).apply { pixelText(15f); setLineSpacing(0f, 1.2f) }
        vcol.addView(verdictTitle); vcol.addView(verdictBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        verdictCard.addView(vcol, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(verdictCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        flow = WaterfallFlowView(this)
        content.addView(flow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        scroll.addView(content)
        testPage.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        pages.addView(testPage, FrameLayout.LayoutParams(-1, -1))

        setupPage = SetupView(this, app) { onSetupChanged() }
        pages.addView(setupPage, FrameLayout.LayoutParams(-1, -1))
        root.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bottom bar.
        root.addView(View(this).apply { setBackgroundColor(color(R.color.outline)) }, LinearLayout.LayoutParams(-1, px().toInt()))
        val nav = LinearLayout(this).apply { setBackgroundColor(color(R.color.surface_container)) }
        navTest = PixelButton(this).apply { text = "Test"; textSize = 15f * PixelFonts.DISPLAY_SCALE; setOnClickListener { showPage(setup = false) } }
        navSetup = PixelButton(this).apply { text = "Setup"; textSize = 15f * PixelFonts.DISPLAY_SCALE; setOnClickListener { showPage(setup = true) } }
        nav.addView(navTest, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        nav.addView(navSetup, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        root.addView(nav)

        stage.onShowNow = { showCurrent() }
        stage.onCancel = { render(animate = false) }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            title.setPadding(dp(16), dp(14) + bars.top, dp(16), dp(14))
            nav.setPadding(dp(16), dp(10), dp(16), dp(10) + bars.bottom)
            insets
        }
        return root
    }

    private fun chip(text: String, fill: Int) = TextView(this).apply {
        this.text = text; pixelText(15f); isAllCaps = true
        background = panel(fill, shadow = false)
        setPadding(dp(8), dp(4), dp(8), dp(5))
    }

    private fun tinted(icon: Int, colorRes: Int) =
        getDrawable(icon)!!.mutate().apply { setTint(color(colorRes)); setBounds(0, 0, dp(18), dp(18)) }

    private fun showPage(setup: Boolean) {
        showingSetup = setup
        testPage.visibility = if (setup) View.GONE else View.VISIBLE
        setupPage.visibility = if (setup) View.VISIBLE else View.GONE
        if (setup) setupPage.refresh()
        navTest.style(if (!setup) R.color.brand_orange else R.color.surface, if (!setup) R.color.brand_orange_light else R.color.surface,
            if (!setup) R.color.on_brand else R.color.on_surface_variant)
        navSetup.style(if (setup) R.color.brand_orange else R.color.surface, if (setup) R.color.brand_orange_light else R.color.surface,
            if (setup) R.color.on_brand else R.color.on_surface_variant)
    }

    private fun updateEnv() {
        envMode.text = when (config.mode) {
            UnitMode.AD4GAME_DEMO -> "Ad4Game demo"; UnitMode.TEST_UNITS -> getString(R.string.mode_test_units)
            UnitMode.MY_UNITS -> "My ad units"
        }
        envMode.background = panel(when (config.mode) {
            UnitMode.AD4GAME_DEMO -> R.color.brand_orange_light; UnitMode.TEST_UNITS -> R.color.status_neutral_container
            UnitMode.MY_UNITS -> R.color.primary_container
        }, shadow = false)
        envDevice.visibility = if (myUnits) View.VISIBLE else View.GONE
        envDevice.text = if (isTestDevice) "Test device" else "Not a test device"
        envDevice.setTextColor(color(if (isTestDevice) R.color.status_success else R.color.status_error))
        envDevice.background = panel(if (isTestDevice) R.color.status_success_container else R.color.status_error_container, shadow = false)
        liveBanner.visibility = if (myUnits && !isTestDevice) View.VISIBLE else View.GONE

        // Adapter chip. The SDK only initializes adapters listed in this App ID's mediation config;
        // the demo calls the adapter directly, so mediation status doesn't apply there.
        val snap = app.initSnapshot
        val missing = engine.info.adapterVersion == "missing"
        val (text, fill, desc) = when {
            snap == null -> Triple(getString(R.string.sdk_starting), R.color.status_neutral_container, null)
            missing -> Triple("A4G adapter missing", R.color.status_error_container, null)
            demo -> Triple("A4G called directly", R.color.status_success_container,
                "Ad4Game adapter is called directly, without AdMob mediation")
            snap.adapterFound -> Triple("A4G ${snap.adapterState} · ${snap.adapterLatencyMs} ms", R.color.status_success_container, null)
            else -> Triple("A4G not configured", R.color.status_neutral_container,
                "Ad4Game adapter is in this build but not in this App ID's mediation configuration")
        }
        envAdapter.text = text
        envAdapter.background = panel(fill, shadow = false)
        envAdapter.contentDescription = desc ?: text
    }

    /** Mode, units or test devices changed: drop loaded ads, they were requested with the old setup. */
    private fun onSetupChanged() {
        states.values.forEach { st ->
            st.outcome?.handle?.let { engine.destroy(it) }
            st.phase = Phase.IDLE; st.outcome = null; st.bannerView = null
            st.impressionAt = null; st.rewardText = null; st.shownSummary = null
        }
        updateEnv()
        render(animate = false)
    }

    // ------------------------------------------------------------------ state

    private fun selectFormat(f: AdFormat) {
        if (f == current) return
        current = f
        render(animate = false)
    }

    private fun render(animate: Boolean) {
        tabs.forEach { (f, b) ->
            if (f == current) b.style(R.color.brand_orange, R.color.brand_orange_light)
            else b.style(R.color.surface_container_high, R.color.surface_container_high, R.color.on_surface_variant)
            b.isSelected = f == current
        }
        val st = states.getValue(current)
        val unit = unitFor(current)
        val o = st.outcome
        stage.reset()
        stage.setPlaceholderText(if (current.isFullscreen) "${current.label} launches from here" else getString(R.string.stage_placeholder))
        primary.isEnabled = sdkReady && st.phase != Phase.LOADING
        secondary.visibility = View.GONE
        verdictCard.visibility = View.GONE

        if (st.phase == Phase.IDLE && unit.isEmpty()) {
            stage.setPlaceholderText("No ${current.label.lowercase()} ad unit yet")
            setChip("Not set", Status.NEUTRAL, R.drawable.ic_info)
            primary.text = "Add ad unit"; primary.isEnabled = true
            flow.showIdle(app.appId, "Not set")
            return
        }

        when (st.phase) {
            Phase.IDLE -> {
                setChip(if (sdkReady) "Ready to load" else getString(R.string.sdk_starting), Status.NEUTRAL, R.drawable.ic_info)
                primary.text = if (sdkReady) getString(R.string.load) else getString(R.string.sdk_starting)
                flow.showIdle(app.appId, unit, demo)
            }
            Phase.LOADING -> {
                setChip("Requesting…", Status.NEUTRAL, R.drawable.ic_info)
                primary.text = "Loading…"
                flow.showRequesting(app.appId, unit, demo)
            }
            Phase.LOADED, Phase.FAILED, Phase.SHOWN -> {
                o!!
                val winner = o.summary?.winner?.sourceName ?: "AdMob"
                if (o.success) {
                    setChip("Filled by $winner in ${o.latencyMs} ms", Status.SUCCESS, R.drawable.ic_check_circle)
                } else {
                    val (t, _) = VerdictEngine.explain(o.error!!.kind)
                    setChip("${t} · ${o.latencyMs} ms", if (o.error.kind.name.contains("NO_FILL")) Status.WARNING else Status.ERROR,
                        if (o.error.kind.name.contains("NO_FILL")) R.drawable.ic_no_fill else R.drawable.ic_error)
                }
                when {
                    st.phase == Phase.FAILED -> primary.text = "Try again"
                    st.phase == Phase.SHOWN -> {
                        primary.text = getString(R.string.load_again)
                        st.shownSummary?.let { setChip(it, Status.SUCCESS, R.drawable.ic_check_circle) }
                    }
                    current == AdFormat.BANNER -> {
                        primary.text = getString(R.string.reload)
                        st.bannerView?.let { stage.showBanner(it) }
                        secondary.text = getString(R.string.destroy); secondary.visibility = View.VISIBLE
                    }
                    else -> {
                        primary.text = "▶ Show ${current.label.lowercase()}"
                        stage.showReadyCard("${current.label} ad ready", "$winner · ${String.format(Locale.US, "%.1f", o.latencyMs / 1000f)} s")
                        secondary.text = getString(R.string.load_again); secondary.visibility = View.VISIBLE
                    }
                }
                val v = VerdictEngine.evaluate(testUnits = st.usedMode == UnitMode.TEST_UNITS, o, demo = st.usedMode == UnitMode.AD4GAME_DEMO)
                showVerdict(v.status, v.title, v.body)
                if (o.success && st.usedMode == UnitMode.MY_UNITS && o.summary?.winner?.sourceName?.contains("AdMob") != true) {
                    verdictBody.append("\n\nMediated ads (including Ad4Game) may be real ads even on a test device. Don't click them.")
                }
                flow.showResult(WaterfallFlowView.Model(app.appId, o, testUnits = st.usedMode == UnitMode.TEST_UNITS,
                    loadedAt = st.loadedAt, demo = st.usedMode == UnitMode.AD4GAME_DEMO), animate, st.impressionAt to st.rewardText)
            }
        }
        applyCooldown(st)
    }

    /** Live mode: 10 s between loads per format, so a live unit can't be hammered. */
    private fun applyCooldown(st: FormatState) {
        val left = st.cooldownUntil - SystemClock.elapsedRealtime()
        val wouldLoad = st.phase != Phase.LOADING && !(st.phase == Phase.LOADED && current.isFullscreen)
        cooldownTick?.let { main.removeCallbacks(it) }
        if (left <= 0 || !wouldLoad) return
        primary.isEnabled = false
        primary.text = "Load again in ${(left + 999) / 1000} s"
        secondary.isEnabled = false
        cooldownTick = Runnable { secondary.isEnabled = true; render(animate = false) }.also { main.postDelayed(it, minOf(left, 1000)) }
    }

    private fun setChip(text: String, status: Status, icon: Int) {
        val (fill, fg) = colors(status)
        resultChip.text = text
        resultChip.background = panel(fill, shadow = false)
        resultChip.setTextColor(color(fg))
        resultChip.setCompoundDrawables(tinted(icon, fg), null, null, null)
    }

    private fun showVerdict(status: Status, title: String, body: String) {
        val (fill, fg) = colors(status)
        verdictCard.visibility = View.VISIBLE
        verdictCard.background = panel(fill)
        verdictIcon.setImageResource(when (status) {
            Status.SUCCESS -> R.drawable.ic_check_circle; Status.WARNING -> R.drawable.ic_no_fill
            Status.ERROR -> R.drawable.ic_error; Status.NEUTRAL -> R.drawable.ic_info
        })
        verdictIcon.setColorFilter(color(fg))
        verdictTitle.text = title; verdictTitle.setTextColor(color(fg))
        verdictBody.text = body
    }

    private fun colors(s: Status) = when (s) {
        Status.SUCCESS -> R.color.status_success_container to R.color.status_success
        Status.WARNING -> R.color.status_warning_container to R.color.status_warning
        Status.ERROR -> R.color.status_error_container to R.color.status_error
        Status.NEUTRAL -> R.color.status_neutral_container to R.color.status_neutral
    }

    // ------------------------------------------------------------------ actions

    private fun onPrimary() {
        val st = states.getValue(current)
        if (st.phase == Phase.IDLE && unitFor(current).isEmpty()) { showPage(setup = true); return }
        if (st.phase == Phase.LOADED && current.isFullscreen) {
            val winner = st.outcome?.summary?.winner?.sourceName ?: "AdMob"
            val talkback = (getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled
            primary.isEnabled = false
            stage.runCurtain("${current.label} from $winner", if (talkback) 5 else 3, reduceMotion = false)
        } else requestLoad(current)
    }

    private fun onSecondary() {
        val st = states.getValue(current)
        if (current == AdFormat.BANNER) {
            st.outcome?.handle?.let { engine.destroy(it) }
            st.apply { phase = Phase.IDLE; outcome = null; bannerView = null; impressionAt = null }
            render(animate = false)
        } else requestLoad(current)
    }

    /** Pre-flight checks for live ad units, then [load]. */
    private fun requestLoad(f: AdFormat) {
        val unit = unitFor(f)
        if (myUnits) {
            Validator.publisherMismatch(app.appId, unit)?.let { (a, u) ->
                showVerdict(Status.ERROR, "App ID and ad unit don't match",
                    "The App ID belongs to pub-$a and the ad unit to pub-$u. Ad units only serve under the App ID of the " +
                            "AdMob app they were created in. Set your App ID in Setup.")
                setChip("Not sent: App ID mismatch", Status.ERROR, R.drawable.ic_error)
                return
            }
            // Live units only as test requests: no "load anyway".
            if (!isTestDevice) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Register this device first")
                    .setMessage("Your ad units are live. Requests from an unregistered device count as real traffic, and " +
                            "clicking your own ads can get your AdMob account limited. Register this device as a test device to continue.")
                    .setPositiveButton("Register this device") { _, _ ->
                        config.testDeviceIds = config.testDeviceIds + hashedId
                        engine.setTestDeviceIds(config.testDeviceIds.toList())
                        updateEnv(); setupPage.refresh(); load(f)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return
            }
        }
        load(f)
    }

    private fun load(f: AdFormat) {
        val st = states.getValue(f)
        st.outcome?.handle?.let { engine.destroy(it) }
        st.apply {
            phase = Phase.LOADING; outcome = null; bannerView = null; impressionAt = null; rewardText = null
            shownSummary = null; usedMode = config.mode
        }
        render(animate = false)
        val onResult: (LoadOutcome) -> Unit = { o ->
            st.outcome = o
            st.loadedAt = System.currentTimeMillis()
            st.phase = if (o.success) Phase.LOADED else Phase.FAILED
            if (st.usedMode == UnitMode.MY_UNITS) st.cooldownUntil = SystemClock.elapsedRealtime() + 10_000
            if (o.success && f == AdFormat.BANNER) {
                st.bannerView = engine.bannerView(o.handle!!) { ev ->
                    when (ev) {
                        ShowEvent.Impression -> {
                            st.impressionAt = System.currentTimeMillis()
                            if (current == f) flow.markImpression(st.impressionAt!!)
                        }
                        ShowEvent.Clicked -> clickGuard(st)
                        else -> Unit
                    }
                }
            }
            if (current == f) {
                render(animate = true)
                // Bring the replay into view: it is the star of the show.
                scroll.post { scroll.smoothScrollTo(0, (flow.top - dp(12)).coerceAtLeast(0)) }
            }
        }
        if (st.usedMode == UnitMode.AD4GAME_DEMO) engine.directLoad(f, unitFor(f), this, onResult)
        else engine.load(f, unitFor(f), this, onResult)
    }

    private fun clickGuard(st: FormatState) {
        if (st.usedMode != UnitMode.MY_UNITS) return
        Snackbar.make(scroll, "Ad click recorded on a live ad. Avoid clicking ads on your own units.", Snackbar.LENGTH_LONG).show()
    }

    private fun showCurrent() {
        val f = current
        val st = states.getValue(f)
        val handle = st.outcome?.handle ?: return
        engine.show(handle, this) { ev ->
            when (ev) {
                ShowEvent.Showed -> st.shownAt = SystemClock.elapsedRealtime()
                ShowEvent.Impression -> {
                    st.impressionAt = System.currentTimeMillis()
                    if (current == f) flow.markImpression(st.impressionAt!!)
                }
                is ShowEvent.Reward -> {
                    st.rewardText = "${ev.amount} ${ev.type}"
                    if (current == f) flow.markReward(st.rewardText!!)
                }
                ShowEvent.Dismissed -> {
                    val secs = (SystemClock.elapsedRealtime() - st.shownAt) / 1000f
                    st.shownSummary = String.format(Locale.US, "Dismissed after %.1f s", secs) +
                            (st.rewardText?.let { " · Reward: $it" } ?: "")
                    st.phase = Phase.SHOWN
                    stage.liftCurtain()
                    if (current == f) render(animate = false)
                    // Reward earned and the ad closed: celebrate.
                    st.rewardText?.let { reward -> scroll.postDelayed({ PixelConfetti.burst(this, "+$reward") }, 350) }
                }
                is ShowEvent.FailedToShow -> {
                    stage.liftCurtain()
                    st.phase = Phase.FAILED
                    if (current == f) { render(animate = false); setChip("Couldn't show: ${ev.message}", Status.ERROR, R.drawable.ic_error) }
                }
                ShowEvent.Clicked -> clickGuard(st)
            }
        }
    }
}
