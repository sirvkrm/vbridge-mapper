package com.vbridge.mapper.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.view.WindowManager
import kotlin.math.roundToInt

class CoordinateTransformer(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * Gets current screen resolution in pixels.
     */
    fun getDisplaySize(): Point {
        val size = Point()
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return size
    }

    val isLandscape: Boolean
        get() = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Clamps coordinates within current screen boundaries.
     */
    fun clamp(point: Point, viewWidth: Int = 0, viewHeight: Int = 0): Point {
        val disp = getDisplaySize()
        val maxX = disp.x - viewWidth
        val maxY = disp.y - viewHeight
        val x = point.x.coerceIn(0, maxX.coerceAtLeast(0))
        val y = point.y.coerceIn(0, maxY.coerceAtLeast(0))
        return Point(x, y)
    }

    /**
     * Transforms landscape game coordinates to native digitizer coordinates
     * if orientation mapping is needed (e.g. K20 Pro 90-degree rotated panel).
     */
    fun gameToDigitizer(gameX: Int, gameY: Int, screenW: Int = 2340, screenH: Int = 1080): Point {
        // For ROTATION_90 landscape on Redmi K20 Pro:
        // WindowManager transform: x_win = y_raw, y_win = screenH - x_raw
        // Inverting:
        // digX = ((screenH - gameY) / screenH) * 1080
        // digY = (gameX / screenW) * 2340
        val digX = (((screenH - gameY).toFloat() / screenH.toFloat()) * 1080f).roundToInt().coerceIn(0, 1079)
        val digY = ((gameX.toFloat() / screenW.toFloat()) * 2340f).roundToInt().coerceIn(0, 2339)
        return Point(digX, digY)
    }

    fun dpToPx(dp: Float): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).roundToInt()
    }

    fun pxToDp(px: Int): Float {
        val density = context.resources.displayMetrics.density
        return px / density
    }
}
