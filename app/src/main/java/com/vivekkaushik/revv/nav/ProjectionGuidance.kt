package com.vivekkaushik.revv.nav

/**
 * A projection's route as the phone guides it, for Revv's map to show beside its own: CarPlay's
 * or Android Auto's next turn and what is left. [source] captions it ("CARPLAY", "ANDROID AUTO").
 */
data class ProjectionGuidance(
    val source: String,
    /** The destination as the phone shows it; empty when it did not say (Android Auto never does). */
    val destination: String,
    /** The next manoeuvre as one of Revv's turns; null when there is none or it has no arrow. */
    val turn: Turn?,
    val roundaboutExit: Int?,
    val maneuverMeters: Int,
    /** The road the next manoeuvre turns onto, else the one the car is on. */
    val road: String,
    /** What is left of the route, when the phone said. */
    val routeMeters: Long?,
    val remainingSeconds: Long?,
    val arrivalEpochSeconds: Long?,
)
