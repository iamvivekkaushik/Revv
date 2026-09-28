package com.vivekkaushik.revv.ui.hmi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.revv.vehicle.DemoData
import kotlin.math.min

@Composable
fun VehicleScreen(live: LiveTelemetry) {
    val figures = live.figures
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            TyreCard(figures.tyres, Modifier.weight(1f).fillMaxHeight())
            ObdCard(live, Modifier.weight(1f).fillMaxHeight())
            VehicleCard(figures, Modifier.weight(1f).fillMaxHeight())
        }
        Row(
            Modifier.fillMaxWidth().height(200.dp).border(1.dp, Hmi.Line).padding(horizontal = 36.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TripFigure("TRIP A", figures.tripKm, " KM", Modifier.weight(1f))
            TripFigure("AVERAGE", figures.averageKmpl, " KM/L", Modifier.weight(1f), Hmi.Cyan)
            TripFigure("DRIVE TIME", figures.driveTime, " H", Modifier.weight(1f))
            TripFigure("FUEL", figures.fuelLitres, " L", Modifier.weight(1f))
        }
    }
}

/** Pressures from the demo; OBD-II has no standard tyre data, so a real car shows dashes. */
@Composable
private fun TyreCard(tyres: List<DemoData.Tyre>?, modifier: Modifier) {
    val cells = tyres ?: TYRE_POSITIONS.map { DemoData.Tyre(it, psi = -1, temperature = "NO SENSOR") }
    Column(
        modifier.border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Caption("TYRE PRESSURE · PSI")
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            cells.chunked(2).forEach { axle ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    axle.forEach { tyre ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight().border(1.dp, Hmi.LineSoft).padding(20.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Caption(tyre.position, size = 14.sp)
                            HText(
                                if (tyre.psi < 0) VehicleFigures.DASH else tyre.psi.toString(),
                                size = 48.sp,
                                color = if (tyre.psi < 0) Hmi.Faint else Hmi.Cyan,
                                family = Hmi.Display,
                            )
                            HText(tyre.temperature, size = 15.sp, color = Hmi.Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ObdCard(live: LiveTelemetry, modifier: Modifier) {
    Column(modifier.border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 28.dp)) {
        val caption = when (live.source) {
            DataSource.Obd -> "LIVE OBD-II"
            DataSource.Demo -> "OBD-II · SIMULATED"
            DataSource.None -> "OBD-II · NOT CONNECTED"
        }
        Caption(caption, Modifier.padding(bottom = 8.dp))
        ObdRow("ENGINE LOAD") { live.engineFigure(live.engineLoad, "%") }
        ObdRow("THROTTLE") { live.engineFigure(live.throttle, "%") }
        ObdRow("ENGINE SPEED") { live.engineFigure(live.engineRpm, "RPM") }
        ObdRow("COOLANT") { withUnit(live.figures.coolant, "°C") }
        ObdRow("INTAKE AIR") { withUnit(live.figures.intakeAir, "°C") }
        ObdRow("BATTERY", last = true) { withUnit(live.figures.battery, "V") }
    }
}

private fun LiveTelemetry.engineFigure(value: Int, unit: String): String =
    if (source == DataSource.None) VehicleFigures.DASH else "$value $unit"

/** [value] is read here, so a changing reading only recomposes its own row. */
@Composable
private fun ObdRow(name: String, last: Boolean = false, value: () -> String) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (last) Modifier else Modifier.edgeLine(Hmi.LineSoft))
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HText(name, Modifier.alignByBaseline(), size = 17.sp, color = Hmi.Muted)
        HText(value(), Modifier.alignByBaseline(), size = 28.sp, weight = FontWeight.Medium)
    }
}

@Composable
private fun VehicleCard(figures: VehicleFigures, modifier: Modifier) {
    val healthColor = when (figures.health) {
        Health.Normal -> Hmi.Cyan
        Health.Alert -> Hmi.Red
        Health.Unknown -> Hmi.Faint
    }
    Column(
        modifier.border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Caption("VEHICLE")
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            CarProfile(Modifier.fillMaxWidth().height(160.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(10.dp).background(healthColor))
            HText(figures.healthText, size = 18.sp, color = if (figures.health == Health.Alert) Hmi.Red else Hmi.Text)
        }
        if (figures.troubleCodes.isNotEmpty()) {
            HText(figures.troubleCodes.joinToString("  ·  "), size = 16.sp, color = Hmi.Red, spacing = 1.sp, maxLines = 1)
        }
        HText("${DemoData.MODEL} · ${DemoData.GEARBOX} · ${DemoData.ENGINE}", size = 15.sp, color = Hmi.Muted)
    }
}

private val TYRE_POSITIONS = listOf("FRONT L", "FRONT R", "REAR L", "REAR R")

/**
 * Blueprint side view of a hatchback, standing in for the design's rotating 3D model (it loads
 * `assets/swift.glb`, which isn't part of the design files).
 */
@Composable
private fun CarProfile(modifier: Modifier) {
    val body = remember { PathParser().parsePathString(CAR_BODY).toPath() }
    val details = remember { PathParser().parsePathString(CAR_DETAILS).toPath() }
    val wheels = remember { PathParser().parsePathString(CAR_WHEELS).toPath() }
    Canvas(modifier) {
        val factor = min(size.width / CAR_WIDTH, size.height / CAR_HEIGHT)
        translate((size.width - CAR_WIDTH * factor) / 2f, (size.height - CAR_HEIGHT * factor) / 2f) {
            scale(factor, factor, pivot = Offset.Zero) {
                drawLine(Hmi.Line, Offset(0f, 118f), Offset(CAR_WIDTH, 118f), strokeWidth = 1f)
                drawPath(body, Hmi.Cyan.copy(alpha = 0.06f))
                drawPath(body, Hmi.Cyan, style = Stroke(1.6f))
                drawPath(details, Hmi.Cyan.copy(alpha = 0.55f), style = Stroke(1.2f))
                drawPath(wheels, Hmi.Cyan, style = Stroke(1.6f))
            }
        }
    }
}

private const val CAR_WIDTH = 320f
private const val CAR_HEIGHT = 124f
private const val CAR_BODY =
    "M24 100V76Q24 64 32 58L40 34Q44 26 56 25L168 22Q182 22 194 30L236 56L284 64Q298 67 298 80V100H268" +
        "A22 22 0 0 0 224 100H100A22 22 0 0 0 56 100Z"
private const val CAR_DETAILS =
    "M56 32L164 29Q176 29 186 36L216 56H60Q52 56 52 48ZM132 30V100M200 58V100M28 62H36M280 68L294 72M112 72H124M206 72H218"
private const val CAR_WHEELS =
    "M60 100a18 18 0 1 0 36 0a18 18 0 1 0 -36 0zM71 100a7 7 0 1 0 14 0a7 7 0 1 0 -14 0z" +
        "M228 100a18 18 0 1 0 36 0a18 18 0 1 0 -36 0zM239 100a7 7 0 1 0 14 0a7 7 0 1 0 -14 0z"

@Composable
private fun TripFigure(label: String, value: String, unit: String, modifier: Modifier, color: Color = Hmi.Text) {
    Column(modifier) {
        Caption(label, size = 14.sp)
        Reading(value, unit, 44.sp, Modifier.padding(top = 10.dp), color = color)
    }
}
