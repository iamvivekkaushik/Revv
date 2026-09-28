package com.vivekkaushik.revv.ui.hmi

import com.vivekkaushik.revv.nav.Turn
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Navigation numbers and words the way the HMI shows them: short, upper case, in kilometres. */
object NavFormat {

    /** A value and its unit: "350" "M", "1.2" "KM". Nearer distances round finer, as they count down faster. */
    fun distance(metres: Double): Pair<String, String> = when {
        metres < 300 -> "${(metres / 10).roundToInt() * 10}" to "M"
        metres < 975 -> "${(metres / 50).roundToInt() * 50}" to "M"
        metres < 9_950 -> tenths((metres / 100).roundToInt()) to "KM"
        else -> "${(metres / 1000).roundToInt()}" to "KM"
    }

    /** What's left of a trip in kilometres, without the unit: "9.4", or "126" once it's far. */
    fun kilometres(metres: Double): String =
        if (metres < 99_950) tenths((metres / 100).roundToInt()) else "${(metres / 1000).roundToInt()}"

    /** Minutes left, or hours and minutes past an hour: "18" "MIN", "1:05" "H". */
    fun duration(seconds: Double): Pair<String, String> {
        val minutes = ceil(seconds / 60).toInt().coerceAtLeast(0)
        return if (minutes < 60) "$minutes" to "MIN" else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}" to "H"
    }

    /** The caption over the next-turn distance, e.g. "NEXT TURN · RIGHT". */
    fun caption(turn: Turn, roundaboutExit: Int?): String = when (turn) {
        Turn.Right -> "NEXT TURN · RIGHT"
        Turn.Left -> "NEXT TURN · LEFT"
        Turn.SlightRight -> "NEXT TURN · SLIGHT RIGHT"
        Turn.SlightLeft -> "NEXT TURN · SLIGHT LEFT"
        Turn.SharpRight -> "NEXT TURN · SHARP RIGHT"
        Turn.SharpLeft -> "NEXT TURN · SHARP LEFT"
        Turn.UTurnRight, Turn.UTurnLeft -> "NEXT · U-TURN"
        Turn.KeepRight -> "NEXT · KEEP RIGHT"
        Turn.KeepLeft -> "NEXT · KEEP LEFT"
        Turn.RampRight -> "NEXT · RAMP ON THE RIGHT"
        Turn.RampLeft -> "NEXT · RAMP ON THE LEFT"
        Turn.ExitRight -> "NEXT · EXIT RIGHT"
        Turn.ExitLeft -> "NEXT · EXIT LEFT"
        Turn.Merge -> "NEXT · MERGE"
        Turn.Roundabout -> if (roundaboutExit != null) "NEXT · ROUNDABOUT · EXIT $roundaboutExit" else "NEXT · ROUNDABOUT"
        Turn.LeaveRoundabout -> "NEXT · LEAVE THE ROUNDABOUT"
        Turn.Ferry -> "NEXT · FERRY"
        Turn.Arrive -> "NEXT · ARRIVE"
        Turn.Straight, Turn.Depart -> "NEXT · CONTINUE"
    }

    /** The arrow for [turn], on a 64-unit viewport; left turns draw the right-hand arrow mirrored. */
    fun icon(turn: Turn): String = when (turn) {
        Turn.Right, Turn.Left -> HmiIcons.TURN_RIGHT
        Turn.SlightRight, Turn.SlightLeft, Turn.RampRight, Turn.RampLeft, Turn.ExitRight, Turn.ExitLeft -> HmiIcons.SLIGHT_RIGHT
        Turn.SharpRight, Turn.SharpLeft -> HmiIcons.SHARP_RIGHT
        Turn.UTurnRight, Turn.UTurnLeft -> HmiIcons.UTURN_RIGHT
        Turn.KeepRight, Turn.KeepLeft -> HmiIcons.KEEP_RIGHT
        Turn.Merge -> HmiIcons.MERGE
        Turn.Roundabout, Turn.LeaveRoundabout -> HmiIcons.ROUNDABOUT
        Turn.Arrive -> HmiIcons.ARRIVE
        Turn.Straight, Turn.Depart, Turn.Ferry -> HmiIcons.STRAIGHT
    }
}
