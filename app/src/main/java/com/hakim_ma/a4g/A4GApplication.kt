package com.hakim_ma.a4g

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.initialization.AdapterStatus
import com.google.android.gms.ads.initialization.InitializationStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue

class A4GApplication : Application() {
    
    companion object {
        private const val TAG = "A4GApplication"
        // Flag to indicate if initialization is complete
        var isAdMobInitialized = false
        
        // Store Ad4Game status for checking
        var ad4GameInitTime = 0L
        var ad4GameStatus = "Unknown"
        
        // Queue to store logs for display in UI
        val logMessages = ConcurrentLinkedQueue<String>()
        private val sdf = SimpleDateFormat("hh:mm:ss")
        
        // Add a log message to the queue and also log it
        fun addLog(message: String) {
            val timestamp = sdf.format(Date())
            val fullMessage = "$timestamp   $message"
            logMessages.add(fullMessage)
            Log.d(TAG, message)
            
            // Keep only the most recent 100 messages
            while (logMessages.size > 100) {
                logMessages.poll()
            }
        }
        
        // Check if Ad4Game adapter is causing a problem
        fun isAd4GameDelaying(): Boolean {
            return ad4GameStatus.contains("NOT_READY", ignoreCase = true) || 
                   ad4GameInitTime > 5000L
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        
        addLog("⏱️ Starting AdMob initialization with 5s timeout")
        
        // Set configuration for AdMob to speed up initialization
        try {
            val requestConfiguration = RequestConfiguration.Builder()
                .setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE)
                .setTagForUnderAgeOfConsent(RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE)
                .build()
            
            MobileAds.setRequestConfiguration(requestConfiguration)
            addLog("✅ Test device configuration set successfully")
        } catch (e: Exception) {
            addLog("⚠️ Error setting test device: ${e.message}")
        }
        
        // Create a timeout handler for AdMob initialization
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (!isAdMobInitialized) {
                isAdMobInitialized = true
                addLog("⚠️ AdMob initialization timed out after 5s, proceeding anyway")
                
                // If Ad4Game is still unknown, mark it as problematic
                if (ad4GameStatus == "Unknown") {
                    ad4GameStatus = "NOT_READY (TIMEOUT)"
                    ad4GameInitTime = 5000L
                    addLog("⚠️ Ad4Game adapter timed out during initialization")
                }
            }
        }
        
        // Set a 5-second timeout for initialization
        handler.postDelayed(timeoutRunnable, 5000)
        
        // Initialize AdMob with a listener to log adapter statuses
        MobileAds.initialize(this) { initializationStatus ->
            // Cancel the timeout since initialization completed
            handler.removeCallbacks(timeoutRunnable)
            
            // Mark initialization as complete
            isAdMobInitialized = true
            
            val statusMap = initializationStatus.adapterStatusMap
            addLog("📊 AdMob initialization completed")
            
            for ((adapter, status) in statusMap) {
                val description = when (status.initializationState) {
                    AdapterStatus.State.NOT_READY -> "NOT_READY"
                    AdapterStatus.State.READY -> "READY"
                    else -> "Unknown"
                }
                
                // Log all adapters
                val message = "Adapter: $adapter Status: $description Latency: ${status.latency}ms"
                Log.d(TAG, message)
                
                // Check if this is the Ad4Game adapter
                if (adapter.contains("ad4game", ignoreCase = true)) {
                    ad4GameStatus = description
                    ad4GameInitTime = status.latency.toLong()
                    
                    if (status.latency > 5000) {
                        addLog("⚠️ Ad4Game adapter initialization took ${status.latency}ms (${description})")
                    } else {
                        addLog("✅ Ad4Game adapter initialization: ${status.latency}ms (${description})")
                    }
                }
            }
        }
    }
}
