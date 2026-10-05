package com.vivekkaushik.revv.ui.hmi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import com.vivekkaushik.revv.obd.ObdLink
import com.vivekkaushik.revv.obd.ObdReadings
import com.vivekkaushik.revv.obd.ObdStatus
import com.vivekkaushik.revv.vehicle.DriveSimulator
import com.vivekkaushik.revv.vehicle.Telemetry
import com.vivekkaushik.revv.vehicle.VehicleProfile
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cluster data as snapshot state, updated every frame. Whole-number fields only invalidate when
 * they change, so text recomposes a few times a second; the fractional fields are meant to be read
 * while drawing, which redraws without recomposing.
 */
@Stable
class LiveTelemetry {
    var phase by mutableIntStateOf(DriveSimulator.PHASE_DARK)
        private set

    /** Seconds into the ignition sequence, held once the intro animations are over. */
    var introSeconds by mutableFloatStateOf(0f)
        private set
    var speedFraction by mutableFloatStateOf(0f)
        private set
    var rpmFraction by mutableFloatStateOf(0f)
        private set
    var speedKmh by mutableIntStateOf(0)
        private set
    var rpmTenths by mutableIntStateOf(0)
        private set

    /** Revs as the rev counter shows them in full, to the nearest 10 rpm. */
    var rpm by mutableIntStateOf(0)
        private set
    var gearIndex by mutableIntStateOf(DriveSimulator.GEAR_NEUTRAL)
        private set

    /** Fuel economy in tenths of a km/L, or -1 while stationary. */
    var kmPerLitreTenths by mutableIntStateOf(-1)
        private set
    var steer by mutableFloatStateOf(0f)
        private set
    var engineLoad by mutableIntStateOf(0)
        private set
    var throttle by mutableIntStateOf(0)
        private set
    var engineRpm by mutableIntStateOf(0)
        private set

    var source by mutableStateOf(DataSource.None)
        private set
    var figures by mutableStateOf(VehicleFigures.Empty)
        private set

    val isReady: Boolean get() = phase >= DriveSimulator.PHASE_READY

    /** Back to a dark cluster; the next frame picks up the restarted ignition. */
    fun restart() {
        phase = DriveSimulator.PHASE_DARK
    }

    /** [decorate] false holds the purely decorative motion: the camera's steering guides. */
    fun update(telemetry: Telemetry, decorate: Boolean) {
        phase = telemetry.phase
        introSeconds = min(telemetry.bootSeconds, INTRO_SECONDS)
        speedFraction = telemetry.speedFraction
        rpmFraction = telemetry.rpmFraction
        speedKmh = telemetry.speedKmh
        rpmTenths = (telemetry.rpmFraction * 80).roundToInt()
        rpm = (telemetry.rpmFraction * DriveSimulator.MAX_RPM / 10).roundToInt() * 10
        gearIndex = telemetry.gearIndex
        kmPerLitreTenths = telemetry.kmPerLitre?.let { (it * 10).roundToInt() } ?: -1
        if (decorate) steer = telemetry.steer
        engineLoad = telemetry.engineLoad
        throttle = telemetry.throttle
        engineRpm = telemetry.engineRpm
    }

    fun present(source: DataSource, figures: VehicleFigures) {
        this.source = source
        this.figures = figures
    }

    private companion object {
        /** The corner brackets finish flying in by 1.06 s. */
        const val INTRO_SECONDS = 1.1f
    }
}

/** Formats tenths as "d.d" without going through String.format every frame. */
fun tenths(value: Int): String = "${value / 10}.${value % 10}"

/**
 * Drives the cluster once per frame: the ignition sequence, then live OBD-II data if an adapter is
 * answering, the demo drive if none is set up, or nothing at all.
 */
@Stable
class Ignition(private val simulator: DriveSimulator, private val profile: VehicleProfile) {
    val live = LiveTelemetry()

    private var shownSpeed = 0f
    private var shownRpm = 0f
    private var presentedSource: DataSource? = null
    private var presentedReadings: ObdReadings? = null
    private var presentedStatus: ObdStatus? = null

    fun start(skipSequence: Boolean) {
        simulator.ignite(skipSequence)
        // Also wakes the frame loop if it was paused on a still screen.
        live.restart()
    }

