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
import com.vbridge.mapper.data.model.KeybindingSlot
import com.vbridge.mapper.data.model.Preset

class GameWelcomeModal(
    private val context: Context,
    private val gameName: String,
    private val preset: Preset,
    private val onSlotSelected: (KeybindingSlot) -> Unit,
    private val onFastSetup: (KeybindingSlot) -> Unit,
    private val onDismiss: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var modalView: View? = null

    @SuppressLint("SetTextI18n")
    fun show() {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { dismiss() }
        }

        // Mirror-like Whitish Frosted Glass Container
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#F0121A26"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#80FFFFFF"))
            }
            background = bg
            setOnClickListener { /* prevent dismissal on clicking inside */ }
        }

        val cardParams = FrameLayout.LayoutParams(
            dp(520).coerceAtMost(context.resources.displayMetrics.widthPixels - dp(24)),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        root.addView(card, cardParams)

        // Title row
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        card.addView(titleRow)

        val gameIcon = TextView(context).apply {
            text = "🎮"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, 0, dp(8), 0)
        }
        titleRow.addView(gameIcon)

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleCol)

        val gameTitle = TextView(context).apply {
            text = gameName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        titleCol.addView(gameTitle)

        val subTitle = TextView(context).apply {
            text = "${preset.info.name} • ${preset.info.controllerType} via ${preset.info.via}"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        titleCol.addView(subTitle)

        val closeBtn = TextView(context).apply {
            text = "✖"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { dismiss() }
        }
        titleRow.addView(closeBtn)

        // 3 Slots Container (Horizontal Row of Whitish Mirror Cards)
        val slotsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(6), 0, dp(12))
        }
        card.addView(slotsRow)

        for (slot in preset.slots.take(3)) {
            val slotCard = makeSlotCard(slot)
            slotsRow.addView(slotCard)
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
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        modalView = root
        windowManager.addView(root, params)
    }

    private fun makeSlotCard(slot: KeybindingSlot): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                setColor(if (slot.isConfigured) Color.parseColor("#26FFFFFF") else Color.parseColor("#15FFFFFF"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), if (slot.id == preset.info.bindingId) Color.parseColor("#00E5FF") else Color.parseColor("#4DFFFFFF"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(0, dp(150), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        }

        val slotNumBadge = TextView(context).apply {
            text = "#${slot.id}"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        card.addView(slotNumBadge)

        val slotTitle = TextView(context).apply {
            text = if (slot.isConfigured) slot.name else "Tap to edit"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, if (slot.isConfigured) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        card.addView(slotTitle)

        if (slot.isConfigured) {
            val keysInfo = TextView(context).apply {
                text = "${slot.clicks.size} Keys Mapped"
                setTextColor(Color.parseColor("#00E676"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(0, 0, 0, dp(8))
            }
            card.addView(keysInfo)

            val selectBtn = Button(context).apply {
                text = if (slot.id == preset.info.bindingId) "Active" else "Select"
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#00E5FF"))
                    cornerRadius = dp(6).toFloat()
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32))
                setOnClickListener {
                    dismiss()
                    onSlotSelected(slot)
                }
            }
            card.addView(selectBtn)
        } else {
            val emptyHint = TextView(context).apply {
                text = "Blank Slot"
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(0, 0, 0, dp(6))
            }
            card.addView(emptyHint)

            val fastSetupBtn = Button(context).apply {
                text = "⚡ Fast Setup"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#3300E5FF"))
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(1), Color.parseColor("#00E5FF"))
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)).apply {
                    bottomMargin = dp(4)
                }
                setOnClickListener {
                    promptNameSlot(slot) { namedSlot ->
                        dismiss()
                        onFastSetup(namedSlot)
                    }
                }
            }
            card.addView(fastSetupBtn)

            card.setOnClickListener {
                promptNameSlot(slot) { namedSlot ->
                    dismiss()
                    onSlotSelected(namedSlot)
                }
            }
        }

        return card
    }

    private fun promptNameSlot(slot: KeybindingSlot, onNamed: (KeybindingSlot) -> Unit) {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#CC000000"))
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#EE121924"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#80FFFFFF"))
            }
            background = bg
        }
        val p = FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        root.addView(box, p)

        val title = TextView(context).apply {
            text = "Name Keybinding #${slot.id}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        box.addView(title)

        val edit = EditText(context).apply {
            setText("Layout #${slot.id}")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(10), 0, dp(12))
            }
            layoutParams = lp
        }
        box.addView(edit)

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(row)

        val cancel = Button(context).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#94A3B8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                setMargins(0, 0, dp(4), 0)
            }
            setOnClickListener {
                try { windowManager.removeView(root) } catch (_: Exception) {}
            }
        }
        row.addView(cancel)

        val confirm = Button(context).apply {
            text = "Confirm"
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00E5FF"))
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
            setOnClickListener {
                val entered = edit.text.toString().trim()
                if (entered.isNotEmpty()) {
                    slot.name = entered
                }
                slot.isConfigured = true
                try { windowManager.removeView(root) } catch (_: Exception) {}
                onNamed(slot)
            }
        }
        row.addView(confirm)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val wmP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(root, wmP)
    }

    fun dismiss() {
        modalView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            modalView = null
            onDismiss()
        }
    }

    private fun dp(v: Int): Int {
        val density = context.resources.displayMetrics.density
        return (v * density).toInt()
    }
}
