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
import com.vbridge.mapper.data.model.ClickPin
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class KeyPinView(
    context: Context,
    val pin: ClickPin,
    var isLocked: Boolean = false,
    private val tetherCanvas: TetherCanvasView?,
    private val onPinClicked: (KeyPinView) -> Unit,
    private val onPositionChanged: (KeyPinView, Int, Int) -> Unit,
    private val onPositionReleased: (KeyPinView) -> Unit
) : FrameLayout(context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val layoutParamsWm: WindowManager.LayoutParams

    private val pinCircle: FrameLayout
    private val keyText: TextView
    private val labelText: TextView

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = pin.opacity
        }
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val currentSize = dp(pin.sizeDp)
        pinCircle = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(currentSize, currentSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC121924"))
                setStroke(dp(2), if (isLocked) Color.parseColor("#4D00E5FF") else Color.parseColor("#00E5FF"))
            }
            background = bg
        }
        content.addView(pinCircle)

        keyText = TextView(context).apply {
            text = formatKeyDisplay(pin.key)
            setTextColor(if (isLocked) Color.parseColor("#B300E5FF") else Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (pin.sizeDp * 0.25f).coerceIn(10f, 18f))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        pinCircle.addView(keyText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        labelText = TextView(context).apply {
            text = pin.label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        content.addView(labelText)

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
            // In locked learning mode: TOUCH PASSES THROUGH DIRECTLY TO GAME!
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
            x = pin.x - currentSize / 2
            y = pin.y - currentSize / 2
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
            val bg = pinCircle.background as? GradientDrawable
            bg?.setStroke(dp(2), Color.parseColor("#4D00E5FF"))
            keyText.setTextColor(Color.parseColor("#B300E5FF"))
        } else {
            layoutParamsWm.flags = layoutParamsWm.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            isClickable = true
            isFocusable = true
            isEnabled = true
            val bg = pinCircle.background as? GradientDrawable
            bg?.setStroke(dp(2), Color.parseColor("#00E5FF"))
            keyText.setTextColor(Color.parseColor("#00E5FF"))
            setupTouchListener()
        }
        try {
            windowManager.updateViewLayout(this, layoutParamsWm)
        } catch (_: Exception) {}
    }

    private fun setupTouchListener() {
        setOnTouchListener { _, event ->
            val centerRadius = dp(pin.sizeDp) / 2
            val currCenterX = (layoutParamsWm.x + centerRadius).toFloat()
            val currCenterY = (layoutParamsWm.y + centerRadius).toFloat()

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParamsWm.x
                    initialY = layoutParamsWm.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false

                    tetherCanvas?.showTether(currCenterX, currCenterY, pin.x, pin.y)
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

                        val newAbsoluteX = layoutParamsWm.x + centerRadius
                        val newAbsoluteY = layoutParamsWm.y + centerRadius
                        pin.x = newAbsoluteX
                        pin.y = newAbsoluteY

                        try {
                            windowManager.updateViewLayout(this, layoutParamsWm)
                        } catch (_: Exception) {}

                        tetherCanvas?.updatePinPosition(
                            (layoutParamsWm.x + centerRadius).toFloat(),
                            (layoutParamsWm.y + centerRadius).toFloat(),
                            pin.x, pin.y
                        )

                        onPositionChanged(this, newAbsoluteX, newAbsoluteY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tetherCanvas?.hideTether()
                    if (isDragging) {
                        // Finger unheld / released -> Live Auto Save!
                        onPositionReleased(this)
                    } else {
                        onPinClicked(this)
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun updatePinData(updatedPin: ClickPin) {
        pin.key = updatedPin.key
        pin.label = updatedPin.label
        pin.x = updatedPin.x
        pin.y = updatedPin.y
        pin.sizeDp = updatedPin.sizeDp
        pin.opacity = updatedPin.opacity

        keyText.text = formatKeyDisplay(pin.key)
        labelText.text = pin.label
        alpha = pin.opacity

        val s = dp(pin.sizeDp)
        val p = pinCircle.layoutParams as LinearLayout.LayoutParams
        p.width = s
        p.height = s
        pinCircle.layoutParams = p

        layoutParamsWm.x = pin.x - s / 2
        layoutParamsWm.y = pin.y - s / 2
        try {
            windowManager.updateViewLayout(this, layoutParamsWm)
        } catch (_: Exception) {}
    }

    private fun formatKeyDisplay(key: String): String {
        return when (key.lowercase()) {
            "mouse_left" -> "LMB"
            "mouse_right" -> "RMB"
            "mouse_middle" -> "MMB"
            else -> key.uppercase()
        }
    }

    private fun dp(v: Int): Int {
        val density = context.resources.displayMetrics.density
        return (v * density).toInt()
    }
}
