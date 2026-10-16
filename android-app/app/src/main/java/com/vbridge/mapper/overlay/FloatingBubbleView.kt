package com.vbridge.mapper.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class FloatingBubbleView(
    context: Context,
    private val onBubbleClicked: () -> Unit,
    private val onUndoClicked: () -> Unit,
    private val onRedoClicked: () -> Unit,
    private val onLockToggleClicked: () -> Unit
) : FrameLayout(context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val layoutParamsWm: WindowManager.LayoutParams

    private val bubbleCircle: FrameLayout
    private val titleBadge: TextView
    private val undoBtn: FrameLayout
    private val redoBtn: FrameLayout
    private val lockBtn: FrameLayout
    private val lockText: TextView

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        // Horizontal cluster: [ ↺ Undo ]  [ 🎮 Center Bubble ]  [ ↻ Redo ]
        val cluster = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(cluster, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // Left Wing: Undo Button
        undoBtn = FrameLayout(context).apply {
            val uSize = dp(34)
            layoutParams = LinearLayout.LayoutParams(uSize, uSize).apply {
                setMargins(0, 0, dp(4), 0)
            }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EE121924"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener { onUndoClicked() }
        }
        val undoText = TextView(context).apply {
            text = "↺"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        undoBtn.addView(undoText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        cluster.addView(undoBtn)

        // Center: Master Bubble Column
        val centerCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        cluster.addView(centerCol)

        val bSize = dp(52)
        bubbleCircle = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(bSize, bSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EE0F141C"))
                setStroke(dp(2), Color.parseColor("#00E5FF"))
            }
            background = bg
            elevation = dp(6).toFloat()
        }
        centerCol.addView(bubbleCircle)

        val icon = TextView(context).apply {
            text = "🎮"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
        }
        bubbleCircle.addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        titleBadge = TextView(context).apply {
            text = "VBRIDGE"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            val badgeBg = GradientDrawable().apply {
                setColor(Color.parseColor("#CC121924"))
                cornerRadius = dp(4).toFloat()
            }
            background = badgeBg
            setPadding(dp(4), dp(1), dp(4), dp(1))
            gravity = Gravity.CENTER
        }
        centerCol.addView(titleBadge)

        // Right Wing: Redo Button
        redoBtn = FrameLayout(context).apply {
            val rSize = dp(34)
            layoutParams = LinearLayout.LayoutParams(rSize, rSize).apply {
                setMargins(dp(4), 0, 0, 0)
            }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EE121924"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener { onRedoClicked() }
        }
        val redoText = TextView(context).apply {
            text = "↻"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        redoBtn.addView(redoText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        cluster.addView(redoBtn)

        // Quick Lock / Unlock Toggle Button
        lockBtn = FrameLayout(context).apply {
            val lSize = dp(34)
            layoutParams = LinearLayout.LayoutParams(lSize, lSize).apply {
                setMargins(dp(4), 0, 0, 0)
            }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EE121924"))
                setStroke(dp(1), Color.parseColor("#00E676"))
            }
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener { onLockToggleClicked() }
        }
        lockText = TextView(context).apply {
            text = "🔒"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }
        lockBtn.addView(lockText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        cluster.addView(lockBtn)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParamsWm = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(160)
        }

        setupCenterBubbleGesture()
    }

    private fun setupCenterBubbleGesture() {
        bubbleCircle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParamsWm.x
                    initialY = layoutParamsWm.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
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

                        clampToScreenBounds()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        animateSnapToEdge()
                    } else {
                        onBubbleClicked()
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { clampToScreenBounds() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        post {
            clampToScreenBounds()
            animateSnapToEdge()
        }
    }

    fun clampToScreenBounds() {
        val disp = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(disp)
        val screenWidth = disp.x
        val screenHeight = disp.y
        val clusterW = if (width > 0) width else dp(130)
        val clusterH = if (height > 0) height else dp(70)

        val maxX = (screenWidth - clusterW - dp(4)).coerceAtLeast(dp(4))
        val maxY = (screenHeight - clusterH - dp(4)).coerceAtLeast(dp(4))

        layoutParamsWm.x = layoutParamsWm.x.coerceIn(dp(4), maxX)
        layoutParamsWm.y = layoutParamsWm.y.coerceIn(dp(4), maxY)

        try {
            windowManager.updateViewLayout(this, layoutParamsWm)
        } catch (_: Exception) {}
    }

    private fun animateSnapToEdge() {
        val disp = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(disp)
        val screenWidth = disp.x
        val totalClusterWidth = width.coerceAtLeast(dp(130))

        val currentX = layoutParamsWm.x
        val targetX = if (currentX + totalClusterWidth / 2 < screenWidth / 2) {
            dp(8)
        } else {
            screenWidth - totalClusterWidth - dp(8)
        }

        val animator = ValueAnimator.ofInt(currentX, targetX)
        animator.duration = 240
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            layoutParamsWm.x = anim.animatedValue as Int
            clampToScreenBounds()
        }
        animator.start()
    }

    fun setBadgeText(text: String) {
        titleBadge.text = text
    }

    fun setLockState(isLocked: Boolean) {
        undoBtn.visibility = if (isLocked) View.GONE else View.VISIBLE
        redoBtn.visibility = if (isLocked) View.GONE else View.VISIBLE
        lockText.text = if (isLocked) "🔒" else "🔓"
        val lockBg = lockBtn.background as? GradientDrawable
        lockBg?.setStroke(dp(1), if (isLocked) Color.parseColor("#00E676") else Color.parseColor("#00E5FF"))
        setGlowingColor(if (isLocked) "#00E676" else "#00E5FF")
        post { clampToScreenBounds() }
    }

    fun setGlowingColor(colorHex: String) {
        val bg = bubbleCircle.background as? GradientDrawable ?: return
        bg.setStroke(dp(2), Color.parseColor(colorHex))
        titleBadge.setTextColor(Color.parseColor(colorHex))
    }

    private fun dp(v: Int): Int {
        val density = context.resources.displayMetrics.density
        return (v * density).toInt()
    }
}
