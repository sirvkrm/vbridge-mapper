package com.vbridge.mapper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.*
import android.widget.*
import com.vbridge.mapper.data.model.Preset

class OverlayHubMenu(
    private val context: Context,
    private val activePreset: Preset,
    private val isLocked: Boolean,
    private val onToggleLockClicked: () -> Unit,
    private val onAddKeyClicked: () -> Unit,
    private val onToggleJoystickClicked: () -> Unit,
    private val onToggleCameraClicked: () -> Unit,
    private val onSwitchSlotClicked: () -> Unit,
    private val onSwitchPresetClicked: () -> Unit = {},
    private val onTogglePinsVisibilityClicked: () -> Unit,
    private val onFastSetupClicked: () -> Unit,
    private val onSyncHostClicked: () -> Unit,
    private val onRestartInjectorClicked: () -> Unit = {},
    private val onDismiss: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var menuView: View? = null

    @SuppressLint("SetTextI18n")
    fun show() {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { dismiss() }
        }

        val disp = context.resources.displayMetrics
        val isLandscape = disp.widthPixels > disp.heightPixels
        val cardWidth = if (isLandscape) dp(460).coerceAtMost(disp.widthPixels - dp(32))
                        else dp(320).coerceAtMost(disp.widthPixels - dp(24))
        val maxCardH = if (isLandscape) (disp.heightPixels - dp(16)).coerceAtLeast(dp(260))
                       else ViewGroup.LayoutParams.WRAP_CONTENT

        val scrollWrapper = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(cardWidth, maxCardH).apply {
                gravity = Gravity.CENTER
            }
        }
        root.addView(scrollWrapper)

        // Frosted Glass Card
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#EE121A26"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#4DFFFFFF"))
            }
            background = bg
            setOnClickListener { /* prevent dismissal */ }
        }
        scrollWrapper.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Header Row: Title on Left, [ 🔒 Lock ] [ ✖ Close ] on Right
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(headerRow)

        val headerText = TextView(context).apply {
            text = "VBridge Hub"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(headerText)

        // Lock/Unlock Icon Button close to cross button
        val lockBtn = FrameLayout(context).apply {
            val s = dp(32)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                setMargins(0, 0, dp(8), 0)
            }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isLocked) Color.parseColor("#3300E676") else Color.parseColor("#3300E5FF"))
                setStroke(dp(1), if (isLocked) Color.parseColor("#00E676") else Color.parseColor("#00E5FF"))
            }
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismiss()
                onToggleLockClicked()
            }
            setOnLongClickListener {
                val tip = if (isLocked) "🔒 Locked: Touches pass through to game" else "🔓 Unlocked: Tap & drag to edit keybindings"
                Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
                true
            }
        }
        val lockIcon = TextView(context).apply {
            text = if (isLocked) "🔒" else "🔓"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        lockBtn.addView(lockIcon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        headerRow.addView(lockBtn)

        // Close '✖' button
        val closeBtn = TextView(context).apply {
            text = "✖"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { dismiss() }
        }
        headerRow.addView(closeBtn)

        // Active Preset & Keybinding Banner with '>' symbol
        val slot = activePreset.activeSlot
        val presetBanner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pBg = GradientDrawable().apply {
                setColor(Color.parseColor("#17202E"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#26FFFFFF"))
            }
            background = pBg
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        card.addView(presetBanner)

        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismiss()
                onSwitchPresetClicked()
            }
        }
        presetBanner.addView(presetRow)

        val presetNameText = TextView(context).apply {
            text = "🎮 ${activePreset.info.name} (${activePreset.info.appName})"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        presetRow.addView(presetNameText)

        val presetChevron = TextView(context).apply {
            text = "Change ›"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        presetRow.addView(presetChevron)

        // Clickable switch keybinding row with '>' icon
        val slotRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismiss()
                onSwitchSlotClicked()
            }
        }
        presetBanner.addView(slotRow)

        val slotName = TextView(context).apply {
            text = slot.name
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        slotRow.addView(slotName)

        val chevron = TextView(context).apply {
            text = "Switch ›"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        slotRow.addView(chevron)

        // Divider
        val divider = View(context).apply {
            setBackgroundColor(Color.parseColor("#26FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(0, dp(10), 0, dp(10))
            }
        }
        card.addView(divider)

        // Scrollable Action Grid (2 buttons per row in landscape!)
        val maxGridHeight = if (isLandscape) dp(200) else dp(260)
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxGridHeight)
            isFillViewport = true
        }
        val gridContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(gridContainer)
        card.addView(scroll)

        val actions = mutableListOf<Triple<String, String, () -> Unit>>()

        if (!isLocked) {
            actions.add(Triple("➕ Add Key", "Spawn new pin") { onAddKeyClicked() })
        }
        actions.add(Triple("🎮 Switch Preset", "Change profile") { onSwitchPresetClicked() })
        actions.add(Triple("👁️ Toggle HUD", "Show/Hide pins") { onTogglePinsVisibilityClicked() })
        actions.add(Triple("🕹️ Joystick", "Movement stick") { onToggleJoystickClicked() })
        actions.add(Triple("🎯 Aim Pivot", "360° mouse look") { onToggleCameraClicked() })
        actions.add(Triple("🔄 Restart Injector", "Reload daemon") { onRestartInjectorClicked() })
        actions.add(Triple("⚡ Fast Setup", "Generate default") { onFastSetupClicked() })
        actions.add(Triple("🚀 Sync to PC", "Notify port 8889") { onSyncHostClicked() })

        // 2-in-one-row mapping
        for (i in actions.indices step 2) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }

            val item1 = actions[i]
            row.addView(makeGridItem(item1.first, item1.second) {
                dismiss()
                item1.third()
            })

            if (i + 1 < actions.size) {
                val item2 = actions[i + 1]
                val v2 = makeGridItem(item2.first, item2.second) {
                    dismiss()
                    item2.third()
                }
                (v2.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6)
                row.addView(v2)
            } else {
                val spacer = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(6)
                    }
                }
                row.addView(spacer)
            }
            gridContainer.addView(row)
        }

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        menuView = root
        windowManager.addView(root, params)
    }

    private fun makeGridItem(title: String, sub: String, onClick: () -> Unit): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#151E2B"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#26FFFFFF"))
            }
            background = bg
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val tView = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        box.addView(tView)

        val sView = TextView(context).apply {
            text = sub
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        box.addView(sView)

        return box
    }

    fun dismiss() {
        menuView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            menuView = null
            onDismiss()
        }
    }

    private fun dp(v: Int): Int {
        val density = context.resources.displayMetrics.density
        return (v * density).toInt()
    }
}
