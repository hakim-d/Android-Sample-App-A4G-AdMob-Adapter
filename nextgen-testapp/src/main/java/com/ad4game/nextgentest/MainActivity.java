package com.ad4game.nextgentest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.ad4game.admobadapter.AdmobCustomEventManager;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.mediation.InitializationCompleteCallback;
import com.google.android.gms.ads.mediation.MediationAdLoadCallback;
import com.google.android.gms.ads.mediation.MediationBannerAd;
import com.google.android.gms.ads.mediation.MediationBannerAdCallback;
import com.google.android.gms.ads.mediation.MediationBannerAdConfiguration;
import com.google.android.gms.ads.mediation.MediationInterstitialAd;
import com.google.android.gms.ads.mediation.MediationInterstitialAdCallback;
import com.google.android.gms.ads.mediation.MediationInterstitialAdConfiguration;
import com.google.android.gms.ads.mediation.MediationNativeAdCallback;
import com.google.android.gms.ads.mediation.MediationNativeAdConfiguration;
import com.google.android.gms.ads.mediation.MediationRewardedAd;
import com.google.android.gms.ads.mediation.MediationRewardedAdCallback;
import com.google.android.gms.ads.mediation.MediationRewardedAdConfiguration;
import com.google.android.gms.ads.mediation.UnifiedNativeAdMapper;
import com.google.android.gms.ads.nativead.NativeAdOptions;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.libraries.ads.mobile.sdk.MobileAds;
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig;

import java.util.Collections;

/**
 * Drives the Ad4Game Next-Gen adapter exactly the way the mediation SDK does: builds the
 * Mediation*AdConfiguration objects and calls AdmobCustomEventManager.load*Ad(). The custom
 * event "parameter" is set to Google's test ad units, so the adapter's inner Next-Gen load runs
 * against real (test) ad responses.
 *
 * Buttons, or adb: am start -n com.ad4game.nextgentest/.MainActivity --es action banner|interstitial|rewarded|native
 * All results are logged under the "A4GTest" tag.
 */
public class MainActivity extends Activity {
    static final String TAG = "A4GTest";

    private static final String APP_ID = "ca-app-pub-3940256099942544~3347511713";
    private static final String BANNER = "ca-app-pub-3940256099942544/6300978111";
    private static final String INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712";
    private static final String REWARDED = "ca-app-pub-3940256099942544/5224354917";
    private static final String NATIVE = "ca-app-pub-3940256099942544/2247696110";

    private final AdmobCustomEventManager adapter = new AdmobCustomEventManager();
    private volatile boolean sdkReady = false;
    private String pendingAction;

