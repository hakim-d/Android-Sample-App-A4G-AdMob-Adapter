package com.hakim_ma.a4g

import android.app.Application
import com.hakim_ma.a4g.data.ConfigRepository
import com.hakim_ma.a4g.data.GoogleTest
import com.hakim_ma.a4g.data.Validator
import com.hakim_ma.a4g.engine.AdEngine
import com.hakim_ma.a4g.engine.AdEngineProvider
import com.hakim_ma.a4g.engine.InitSnapshot

class A4GApplication : Application() {
    lateinit var engine: AdEngine
        private set
    lateinit var config: ConfigRepository
        private set
    var initSnapshot: InitSnapshot? = null
        private set
    private val initListeners = mutableListOf<(InitSnapshot) -> Unit>()

    /**
     * The App ID the SDK was started with (fixed for this process):
     * classic = manifest (build-time -Pa4g.admobAppId); Next-Gen = saved runtime App ID, else
     * Google's sample ID. Google test units serve under any App ID.
     */
    lateinit var appId: String
        private set

    override fun onCreate() {
        super.onCreate()
        config = ConfigRepository(this)
        engine = AdEngineProvider.create(this) // flavor-specific: classic or Next-Gen
        // A custom App ID only matters for "My ad units"; otherwise use Google's sample ID.
        appId = if (engine.info.supportsRuntimeAppId) {
            config.appId.takeIf { BuildConfig.PUBLISHER_UNITS && it.isNotEmpty() && Validator.appIdError(it) == null }
                ?: GoogleTest.APP_ID
        } else BuildConfig.ADMOB_APP_ID
        engine.initialize(appId, config.testDeviceIds.toList()) { snap ->
            initSnapshot = snap
            initListeners.toList().forEach { it(snap) }
            initListeners.clear()
        }
    }

    fun whenInitialized(block: (InitSnapshot) -> Unit) {
        initSnapshot?.let(block) ?: initListeners.add(block)
    }
}
