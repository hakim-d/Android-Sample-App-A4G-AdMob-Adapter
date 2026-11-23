package com.hakim_ma.a4g.engine

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.ad4game.admobadapter.AdmobCustomEventManager
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.hakim_ma.a4g.BuildConfig

object AdEngineProvider {
    fun create(app: Application): AdEngine = ClassicAdEngine(app)
}

class ClassicAdEngine(private val app: Application) : AdEngine {
    private val main = Handler(Looper.getMainLooper())
    private val ads = HashMap<Long, Any>()
    private val bannerEvents = HashMap<Long, (ShowEvent) -> Unit>()
    private var nextId = 1L
    private val direct by lazy { DirectAdapter(app) }

    override val info: EngineInfo by lazy {
        val sdk = MobileAds.getVersion()
        val adapter = runCatching { AdmobCustomEventManager().versionInfo }.getOrNull()
        EngineInfo(
            flavorTag = "C",
            flavorLabel = "Classic",
            sdkName = "GMA SDK",
            sdkVersion = "${sdk.majorVersion}.${sdk.minorVersion}.${sdk.microVersion}",
            adapterVersion = adapter?.let { "${it.majorVersion}.${it.minorVersion}.${it.microVersion}" } ?: "missing",
            supportsRuntimeAppId = BuildConfig.RUNTIME_APP_ID,
        )
    }

    override fun initialize(appId: String, testDeviceIds: List<String>, onReady: (InitSnapshot) -> Unit) {
        // Classic reads the App ID from the manifest only; appId is informational here.
        setTestDeviceIds(testDeviceIds)
        val start = SystemClock.elapsedRealtime()
        Thread {
            MobileAds.initialize(app) { status ->
                val a4g = status.adapterStatusMap[AD4GAME_CLASS]
                val snap = InitSnapshot(
                    adapterFound = a4g != null,
                    adapterState = a4g?.initializationState?.name ?: "NOT_FOUND",
                    adapterLatencyMs = a4g?.latency?.toLong() ?: 0,
                    totalLatencyMs = SystemClock.elapsedRealtime() - start,
                )
                main.post { onReady(snap) }
            }
        }.start()
    }

    override fun setTestDeviceIds(ids: List<String>) {
        MobileAds.setRequestConfiguration(
            MobileAds.getRequestConfiguration().toBuilder().setTestDeviceIds(ids).build()
        )
    }

