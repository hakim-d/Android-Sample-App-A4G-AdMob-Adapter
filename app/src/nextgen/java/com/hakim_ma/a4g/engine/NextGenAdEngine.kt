package com.hakim_ma.a4g.engine

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.ad4game.admobadapter.AdmobCustomEventManager
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationStatus
import com.google.android.libraries.ads.mobile.sdk.initialization.OnAdapterInitializationCompleteListener
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.hakim_ma.a4g.BuildConfig

object AdEngineProvider {
    fun create(app: Application): AdEngine = NextGenAdEngine(app)
}

/** Next-Gen SDK: initialize off the main thread, callbacks arrive on background threads. */
class NextGenAdEngine(private val app: Application) : AdEngine {
    private val main = Handler(Looper.getMainLooper())
    private val ads = HashMap<Long, Any>()
    private var nextId = 1L
    private val direct by lazy { DirectAdapter(app) }
    private var lastActivity: java.lang.ref.WeakReference<Activity>? = null

    override val info: EngineInfo by lazy {
        val sdk = MobileAds.getVersion()
        val adapter = runCatching { AdmobCustomEventManager().versionInfo }.getOrNull()
        EngineInfo(
            flavorTag = "NG",
            flavorLabel = "Next-Gen",
            sdkName = "Next-Gen SDK",
            sdkVersion = "${sdk.majorVersion}.${sdk.minorVersion}.${sdk.microVersion}",
            adapterVersion = adapter?.let { "${it.majorVersion}.${it.minorVersion}.${it.microVersion}" } ?: "missing",
            supportsRuntimeAppId = BuildConfig.RUNTIME_APP_ID,
        )
    }

    override fun initialize(appId: String, testDeviceIds: List<String>, onReady: (InitSnapshot) -> Unit) {
        val start = SystemClock.elapsedRealtime()
        val requestConfig = RequestConfiguration.Builder().setTestDeviceIds(testDeviceIds).build()
        Thread {
            MobileAds.initialize(app, InitializationConfig.Builder(appId).setRequestConfiguration(requestConfig).build(),
                object : OnAdapterInitializationCompleteListener {
                    override fun onAdapterInitializationComplete(status: InitializationStatus) {
                        val a4g = status.adapterStatusMap[AD4GAME_CLASS]
                        val snap = InitSnapshot(
                            adapterFound = a4g != null,
                            adapterState = a4g?.initializationState?.name ?: "NOT_FOUND",
                            adapterLatencyMs = a4g?.latency?.toLong() ?: 0,
                            totalLatencyMs = SystemClock.elapsedRealtime() - start,
                        )
                        main.post { onReady(snap) }
                    }
                })
        }.start()
    }

    override fun setTestDeviceIds(ids: List<String>) {
        if (MobileAds.isInitialized) {
            MobileAds.setRequestConfiguration(RequestConfiguration.Builder().setTestDeviceIds(ids).build())
        }
    }

