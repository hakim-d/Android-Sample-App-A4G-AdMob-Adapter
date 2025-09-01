package com.hakim_ma.a4g

import android.app.AlertDialog
import android.os.Bundle
import android.text.Html
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.admanager.AdManagerAdRequest
import com.google.android.gms.ads.admanager.AdManagerAdView
import com.hakim_ma.a4g.databinding.FragmentFirstBinding
import com.hakim_ma.a4g.model.Publisher
import java.text.SimpleDateFormat
import java.util.Date

class FirstFragment : Fragment() {
    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private val sdf = SimpleDateFormat("hh:mm:ss")
    
    // List of publishers for testing
    private val publishers = listOf(
        Publisher(
            name = "Test with Google Test Ad Units",
            bannerAdUnit = "ca-app-pub-3940256099942544/6300978111", // Google test banner
            interstitialAdUnit = "ca-app-pub-3940256099942544/1033173712", // Google test interstitial
            rewardedAdUnit = "ca-app-pub-3940256099942544/5224354917" // Google test rewarded
        ),
        Publisher(
            name = "Test Your Ad Units",
            bannerAdUnit = "", // Will be set by user input
            interstitialAdUnit = "", // Will be set by user input
            rewardedAdUnit = ""  // Will be set by user input
        )
    )
    
    // Track currently selected publisher
    private var selectedPublisherIndex = 0
    private var customAdUnits: Publisher? = null
    private val currentPublisher: Publisher get() = 
        if (selectedPublisherIndex == 1 && customAdUnits != null) {
            customAdUnits!!
        } else {
            publishers[selectedPublisherIndex]
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainActivity = requireActivity() as MainActivity

        // Display any initial logs from application
        updateUIWithLogs()
        
        // Set up version information
        setupVersionInfo()
        
        // Set up publisher spinner
        setupPublisherSpinner()

        // Wait for AdMob initialization or timeout before loading banner
        if (A4GApplication.isAdMobInitialized) {
            loadBannerAd(mainActivity)
        } else {
            // Set up a polling mechanism to check initialization status
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val checkInitRunnable = object : Runnable {
                override fun run() {
                    // Update logs in UI
                    updateUIWithLogs()
                    
                    if (A4GApplication.isAdMobInitialized) {
                        appendToStats("✅ AdMob initialization completed, loading banner")
                        loadBannerAd(mainActivity)
                    } else {
                        // Check again in 500ms, up to a maximum of 5 seconds
                        if ((System.currentTimeMillis() - startTime) < 5000) {
                            handler.postDelayed(this, 500)
                        } else {
                            // Timeout reached, load banner anyway
                            appendToStats("⚠️ Loading banner after timeout, AdMob may not be fully initialized")
                            loadBannerAd(mainActivity)
                        }
                    }
                }

                private val startTime = System.currentTimeMillis()
            }
            
            // Start checking for initialization
            handler.post(checkInitRunnable)
        }

        binding.buttonFirst.setOnClickListener {
            // 👉 Show Interstitial Ad
            if (mainActivity.mAdManagerInterstitialAd != null) {
                mainActivity.mAdManagerInterstitialAd?.show(this.requireActivity())
                appendToStats("🌠 Show Interstitial Ad")
            } else {
                appendToStats("❌ The interstitial Ad wasn't ready yet")
            }

//            findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
        }

        binding.buttonReward.setOnClickListener {
            // 👉 Show Reward Ad
            mainActivity.rewardedAd?.let { ad ->
                ad.show(this.requireActivity(), OnUserEarnedRewardListener { rewardItem ->
                    // Handle the reward.
                    val rewardAmount = rewardItem.amount
                    val rewardType = rewardItem.type
                    appendToStats("🤑 The user earned the reward")
                    appendToStats("🤑 Reward Amount: ${rewardAmount}")
                    appendToStats("🤑 Reward Type: ${rewardType}")
                })
            } ?: run {
                appendToStats("❌ The Rewarded Ad wasn't ready yet")
            }
//            findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
        }
        
        // Add click handler for checking Ad4Game status
        binding.buttonCheckAdapter.setOnClickListener {
            val status = A4GApplication.ad4GameStatus
            val initTime = A4GApplication.ad4GameInitTime
            appendToStats("🔍 Ad4Game Status: $status (${initTime}ms)")
            
            if (A4GApplication.isAd4GameDelaying()) {
                appendToStats("⚠️ Ad4Game adapter is causing delays")
                appendToStats("👉 Consider checking if there's a newer adapter version or contacting Ad4Game support")
            } else {
                appendToStats("✅ Ad4Game adapter is working properly")
            }
        }
    }
    
