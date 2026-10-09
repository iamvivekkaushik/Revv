package com.andrerinas.openheadunit.embed

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig

/**
 * The view that shows the embedded Android Auto session inside the app's own screen: the phone's
 * picture, a status line while there is none, and touch straight to the phone. Its surface goes to
 * [EmbeddedAndroidAuto] by itself; the host gives it a size and starts the session at it.
 */
@SuppressLint("ViewConstructor")
class EmbeddedAndroidAutoView(context: Context) : FrameLayout(context) {
    private val video = TextureView(context)
    private val message = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }
    private var surface: Surface? = null
    private val statusListener: (EmbeddedAndroidAuto.Status) -> Unit = ::show

    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                val created = Surface(texture)
                surface = created
                EmbeddedAndroidAuto.attachSurface(created, width, height)
                layoutVideo(width, height)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = layoutVideo(width, height)
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                surface?.let {
                    EmbeddedAndroidAuto.clearSurface(it)
                    it.release()
                }
                surface = null
                return true
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        EmbeddedAndroidAuto.addListener(statusListener)
    }

    override fun onDetachedFromWindow() {
        EmbeddedAndroidAuto.removeListener(statusListener)
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        EmbeddedAndroidAuto.sendTouch(event, width, height)
        return true
    }

    private fun show(status: EmbeddedAndroidAuto.Status) {
        message.visibility = if (status.videoActive) View.GONE else View.VISIBLE
        message.text = status.detail
        if (width > 0 && height > 0) layoutVideo(width, height)
    }

    /**
     * The phone draws the view's pixels in the middle of a bigger frame, with a margin around
     * them it leaves black; the frame is scaled up about its centre so that middle fills the view.
     */
    private fun layoutVideo(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val scaleX = HeadUnitScreenConfig.getScaleX().takeIf { it > 0f } ?: 1f
        val scaleY = HeadUnitScreenConfig.getScaleY().takeIf { it > 0f } ?: 1f
        video.setTransform(Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
    }
}
