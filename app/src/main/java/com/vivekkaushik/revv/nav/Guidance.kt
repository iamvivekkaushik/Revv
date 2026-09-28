package com.vivekkaushik.revv.nav

/**
 * Turn-by-turn state at one moment of a drive: the next thing the driver has to do, how far off it
 * is, and what's left of the trip. [fix] is where the car sits on the route.
 */
data class Guidance(
    val next: Maneuver,
    val metresToNext: Double,
    val remainingMetres: Double,
    val remainingSeconds: Double,
    val fix: PathFix,
) {
    val arrived: Boolean get() = remainingMetres <= ARRIVAL_METRES

    companion object {
        /** Close enough to the end to call it arrived; GPS is rarely better than this in a city. */
        const val ARRIVAL_METRES = 30.0

        fun at(route: Route, fix: PathFix): Guidance {
            val maneuvers = route.maneuvers.ifEmpty { listOf(Maneuver(Turn.Arrive, null, route.metres, 0.0)) }
            val along = fix.along
            // The stretch the car is on starts at the last maneuver behind it.
            val current = maneuvers.indexOfLast { it.along <= along }.coerceAtLeast(0)
            val ahead = maneuvers.subList(current + 1, maneuvers.size)
            // "Continue" and similar are nothing to act on, so the next real turn is what counts.
            val next = ahead.firstOrNull { !it.turn.isPassive } ?: maneuvers.last()

            val stretchStart = maneuvers[current].along
            val stretchEnd = maneuvers.getOrNull(current + 1)?.along ?: route.metres
            val left = if (stretchEnd > stretchStart) ((stretchEnd - along) / (stretchEnd - stretchStart)).coerceIn(0.0, 1.0) else 0.0
            val seconds = left * maneuvers[current].seconds + ahead.sumOf { it.seconds }

            return Guidance(
                next = next,
                metresToNext = (next.along - along).coerceAtLeast(0.0),
                remainingMetres = (route.metres - along).coerceAtLeast(0.0),
                remainingSeconds = seconds,
                fix = fix,
            )
        }
    }
}