    private fun setupVersionInfo() {
        try {
            val versionTextView = binding.root.findViewById<TextView>(R.id.textview_versions)
            val admobVersion = "24.1.0" // From build.gradle
            val ad4gameVersion = "1.1.5" // From admobadapter build.gradle
            versionTextView.text = "AdMob SDK: $admobVersion | Ad4Game Adapter: $ad4gameVersion"
        } catch (e: Exception) {
            Log.e("FirstFragment", "Error setting up version info", e)
        }
    }
    
    private fun setupPublisherSpinner() {
        try {
            // Create an adapter with publisher names
            val publisherNames = publishers.map { it.name }.toTypedArray()
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, publisherNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            
            // Set the adapter to the spinner
            val spinner = binding.root.findViewById<Spinner>(R.id.publisher_spinner)
            spinner.adapter = adapter
            
            // Set the selection listener
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                    if (selectedPublisherIndex != position) {
                        selectedPublisherIndex = position
                        
                        if (position == 1) {
                            // Show dialog for custom ad units
                            showAdUnitsDialog()
                        } else {
                            appendToStats("🔄 Switching to publisher: ${currentPublisher.name}")
                            appendToStats("📱 Banner Ad Unit: ${currentPublisher.bannerAdUnit}")
                            appendToStats("📱 Interstitial Ad Unit: ${currentPublisher.interstitialAdUnit}")
                            appendToStats("📱 Rewarded Ad Unit: ${currentPublisher.rewardedAdUnit}")
                            
                            try {
                                // Reload ads with new publisher
                                val mainActivity = requireActivity() as MainActivity
                                loadBannerAd(mainActivity)
                                mainActivity.loadAds(currentPublisher.interstitialAdUnit, currentPublisher.rewardedAdUnit)
                            } catch (e: Exception) {
                                appendToStats("❌ Error switching publisher: ${e.message}")
                                Log.e("FirstFragment", "Error switching publisher", e)
                            }
                        }
                    }
                }
                
                override fun onNothingSelected(parent: AdapterView<*>) {
                    // Do nothing
                }
            }
            
