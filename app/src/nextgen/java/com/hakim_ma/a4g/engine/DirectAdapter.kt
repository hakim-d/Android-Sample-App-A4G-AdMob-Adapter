package com.hakim_ma.a4g.engine

// NOTE: near-identical copy in src/classic and src/nextgen (the Next-Gen mediation API adds a few overloads). The mediation adapter API
// (com.google.android.gms.ads.mediation.*) has the same names in both SDKs, but each flavor
// must compile it against its own SDK, so this file can't live in `main`.

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.ad4game.admobadapter.AdmobCustomEventManager
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.mediation.InitializationCompleteCallback
import com.google.android.gms.ads.mediation.MediationAdLoadCallback
import com.google.android.gms.ads.mediation.MediationBannerAd
import com.google.android.gms.ads.mediation.MediationBannerAdCallback
import com.google.android.gms.ads.mediation.MediationBannerAdConfiguration
import com.google.android.gms.ads.mediation.MediationInterstitialAd
import com.google.android.gms.ads.mediation.MediationInterstitialAdCallback
import com.google.android.gms.ads.mediation.MediationInterstitialAdConfiguration
import com.google.android.gms.ads.mediation.MediationRewardedAd
import com.google.android.gms.ads.mediation.MediationRewardedAdCallback
import com.google.android.gms.ads.mediation.MediationRewardedAdConfiguration
import com.google.android.gms.ads.rewarded.RewardItem

/**
 * "Ad4Game demo": calls the Ad4Game adapter exactly the way AdMob mediation does (builds the
 * Mediation*AdConfiguration and calls AdmobCustomEventManager.load*Ad), with a Google Ad Manager
 * demo unit as the custom event Parameter. The adapter's real code path runs and fills with
 * Google test creatives: no live traffic, no AdMob mediation in between.
 */
class DirectAdapter(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter = AdmobCustomEventManager()
    private var initialized = false
    private val ads = HashMap<Long, Any>()
    private val forwarders = HashMap<Long, Forwarder>()

    fun owns(handle: AdHandle) = ads.containsKey(handle.id)

    fun load(format: AdFormat, parameter: String, handleId: Long, onResult: (LoadOutcome) -> Unit) {
        if (!initialized) {
            initialized = true
            adapter.initialize(context, object : InitializationCompleteCallback {
                override fun onInitializationSucceeded() {}
                override fun onInitializationFailed(error: String) {}
                override fun onInitializationFailed(error: AdError) {}
            }, emptyList())
        }
        val start = SystemClock.elapsedRealtime()
        val params = Bundle().apply { putString("parameter", parameter) }
        val fwd = Forwarder()
        var done = false
        fun finish(ad: Any?, err: AdError?) = main.post {
            if (done) return@post
            done = true
            val ms = SystemClock.elapsedRealtime() - start
            val handle = ad?.let { AdHandle(handleId, format).also { h -> ads[h.id] = it; forwarders[h.id] = fwd } }
            val attempt = AdapterAttempt(
                sourceName = "Ad4Game", instanceName = "Direct adapter demo", adapterClassName = AD4GAME_CLASS,
                latencyMs = ms, error = err?.let { AttemptError(it.code, it.domain, it.message) },
                isWinner = ad != null, credentials = mapOf("parameter" to parameter),
            )
            val kind = when {
                err == null -> null
                err.code == 408 -> AdErrorKind.TIMEOUT
                err.code == 3 || err.code == 9 -> AdErrorKind.NO_FILL
                err.code == 1 -> AdErrorKind.INVALID_REQUEST
                err.code == 2 -> AdErrorKind.NETWORK_ERROR
                else -> AdErrorKind.UNKNOWN
            }
            onResult(LoadOutcome(format, parameter, ms, handle, ResponseSummary(null, null, listOf(attempt)),
                kind?.let { NormalizedError(it, "${err!!.code}", err.message) }))
        }
        when (format) {
            AdFormat.BANNER -> adapter.loadBannerAd(
                MediationBannerAdConfiguration(context, "", params, Bundle(), true, null, -1, -1, "", AdSize.BANNER, ""),
                object : MediationAdLoadCallback<MediationBannerAd, MediationBannerAdCallback> {
                    override fun onSuccess(ad: MediationBannerAd): MediationBannerAdCallback { finish(ad, null); return fwd }
                    override fun onFailure(error: AdError) { finish(null, error) }
                    override fun onFailure(error: String) { finish(null, AdError(0, error, "com.ad4game.admobadapter")) }
                })
            AdFormat.INTERSTITIAL -> adapter.loadInterstitialAd(
                MediationInterstitialAdConfiguration(context, "", params, Bundle(), true, null, -1, -1, "", ""),
                object : MediationAdLoadCallback<MediationInterstitialAd, MediationInterstitialAdCallback> {
                    override fun onSuccess(ad: MediationInterstitialAd): MediationInterstitialAdCallback { finish(ad, null); return fwd }
                    override fun onFailure(error: AdError) { finish(null, error) }
                    override fun onFailure(error: String) { finish(null, AdError(0, error, "com.ad4game.admobadapter")) }
                })
            AdFormat.REWARDED -> adapter.loadRewardedAd(
                MediationRewardedAdConfiguration(context, "", params, Bundle(), true, null, -1, -1, "", ""),
                object : MediationAdLoadCallback<MediationRewardedAd, MediationRewardedAdCallback> {
                    override fun onSuccess(ad: MediationRewardedAd): MediationRewardedAdCallback { finish(ad, null); return fwd }
                    override fun onFailure(error: AdError) { finish(null, error) }
                    override fun onFailure(error: String) { finish(null, AdError(0, error, "com.ad4game.admobadapter")) }
                })
        }
    }

    fun show(handle: AdHandle, activity: Activity, events: (ShowEvent) -> Unit) {
        forwarders[handle.id]?.sink = events
        when (val ad = ads.remove(handle.id)) {
            is MediationInterstitialAd -> ad.showAd(activity)
            is MediationRewardedAd -> ad.showAd(activity)
            else -> events(ShowEvent.FailedToShow("Ad isn't loaded"))
        }
    }

    fun bannerView(handle: AdHandle, events: (ShowEvent) -> Unit): View? {
        forwarders[handle.id]?.sink = events
        return (ads[handle.id] as? MediationBannerAd)?.getView()
    }

    fun destroy(handle: AdHandle) {
        ads.remove(handle.id); forwarders.remove(handle.id)
    }

    /** Receives the adapter's event callbacks and forwards them to the UI on the main thread. */
    private inner class Forwarder : MediationBannerAdCallback, MediationInterstitialAdCallback, MediationRewardedAdCallback {
        var sink: ((ShowEvent) -> Unit)? = null
        private fun ev(e: ShowEvent) { main.post { sink?.invoke(e) } }
        override fun reportAdClicked() = ev(ShowEvent.Clicked)
        override fun reportAdImpression() = ev(ShowEvent.Impression)
        override fun onAdOpened() = ev(ShowEvent.Showed)
        override fun onAdClosed() = ev(ShowEvent.Dismissed)
        override fun onAdLeftApplication() {}
        override fun onAdFailedToShow(error: AdError) = ev(ShowEvent.FailedToShow(error.message))
        override fun onAdFailedToShow(error: String) = ev(ShowEvent.FailedToShow(error))
        override fun onUserEarnedReward(reward: RewardItem) = ev(ShowEvent.Reward(reward.type, reward.amount))
        override fun onUserEarnedReward() {}
        override fun onVideoStart() {}
        override fun onVideoComplete() {}
    }
}
