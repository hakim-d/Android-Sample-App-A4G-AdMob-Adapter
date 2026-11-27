package com.hakim_ma.a4g

import com.hakim_ma.a4g.domain.Status
import com.hakim_ma.a4g.domain.VerdictEngine
import com.hakim_ma.a4g.engine.AD4GAME_CLASS
import com.hakim_ma.a4g.engine.AdErrorKind
import com.hakim_ma.a4g.engine.AdFormat
import com.hakim_ma.a4g.engine.AdHandle
import com.hakim_ma.a4g.engine.AdapterAttempt
import com.hakim_ma.a4g.engine.AttemptError
import com.hakim_ma.a4g.engine.LoadOutcome
import com.hakim_ma.a4g.engine.NormalizedError
import com.hakim_ma.a4g.engine.ResponseSummary
import com.hakim_ma.a4g.ui.flow.ReplayTimeline
import org.junit.Assert.assertEquals
import org.junit.Test

class LogicTest {
    private fun attempt(name: String, cls: String, ms: Long, err: Int? = null, win: Boolean = false, param: String = "x") =
        AdapterAttempt(name, null, cls, ms, err?.let { AttemptError(it, "d", "m") }, win, mapOf("parameter" to param))

    private fun outcome(success: Boolean, vararg a: AdapterAttempt, kind: AdErrorKind = AdErrorKind.NO_FILL) = LoadOutcome(
        AdFormat.BANNER, "u", 100, if (success) AdHandle(1, AdFormat.BANNER) else null,
        ResponseSummary("r", null, a.toList()), if (success) null else NormalizedError(kind, "3", "no fill"))

    @Test fun shortWaterfallsReplayInRealTime() =
        assertEquals(listOf(300L, 800L), ReplayTimeline.barDurations(listOf(300, 800)))

    @Test fun longWaterfallsCompressAndClamp() {
        val d = ReplayTimeline.barDurations(listOf(5000, 100, 4900))
        assertEquals(listOf(1200L, 250L, 1200L), d)
        assertEquals(listOf(600L, 125L, 600L), ReplayTimeline.barDurations(listOf(5000, 100, 4900), 2f))
    }

    @Test fun testUnitsSuccessIsNeutral() =
        assertEquals("SDK works (test ad)", VerdictEngine.evaluate(true, outcome(true, attempt("AdMob", "g", 10, win = true))).title)

    @Test fun ad4gameWin() {
        val v = VerdictEngine.evaluate(false, outcome(true, attempt("AdMob", "g", 10, 3), attempt("Ad4Game", AD4GAME_CLASS, 900, win = true)))
        assertEquals(Status.SUCCESS, v.status); assertEquals("Served by Ad4Game", v.title)
    }

    @Test fun wrongClassName() = assertEquals("Ad4Game class name is wrong",
        VerdictEngine.evaluate(false, outcome(false, attempt("Ad4Game", "com.ad4game.Wrong", 10, 1))).title)

    @Test fun emptyParameter() = assertEquals("Ad4Game parameter is empty",
        VerdictEngine.evaluate(false, outcome(false, attempt("Ad4Game", AD4GAME_CLASS, 10, 3, param = ""))).title)

    @Test fun ad4gameMissingFromGroup() = assertEquals("Ad4Game isn't in this mediation group",
        VerdictEngine.evaluate(false, outcome(false, attempt("AdMob", "g", 10, 3), attempt("Meta", "f", 20, 1))).title)

    @Test fun ownAd4gameUnitFills() {
        val v = VerdictEngine.evaluate(false, outcome(true, attempt("Ad4Game", AD4GAME_CLASS, 900, win = true)), ad4gameUnit = true)
        assertEquals(Status.SUCCESS, v.status); assertEquals("Your Ad4Game Unit ID fills", v.title)
    }

    @Test fun ownAd4gameUnitNoFillIsAWarningNotAnError() {
        val v = VerdictEngine.evaluate(false, outcome(false, attempt("Ad4Game", AD4GAME_CLASS, 900, 3)), ad4gameUnit = true)
        assertEquals(Status.WARNING, v.status); assertEquals("No fill in this tester", v.title)
    }

    @Test fun ownAd4gameUnitRejected() = assertEquals("This Unit ID was rejected",
        VerdictEngine.evaluate(false, outcome(false, attempt("Ad4Game", AD4GAME_CLASS, 900, 1), kind = AdErrorKind.INVALID_REQUEST),
            ad4gameUnit = true).title)
}
