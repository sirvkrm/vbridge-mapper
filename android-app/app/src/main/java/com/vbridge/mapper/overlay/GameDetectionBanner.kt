package com.vbridge.mapper.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.vbridge.mapper.data.model.Preset

class GameDetectionBanner(
    private val context: Context,
    private val gameName: String,
    private val preset: Preset,
    private val onApplyAndSync: (Preset) -> Unit,
    private val onSwitchPreset: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bannerView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    @SuppressLint("SetTextI18n")
    fun show() {
        val root = FrameLayout(context)

        // Banner Card
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = dp(16)
            val padV = dp(12)
            setPadding(padH, padV, padH, padV)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#EE0F141C"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(2), Color.parseColor("#00E5FF"))
            }
            background = bg
            elevation = dp(12).toFloat()
        }

        val cardParams = FrameLayout.LayoutParams(
            dp(460).coerceAtMost(context.resources.displayMetrics.widthPixels - dp(32)),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(24)
        }
        root.addView(card, cardParams)

        // Game Icon
        val iconText = TextView(context).apply {
            text = "🎮"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setPadding(0, 0, dp(12), 0)
        }
        card.addView(iconText)

        // Info Column
        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        card.addView(infoCol)

        val titleView = TextView(context).apply {
            text = "Game Detected: $gameName"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        infoCol.addView(titleView)

        val subView = TextView(context).apply {
            text = "Active Preset: ${preset.info.name}"
            setTextColor(Color.parseColor("#FFD600"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        infoCol.addView(subView)

        // Action Buttons Row
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(btnRow)

        val applyBtn = Button(context).apply {
            text = "Sync Host"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#00E5FF"))
                cornerRadius = dp(6).toFloat()
            }
            background = btnBg
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                setMargins(dp(6), 0, 0, 0)
            }
            setOnClickListener {
                dismiss()
                onApplyAndSync(preset)
            }
        }
        btnRow.addView(applyBtn)

        val switchBtn = Button(context).apply {
            text = "Switch"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            background = btnBg
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                setMargins(dp(4), 0, 0, 0)
            }
            setOnClickListener {
                dismiss()
                onSwitchPreset()
            }
        }
        btnRow.addView(switchBtn)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 20
        }

        bannerView = root
        windowManager.addView(root, params)

        // Slide Down Entry Animation
        card.translationY = -dp(120).toFloat()
        card.alpha = 0f
        card.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(350)
            .start()

        // Auto dismiss after 6 seconds
        dismissRunnable = Runnable { dismiss() }
        dismissRunnable?.let { handler.postDelayed(it, 6000) }
    }

    fun dismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null

        val viewToDismiss = bannerView ?: return
        val card = (viewToDismiss as? ViewGroup)?.getChildAt(0)
        if (card != null) {
            card.animate()
                .translationY(-dp(120).toFloat())
                .alpha(0f)
                .setDuration(250)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        try {
                            windowManager.removeView(viewToDismiss)
                        } catch (_: Exception) {}
                        bannerView = null
                    }
                })
                .start()
        } else {
            try {
                windowManager.removeView(viewToDismiss)
            } catch (_: Exception) {}
            bannerView = null
        }
    }

    private fun dp(v: Int): Int {
        val density = context.resources.displayMetrics.density
        return (v * density).toInt()
    }
}