    /**
     * Whether another frame would change anything. With reduced motion on, no live data and no
     * demo, a parked car is completely still, so the HMI stops redrawing altogether.
     */
    internal fun isMoving(
        reducedMotion: Boolean,
        demoDrive: Boolean,
        reversing: Boolean,
        adapterSetUp: Boolean,
        obdLive: Boolean,
    ): Boolean {
        val demo = demoDrive && !adapterSetUp
        return !reducedMotion ||
            obdLive ||
            demo ||
            !live.isReady ||
            live.speedKmh > 0 ||
            (demo && reversing) != (live.gearIndex == DriveSimulator.GEAR_REVERSE)
    }

    internal fun step(
        seconds: Float,
        reducedMotion: Boolean,
        demoDrive: Boolean,
        reversing: Boolean,
        adapterSetUp: Boolean,
        readings: ObdReadings?,
        status: ObdStatus,
    ) {
        val source = when {
            readings != null -> if (status.estimated) DataSource.Gps else DataSource.Obd
            // With an adapter set up, a dropped link must never be papered over with fake driving.
            adapterSetUp -> DataSource.None
            demoDrive -> DataSource.Demo
            else -> DataSource.None
        }
        val demo = source == DataSource.Demo
        val simulated = simulator.step(seconds, demoDrive = demo, reversing = reversing && demo)
        val frame = if (readings != null && simulated.phase == DriveSimulator.PHASE_READY) {
            withReadings(simulated, readings, seconds)
        } else {
            shownSpeed = 0f
            shownRpm = 0f
            simulated
        }
        live.update(frame, decorate = !reducedMotion)

        if (source != presentedSource || readings !== presentedReadings || status !== presentedStatus) {
            presentedSource = source
            presentedReadings = readings
            presentedStatus = status
            val figures = when {
                readings != null -> VehicleFigures.live(readings, status, profile)
                demo -> VehicleFigures.Demo
                else -> VehicleFigures.Empty
            }
            live.present(source, figures)
        }
    }

    /** Swaps the simulated car for the real one, easing toward each reading between polls. */
    private fun withReadings(simulated: Telemetry, readings: ObdReadings, seconds: Float): Telemetry {
        val easing = min(1f, seconds * READING_EASING)
        shownSpeed += ((readings.speedKmh ?: 0) - shownSpeed) * easing
        shownRpm += ((readings.rpm ?: 0) - shownRpm) * easing
        return simulated.copy(
            speedFraction = shownSpeed / DriveSimulator.MAX_SPEED,
            rpmFraction = shownRpm / DriveSimulator.MAX_RPM,
            gearIndex = when (val gear = readings.gear) {
                // Neither known nor neutral: nothing lit rather than a wrong N.
                null -> DriveSimulator.GEAR_NONE
                0 -> DriveSimulator.GEAR_NEUTRAL
                else -> gear
            },
            kmPerLitre = readings.kmPerLitre,
            engineLoad = readings.engineLoad ?: 0,
            throttle = readings.throttle ?: 0,
            engineRpm = readings.rpm ?: 0,
        )
    }

    private companion object {
        /** How quickly the gauges catch up with a new reading, per second. */
        const val READING_EASING = 6f
    }
}

@Composable
fun rememberIgnition(
    reducedMotion: Boolean,
    demoDrive: Boolean,
    reversing: Boolean,
    adapterSetUp: Boolean,
    obdStatus: ObdStatus,
    obdReadings: StateFlow<ObdReadings?>,
): Ignition {
    val ignition = remember {
        Ignition(DriveSimulator(), VehicleProfile.SWIFT_VXI_2015).also { it.start(skipSequence = reducedMotion) }
    }
    val currentReducedMotion by rememberUpdatedState(reducedMotion)
    val currentDemoDrive by rememberUpdatedState(demoDrive)
    val currentReversing by rememberUpdatedState(reversing)
    val currentAdapterSetUp by rememberUpdatedState(adapterSetUp)
    val currentStatus by rememberUpdatedState(obdStatus)
    LaunchedEffect(ignition, obdReadings) {
        fun moving() = ignition.isMoving(
            currentReducedMotion,
            currentDemoDrive,
            currentReversing,
            currentAdapterSetUp,
            obdLive = currentStatus.link == ObdLink.Live,
        )
        var lastFrame = 0L
        while (true) {
            if (!moving()) {
                snapshotFlow { moving() }.first { it }
                lastFrame = 0L
            }
            withFrameNanos { now ->
                val seconds = if (lastFrame == 0L) 0.016f else (now - lastFrame) / 1_000_000_000f
                lastFrame = now
                ignition.step(
                    seconds,
                    currentReducedMotion,
                    currentDemoDrive,
                    currentReversing,
                    currentAdapterSetUp,
                    obdReadings.value,
                    currentStatus,
                )
            }
        }
    }
    return ignition
}
