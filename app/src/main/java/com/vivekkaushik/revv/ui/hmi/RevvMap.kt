package com.vivekkaushik.revv.ui.hmi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.os.SystemClock
import android.util.DisplayMetrics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vivekkaushik.revv.R
import com.vivekkaushik.revv.nav.Fix
import com.vivekkaushik.revv.nav.LatLon
import kotlin.math.roundToInt
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** How the map camera follows the car. [Free] is after the driver pans the map away. */
enum class MapCamera { NorthUp, HeadingUp, Free }

/**
 * The live map in the design's wireframe style: OpenStreetMap vector tiles from OpenFreeMap drawn by
 * MapLibre, with the car, what's left of the route and the destination on top. Sizes in the style
 * are design pixels, as everywhere in the HMI. [visible] false stops drawing while an app covers it.
 * [headingUpShift] is how much of the map's height goes above the car in [MapCamera.HeadingUp], to
 * show more of the road ahead.
 */
@Composable
fun RevvMap(
    fix: Fix?,
    route: List<LatLon>,
    destination: LatLon?,
    camera: MapCamera,
    zoom: Double,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    visible: Boolean = true,
    headingUpTilt: Double = 0.0,
    headingUpShift: Double = 0.35,
    onCameraFreed: () -> Unit = {},
    onZoomChanged: (Double) -> Unit = {},
    onBearingChanged: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    // Inside DesignCanvas this is device pixels per design pixel.
    val pixelRatio = LocalDensity.current.density
    val controller = remember(pixelRatio, interactive) {
        MapController(context, pixelRatio, interactive, start = fix?.position ?: GURUGRAM, zoom = zoom)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, _ -> controller.syncLifecycle(lifecycle.currentState) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.destroy()
        }
    }
    SideEffect {
        controller.onCameraFreed = onCameraFreed
        controller.onZoomChanged = onZoomChanged
        controller.onBearingChanged = onBearingChanged
        controller.visible = visible
        controller.syncLifecycle(lifecycle.currentState)
        controller.show(MapScene(fix, route, destination, camera, zoom, headingUpTilt, headingUpShift))
    }
    key(controller) {
        AndroidView(factory = { controller.view }, modifier)
    }
}

/** Where Revv's map opens before it knows where the car is. */
private val GURUGRAM = LatLon(28.4595, 77.0266)

private data class MapScene(
    val fix: Fix?,
    val route: List<LatLon>,
    val destination: LatLon?,
    val camera: MapCamera,
    val zoom: Double,
    val tilt: Double,
    val shift: Double,
)

/** Owns one MapView and applies [MapScene]s to it once its style has loaded. */
private class MapController(context: Context, private val pixelRatio: Float, interactive: Boolean, start: LatLon, zoom: Double) {

    val view: MapView
    var onCameraFreed: () -> Unit = {}
    var onZoomChanged: (Double) -> Unit = {}
    var onBearingChanged: (Float) -> Unit = {}
    var visible = true

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var wanted: MapScene? = null
    private var shown: MapScene? = null
    private var state = Lifecycle.State.CREATED
    private var destroyed = false

    init {
        prepare(context)
        val options = MapLibreMapOptions.createFromAttributes(context)
            // A TextureView fades and slides with the HMI's transitions; a SurfaceView wouldn't.
            .textureMode(true)
            .pixelRatio(pixelRatio)
            .foregroundLoadColor(Hmi.MapBg.toArgb())
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(false)
            .rotateGesturesEnabled(interactive)
            .tiltGesturesEnabled(interactive)
            .camera(CameraPosition.Builder().target(LatLng(start.lat, start.lon)).zoom(zoom).build())
        view = MapView(context, options)
        view.onCreate(null)
        view.setMaximumFps(MAX_FPS)
        view.getMapAsync { map ->
            this.map = map
            map.uiSettings.setAllGesturesEnabled(interactive)
            map.setMinZoomPreference(MIN_ZOOM)
            map.setMaxZoomPreference(MAX_ZOOM)
            map.addOnCameraMoveListener { onBearingChanged(map.cameraPosition.bearing.toFloat()) }
            map.addOnCameraIdleListener { onZoomChanged(map.cameraPosition.zoom) }
            map.setStyle(Style.Builder().fromUri(STYLE)) { style ->
                if (destroyed) return@setStyle
                this.style = style
                style.addImage(DESTINATION_IMAGE, destinationMarker())
                activateLocation(context, map, style)
                // Tracking padding needs the view's size.
                view.doOnLayout { wanted?.let(::show) }
            }
        }
    }

