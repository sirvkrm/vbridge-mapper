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
import com.vbridge.mapper.data.model.JoystickPin
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class JoystickPinView(
    context: Context,
    val joystick: JoystickPin,
    var isLocked: Boolean = false,
    private val tetherCanvas: TetherCanvasView?,
    private val onJoystickChanged: (JoystickPin) -> Unit,
    private val onJoystickReleased: (JoystickPin) -> Unit
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
        val sizePx = joystick.radius * 2

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // Outer Ring
        val ring = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#267C4DFF"))
                setStroke(dp(2), if (isLocked) Color.parseColor("#4D7C4DFF") else Color.parseColor("#7C4DFF"), dp(4).toFloat(), dp(4).toFloat())
            }
            background = bg
        }
        container.addView(ring)

        // Inner Center Cross / Direction Labels
        val innerCircle = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val cSize = dp(64)
            layoutParams = LayoutParams(cSize, cSize, Gravity.CENTER)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC121924"))
                setStroke(dp(2), Color.parseColor("#7C4DFF"))
            }
            background = bg
        }
        ring.addView(innerCircle)

        val dirText = TextView(context).apply {
            text = "W\nA S D"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        innerCircle.addView(dirText)

        val sprintBadge = TextView(context).apply {
            text = "SHIFT: RUN"
            setTextColor(Color.parseColor("#FFD600"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            gravity = Gravity.CENTER
        }
        innerCircle.addView(sprintBadge)

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
            x = joystick.centerX - joystick.radius
            y = joystick.centerY - joystick.radius
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
            val currCenterX = (layoutParamsWm.x + joystick.radius).toFloat()
            val currCenterY = (layoutParamsWm.y + joystick.radius).toFloat()

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParamsWm.x
                    initialY = layoutParamsWm.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false

                    tetherCanvas?.showTether(currCenterX, currCenterY, joystick.centerX, joystick.centerY)
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

                        val newCenterX = layoutParamsWm.x + joystick.radius
                        val newCenterY = layoutParamsWm.y + joystick.radius
                        joystick.centerX = newCenterX
                        joystick.centerY = newCenterY

                        try {
                            windowManager.updateViewLayout(this, layoutParamsWm)
                        } catch (_: Exception) {}

                        tetherCanvas?.updatePinPosition(
                            newCenterX.toFloat(),
                            newCenterY.toFloat(),
                            newCenterX,
                            newCenterY
                        )

                        onJoystickChanged(joystick)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tetherCanvas?.hideTether()
                    if (isDragging) {
                        onJoystickReleased(joystick)
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
