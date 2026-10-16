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
import com.vbridge.mapper.data.model.ClickPin

class KeyBindingDialog(
    private val context: Context,
    private val pin: ClickPin,
    private val onSave: (ClickPin) -> Unit,
    private val onDelete: (ClickPin) -> Unit,
    private val onDismiss: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var dialogView: View? = null

    private enum class KeyCategory {
        ALPHABETS,
        MODIFIERS,
        FUNCTION,
        NUMBERS,
        ARROWS_SYMBOLS,
        ALL
    }

    private var activeCategory = KeyCategory.ALPHABETS

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun show() {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#B3000000"))
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val disp = context.resources.displayMetrics
        val isLandscape = disp.widthPixels > disp.heightPixels
        val cardWidth = if (isLandscape) dp(580).coerceAtMost(disp.widthPixels - dp(24))
                        else dp(380).coerceAtMost(disp.widthPixels - dp(24))
        val cardMaxHeight = if (isLandscape) (disp.heightPixels - dp(20)).coerceAtLeast(dp(260))
                            else ViewGroup.LayoutParams.WRAP_CONTENT

        // Outer Scroll Wrapper for Safety
        val scrollWrapper = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(cardWidth, cardMaxHeight).apply {
                gravity = Gravity.CENTER
            }
        }
        root.addView(scrollWrapper)

        // Glassy Frosted Card
        val card = LinearLayout(context).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#EE121924"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            background = bg
            setOnClickListener { /* prevent dismiss */ }
        }
        scrollWrapper.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Left Pane (in landscape) or Top Section (in portrait)
        val leftPane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (isLandscape) {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                    setMargins(0, 0, dp(12), 0)
                }
            } else {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        card.addView(leftPane)

        // Right Pane (in landscape) or Bottom Section (in portrait)
        val rightPane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (isLandscape) {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
            } else {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        card.addView(rightPane)

        // Header Row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        leftPane.addView(header)

        val title = TextView(context).apply {
            text = "Key Binding"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        val keyPreviewBadge = TextView(context).apply {
            text = formatKeyDisplay(pin.key)
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            val badgeBg = GradientDrawable().apply {
                setColor(Color.parseColor("#2600E5FF"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#00E5FF"))
            }
            background = badgeBg
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        header.addView(keyPreviewBadge)

        // Label input & live coordinate indicator
        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        leftPane.addView(labelRow)

        val labelEdit = EditText(context).apply {
            hint = "Key Label (e.g. Fire)"
            setText(pin.label)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val editBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1A2433"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            background = editBg
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        labelRow.addView(labelEdit)

        val posText = TextView(context).apply {
            text = "(${pin.x}, ${pin.y})"
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), 0, 0, 0)
        }
        labelRow.addView(posText)

        // Size & Opacity Controls
        val slidersBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        leftPane.addView(slidersBox)

        // Size Control
        val sizeCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        slidersBox.addView(sizeCol)

        val sizeLabel = TextView(context).apply {
            text = "Size: ${pin.sizeDp} dp"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        sizeCol.addView(sizeLabel)

        val sizeBtnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        sizeCol.addView(sizeBtnRow)

        val sizeMinus = makeStepperButton("-") {
            pin.sizeDp = (pin.sizeDp - 4).coerceAtLeast(32)
            sizeLabel.text = "Size: ${pin.sizeDp} dp"
        }
        sizeBtnRow.addView(sizeMinus)

        val sizePlus = makeStepperButton("+") {
            pin.sizeDp = (pin.sizeDp + 4).coerceAtMost(80)
            sizeLabel.text = "Size: ${pin.sizeDp} dp"
        }
        sizeBtnRow.addView(sizePlus)

        // Opacity Control
        val opacityCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(8), 0, 0, 0)
            }
        }
        slidersBox.addView(opacityCol)

        val opacityLabel = TextView(context).apply {
            text = "Opacity: ${(pin.opacity * 100).toInt()}%"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        opacityCol.addView(opacityLabel)

        val opBtnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        opacityCol.addView(opBtnRow)

        val opMinus = makeStepperButton("-") {
            pin.opacity = (pin.opacity - 0.10f).coerceAtLeast(0.20f)
            opacityLabel.text = "Opacity: ${(pin.opacity * 100).toInt()}%"
        }
        opBtnRow.addView(opMinus)

        val opPlus = makeStepperButton("+") {
            pin.opacity = (pin.opacity + 0.10f).coerceAtMost(1.0f)
            opacityLabel.text = "Opacity: ${(pin.opacity * 100).toInt()}%"
        }
        opBtnRow.addView(opPlus)

        // Action Buttons Row on Left Pane (in landscape)
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        leftPane.addView(actionRow)

        val deleteBtn = Button(context).apply {
            text = "Del"
            setTextColor(Color.parseColor("#FF1744"))
            val delBg = GradientDrawable().apply {
                setColor(Color.parseColor("#26FF1744"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#FF1744"))
            }
            background = delBg
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 0.9f).apply {
                setMargins(0, 0, dp(4), 0)
            }
            setOnClickListener {
                dismiss()
                onDelete(pin)
            }
        }
        actionRow.addView(deleteBtn)

        val cancelBtn = Button(context).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#94A3B8"))
            val cBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(8).toFloat()
            }
            background = cBg
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                setMargins(0, 0, dp(4), 0)
            }
            setOnClickListener {
                dismiss()
                onDismiss()
            }
        }
        actionRow.addView(cancelBtn)

        val applyBtn = Button(context).apply {
            text = "Save"
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            val aBg = GradientDrawable().apply {
                setColor(Color.parseColor("#00E5FF"))
                cornerRadius = dp(8).toFloat()
            }
            background = aBg
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1.2f)
            setOnClickListener {
                val entered = labelEdit.text.toString().trim()
                if (entered.isNotEmpty()) {
                    pin.label = entered
                }
                dismiss()
                onSave(pin)
            }
        }
        actionRow.addView(applyBtn)

        // Right Pane: Category Tab Bar
        val categoryScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(6))
        }
        val tabRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        categoryScroll.addView(tabRow)
        rightPane.addView(categoryScroll)

        // Right Pane: Keys Grid Container
        val gridHeight = if (isLandscape) dp(190) else dp(150)
        val gridScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gridHeight)
            isFillViewport = true
        }
        val gridContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        gridScroll.addView(gridContainer)
        rightPane.addView(gridScroll)

        fun renderKeysForCategory(cat: KeyCategory) {
            gridContainer.removeAllViews()
            val keys = getKeysForCategory(cat)

            val cols = if (isLandscape) 4 else 5
            val rowCount = (keys.size + (cols - 1)) / cols
            for (r in 0 until rowCount) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(4))
                }
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    if (idx < keys.size) {
                        val (rawKey, displayKey) = keys[idx]
                        val isSelected = pin.key.equals(rawKey, ignoreCase = true)
                        val btn = Button(context).apply {
                            text = displayKey
                            setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                            setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                            val bBg = GradientDrawable().apply {
                                setColor(if (isSelected) Color.parseColor("#00E5FF") else Color.parseColor("#1E293B"))
                                cornerRadius = dp(6).toFloat()
                                setStroke(dp(1), if (isSelected) Color.parseColor("#00E5FF") else Color.parseColor("#33FFFFFF"))
                            }
                            background = bBg
                            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                                if (c < cols - 1) setMargins(0, 0, dp(4), 0)
                            }
                            setOnClickListener {
                                pin.key = rawKey
                                keyPreviewBadge.text = formatKeyDisplay(rawKey)
                                renderKeysForCategory(activeCategory)
                            }
                        }
                        row.addView(btn)
                    } else {
                        val spacer = View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
                        }
                        row.addView(spacer)
                    }
                }
                gridContainer.addView(row)
            }
        }

        val categories = listOf(
            KeyCategory.ALPHABETS to "A-Z",
            KeyCategory.MODIFIERS to "Modifiers",
            KeyCategory.FUNCTION to "F1-F12",
            KeyCategory.NUMBERS to "0-9",
            KeyCategory.ARROWS_SYMBOLS to "Symbols/Mouse",
            KeyCategory.ALL to "All"
        )

        for ((cat, catName) in categories) {
            val tabBtn = Button(context).apply {
                text = catName
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                val isAct = cat == activeCategory
                val tBg = GradientDrawable().apply {
                    setColor(if (isAct) Color.parseColor("#3300E5FF") else Color.parseColor("#151D2A"))
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(1), if (isAct) Color.parseColor("#00E5FF") else Color.parseColor("#253245"))
                }
                background = tBg
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply {
                    setMargins(0, 0, dp(4), 0)
                }
                layoutParams = lp
                setOnClickListener {
                    activeCategory = cat
                    for (i in 0 until tabRow.childCount) {
                        val child = tabRow.getChildAt(i) as? Button ?: continue
                        val isThis = child.text == catName
                        (child.background as? GradientDrawable)?.apply {
                            setColor(if (isThis) Color.parseColor("#3300E5FF") else Color.parseColor("#151D2A"))
                            setStroke(dp(1), if (isThis) Color.parseColor("#00E5FF") else Color.parseColor("#253245"))
                        }
                    }
                    renderKeysForCategory(cat)
                }
            }
            tabRow.addView(tabBtn)
        }

        renderKeysForCategory(activeCategory)

        // Hardware Keyboard Listener
        root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                val mapped = mapKeyCodeToString(keyCode)
                if (mapped != null) {
                    pin.key = mapped
                    keyPreviewBadge.text = formatKeyDisplay(mapped)
                    renderKeysForCategory(activeCategory)
                    return@setOnKeyListener true
                }
            }
            false
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

        dialogView = root
        windowManager.addView(root, params)
        root.requestFocus()
    }

    private fun makeStepperButton(text: String, onClick: () -> Unit): Button {
        return Button(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(32)).apply {
                setMargins(0, dp(4), dp(6), 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun getKeysForCategory(cat: KeyCategory): List<Pair<String, String>> {
        return when (cat) {
            KeyCategory.ALPHABETS -> ('A'..'Z').map { it.toString().lowercase() to it.toString() }
            KeyCategory.MODIFIERS -> listOf(
                "space" to "SPACE", "shift" to "SHIFT", "ctrl" to "CTRL", "alt" to "ALT",
                "tab" to "TAB", "enter" to "ENTER", "esc" to "ESC", "fn" to "FN"
            )
            KeyCategory.FUNCTION -> (1..12).map { "f$it" to "F$it" }
            KeyCategory.NUMBERS -> (0..9).map { "$it" to "$it" } + (0..9).map { "num$it" to "N$it" }
            KeyCategory.ARROWS_SYMBOLS -> listOf(
                "mouse_left" to "LMB", "mouse_right" to "RMB", "mouse_middle" to "MMB",
                "up" to "▲", "down" to "▼", "left" to "◄", "right" to "►",
                "`" to "~", "[" to "[", "]" to "]", ";" to ";", "'" to "'",
                "," to ",", "." to ".", "/" to "/", "-" to "-", "=" to "="
            )
            KeyCategory.ALL -> getKeysForCategory(KeyCategory.ALPHABETS) +
                    getKeysForCategory(KeyCategory.MODIFIERS) +
                    getKeysForCategory(KeyCategory.FUNCTION) +
                    getKeysForCategory(KeyCategory.NUMBERS) +
                    getKeysForCategory(KeyCategory.ARROWS_SYMBOLS)
        }
    }

    private fun formatKeyDisplay(key: String): String {
        return when (key.lowercase()) {
            "mouse_left" -> "LMB"
            "mouse_right" -> "RMB"
            "mouse_middle" -> "MMB"
            else -> key.uppercase()
        }
    }

    private fun mapKeyCodeToString(keyCode: Int): String? {
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> "space"
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "shift"
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "ctrl"
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "alt"
            KeyEvent.KEYCODE_TAB -> "tab"
            KeyEvent.KEYCODE_ENTER -> "enter"
            KeyEvent.KEYCODE_GRAVE -> "`"
            KeyEvent.KEYCODE_ESCAPE -> "esc"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_A -> "a"
            KeyEvent.KEYCODE_B -> "b"
            KeyEvent.KEYCODE_C -> "c"
            KeyEvent.KEYCODE_D -> "d"
            KeyEvent.KEYCODE_E -> "e"
            KeyEvent.KEYCODE_F -> "f"
            KeyEvent.KEYCODE_G -> "g"
            KeyEvent.KEYCODE_H -> "h"
            KeyEvent.KEYCODE_I -> "i"
            KeyEvent.KEYCODE_J -> "j"
            KeyEvent.KEYCODE_K -> "k"
            KeyEvent.KEYCODE_L -> "l"
            KeyEvent.KEYCODE_M -> "m"
            KeyEvent.KEYCODE_N -> "n"
            KeyEvent.KEYCODE_O -> "o"
            KeyEvent.KEYCODE_P -> "p"
            KeyEvent.KEYCODE_Q -> "q"
            KeyEvent.KEYCODE_R -> "r"
            KeyEvent.KEYCODE_S -> "s"
            KeyEvent.KEYCODE_T -> "t"
            KeyEvent.KEYCODE_U -> "u"
            KeyEvent.KEYCODE_V -> "v"
            KeyEvent.KEYCODE_W -> "w"
            KeyEvent.KEYCODE_X -> "x"
            KeyEvent.KEYCODE_Y -> "y"
            KeyEvent.KEYCODE_Z -> "z"
            KeyEvent.KEYCODE_0 -> "0"
            KeyEvent.KEYCODE_1 -> "1"
            KeyEvent.KEYCODE_2 -> "2"
            KeyEvent.KEYCODE_3 -> "3"
            KeyEvent.KEYCODE_4 -> "4"
            KeyEvent.KEYCODE_5 -> "5"
            KeyEvent.KEYCODE_6 -> "6"
            KeyEvent.KEYCODE_7 -> "7"
            KeyEvent.KEYCODE_8 -> "8"
            KeyEvent.KEYCODE_9 -> "9"
            else -> null
        }
    }

    fun dismiss() {
        dialogView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            dialogView = null
        }
    }

    private fun dp(v: Int): Int {
        val density = context.resources.displayMetrics.density
        return (v * density).toInt()
    }
}
