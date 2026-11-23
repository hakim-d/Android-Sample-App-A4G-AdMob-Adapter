package com.hakim_ma.a4g.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hakim_ma.a4g.A4GApplication
import com.hakim_ma.a4g.BuildConfig
import com.hakim_ma.a4g.R
import com.hakim_ma.a4g.data.GoogleTest
import com.hakim_ma.a4g.data.TestDevice
import com.hakim_ma.a4g.data.UnitMode
import com.hakim_ma.a4g.data.Validator
import com.hakim_ma.a4g.engine.AD4GAME_CLASS
import com.hakim_ma.a4g.engine.AdFormat
import com.hakim_ma.a4g.ui.pixel.PixelButton
import com.hakim_ma.a4g.ui.pixel.color
import com.hakim_ma.a4g.ui.pixel.dp
import com.hakim_ma.a4g.ui.pixel.monoText
import com.hakim_ma.a4g.ui.pixel.panel
import com.hakim_ma.a4g.ui.pixel.pixelText

/**
 * Setup: unit mode, App ID, test device and the publisher's own ad units.
 * [onChanged] fires after anything that affects ad requests (mode, units, test devices).
 */
class SetupView(context: Context, private val app: A4GApplication, private val onChanged: () -> Unit) : ScrollView(context) {
    private val config = app.config
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val checklist = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val modeButtons = mutableMapOf<UnitMode, PixelButton>()
    private val publisherUnits = BuildConfig.PUBLISHER_UNITS
    private val deviceButton = PixelButton(context)
    private val deviceStatus = TextView(context)
    private val unitFields = mutableMapOf<AdFormat, Pair<EditText, TextView>>()
    private val hashedId = TestDevice.hashedId(context)

    init {
        isFillViewport = true
        clipToPadding = false
        content.setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(24))
        addView(content)

        content.addView(TextView(context).apply {
            text = "What should the Test tab load?"; pixelText(18f, bold = true)
        })
        content.addView(body("Every mode shows test ads only. Nothing here earns revenue or counts as real traffic."), lp(top = 6))

        // 0. Which SDK: Classic and Next-Gen are two separate apps (the SDKs can't share one APK).
        content.addView(card("SDK", sdkSection()), lp(top = 16))

        // 1. Readiness checklist.
        content.addView(card("Readiness", checklist), lp(top = 16))

        // 2. Mode.
        val modes = buildList {
            add(Triple(UnitMode.AD4GAME_DEMO, "Ad4Game demo",
                "Runs the Ad4Game adapter directly with Google's Ad Manager demo units. Shows the adapter working end to end with test creatives."))
            add(Triple(UnitMode.TEST_UNITS, "Google test units",
                "Real AdMob mediation with Google's test ad units. Proves the SDK works, but never calls Ad4Game."))
            if (publisherUnits) add(Triple(UnitMode.MY_UNITS, "My ad units",
                "Your own AdMob ad units, as test requests on a registered test device. Internal builds only."))
        }
        val modeCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        modes.forEachIndexed { i, (m, label, desc) ->
            val b = PixelButton(context).apply { text = label; textSize = 17f; setOnClickListener { setMode(m) } }
            modeButtons[m] = b
            modeCol.addView(b, lp(top = if (i == 0) 0 else 14))
            modeCol.addView(body(desc), lp(top = 6))
        }
        content.addView(card("Mode", modeCol), lp(top = 16))

        if (publisherUnits) {
            // 3. App ID.  4. Test device.  5. Ad units.
            content.addView(card("App ID", appIdSection()), lp(top = 16))
            content.addView(card("Test device", testDeviceSection()), lp(top = 16))
            content.addView(card("My ad units", adUnitsSection()), lp(top = 16))
        }