    private FrameLayout bannerContainer;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        // Next-Gen SDK must be initialized off the main thread.
        new Thread(() -> MobileAds.initialize(this,
                new InitializationConfig.Builder(APP_ID).build(),
                status -> runOnUiThread(() -> {
                    sdkReady = true;
                    log("RESULT init: Next-Gen SDK " + MobileAds.getVersion() + " initialized");
                    adapter.initialize(this, new InitializationCompleteCallback() {
                        @Override public void onInitializationSucceeded() { log("RESULT adapter initialize: succeeded"); }
                        @Override public void onInitializationFailed(@NonNull String s) { log("RESULT adapter initialize: FAILED " + s); }
                        @Override public void onInitializationFailed(@NonNull AdError e) { log("RESULT adapter initialize: FAILED " + e); }
                    }, Collections.emptyList());
                    log("adapter version " + adapter.getVersionInfo());
                    if (pendingAction != null) { run(pendingAction); pendingAction = null; }
                }))).start();

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String action = intent.getStringExtra("action");
        if (action == null) return;
        if (sdkReady) run(action); else pendingAction = action;
    }

    private void run(String action) {
        switch (action) {
            case "banner": loadBanner(); break;
            case "interstitial": loadInterstitial(); break;
            case "rewarded": loadRewarded(); break;
            case "native": loadNative(); break;
            case "fail": loadFail(); break;
            default: log("unknown action " + action);
        }
    }

    private Bundle params(String adUnit) {
        Bundle b = new Bundle();
        b.putString("parameter", adUnit);
        return b;
    }

    // ---- Banner ----
    private void loadBanner() {
        log("START banner");
        MediationBannerAdConfiguration config = new MediationBannerAdConfiguration(
                this, "", params(BANNER), new Bundle(), true, null, -1, -1, "", AdSize.BANNER, "");
        adapter.loadBannerAd(config, new MediationAdLoadCallback<MediationBannerAd, MediationBannerAdCallback>() {
            @Override
            public MediationBannerAdCallback onSuccess(MediationBannerAd ad) {
                View view = ad.getView();
                log("RESULT banner: onSuccess " + thread() + " view=" + (view == null ? "null" : view.getClass().getSimpleName()));
                if (view != null) {
                    bannerContainer.removeAllViews();
                    if (view.getParent() != null) ((ViewGroup) view.getParent()).removeView(view);
                    bannerContainer.addView(view);
                }
                return new LoggingCallback("banner");
            }
            @Override public void onFailure(@NonNull AdError e) { log("RESULT banner: onFailure " + thread() + " " + e); }
            @Override public void onFailure(@NonNull String s) { log("RESULT banner: onFailure " + s); }
        });
    }

    // ---- Interstitial ----
    private void loadInterstitial() {
        log("START interstitial");
        MediationInterstitialAdConfiguration config = new MediationInterstitialAdConfiguration(
                this, "", params(INTERSTITIAL), new Bundle(), true, null, -1, -1, "", "");
        adapter.loadInterstitialAd(config, new MediationAdLoadCallback<MediationInterstitialAd, MediationInterstitialAdCallback>() {
            @Override
            public MediationInterstitialAdCallback onSuccess(MediationInterstitialAd ad) {
                log("RESULT interstitial: onSuccess " + thread() + ", showing");
                LoggingCallback cb = new LoggingCallback("interstitial");
                getWindow().getDecorView().post(() -> ad.showAd(MainActivity.this));
                return cb;
            }
            @Override public void onFailure(@NonNull AdError e) { log("RESULT interstitial: onFailure " + thread() + " " + e); }
            @Override public void onFailure(@NonNull String s) { log("RESULT interstitial: onFailure " + s); }
        });
    }

    // ---- Rewarded ----
    private void loadRewarded() {
        log("START rewarded");
        MediationRewardedAdConfiguration config = new MediationRewardedAdConfiguration(
                this, "", params(REWARDED), new Bundle(), true, null, -1, -1, "", "");
        adapter.loadRewardedAd(config, new MediationAdLoadCallback<MediationRewardedAd, MediationRewardedAdCallback>() {
            @Override
            public MediationRewardedAdCallback onSuccess(MediationRewardedAd ad) {
                log("RESULT rewarded: onSuccess " + thread() + ", showing");
                LoggingCallback cb = new LoggingCallback("rewarded");
                getWindow().getDecorView().post(() -> ad.showAd(MainActivity.this));
                return cb;
            }
            @Override public void onFailure(@NonNull AdError e) { log("RESULT rewarded: onFailure " + thread() + " " + e); }
            @Override public void onFailure(@NonNull String s) { log("RESULT rewarded: onFailure " + s); }
        });
    }

    // ---- Failure path: invalid ad unit must produce exactly one onFailure ----
    private void loadFail() {
        log("START fail");
        MediationInterstitialAdConfiguration config = new MediationInterstitialAdConfiguration(
                this, "", params("ca-app-pub-0000000000000000/0000000000"), new Bundle(), true, null, -1, -1, "", "");
        adapter.loadInterstitialAd(config, new MediationAdLoadCallback<MediationInterstitialAd, MediationInterstitialAdCallback>() {
            @Override public MediationInterstitialAdCallback onSuccess(MediationInterstitialAd ad) { log("RESULT fail: UNEXPECTED onSuccess"); return new LoggingCallback("fail"); }
            @Override public void onFailure(@NonNull AdError e) { log("RESULT fail: onFailure " + thread() + " " + e); }
            @Override public void onFailure(@NonNull String s) { log("RESULT fail: onFailure " + s); }
        });
    }

    // ---- Native ----
    private void loadNative() {
        log("START native");
        MediationNativeAdConfiguration config = new MediationNativeAdConfiguration(
                this, "", params(NATIVE), new Bundle(), true, null, -1, -1, "", "",
                new NativeAdOptions.Builder().build());
        adapter.loadNativeAd(config, new MediationAdLoadCallback<UnifiedNativeAdMapper, MediationNativeAdCallback>() {
            @Override
            public MediationNativeAdCallback onSuccess(UnifiedNativeAdMapper m) {
                log("RESULT native: onSuccess " + thread()
                        + " headline=\"" + m.getHeadline() + "\" body=\"" + m.getBody()
                        + "\" cta=\"" + m.getCallToAction() + "\" advertiser=\"" + m.getAdvertiser()
                        + "\" icon=" + (m.getIcon() != null)
                        + " images=" + (m.getImages() == null ? 0 : m.getImages().size()));
                m.recordImpression();
                log("native: recordImpression() forwarded without crash");
                return new LoggingCallback("native");
            }
            @Override public void onFailure(@NonNull AdError e) { log("RESULT native: onFailure " + thread() + " " + e); }
            @Override public void onFailure(@NonNull String s) { log("RESULT native: onFailure " + s); }
        });
    }

    static String thread() {
        return Looper.myLooper() == Looper.getMainLooper() ? "[main]" : "[BG:" + Thread.currentThread().getName() + "]";
    }

    void log(String msg) {
        Log.i(TAG, msg);
        runOnUiThread(() -> logView.append(msg + "\n"));
    }

    /** Implements every mediation event callback and logs each call plus the calling thread. */
    class LoggingCallback implements MediationBannerAdCallback, MediationInterstitialAdCallback,
            MediationRewardedAdCallback, MediationNativeAdCallback {
        private final String format;
        LoggingCallback(String format) { this.format = format; }
        private void ev(String name) { log("EVENT " + format + ": " + name + " " + thread()); }

        @Override public void reportAdClicked() { ev("reportAdClicked"); }
        @Override public void reportAdImpression() { ev("reportAdImpression"); }
        @Override public void onAdOpened() { ev("onAdOpened"); }
        @Override public void onAdClosed() { ev("onAdClosed"); }
        @Override public void onAdLeftApplication() { ev("onAdLeftApplication"); }
        @Override public void onAdFailedToShow(@NonNull String s) { ev("onAdFailedToShow " + s); }
        @Override public void onAdFailedToShow(@NonNull AdError e) { ev("onAdFailedToShow " + e); }
        @Override public void onUserEarnedReward(@NonNull RewardItem r) { ev("onUserEarnedReward type=" + r.getType() + " amount=" + r.getAmount()); }
        @Override public void onUserEarnedReward() { ev("onUserEarnedReward()"); }
        @Override public void onVideoStart() { ev("onVideoStart"); }
        @Override public void onVideoComplete() { ev("onVideoComplete"); }
        @Override public void onVideoPause() { ev("onVideoPause"); }
        @Override public void onVideoPlay() { ev("onVideoPlay"); }
        @Override public void onVideoMute() { ev("onVideoMute"); }
        @Override public void onVideoUnmute() { ev("onVideoUnmute"); }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 96, 32, 32);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        for (String a : new String[]{"banner", "interstitial", "rewarded", "native"}) {
            Button b = new Button(this);
            b.setText(a);
            b.setTextSize(11);
            b.setOnClickListener(v -> { if (sdkReady) run(a); else log("SDK not ready yet"); });
            buttons.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(buttons);

        bannerContainer = new FrameLayout(this);
        bannerContainer.setMinimumHeight(160);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(bannerContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200));

        logView = new TextView(this);
        logView.setTextSize(11);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }
}