    override fun load(format: AdFormat, adUnitId: String, activity: Activity, onResult: (LoadOutcome) -> Unit) {
        val start = SystemClock.elapsedRealtime()
        val request = AdRequest.Builder().build()
        fun done(ad: Any?, info: ResponseInfo?, err: LoadAdError?) {
            val handle = ad?.let { AdHandle(nextId++, format).also { h -> ads[h.id] = it } }
            onResult(
                LoadOutcome(
                    format, adUnitId, SystemClock.elapsedRealtime() - start, handle,
                    info?.let(::summarize), err?.let(::normalize)
                )
            )
        }
        when (format) {
            AdFormat.BANNER -> {
                val view = AdView(activity)
                val widthDp = (activity.resources.displayMetrics.widthPixels /
                        activity.resources.displayMetrics.density).toInt() - 84
                view.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp))
                view.adUnitId = adUnitId
                var handleId = -1L
                view.adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        if (handleId != -1L) return // banner refresh: ignore repeat loads
                        done(view, view.responseInfo, null)
                        handleId = ads.entries.first { it.value === view }.key
                    }
                    override fun onAdFailedToLoad(e: LoadAdError) {
                        if (handleId == -1L) { handleId = 0; view.destroy(); done(null, e.responseInfo, e) }
                    }
                    override fun onAdImpression() { bannerEvents[handleId]?.invoke(ShowEvent.Impression) }
                    override fun onAdClicked() { bannerEvents[handleId]?.invoke(ShowEvent.Clicked) }
                }
                view.loadAd(request)
            }
            AdFormat.INTERSTITIAL -> InterstitialAd.load(activity, adUnitId, request,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) = done(ad, ad.responseInfo, null)
                    override fun onAdFailedToLoad(e: LoadAdError) = done(null, e.responseInfo, e)
                })
            AdFormat.REWARDED -> RewardedAd.load(activity, adUnitId, request,
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) = done(ad, ad.responseInfo, null)
                    override fun onAdFailedToLoad(e: LoadAdError) = done(null, e.responseInfo, e)
                })
        }
    }

    override fun directLoad(format: AdFormat, parameter: String, activity: Activity, onResult: (LoadOutcome) -> Unit) =
        direct.load(format, parameter, nextId++, onResult)

    override fun show(handle: AdHandle, activity: Activity, events: (ShowEvent) -> Unit) {
        if (direct.owns(handle)) return direct.show(handle, activity, events)
        val cb = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() = events(ShowEvent.Showed)
            override fun onAdImpression() = events(ShowEvent.Impression)
            override fun onAdClicked() = events(ShowEvent.Clicked)
            override fun onAdDismissedFullScreenContent() = events(ShowEvent.Dismissed)
            override fun onAdFailedToShowFullScreenContent(e: com.google.android.gms.ads.AdError) =
                events(ShowEvent.FailedToShow(e.message))
        }
        when (val ad = ads.remove(handle.id)) {
            is InterstitialAd -> { ad.fullScreenContentCallback = cb; ad.show(activity) }
            is RewardedAd -> {
                ad.fullScreenContentCallback = cb
                ad.show(activity) { r -> events(ShowEvent.Reward(r.type, r.amount)) }
            }
            else -> events(ShowEvent.FailedToShow("Ad isn't loaded"))
        }
    }

    override fun bannerView(handle: AdHandle, events: (ShowEvent) -> Unit): View? {
        if (direct.owns(handle)) return direct.bannerView(handle, events)
        bannerEvents[handle.id] = events
        return ads[handle.id] as? AdView
    }

    override fun destroy(handle: AdHandle) {
        if (direct.owns(handle)) return direct.destroy(handle)
        (ads.remove(handle.id) as? AdView)?.destroy()
        bannerEvents.remove(handle.id)
    }

    private fun summarize(r: ResponseInfo): ResponseSummary {
        val loaded = r.loadedAdapterResponseInfo
        var winnerMarked = false
        val attempts = r.adapterResponses.map { a ->
            val isWinner = !winnerMarked && loaded != null && a.adError == null &&
                    a.adapterClassName == loaded.adapterClassName &&
                    a.adSourceInstanceId == loaded.adSourceInstanceId
            if (isWinner) winnerMarked = true
            AdapterAttempt(
                sourceName = a.adSourceName.ifBlank { a.adapterClassName.substringAfterLast('.') },
                instanceName = a.adSourceInstanceName.takeIf { it.isNotBlank() && it != a.adSourceName },
                adapterClassName = a.adapterClassName,
                latencyMs = a.latencyMillis,
                error = a.adError?.let { AttemptError(it.code, it.domain, it.message) },
                isWinner = isWinner,
                credentials = a.credentials.toStringMap(),
            )
        }
        return ResponseSummary(
            r.responseId,
            r.responseExtras.getString("mediation_group_name"),
            attempts,
        )
    }

    private fun normalize(e: LoadAdError): NormalizedError {
        val kind = when {
            e.domain == "com.ad4game.admobadapter" && e.code == 408 -> AdErrorKind.TIMEOUT
            e.code == AdRequest.ERROR_CODE_NO_FILL -> AdErrorKind.NO_FILL
            e.code == AdRequest.ERROR_CODE_MEDIATION_NO_FILL -> AdErrorKind.MEDIATION_NO_FILL
            e.code == AdRequest.ERROR_CODE_INVALID_REQUEST -> AdErrorKind.INVALID_REQUEST
            e.code == AdRequest.ERROR_CODE_APP_ID_MISSING -> AdErrorKind.APP_ID_MISSING
            e.code == AdRequest.ERROR_CODE_NETWORK_ERROR -> AdErrorKind.NETWORK_ERROR
            e.code == AdRequest.ERROR_CODE_INTERNAL_ERROR -> AdErrorKind.INTERNAL_ERROR
            else -> AdErrorKind.UNKNOWN
        }
        return NormalizedError(kind, "${e.code}", e.message)
    }
}

private fun Bundle.toStringMap(): Map<String, String> =
    keySet().associateWith { k -> @Suppress("DEPRECATION") get(k)?.toString() ?: "" }
