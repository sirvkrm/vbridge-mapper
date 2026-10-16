package com.vbridge.mapper.overlay

import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

class TetherCanvasView(context: Context) : View(context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    var isAttachedToWm = false

    // State
    private var isTetherActive = false
    private var pinX = 0f
    private var pinY = 0f
    private var badgeCurrX = 0f
    private var badgeCurrY = 0f
    private var targetCoordX = 0
    private var targetCoordY = 0

    // Paints
    private val threadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9900E5FF")
        strokeWidth = 3f * context.resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val threadGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300E5FF")
        strokeWidth = 7f * context.resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EE0F141C")
        style = Paint.Style.FILL
    }

    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 1.5f * context.resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        textSize = 12f * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val badgeRect = RectF()
    private val density = context.resources.displayMetrics.density
    private val badgeWidth = 100f * density
    private val badgeHeight = 32f * density
    private val edgePadding = 16f * density

    fun showTether(startX: Float, startY: Float, coordX: Int, coordY: Int) {
        pinX = startX
        pinY = startY
        targetCoordX = coordX
        targetCoordY = coordY

        if (!isTetherActive) {
            badgeCurrX = pinX
            badgeCurrY = pinY - 70f * density
            isTetherActive = true
        }

        invalidate()
    }

    fun updatePinPosition(currPinX: Float, currPinY: Float, coordX: Int, coordY: Int) {
        pinX = currPinX
        pinY = currPinY
        targetCoordX = coordX
        targetCoordY = coordY
        invalidate()
    }

    fun hideTether() {
        isTetherActive = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isTetherActive) return

        val screenW = width.toFloat().coerceAtLeast(1f)
        val screenH = height.toFloat().coerceAtLeast(1f)

        // Ideal target for coordinate badge: floating 65dp above key center, slightly to the right
        var targetBadgeX = pinX + 24f * density
        var targetBadgeY = pinY - 65f * density

        // Screen Edge Repulsion & Clamping: keeps the badge strictly inside the screen boundaries
        targetBadgeX = max(edgePadding + badgeWidth / 2f, min(screenW - edgePadding - badgeWidth / 2f, targetBadgeX))

        if (targetBadgeY - badgeHeight / 2f < edgePadding) {
            // Near top edge of screen: magnetically push below the key so it stays visible!
            targetBadgeY = pinY + 65f * density
        }
        targetBadgeY = max(edgePadding + badgeHeight / 2f, min(screenH - edgePadding - badgeHeight / 2f, targetBadgeY))

        // Fluid Elastic Motion: Smooth spring lag towards target
        badgeCurrX += (targetBadgeX - badgeCurrX) * 0.28f
        badgeCurrY += (targetBadgeY - badgeCurrY) * 0.28f

        // Draw elastic curved thread from pin center to coordinate badge
        val ctrlX = (pinX + badgeCurrX) / 2f
        val ctrlY = (pinY + badgeCurrY) / 2f + 15f * density // slight sag curve for realistic rope tension

        val path = Path().apply {
            moveTo(pinX, pinY)
            quadTo(ctrlX, ctrlY, badgeCurrX, badgeCurrY)
        }

        canvas.drawPath(path, threadGlowPaint)
        canvas.drawPath(path, threadPaint)

        // Draw Badge Background Box
        badgeRect.set(
            badgeCurrX - badgeWidth / 2f,
            badgeCurrY - badgeHeight / 2f,
            badgeCurrX + badgeWidth / 2f,
            badgeCurrY + badgeHeight / 2f
        )

        canvas.drawRoundRect(badgeRect, 8f * density, 8f * density, badgeBgPaint)
        canvas.drawRoundRect(badgeRect, 8f * density, 8f * density, badgeBorderPaint)

        // Draw Coordinate Text
        val coordText = "X:$targetCoordX Y:$targetCoordY"
        val textBaseline = badgeCurrY + (textPaint.textSize / 3f)
        canvas.drawText(coordText, badgeCurrX, textBaseline, textPaint)

        // Animate next frame if still adjusting
        if (Math.abs(targetBadgeX - badgeCurrX) > 0.5f || Math.abs(targetBadgeY - badgeCurrY) > 0.5f) {
            postInvalidateOnAnimation()
        }
    }

    fun attachToWindow() {
        if (isAttachedToWm) return
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(this, params)
        isAttachedToWm = true
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean = false
    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false

    fun detachFromWindow() {
        if (!isAttachedToWm) return
        try {
            windowManager.removeView(this)
        } catch (_: Exception) {}
        isAttachedToWm = false
    }
}