            // Set initial selection
            spinner.setSelection(selectedPublisherIndex)
        } catch (e: Exception) {
            appendToStats("❌ Error setting up publisher spinner: ${e.message}")
            Log.e("FirstFragment", "Error setting up publisher spinner", e)
        }
    }
    
    private fun loadBannerAd(mainActivity: MainActivity) {
        appendToStats("🔄 Loading banner ad at ${sdf.format(Date())}")
        
        try {
            // Remove the old ad view if it exists
            val adViewContainer = binding.root.findViewById<ViewGroup>(R.id.ad_container)
            adViewContainer.removeAllViews()
            
            // Create a new AdManagerAdView programmatically with the current publisher's ad unit
            val adView = AdManagerAdView(requireContext())
            adView.adUnitId = currentPublisher.bannerAdUnit
            adView.setAdSize(AdSize.BANNER)
            
            // Add the new ad view to the container
            adViewContainer.addView(adView)
            
            // Build the ad request
            val adRequest = AdManagerAdRequest.Builder()
                .addCustomTargeting("test_mode", "true")
                .build()
            
            appendToStats("📱 Using banner ad unit: ${currentPublisher.bannerAdUnit}")
            appendToStats("📊 Ad request created with test mode targeting")
            
            // Set the ad listener
            adView.adListener = object: AdListener() {
                override fun onAdClicked() {
                    appendToStats("👆 Banner Ad Clicked")
                }
                override fun onAdClosed() {
                    appendToStats("✋ Back from clicking the Banner Ad")
                }
                override fun onAdFailedToLoad(adError : LoadAdError) {
                    appendToStats("❌ Banner Ad error:\n${adError.responseInfo}")
                    Log.e("FirstFragment", "Banner ad failed to load: ${adError.message}")
                }
                override fun onAdImpression() {
                    appendToStats("✅ Banner Ad Impression")
                }
                override fun onAdLoaded() {
                    appendToStats("✅ Banner Ad Loaded")
                }
                override fun onAdOpened() {
                    // Code to be executed when an ad opens an overlay that
                    // covers the screen.
                    appendToStats("✅ Banner Ad Opened")
                }
            }
            
            // Load the ad
            adView.loadAd(adRequest)
            
            // Store the reference in MainActivity
            mainActivity.mAdManagerAdView = adView
        } catch (e: Exception) {
            appendToStats("❌ Error loading banner ad: ${e.message}")
            Log.e("FirstFragment", "Error loading banner ad", e)
        }
    }
    
    // Helper method to update the UI with logs from the Application class
    private fun updateUIWithLogs() {
        if (A4GApplication.logMessages.isNotEmpty()) {
            val allLogs = A4GApplication.logMessages.joinToString("\n")
            binding.root.findViewById<TextView>(R.id.textview_stats).text = allLogs
        }
    }
    
    // Helper method to append a message to the stats TextView
    private fun appendToStats(message: String) {
        val statsView = binding.root.findViewById<TextView>(R.id.textview_stats)
        val timestamp = sdf.format(Date())
        statsView.text = "$timestamp   $message\n${statsView.text}"
    }

    private fun showAdUnitsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_ad_units, null)
        
        val editTextBanner = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextBanner)
        val editTextInterstitial = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextInterstitial)
        val editTextReward = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextReward)
        
        // Pre-fill with existing custom ad units if available, otherwise use default GAM Publisher ad units
        if (customAdUnits != null) {
            customAdUnits?.let { publisher ->
                editTextBanner.setText(publisher.bannerAdUnit)
                editTextInterstitial.setText(publisher.interstitialAdUnit)
                editTextReward.setText(publisher.rewardedAdUnit)
            }
        } else {
            // Pre-fill with default GAM Publisher ad units
            editTextBanner.setText("/60257202,107083982/721319")
            editTextInterstitial.setText("/60257202,107083982/721321")
            editTextReward.setText("/60257202,107083982/721322")
        }
        
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        dialogView.findViewById<View>(R.id.buttonCancel).setOnClickListener {
            dialog.dismiss()
            // Reset spinner to previous selection
            val spinner = binding.root.findViewById<Spinner>(R.id.publisher_spinner)
            spinner.setSelection(if (selectedPublisherIndex == 1) 0 else 1)
        }
        
        dialogView.findViewById<View>(R.id.buttonTest).setOnClickListener {
            val bannerUnit = editTextBanner.text.toString().trim()
            val interstitialUnit = editTextInterstitial.text.toString().trim()
            val rewardUnit = editTextReward.text.toString().trim()
            
            if (bannerUnit.isEmpty() && interstitialUnit.isEmpty() && rewardUnit.isEmpty()) {
                appendToStats("⚠️ Please enter at least one ad unit ID")
                return@setOnClickListener
            }
            
            // Create custom publisher with user input
            customAdUnits = Publisher(
                name = "Test Your Ad Units",
                bannerAdUnit = bannerUnit,
                interstitialAdUnit = interstitialUnit,
                rewardedAdUnit = rewardUnit
            )
            
            appendToStats("🔄 Using custom ad units:")
            if (bannerUnit.isNotEmpty()) appendToStats("📱 Banner: $bannerUnit")
            if (interstitialUnit.isNotEmpty()) appendToStats("📱 Interstitial: $interstitialUnit")
            if (rewardUnit.isNotEmpty()) appendToStats("📱 Reward: $rewardUnit")
            
            try {
                // Reload ads with custom ad units
                val mainActivity = requireActivity() as MainActivity
                if (bannerUnit.isNotEmpty()) {
                    loadBannerAd(mainActivity)
                }
                if (interstitialUnit.isNotEmpty() || rewardUnit.isNotEmpty()) {
                    mainActivity.loadAds(interstitialUnit, rewardUnit)
                }
            } catch (e: Exception) {
                appendToStats("❌ Error loading custom ad units: ${e.message}")
                Log.e("FirstFragment", "Error loading custom ad units", e)
            }
            
            dialog.dismiss()
        }
        
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