    /** Runs the MapView through its lifecycle, held at CREATED (not drawing) while hidden. */
    fun syncLifecycle(activityState: Lifecycle.State) {
        if (destroyed) return
        val target = when {
            !visible && activityState.isAtLeast(Lifecycle.State.STARTED) -> Lifecycle.State.CREATED
            activityState.isAtLeast(Lifecycle.State.CREATED) -> activityState
            else -> Lifecycle.State.CREATED
        }
        if (target == state) return
        if (target.isAtLeast(Lifecycle.State.STARTED) && !state.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (target.isAtLeast(Lifecycle.State.RESUMED) && !state.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        if (!target.isAtLeast(Lifecycle.State.RESUMED) && state.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
        if (!target.isAtLeast(Lifecycle.State.STARTED) && state.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
        state = target
    }

    fun destroy() {
        if (destroyed) return
        visible = false
        syncLifecycle(Lifecycle.State.CREATED)
        destroyed = true
        view.onDestroy()
    }

    fun show(scene: MapScene) {
        wanted = scene
        val map = map ?: return
        val style = style?.takeIf { it.isFullyLoaded } ?: return
        if (view.height == 0) return
        val before = shown
        val location = map.locationComponent

        if (before == null || scene.route !== before.route) {
            style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.let { source ->
                if (scene.route.size >= 2) {
                    source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(scene.route.map { Point.fromLngLat(it.lon, it.lat) })))
                } else {
                    source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                }
            }
        }
        if (before == null || scene.destination != before.destination) {
            style.getSourceAs<GeoJsonSource>(DESTINATION_SOURCE)?.let { source ->
                val destination = scene.destination
                if (destination != null) {
                    source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(destination.lon, destination.lat)))
                } else {
                    source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                }
            }
        }
        val fix = scene.fix
        if ((fix == null) != (before?.fix == null)) location.isLocationComponentEnabled = fix != null
        if (fix != null && fix != before?.fix) location.forceLocationUpdate(fix.toLocation())

        val cameraChanged = before == null || scene.camera != before.camera || scene.tilt != before.tilt ||
            scene.shift != before.shift || (fix != null && before.fix == null)
        when {
            cameraChanged -> follow(map, location, scene)
            scene.zoom != before?.zoom && scene.camera != MapCamera.Free -> location.zoomWhileTracking(scene.zoom, ZOOM_MILLIS)
            scene.zoom != before?.zoom -> map.animateCamera(CameraUpdateFactory.zoomTo(scene.zoom), ZOOM_MILLIS.toInt())
        }
        shown = scene
    }

    private fun follow(map: MapLibreMap, location: LocationComponent, scene: MapScene) {
        // Padding goes on the camera first: the location component only takes it once it's tracking.
        when (scene.camera) {
            MapCamera.NorthUp -> {
                map.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
                location.setCameraMode(CameraMode.TRACKING_GPS_NORTH, CAMERA_MILLIS, scene.zoom, 0.0, 0.0, null)
            }
            MapCamera.HeadingUp -> {
                map.moveCamera(CameraUpdateFactory.paddingTo(0.0, view.height * scene.shift, 0.0, 0.0))
                location.setCameraMode(CameraMode.TRACKING_GPS, CAMERA_MILLIS, scene.zoom, null, scene.tilt, null)
            }
            MapCamera.Free -> location.cameraMode = CameraMode.NONE
        }
    }

    // Revv feeds the positions itself, so no location engine and no permission check here.
    @SuppressLint("MissingPermission")
    private fun activateLocation(context: Context, map: MapLibreMap, style: Style) {
        val options = LocationComponentOptions.builder(context)
            .gpsDrawable(R.drawable.map_puck)
            .foregroundDrawable(R.drawable.map_puck)
            .backgroundDrawable(R.drawable.map_puck_none)
            .bearingDrawable(R.drawable.map_puck_none)
            .accuracyAlpha(0f)
            .elevation(0f)
            .enableStaleState(false)
            .pulseEnabled(false)
            .trackingGesturesManagement(true)
            .build()
        map.locationComponent.apply {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .locationComponentOptions(options)
                    .useDefaultLocationEngine(false)
                    .useSpecializedLocationLayer(true)
                    .build(),
            )
            isLocationComponentEnabled = false
            renderMode = RenderMode.GPS
            setMaxAnimationFps(MAX_FPS)
            addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() = onCameraFreed()

                override fun onCameraTrackingChanged(currentMode: Int) = Unit
            })
        }
    }

    /** The design's red destination square, at [pixelRatio] so it's 20 design pixels across. */
    private fun destinationMarker(): Bitmap {
        val size = (DESTINATION_SIZE * pixelRatio).roundToInt().coerceAtLeast(4)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            // MapLibre reads an image's scale from its density.
            density = (pixelRatio * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
            eraseColor(Hmi.Red.toArgb())
        }
    }

    private fun Fix.toLocation(): Location {
        val fix = this
        return Location("revv").apply {
            latitude = fix.position.lat
            longitude = fix.position.lon
            fix.bearing?.let { bearing = it.toFloat() }
            fix.speedMps?.let { speed = it.toFloat() }
            fix.accuracyMetres?.let { accuracy = it.toFloat() }
            time = fix.timeMillis
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
    }

    companion object {
        private const val STYLE = "asset://map/wireframe.json"
        private const val ROUTE_SOURCE = "route"
        private const val DESTINATION_SOURCE = "destination"
        private const val DESTINATION_IMAGE = "destination"
        private const val DESTINATION_SIZE = 20f
        private const val MIN_ZOOM = 4.0
        private const val MAX_ZOOM = 19.0
        private const val MAX_FPS = 30
        private const val CAMERA_MILLIS = 750L
        private const val ZOOM_MILLIS = 300L

        /** Tiles already seen stay on the head unit for the drives without signal. */
        private const val TILE_CACHE_BYTES = 200L * 1024 * 1024

        private var prepared = false

        fun prepare(context: Context) {
            if (prepared) return
            MapLibre.getInstance(context.applicationContext)
            OfflineManager.getInstance(context.applicationContext)
                .setMaximumAmbientCacheSize(TILE_CACHE_BYTES, object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() = Unit

                    override fun onError(message: String) = Unit
                })
            prepared = true
        }
    }
}
