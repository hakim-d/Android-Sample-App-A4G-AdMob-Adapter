package com.hakim_ma.a4g.engine

import android.app.Activity
import android.view.View

/**
 * The only door between the UI and an ads SDK. `main` never imports an SDK class: each flavor
 * (src/classic, src/nextgen) provides `AdEngineProvider.create()` returning its own engine.
 * All callbacks are delivered on the main thread.
 */
interface AdEngine {
    val info: EngineInfo

    /** Starts the SDK. [onReady] fires once, on the main thread, with the Ad4Game adapter status. */
    fun initialize(appId: String, testDeviceIds: List<String>, onReady: (InitSnapshot) -> Unit)

    /** Requests from these hashed device IDs are test requests (no real traffic). */
    fun setTestDeviceIds(ids: List<String>)

    fun load(format: AdFormat, adUnitId: String, activity: Activity, onResult: (LoadOutcome) -> Unit)

    /** Ad4Game demo: calls the Ad4Game adapter directly (no AdMob mediation) with [parameter]. */
    fun directLoad(format: AdFormat, parameter: String, activity: Activity, onResult: (LoadOutcome) -> Unit)

    /** Shows a loaded fullscreen ad. Events (impression, dismiss, reward...) stream to [events]. */
    fun show(handle: AdHandle, activity: Activity, events: (ShowEvent) -> Unit)

    /** Banner only: the view to place in the stage. Banner events stream to [events]. */
    fun bannerView(handle: AdHandle, events: (ShowEvent) -> Unit): View?

    fun destroy(handle: AdHandle)
}

enum class AdFormat(val label: String) {
    BANNER("Banner"), INTERSTITIAL("Interstitial"), REWARDED("Rewarded");

    val isFullscreen get() = this != BANNER
}

data class EngineInfo(
    val flavorTag: String,        // "C" or "NG"
    val flavorLabel: String,      // "Classic" or "Next-Gen"
    val sdkName: String,
    val sdkVersion: String,
    val adapterVersion: String,
    val supportsRuntimeAppId: Boolean,
)

data class InitSnapshot(
    val adapterFound: Boolean,
    val adapterState: String,     // READY / NOT_READY / COMPLETE / ...
    val adapterLatencyMs: Long,
    val totalLatencyMs: Long,
)

/** Opaque token for a loaded ad; the engine keeps the SDK object. */
class AdHandle(val id: Long, val format: AdFormat)

data class AdapterAttempt(
    val sourceName: String,       // adSourceName / name, falls back to short class name
    val instanceName: String?,
    val adapterClassName: String,
    val latencyMs: Long,
    val error: AttemptError?,     // null = no error (filled, or called but not selected)
    val isWinner: Boolean,
    val credentials: Map<String, String>,
) {
    val isAd4Game: Boolean
        get() = adapterClassName.contains("ad4game", ignoreCase = true) ||
                sourceName.contains("ad4game", ignoreCase = true)
}

data class AttemptError(val code: Int, val domain: String, val message: String)

data class ResponseSummary(
    val responseId: String?,
    val mediationGroupName: String?,
    val attempts: List<AdapterAttempt>,
) {
    val winner: AdapterAttempt? get() = attempts.firstOrNull { it.isWinner }
}

enum class AdErrorKind { NO_FILL, MEDIATION_NO_FILL, INVALID_REQUEST, APP_ID_MISSING, NETWORK_ERROR, INTERNAL_ERROR, TIMEOUT, UNKNOWN }

data class NormalizedError(val kind: AdErrorKind, val code: String, val message: String)

data class LoadOutcome(
    val format: AdFormat,
    val adUnitId: String,
    val latencyMs: Long,
    val handle: AdHandle?,                 // non-null on success
    val summary: ResponseSummary?,         // null if the request failed before mediation ran
    val error: NormalizedError?,
) {
    val success get() = handle != null
}

sealed interface ShowEvent {
    data object Showed : ShowEvent
    data object Impression : ShowEvent
    data object Clicked : ShowEvent
    data object Dismissed : ShowEvent
    data class Reward(val type: String, val amount: Int) : ShowEvent
    data class FailedToShow(val message: String) : ShowEvent
}

/** Ad4Game custom event class publishers must register in their AdMob mediation group. */
const val AD4GAME_CLASS = "com.ad4game.admobadapter.AdmobCustomEventManager"