    override fun load(format: AdFormat, adUnitId: String, activity: Activity, onResult: (LoadOutcome) -> Unit) {
        lastActivity = java.lang.ref.WeakReference(activity)
        val start = SystemClock.elapsedRealtime()
        fun done(ad: Any?, info: ResponseInfo?, err: LoadAdError?) = main.post {
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
                val widthDp = (activity.resources.displayMetrics.widthPixels /
                        activity.resources.displayMetrics.density).toInt() - 84
                val size = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp)
                BannerAd.load(BannerAdRequest.Builder(adUnitId, size).build(), object : AdLoadCallback<BannerAd> {
                    override fun onAdLoaded(ad: BannerAd) { done(ad, ad.getResponseInfo(), null) }
                    override fun onAdFailedToLoad(adError: LoadAdError) { done(null, adError.responseInfo, adError) }
                })
            }
            AdFormat.INTERSTITIAL -> InterstitialAd.load(AdRequest.Builder(adUnitId).build(),
                object : AdLoadCallback<InterstitialAd> {
                    override fun onAdLoaded(ad: InterstitialAd) { done(ad, ad.getResponseInfo(), null) }
                    override fun onAdFailedToLoad(adError: LoadAdError) { done(null, adError.responseInfo, adError) }
                })
            AdFormat.REWARDED -> RewardedAd.load(AdRequest.Builder(adUnitId).build(),
                object : AdLoadCallback<RewardedAd> {
                    override fun onAdLoaded(ad: RewardedAd) { done(ad, ad.getResponseInfo(), null) }
                    override fun onAdFailedToLoad(adError: LoadAdError) { done(null, adError.responseInfo, adError) }
                })
        }
    }

    override fun directLoad(format: AdFormat, parameter: String, activity: Activity, onResult: (LoadOutcome) -> Unit) =
        direct.load(format, parameter, nextId++, onResult)

    override fun show(handle: AdHandle, activity: Activity, events: (ShowEvent) -> Unit) {
        if (direct.owns(handle)) return direct.show(handle, activity, events)
        fun ev(e: ShowEvent) { main.post { events(e) } }
        when (val ad = ads.remove(handle.id)) {
            is InterstitialAd -> {
                ad.adEventCallback = object : InterstitialAdEventCallback {
                    override fun onAdShowedFullScreenContent() = ev(ShowEvent.Showed)
                    override fun onAdImpression() = ev(ShowEvent.Impression)
                    override fun onAdClicked() = ev(ShowEvent.Clicked)
                    override fun onAdDismissedFullScreenContent() = ev(ShowEvent.Dismissed)
                    override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) =
                        ev(ShowEvent.FailedToShow(fullScreenContentError.message))
                }
                ad.show(activity)
            }
            is RewardedAd -> {
                ad.adEventCallback = object : RewardedAdEventCallback {
                    override fun onAdShowedFullScreenContent() = ev(ShowEvent.Showed)
                    override fun onAdImpression() = ev(ShowEvent.Impression)
                    override fun onAdClicked() = ev(ShowEvent.Clicked)
                    override fun onAdDismissedFullScreenContent() = ev(ShowEvent.Dismissed)
                    override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) =
                        ev(ShowEvent.FailedToShow(fullScreenContentError.message))
                }
                ad.show(activity, object : OnUserEarnedRewardListener {
                    override fun onUserEarnedReward(rewardItem: RewardItem) {
                        ev(ShowEvent.Reward(rewardItem.type, rewardItem.amount))
                    }
                })
            }
            else -> events(ShowEvent.FailedToShow("Ad isn't loaded"))
        }
    }

    override fun bannerView(handle: AdHandle, events: (ShowEvent) -> Unit): View? {
        if (direct.owns(handle)) return direct.bannerView(handle, events)
        val ad = ads[handle.id] as? BannerAd ?: return null
        ad.adEventCallback = object : BannerAdEventCallback {
            override fun onAdImpression() { main.post { events(ShowEvent.Impression) } }
            override fun onAdClicked() { main.post { events(ShowEvent.Clicked) } }
        }
        val activity = lastActivity?.get() ?: return null
        return ad.getView(activity)
    }

    override fun destroy(handle: AdHandle) {
        if (direct.owns(handle)) return direct.destroy(handle)
        (ads.remove(handle.id) as? BannerAd)?.destroy()
    }

    private fun summarize(r: ResponseInfo): ResponseSummary {
        val loaded = r.loadedAdSourceResponseInfo
        var winnerMarked = false
        val attempts = r.adSourceResponses.map { a ->
            val isWinner = !winnerMarked && loaded != null && a.adError == null &&
                    a.adapterClassName == loaded.adapterClassName && a.instanceId == loaded.instanceId
            if (isWinner) winnerMarked = true
            AdapterAttempt(
                sourceName = a.name.ifBlank { a.adapterClassName.substringAfterLast('.') },
                instanceName = a.instanceName.takeIf { it.isNotBlank() && it != a.name },
                adapterClassName = a.adapterClassName,
                latencyMs = a.latencyMillis,
                error = a.adError?.let { AttemptError(it.code, it.domain, it.message) },
                isWinner = isWinner,
                credentials = a.credentials.toStringMap(),
            )
        }
        return ResponseSummary(r.responseId, r.responseExtras.getString("mediation_group_name"), attempts)
    }

    private fun normalize(e: LoadAdError): NormalizedError {
        val kind = when (e.code) {
            LoadAdError.ErrorCode.NO_FILL -> AdErrorKind.NO_FILL
            LoadAdError.ErrorCode.INVALID_REQUEST, LoadAdError.ErrorCode.NOT_FOUND -> AdErrorKind.INVALID_REQUEST
            LoadAdError.ErrorCode.APP_ID_MISSING -> AdErrorKind.APP_ID_MISSING
            LoadAdError.ErrorCode.NETWORK_ERROR -> AdErrorKind.NETWORK_ERROR
            LoadAdError.ErrorCode.INTERNAL_ERROR -> AdErrorKind.INTERNAL_ERROR
            LoadAdError.ErrorCode.TIMEOUT -> AdErrorKind.TIMEOUT
            else -> AdErrorKind.UNKNOWN
        }
        return NormalizedError(kind, e.code.name, e.message)
    }
}

private fun Bundle.toStringMap(): Map<String, String> =
    keySet().associateWith { k -> @Suppress("DEPRECATION") get(k)?.toString() ?: "" }
