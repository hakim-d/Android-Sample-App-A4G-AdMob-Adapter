package com.hakim_ma.a4g.domain

import com.hakim_ma.a4g.engine.AD4GAME_CLASS
import com.hakim_ma.a4g.engine.AdErrorKind
import com.hakim_ma.a4g.engine.LoadOutcome

enum class Status { SUCCESS, NEUTRAL, WARNING, ERROR }

data class Verdict(val status: Status, val title: String, val body: String)

/** Spec section 5: first matching rule wins. Pure Kotlin, no Android. */
object VerdictEngine {
    fun evaluate(testUnits: Boolean, o: LoadOutcome, demo: Boolean = false, ad4gameUnit: Boolean = false): Verdict {
        val attempts = o.summary?.attempts.orEmpty()
        val a4g = attempts.firstOrNull { it.isAd4Game }
        val winner = o.summary?.winner

        if (ad4gameUnit) return ad4gameUnitVerdict(o)

        if (demo) return if (o.success) Verdict(
            Status.SUCCESS, "Ad4Game adapter works",
            "The Ad4Game adapter loaded a test creative through its own code, called the same way AdMob " +
                    "mediation calls it. In a real app, AdMob reaches it through your mediation group."
        ) else Verdict(
            Status.ERROR, "Ad4Game adapter didn't fill",
            (o.error?.let { if (it.code == "408") "The adapter got no answer within 10 seconds." else it.message }
                ?: "No ad returned.") + " Check the connection and try again."
        )

        if (testUnits && o.success) return Verdict(
            Status.NEUTRAL, "SDK works (test ad)",
            "Google test units confirm the SDK setup only. They never call Ad4Game. " +
                    "Use your own ad units to test Ad4Game."
        )
        if (!o.success && attempts.isEmpty()) {
            val (t, b) = explain(o.error?.kind ?: AdErrorKind.UNKNOWN)
            return Verdict(Status.ERROR, t, b)
        }
        attempts.firstOrNull {
            it.adapterClassName.contains("ad4game", true) && it.adapterClassName != AD4GAME_CLASS
        }?.let {
            return Verdict(
                Status.ERROR, "Ad4Game class name is wrong",
                "The custom event points to ${it.adapterClassName}. It must be exactly $AD4GAME_CLASS."
            )
        }
        if (a4g != null && a4g.credentials["parameter"].isNullOrBlank() && a4g.credentials.isNotEmpty()) return Verdict(
            Status.ERROR, "Ad4Game parameter is empty",
            "The Ad4Game custom event in AdMob has no Parameter. Paste the value your Ad4Game account manager gave you."
        )
        if (o.success && winner?.isAd4Game == true) return Verdict(
            Status.SUCCESS, "Served by Ad4Game", "Ad4Game filled this request through your mediation group."
        )
        if (o.success && winner != null && a4g?.error != null) return Verdict(
            Status.NEUTRAL, "Served by ${winner.sourceName}",
            "Ad4Game was tried but didn't fill (${a4g.error.message}). ${winner.sourceName} filled instead."
        )
        if (o.success && winner != null && a4g == null) return Verdict(
            Status.NEUTRAL, "Served by ${winner.sourceName}",
            "Ad4Game wasn't reached because a higher source filled first. To test Ad4Game alone, " +
                    "use a mediation group with only Ad4Game, or move it up in the waterfall."
        )
        if (!o.success && a4g != null) return Verdict(
            Status.WARNING, "Ad4Game didn't fill",
            a4g.error?.let { if (it.code == 408) "The Ad4Game adapter got no answer within 10 seconds." else it.message }
                ?: "Ad4Game was called but returned no ad."
        )
        if (!o.success) return Verdict(
            Status.ERROR, "Ad4Game isn't in this mediation group",
            "AdMob tried ${attempts.size} source(s) and none was Ad4Game. In AdMob > Mediation, add the Ad4Game " +
                    "custom event ($AD4GAME_CLASS) to the group targeting this ad unit."
        )
        return Verdict(Status.NEUTRAL, "Result recorded", "See the waterfall for details.")
    }

    /** The publisher's own Ad4Game Unit ID, loaded through the adapter directly. */
    private fun ad4gameUnitVerdict(o: LoadOutcome): Verdict {
        if (o.success) return Verdict(
            Status.SUCCESS, "Your Ad4Game Unit ID fills",
            "The Ad4Game adapter loaded an ad from this Unit ID. On AdMob it goes in the Ad4Game custom event " +
                    "Parameter; on AppLovin MAX and Unity LevelPlay, in the Google Ad Manager network setup."
        )
        val err = o.error
        return when (err?.kind) {
            AdErrorKind.INVALID_REQUEST -> Verdict(
                Status.ERROR, "This Unit ID was rejected",
                "Google Ad Manager didn't accept this Unit ID. Check it matches what your Ad4Game account manager " +
                        "sent, character for character, and that it's for this ad format."
            )
            AdErrorKind.NETWORK_ERROR -> Verdict(
                Status.ERROR, "No connection", "The request couldn't reach Google Ad Manager. Check the connection and try again."
            )
            AdErrorKind.TIMEOUT -> Verdict(
                Status.WARNING, "No answer in time", "The Ad4Game adapter got no answer within 10 seconds. Try again."
            )
            else -> Verdict(
                Status.WARNING, "No fill in this tester",
                "The request worked but no ad came back. Ad4Game Unit IDs can be set to serve only in your own app, " +
                        "so this doesn't mean your setup is wrong. Ask your account manager, then test in your app with " +
                        "Ad Inspector (AdMob), the Mediation Debugger (MAX) or the Integration Test Suite (LevelPlay)."
            )
        }
    }

    fun explain(kind: AdErrorKind): Pair<String, String> = when (kind) {
        AdErrorKind.NO_FILL -> "No fill" to "The request worked, but no ad source returned an ad."
        AdErrorKind.MEDIATION_NO_FILL -> "No source filled" to "AdMob ran your mediation waterfall and every source passed."
        AdErrorKind.INVALID_REQUEST -> "AdMob rejected the request" to "Usually the ad unit ID is wrong, or belongs to a different app or format."
        AdErrorKind.APP_ID_MISSING -> "App ID missing or invalid" to "The SDK has no valid AdMob App ID."
        AdErrorKind.NETWORK_ERROR -> "No connection" to "The ad request couldn't reach AdMob."
        AdErrorKind.INTERNAL_ERROR -> "AdMob internal error" to "Something went wrong inside the SDK or on AdMob's side. Try again."
        AdErrorKind.TIMEOUT -> "Timed out" to "The ad request took too long and was abandoned."
        AdErrorKind.UNKNOWN -> "Ad request failed" to "See the technical details."
    }
}
