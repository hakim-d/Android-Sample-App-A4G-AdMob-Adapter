package com.hakim_ma.a4g.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.hakim_ma.a4g.BuildConfig
import com.hakim_ma.a4g.engine.AdFormat
import java.security.MessageDigest

enum class UnitMode { AD4GAME_DEMO, TEST_UNITS, MY_UNITS }

/** Google's sample App ID and test ad units: safe, never reach Ad4Game. */
object GoogleTest {
    const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
    val units = mapOf(
        AdFormat.BANNER to "ca-app-pub-3940256099942544/9214589741",
        AdFormat.INTERSTITIAL to "ca-app-pub-3940256099942544/1033173712",
        AdFormat.REWARDED to "ca-app-pub-3940256099942544/5224354917",
    )

    /**
     * Ad4Game demo Parameters: Google Ad Manager's official demo ad units. The Ad4Game adapter
     * loads its Parameter as an Ad Manager unit, so these run its real code path with test creatives.
     */
    val ad4gameDemoParams = mapOf(
        AdFormat.BANNER to "/21775744923/example/fixed-size-banner",
        AdFormat.INTERSTITIAL to "/21775744923/example/interstitial",
        AdFormat.REWARDED to "/21775744923/example/rewarded",
    )
}

/** Persisted publisher setup (SharedPreferences: small, synchronous, no extra dependency). */
class ConfigRepository(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    /** Defaults to the Ad4Game demo. "My ad units" only exists in builds with PUBLISHER_UNITS. */
    var mode: UnitMode
        get() {
            val m = runCatching { UnitMode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(UnitMode.AD4GAME_DEMO)
            return if (m == UnitMode.MY_UNITS && !BuildConfig.PUBLISHER_UNITS) UnitMode.AD4GAME_DEMO else m
        }
        set(v) = prefs.edit().putString("mode", v.name).apply()

    /** Runtime App ID (Next-Gen only). Empty = not set. */
    var appId: String
        get() = prefs.getString("appId", "")!!
        // commit(), not apply(): the app restarts its process right after saving the App ID,
        // and apply()'s background write would be lost.
        set(v) { prefs.edit().putString("appId", v.trim()).commit() }

    fun unit(f: AdFormat): String = prefs.getString("unit_${f.name}", "")!!
    fun setUnit(f: AdFormat, v: String) = prefs.edit().putString("unit_${f.name}", v.trim()).apply()

    var testDeviceIds: Set<String>
        get() = prefs.getStringSet("testDevices", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("testDevices", v).apply()

    /** "Load anyway" acknowledged for unprotected live requests, this app session only. */
    var liveAckThisSession = false
}

object Validator {
    private val UNIT = Regex("^ca-app-pub-\\d{16}/\\d{10}$")
    private val APP = Regex("^ca-app-pub-\\d{16}~\\d{10}$")
    private val PUB = Regex("ca-app-pub-(\\d{16})")

    /** null = valid (or empty, which means "not set"). */
    fun adUnitError(v: String): String? {
        val s = v.trim()
        return when {
            s.isEmpty() || UNIT.matches(s) -> null
            s.contains('~') -> "That's an App ID (it has ~). Ad unit IDs use / instead."
            s.startsWith("/") || s.contains(',') ->
                "That looks like the Ad4Game Parameter from your custom event, not an AdMob ad unit ID."
            s.contains(' ') -> "Remove the spaces."
            else -> "Expected ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN (16 digits, slash, 10 digits)."
        }
    }

    fun appIdError(v: String): String? {
        val s = v.trim()
        return when {
            s.isEmpty() || APP.matches(s) -> null
            s.contains('/') -> "That's an ad unit ID (it has /). App IDs use ~ instead."
            else -> "Expected ca-app-pub-XXXXXXXXXXXXXXXX~NNNNNNNNNN (16 digits, tilde, 10 digits)."
        }
    }

    fun publisher(id: String): String? = PUB.find(id)?.groupValues?.get(1)

    /** Ad units only serve under an App ID from the same AdMob account. */
    fun publisherMismatch(appId: String, unit: String): Pair<String, String>? {
        val a = publisher(appId) ?: return null
        val u = publisher(unit) ?: return null
        return if (a != u) a to u else null
    }
}

object TestDevice {
    /** The ID AdMob logs for this device: MD5 of ANDROID_ID, upper-case hex. */
    @SuppressLint("HardwareIds")
    fun hashedId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        return MessageDigest.getInstance("MD5").digest(androidId.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }

    /** The SDK treats emulators as test devices automatically. */
    val isEmulator: Boolean
        get() = Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
                Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk")
}
