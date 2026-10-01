package com.vivekkaushik.revv.vehicle

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * A car's gears, learnt from driving it. In gear with the clutch up the engine turns a fixed number
 * of rpm per km/h, so the time spent steady piles up around one ratio per gear. Moving off from a
 * standstill shows which of them is first. [learnt] restores gears saved from earlier drives.
 */
class GearLearner(learnt: List<Float> = emptyList()) {

    private class Cluster(var logRatio: Double, var seconds: Double, var launches: Int)

    private val clusters = learnt.mapIndexed { index, ratio ->
        Cluster(ln(ratio.toDouble()), ESTABLISHED_SECONDS, launches = if (index == 0) 1 else 0)
    }.toMutableList()

    /**
     * Records [seconds] held steadily at [ratio] rpm per km/h. [launch] marks the car moving off
     * from a standstill, which it does in first.
     */
    fun add(ratio: Float, seconds: Double, launch: Boolean = false) {
        if (ratio <= 0f) return
        val log = ln(ratio.toDouble())
        val cluster = nearest(log) ?: Cluster(log, 0.0, 0).also { clusters += it }
        val total = cluster.seconds + seconds
        if (total > 0) cluster.logRatio = (cluster.logRatio * cluster.seconds + log * seconds) / total
        cluster.seconds = total
        if (launch) cluster.launches++
        // Two clusters that have drifted into one gear become one.
        clusters.firstOrNull { it !== cluster && abs(it.logRatio - cluster.logRatio) <= SAME_GEAR }?.let { other ->
            val both = cluster.seconds + other.seconds
            if (both > 0) cluster.logRatio = (cluster.logRatio * cluster.seconds + other.logRatio * other.seconds) / both
            cluster.seconds = both
            cluster.launches += other.launches
            clusters.remove(other)
        }
        if (clusters.size > MAX_CLUSTERS) clusters.remove(clusters.minBy { it.seconds })
    }

    /** The gears known so far in rpm per km/h, first gear first; empty until one has been held long enough. */
    fun gears(): List<Float> {
        val known = clusters.filter { it.seconds >= ESTABLISHED_SECONDS || it.launches > 0 }
        if (known.isEmpty()) return emptyList()
        // First is the gear the car most often moves off in; anything geared lower is a slipping clutch.
        val first = known.filter { it.launches > 0 }.maxWithOrNull(compareBy({ it.launches }, { it.logRatio }))
            ?: known.maxBy { it.logRatio }
        return known.filter { it.logRatio <= first.logRatio }
            .sortedByDescending { it.logRatio }
            .map { exp(it.logRatio).toFloat() }
    }

    private fun nearest(log: Double): Cluster? =
        clusters.minByOrNull { abs(it.logRatio - log) }?.takeIf { abs(it.logRatio - log) <= SAME_GEAR }

    private companion object {
        /** Ratios this close (7%) are one gear; neighbouring gears are 15% or more apart. */
        const val SAME_GEAR = 0.07

        /** Held this long in all, a ratio counts as a gear rather than a slipping clutch. */
        const val ESTABLISHED_SECONDS = 6.0

        const val MAX_CLUSTERS = 12
    }
}
