package com.vivekkaushik.revv.ui.hmi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/** A camera the head unit offers: its Camera2 id and a name to show. */
data class CameraOption(val id: String, val label: String)

/** Finding the head unit's cameras and choosing the one that is the reversing camera. */
object RearCameras {

    /** Every camera, USB and other external ones first since a head unit's rear camera usually is one. */
    fun list(context: Context): List<CameraOption> {
        val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        return try {
            manager.cameraIdList
                .map { id -> id to runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) }.getOrNull() }
                .sortedBy { (_, facing) ->
                    when (facing) {
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> 0
                        CameraCharacteristics.LENS_FACING_BACK -> 1
                        else -> 2
                    }
                }
                .map { (id, facing) ->
                    val kind = when (facing) {
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        else -> "CAMERA"
                    }
                    CameraOption(id, "$kind $id")
                }
        } catch (e: CameraAccessException) {
            emptyList()
        }
    }
}

/**
 * Live video from camera [cameraId], filling the space (cropping the edges rather than stretching).
 * Opens when shown and lets the camera go when it leaves. Needs the CAMERA permission already granted.
 * [onFailed] hears if the camera could not be opened, such as when something else is using it.
 */
@Composable
fun CameraPreview(cameraId: String, rotationDegrees: Int, onFailed: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember { TextureView(context) }
    val failed by rememberUpdatedState(onFailed)
    val rotation by rememberUpdatedState(rotationDegrees)
    val controllerRef = remember { arrayOfNulls<CameraController>(1) }
    DisposableEffect(cameraId) {
        val controller = CameraController(context, view, cameraId, { rotation }) { view.post { failed() } }
        controllerRef[0] = controller
        controller.start()
        onDispose {
            controllerRef[0] = null
            controller.stop()
        }
    }
    LaunchedEffect(rotationDegrees) { controllerRef[0]?.refit() }
    AndroidView({ view }, modifier)
}

@SuppressLint("MissingPermission")
private class CameraController(
    private val context: Context,
    private val view: TextureView,
    private val cameraId: String,
    private val rotationDegrees: () -> Int,
    private val onFailed: () -> Unit,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("rear-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewWidth = 1280
    private var previewHeight = 720

    @Volatile private var stopped = false

    fun start() {
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                open()
                fit(width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = fit(width, height)

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (view.isAvailable) {
            open()
            fit(view.width, view.height)
        }
    }

    fun stop() {
        stopped = true
        view.surfaceTextureListener = null
        runCatching { session?.close() }
        runCatching { camera?.close() }
        session = null
        camera = null
        thread.quitSafely()
    }

    private fun open() {
        if (manager == null || stopped) return
        try {
            chooseSize()
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (stopped) {
                            device.close()
                            return
                        }
                        camera = device
                        startPreview(device)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        if (!stopped) onFailed()
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        if (!stopped) onFailed()
                    }
                },
                handler,
            )
        } catch (e: CameraAccessException) {
            onFailed()
        } catch (e: SecurityException) {
            onFailed()
        } catch (e: IllegalArgumentException) {
            onFailed()
        }
    }

    /** The largest picture up to 1080p whose shape is nearest 16:9. */
    private fun chooseSize() {
        val sizes = runCatching {
            manager?.getCameraCharacteristics(cameraId)
                ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)
        }.getOrNull().orEmpty()
        val best = sizes.filter { it.width <= 1920 && it.height <= 1080 }
            .minWithOrNull(compareBy({ kotlin.math.abs(it.width.toFloat() / it.height - 16f / 9f) }, { -it.width * it.height }))
            ?: sizes.firstOrNull()
        if (best != null) {
            previewWidth = best.width
            previewHeight = best.height
        }
    }

    @Suppress("DEPRECATION")
    private fun startPreview(device: CameraDevice) {
        val texture = view.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewWidth, previewHeight)
        val surface = Surface(texture)
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captured: CameraCaptureSession) {
                        if (stopped) {
                            captured.close()
                            return
                        }
                        session = captured
                        runCatching {
                            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            captured.setRepeatingRequest(request.build(), null, handler)
                        }.onFailure { onFailed() }
                    }

                    override fun onConfigureFailed(captured: CameraCaptureSession) = onFailed()
                },
                handler,
            )
        } catch (e: CameraAccessException) {
            onFailed()
        } catch (e: IllegalStateException) {
            onFailed()
        }
    }

    /** Lays the picture out again, as after the rotation changes. */
    fun refit() = fit(view.width, view.height)

    /**
     * Turns the picture by the chosen rotation and fills the view with it, cropping the overflow
     * instead of squeezing it. A texture view stretches its picture to the view, so this first
     * restores the picture's own shape, then turns it, then scales it to cover.
     */
    private fun fit(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val centreX = width / 2f
        val centreY = height / 2f
        val pictureRatio = previewWidth.toFloat() / previewHeight
        val shapedWidth = height * pictureRatio
        val degrees = rotationDegrees()
        val turnedSideways = degrees % 180 != 0
        val extentX = if (turnedSideways) height.toFloat() else shapedWidth
        val extentY = if (turnedSideways) shapedWidth else height.toFloat()
        val cover = maxOf(width / extentX, height / extentY)
        val matrix = Matrix().apply {
            setScale(shapedWidth / width, 1f, centreX, centreY)
            postRotate(degrees.toFloat(), centreX, centreY)
            postScale(cover, cover, centreX, centreY)
        }
        view.post { view.setTransform(matrix) }
    }
}
