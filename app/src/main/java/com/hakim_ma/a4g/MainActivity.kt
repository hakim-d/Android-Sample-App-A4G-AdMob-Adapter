package com.hakim_ma.a4g

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import com.google.android.gms.ads.AdListener
import com.hakim_ma.a4g.databinding.ActivityMainBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.admanager.AdManagerAdRequest
import com.google.android.gms.ads.admanager.AdManagerAdView
import com.google.android.gms.ads.admanager.AdManagerInterstitialAd
import com.google.android.gms.ads.admanager.AdManagerInterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    private val sdf = SimpleDateFormat("hh:mm:ss")

    lateinit var mAdManagerAdView : AdManagerAdView
    var mAdManagerInterstitialAd: AdManagerInterstitialAd? = null
    var rewardedAd: RewardedAd? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load ads either when initialization is complete or after the timeout
        val adLoadRunnable = Runnable {
            loadAds()
        }

        // Check initialization status or set a polling mechanism
        if (A4GApplication.isAdMobInitialized) {
            // AdMob already initialized, load ads immediately
            loadAds()
        } else {
            // Set up a polling mechanism to check initialization status
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val checkInitRunnable = object : Runnable {
                override fun run() {
                    if (A4GApplication.isAdMobInitialized) {
                        loadAds()
                    } else {
                        // Check again in 500ms, up to a maximum of 5 seconds
                        if ((System.currentTimeMillis() - startTime) < 5000) {
                            handler.postDelayed(this, 500)
                        } else {
                            // Timeout reached, load ads anyway
                            A4GApplication.addLog("⚠️ Loading ads after timeout, AdMob may not be fully initialized")
                            loadAds()
                        }
                    }
                }

                private val startTime = System.currentTimeMillis()
            }
            
            // Start checking for initialization
            handler.post(checkInitRunnable)
        }

        setSupportActionBar(binding.toolbar)
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)
    }
    
    private fun loadAds() {
        loadAds("/60257202,107083982/721321", "/60257202,107083982/721322")
    }
    
    fun loadAds(interstitialAdUnit: String, rewardedAdUnit: String) {
        A4GApplication.addLog("🔄 Loading interstitial and reward ads")
        
        // 👉 Load an Interstitial Ad using Google Ad Manager format
        val adRequestInterstitial = AdManagerAdRequest.Builder()
            .addCustomTargeting("test_mode", "true")
            .build()
        AdManagerInterstitialAd.load(
            this.applicationContext,
            interstitialAdUnit,
            adRequestInterstitial,
            object : AdManagerInterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    mAdManagerInterstitialAd = null
                    A4GApplication.addLog("❌ Interstitial ad failed to load: ${adError.message}")
                }
                override fun onAdLoaded(interstitialAd: AdManagerInterstitialAd) {
                    mAdManagerInterstitialAd = interstitialAd
                    A4GApplication.addLog("✅ Interstitial ad loaded successfully")
                }
            })

        // 👉 Load a Reward Ad using Google Ad Manager format
        val adRequest = AdManagerAdRequest.Builder()
            .addCustomTargeting("test_mode", "true")
            .build()
        // Note: When using GAM ad units with the RewardedAd class, we need to ensure compatibility
        RewardedAd.load(this, rewardedAdUnit, adRequest, object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                rewardedAd = null
                A4GApplication.addLog("❌ Reward ad failed to load: ${adError.message}")
            }
            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
                A4GApplication.addLog("⭐️ Reward Ad loaded successfully")
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }

}