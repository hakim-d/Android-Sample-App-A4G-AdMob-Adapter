package com.hakim_ma.a4g.ui.flow

/**
 * Latency scaling for the waterfall replay (spec 4.8):
 *   d_i = clamp(latency_i * s, 250 ms, 1200 ms), s = min(1, 2500 ms / sum(latency))
 * Short waterfalls replay in real time, long ones compress to ~2.5 s of bar time.
 * [speed] 1.0 = normal, 2.0 = double speed.
 */
object ReplayTimeline {
    fun barDurations(latencies: List<Long>, speed: Float = 1f): List<Long> {
        val total = latencies.sum().coerceAtLeast(1)
        val s = minOf(1.0, 2500.0 / total)
        return latencies.map { ((it * s).coerceIn(250.0, 1200.0) / speed).toLong() }
    }

    /** Bar fill ratio: latency relative to the slowest source in this response. */
    fun barRatios(latencies: List<Long>): List<Float> {
        val max = latencies.maxOrNull()?.coerceAtLeast(1) ?: 1
        return latencies.map { (it.toFloat() / max).coerceIn(0.04f, 1f) }
    }
}