        refresh()
    }

    // ------------------------------------------------------------------ sections

    private fun sdkSection(): View {
        val info = app.engine.info
        val classic = info.flavorTag == "C"
        val otherLabel = if (classic) "Next-Gen" else "Classic"
        val otherPkg = if (classic) "com.ad4game.tester.nextgen" else "com.hakim_ma.a4g"
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(context).apply {
            text = "This app: ${info.flavorLabel} · ${info.sdkName} ${info.sdkVersion} · Ad4Game adapter ${info.adapterVersion}"
            pixelText(15f)
        })
        col.addView(body("The classic Google Mobile Ads SDK and the Next-Gen SDK can't run in the same app, so each " +
                "has its own tester. Use the one that matches the SDK in your game."), lp(top = 6))
        val launch = context.packageManager.getLaunchIntentForPackage(otherPkg)
        col.addView(PixelButton(context).apply {
            textSize = 17f
            if (launch != null) {
                text = "Open the $otherLabel tester"
                style(R.color.surface, R.color.surface, R.color.on_surface)
                setOnClickListener { context.startActivity(launch) }
            } else {
                text = "$otherLabel tester not installed"
                isEnabled = false
            }
        }, lp(top = 10))
        return col
    }

    private fun appIdSection(): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (app.engine.info.supportsRuntimeAppId) {
            val (field, error) = field("ca-app-pub-0000000000000000~0000000000", config.appId)
            val restart = PixelButton(context).apply { text = "Save & restart"; textSize = 17f; isEnabled = false }
            field.addTextChangedListener(watcher {
                val v = field.text.toString().trim()
                val err = Validator.appIdError(v)
                error.text = err ?: ""; error.visibility = if (err == null) GONE else VISIBLE
                restart.isEnabled = err == null && v != config.appId
            })
            restart.setOnClickListener {
                config.appId = field.text.toString().trim()
                restartApp()
            }
            col.addView(body("In use now:"))
            col.addView(mono(app.appId))
            col.addView(field, lp(top = 10)); col.addView(error)
            col.addView(body("AdMob > Apps > App settings. The SDK reads the App ID when it starts, so the app restarts. Leave empty to use Google's sample App ID."), lp(top = 6))
            col.addView(restart, lp(top = 10))
        } else {
            col.addView(body("Built into this APK (the classic SDK reads it from the manifest only):"))
            col.addView(mono(BuildConfig.ADMOB_APP_ID), lp(top = 4))
            if (BuildConfig.ADMOB_APP_ID == GoogleTest.APP_ID) col.addView(note("This is Google's sample App ID. Your ad units won't serve under it.", R.color.status_warning), lp(top = 6))
            val cmd = "./gradlew :app:assembleClassicDebug -Pa4g.admobAppId=YOUR_APP_ID"
            col.addView(body("Rebuild with your App ID:"), lp(top = 10))
            col.addView(mono(cmd).apply { setTextIsSelectable(true) }, lp(top = 4))
            col.addView(PixelButton(context).apply {
                text = "Copy command"; textSize = 17f
                style(R.color.surface, R.color.surface)
                setOnClickListener { copy("Build command", cmd) }
            }, lp(top = 10))
        }
        return col
    }

    private fun testDeviceSection(): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body("This device's test ID:"))
        col.addView(mono(hashedId).apply { setTextIsSelectable(true) }, lp(top = 4))
        deviceStatus.pixelText(15f)
        col.addView(deviceStatus, lp(top = 8))
        deviceButton.textSize = 17f
        deviceButton.setOnClickListener {
            val ids = config.testDeviceIds
            config.testDeviceIds = if (hashedId in ids) ids - hashedId else ids + hashedId
            app.engine.setTestDeviceIds(config.testDeviceIds.toList())
            refresh(); onChanged()
        }
        col.addView(deviceButton, lp(top = 10))
        col.addView(body("Test requests from your live ad units don't count as real traffic, so testing can't get your AdMob account flagged. " +
                "Ad4Game and other mediated sources may still serve real creatives: never click them."), lp(top = 8))
        return col
    }

    private fun adUnitsSection(): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        AdFormat.entries.forEach { f ->
            col.addView(TextView(context).apply { text = f.label; pixelText(15f, bold = true) }, lp(top = if (f == AdFormat.BANNER) 0 else 12))
            val (field, error) = field("ca-app-pub-0000000000000000/0000000000", config.unit(f))
            field.addTextChangedListener(watcher { showUnitError(f) })
            unitFields[f] = field to error
            col.addView(field, lp(top = 4)); col.addView(error)
        }
        col.addView(body("Not the Ad4Game Parameter: that value goes in the custom event in AdMob, with class $AD4GAME_CLASS."), lp(top = 10))
        col.addView(PixelButton(context).apply {
            text = "Save ad units"; textSize = 18f
            setOnClickListener { saveUnits() }
        }, lp(top = 12))
        return col
    }

    // ------------------------------------------------------------------ actions

    private fun setMode(m: UnitMode) {
        if (config.mode == m) return
        config.mode = m
        refresh(); onChanged()
    }

    private fun showUnitError(f: AdFormat): Boolean {
        val (field, error) = unitFields.getValue(f)
        val v = field.text.toString().trim()
        val err = Validator.adUnitError(v) ?: Validator.publisherMismatch(app.appId, v)?.let { (a, u) ->
            if (app.appId == GoogleTest.APP_ID) null
            else "This ad unit belongs to pub-$u but the App ID is pub-$a. They must come from the same AdMob account."
        }
        error.text = err ?: ""
        error.visibility = if (err == null) GONE else VISIBLE
        return Validator.adUnitError(v) == null // a publisher mismatch only warns; a bad format blocks saving
    }

    private fun saveUnits() {
        val ok = AdFormat.entries.map { showUnitError(it) }.all { it }
        if (!ok) {
            unitFields.values.firstOrNull { it.second.visibility == VISIBLE }?.first?.requestFocus()
            return
        }
        AdFormat.entries.forEach { config.setUnit(it, unitFields.getValue(it).first.text.toString()) }
        val any = AdFormat.entries.any { config.unit(it).isNotEmpty() }
        if (any) config.mode = UnitMode.MY_UNITS
        Toast.makeText(context, if (any) "Ad units saved. Using your ad units." else "Ad units cleared.", Toast.LENGTH_SHORT).show()
        refresh(); onChanged()
    }

    private fun restartApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }

    private fun copy(label: String, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ state

    fun refresh() {
        val mode = config.mode
        modeButtons.forEach { (m, b) ->
            val on = m == mode
            b.style(if (on) R.color.brand_orange else R.color.surface, if (on) R.color.brand_orange_light else R.color.surface,
                if (on) R.color.on_brand else R.color.on_surface_variant)
            b.contentDescription = b.text.toString() + if (on) ", selected" else ""
        }
        checklist.removeAllViews()
        if (!publisherUnits || mode != UnitMode.MY_UNITS) {
            when (mode) {
                UnitMode.AD4GAME_DEMO -> {
                    checkRow(true, "Ad4Game adapter ${app.engine.info.adapterVersion} in this build")
                    checkRow(true, "Called directly with Ad Manager demo units")
                    checkRow(true, "Test creatives only: no live traffic")
                }
                else -> {
                    checkRow(true, "AdMob mediation with Google test units")
                    checkRow(false, "Google test units never call Ad4Game")
                }
            }
        }
        if (!publisherUnits) return

        val registered = hashedId in config.testDeviceIds
        deviceButton.text = if (registered) "Unregister this device" else "Register this device"
        if (registered) deviceButton.style(R.color.surface, R.color.surface, R.color.on_surface)
        else deviceButton.style(R.color.brand_orange, R.color.brand_orange_light)
        deviceStatus.text = when {
            registered -> "✓ Registered as a test device"
            TestDevice.isEmulator -> "Emulator: the SDK always treats it as a test device"
            else -> "Not registered: requests count as real traffic"
        }
        deviceStatus.setTextColor(context.color(when {
            registered || TestDevice.isEmulator -> R.color.status_success
            else -> R.color.status_error
        }))

        if (mode != UnitMode.MY_UNITS) return
        val units = AdFormat.entries.count { config.unit(it).isNotEmpty() }
        val customAppId = app.appId != GoogleTest.APP_ID
        checkRow(true, "Using my ad units")
        checkRow(customAppId, if (customAppId) "App ID ${app.appId.takeLast(11)}" else "App ID is Google's sample: set yours")
        checkRow(units > 0, "$units of 3 ad units set")
        checkRow(registered || TestDevice.isEmulator, if (registered || TestDevice.isEmulator) "Test device protected" else "Device not registered as a test device")
    }

    private fun checkRow(done: Boolean, text: String) {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(context).apply {
            setImageResource(if (done) R.drawable.ic_check_circle else R.drawable.ic_no_fill)
            setColorFilter(context.color(if (done) R.color.status_success else R.color.status_warning))
        }, LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply { marginEnd = context.dp(10) })
        row.addView(TextView(context).apply { this.text = text; pixelText(15f) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.contentDescription = (if (done) "Done: " else "To do: ") + text
        checklist.addView(row, lp(top = 4))
    }

    // ------------------------------------------------------------------ widgets

    private fun card(title: String, inner: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.panel(R.color.surface_container_high)
        setPadding(context.dp(14), context.dp(12), context.dp(16), context.dp(16))
        addView(TextView(context).apply { text = title; pixelText(15f, bold = true); isAllCaps = true })
        addView(inner, lp(top = 8))
    }

    private fun column(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        views.forEachIndexed { i, v -> addView(v, lp(top = if (i == 0) 0 else 8)) }
    }

    private fun body(text: String) = TextView(context).apply {
        this.text = text; pixelText(14f, colorRes = R.color.on_surface_variant); setLineSpacing(0f, 1.15f)
    }

    private fun note(text: String, colorRes: Int) = TextView(context).apply { this.text = text; pixelText(14f, colorRes = colorRes) }

    private fun mono(text: String) = TextView(context).apply { this.text = text; monoText(14f) }

    private fun field(hint: String, value: String): Pair<EditText, TextView> {
        val e = EditText(context).apply {
            setText(value); this.hint = hint
            monoText(14f); setHintTextColor(context.color(R.color.outline_variant))
            background = context.panel(R.color.surface, shadow = false)
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        val err = TextView(context).apply { pixelText(14f, colorRes = R.color.status_error); visibility = GONE; setPadding(0, context.dp(4), 0, 0) }
        return e to err
    }

    private fun watcher(block: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = block()
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(top) }
}
