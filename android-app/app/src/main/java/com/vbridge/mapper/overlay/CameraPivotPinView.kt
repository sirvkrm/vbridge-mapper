package com.vbridge.mapper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.vbridge.mapper.data.model.CameraPin
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class CameraPivotPinView(
    context: Context,
    val camera: CameraPin,
    var isLocked: Boolean = false,
    private val tetherCanvas: TetherCanvasView?,
    private val onCameraChanged: (CameraPin) -> Unit,
    private val onCameraReleased: (CameraPin) -> Unit
) : FrameLayout(context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val layoutParamsWm: WindowManager.LayoutParams

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // Pivot Crosshair Icon
        val pivotCircle = FrameLayout(context).apply {
            val size = dp(56)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC121924"))
                setStroke(dp(2), if (isLocked) Color.parseColor("#4DFFD600") else Color.parseColor("#FFD600"))
            }
            background = bg
        }
        container.addView(pivotCircle)

        val crossText = TextView(context).apply {
            text = "🎯\n${camera.toggleKey}"
            setTextColor(Color.parseColor("#FFD600"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        pivotCircle.addView(crossText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        if (isLocked) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        layoutParamsWm = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = camera.pivotCenterX - dp(28)
            y = camera.pivotCenterY - dp(28)
        }

        if (isLocked) {
            isClickable = false
            isFocusable = false
            isEnabled = false
        } else {
            setupTouchListener()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (isLocked) return false
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (isLocked) return false
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (isLocked) return false
        return super.onTouchEvent(event)
    }

    fun updateLockState(locked: Boolean) {
        this.isLocked = locked
        if (locked) {
            layoutParamsWm.flags = layoutParamsWm.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            setOnTouchListener(null)
            isClickable = false
            isFocusable = false
            isEnabled = false
        } else {
            layoutParamsWm.flags = layoutParamsWm.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            isClickable = true
            isFocusable = true
            isEnabled = true
            setupTouchListener()
        }
        try {
            windowManager.updateViewLayout(this, layoutParamsWm)
        } catch (_: Exception) {}
    }

    private fun setupTouchListener() {
        setOnTouchListener { _, event ->
            val currCenterX = (layoutParamsWm.x + dp(28)).toFloat()
            val currCenterY = (layoutParamsWm.y + dp(28)).toFloat()

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParamsWm.x
                    initialY = layoutParamsWm.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false

                    tetherCanvas?.showTether(currCenterX, currCenterY, camera.pivotCenterX, camera.pivotCenterY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                    }

                    if (isDragging) {
                        layoutParamsWm.x = initialX + dx
                        layoutParamsWm.y = initialY + dy

                        val newCenterX = layoutParamsWm.x + dp(28)
                        val newCenterY = layoutParamsWm.y + dp(28)
                        camera.pivotCenterX = newCenterX
                        camera.pivotCenterY = newCenterY

                        try {
                            windowManager.updateViewLayout(this, layoutParamsWm)
                        } catch (_: Exception) {}

                        tetherCanvas?.updatePinPosition(
                            newCenterX.toFloat(),
                            newCenterY.toFloat(),
                            newCenterX,
                            newCenterY
                        )

                        onCameraChanged(camera)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tetherCanvas?.hideTether()
                    if (isDragging) {
                        onCameraReleased(camera)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int): Int {
        val density = context.resources.displayMetrics.density
        return (v * density).toInt()
    }
}
