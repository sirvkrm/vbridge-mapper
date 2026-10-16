package com.vbridge.mapper.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.vbridge.mapper.R
import com.vbridge.mapper.VBridgeApplication
import com.vbridge.mapper.data.model.ClickPin
import com.vbridge.mapper.data.model.KeybindingSlot
import com.vbridge.mapper.data.model.Preset
import com.vbridge.mapper.data.repository.PresetRepository
import com.vbridge.mapper.network.WebSocketBridgeClient
import com.vbridge.mapper.overlay.*
import com.vbridge.mapper.ui.MainActivity
import com.vbridge.mapper.utils.CoordinateTransformer
import com.vbridge.mapper.utils.InjectorManager
import kotlinx.coroutines.*
import java.util.ArrayDeque
import java.util.UUID

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var repository: PresetRepository
    private lateinit var wsClient: WebSocketBridgeClient
    private lateinit var coordTransformer: CoordinateTransformer

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var floatingBubble: FloatingBubbleView? = null
    private var hubMenu: OverlayHubMenu? = null
    private var tetherCanvas: TetherCanvasView? = null
    private var activePreset: Preset? = null

    private val activePinViews = mutableListOf<KeyPinView>()
    private var activeJoystickView: JoystickPinView? = null
    private var activeCameraView: CameraPivotPinView? = null

    private var arePinsVisible = true

    // Undo / Redo Stacks
    private val undoStack = ArrayDeque<List<ClickPin>>()
    private val redoStack = ArrayDeque<List<ClickPin>>()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = (application as VBridgeApplication).presetRepository
        wsClient = (application as VBridgeApplication).wsClient
        coordTransformer = CoordinateTransformer(this)

        startForegroundServiceNotification()
        loadActivePreset()

        tetherCanvas = TetherCanvasView(this).apply {
            attachToWindow()
        }

        showFloatingBubble()
        renderPresetPins()

        wsClient.onSyncRequested = {
            syncActivePresetToHost()
        }

        serviceScope.launch {
            repository.activePresetFlow.collect { (presetId, slotId) ->
                if (activePreset?.info?.presetId != presetId || activePreset?.activeSlot?.id != slotId) {
                    switchPreset(presetId, slotId)
                }
            }
        }

        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floatingBubble?.post {
            floatingBubble?.clampToScreenBounds()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_STOP_OVERLAY -> {
                stopSelf()
            }
            ACTION_SWITCH_PRESET -> {
                val pId = intent.getIntExtra(EXTRA_PRESET_ID, 1)
                val bId = intent.getIntExtra(EXTRA_BINDING_ID, 1)
                switchPreset(pId, bId)
            }
            ACTION_GAME_DETECTED -> {
                val gamePkg = intent.getStringExtra(EXTRA_GAME_PACKAGE) ?: ""
                val gameName = intent.getStringExtra(EXTRA_GAME_NAME) ?: "Game"
                onGameDetected(gamePkg, gameName)
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, VBridgeApplication.CHANNEL_OVERLAY)
            .setContentTitle("VBridge In-Game Overlay")
            .setContentText("Touch mapper active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(VBridgeApplication.NOTIFICATION_ID_OVERLAY, notification)
    }

    private fun loadActivePreset() {
        activePreset = repository.getActivePreset()
    }

    private fun showFloatingBubble() {
        if (floatingBubble == null) {
            floatingBubble = FloatingBubbleView(
                context = this,
                onBubbleClicked = { toggleHubMenu() },
                onUndoClicked = { performUndo() },
                onRedoClicked = { performRedo() },
                onLockToggleClicked = { toggleLockState() }
            )
            windowManager.addView(floatingBubble, floatingBubble?.layoutParamsWm)
        }
        activePreset?.let {
            floatingBubble?.setBadgeText(it.activeSlot.name.take(8).uppercase())
            floatingBubble?.setLockState(it.isLocked)
        }
    }

    private fun toggleHubMenu() {
        val preset = activePreset ?: return
        if (hubMenu == null) {
            hubMenu = OverlayHubMenu(
                context = this,
                activePreset = preset,
                isLocked = preset.isLocked,
                onToggleLockClicked = { toggleLockState() },
                onAddKeyClicked = { spawnNewPinAtCenter() },
                onToggleJoystickClicked = { toggleJoystick() },
                onToggleCameraClicked = { toggleCameraPivot() },
                onSwitchSlotClicked = { showSlotSwitchDialog() },
                onSwitchPresetClicked = { showPresetSwitchDialog() },
                onTogglePinsVisibilityClicked = { togglePinsVisibility() },
                onFastSetupClicked = { fastSetupSlot(preset.activeSlot) },
                onSyncHostClicked = { syncActivePresetToHost() },
                onRestartInjectorClicked = {
                    Toast.makeText(this, "🔄 Restarting touch injector daemon...", Toast.LENGTH_SHORT).show()
                    serviceScope.launch {
                        val res = InjectorManager.restartInjector(this@OverlayService)
                        res.onSuccess { msg ->
                            Toast.makeText(this@OverlayService, "✅ $msg", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            Toast.makeText(this@OverlayService, "❌ Injector failed: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onDismiss = { hubMenu = null }
            )
            hubMenu?.show()
        } else {
            hubMenu?.dismiss()
            hubMenu = null
        }
    }

    private fun toggleLockState() {
        val preset = activePreset ?: return
        preset.isLocked = !preset.isLocked
        repository.savePreset(preset)
        floatingBubble?.setLockState(preset.isLocked)

        renderPresetPins()

        if (preset.isLocked) {
            Toast.makeText(this, "🔒 Locked: Touches pass through to game", Toast.LENGTH_SHORT).show()
            syncActivePresetToHost()
        } else {
            Toast.makeText(this, "🔓 Unlocked: Edit mode enabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderPresetPins() {
        clearAllPinViews()
        val preset = activePreset ?: return
        if (!arePinsVisible) return

        val isLocked = preset.isLocked

        // Render Click Pins
        for (click in preset.clicks) {
            spawnClickPinView(click, isLocked)
        }

        // Render Joystick Pin if enabled
        if (preset.joystick.enabled) {
            val joyView = JoystickPinView(
                context = this,
                joystick = preset.joystick,
                isLocked = isLocked,
                tetherCanvas = tetherCanvas,
                onJoystickChanged = { updatedJoy ->
                    preset.joystick = updatedJoy
                    syncActivePresetToHost()
                },
                onJoystickReleased = { updatedJoy ->
                    preset.joystick = updatedJoy
                    autoSaveLive()
                    wsClient.sendLiveJoystickUpdate(updatedJoy.centerX, updatedJoy.centerY, updatedJoy.radius)
                    serviceScope.launch {
                        InjectorManager.sendIpcCommand("UPDATE_JOYSTICK: ${updatedJoy.centerX} ${updatedJoy.centerY} ${updatedJoy.radius}")
                    }
                }
            )
            windowManager.addView(joyView, joyView.layoutParamsWm)
            activeJoystickView = joyView
        }

        // Render Camera Pivot Pin if enabled
        if (preset.camera.enabled) {
            val camView = CameraPivotPinView(
                context = this,
                camera = preset.camera,
                isLocked = isLocked,
                tetherCanvas = tetherCanvas,
                onCameraChanged = { updatedCam ->
                    preset.camera = updatedCam
                    syncActivePresetToHost()
                },
                onCameraReleased = { updatedCam ->
                    preset.camera = updatedCam
                    autoSaveLive()
                    wsClient.sendLiveCameraUpdate(updatedCam.pivotCenterX, updatedCam.pivotCenterY, updatedCam.resetRadius)
                    serviceScope.launch {
                        InjectorManager.sendIpcCommand("UPDATE_CAMERA: ${updatedCam.pivotCenterX} ${updatedCam.pivotCenterY} ${updatedCam.resetRadius} ${updatedCam.sensitivityX} ${updatedCam.sensitivityY}")
                    }
                }
            )
            windowManager.addView(camView, camView.layoutParamsWm)
            activeCameraView = camView
        }
    }

    private fun spawnClickPinView(pin: ClickPin, isLocked: Boolean) {
        val pinView = KeyPinView(
            context = this,
            pin = pin,
            isLocked = isLocked,
            tetherCanvas = tetherCanvas,
            onPinClicked = { v ->
                if (!isLocked) openKeyBindingModal(v.pin)
            },
            onPositionChanged = { _, x, y ->
                pin.x = x
                pin.y = y
            },
            onPositionReleased = { v ->
                pushSnapshotToUndo()
                autoSaveLive()
                wsClient.sendLiveKeyUpdate(v.pin.key, v.pin.x, v.pin.y)
            }
        )
        windowManager.addView(pinView, pinView.layoutParamsWm)
        activePinViews.add(pinView)
    }

    private fun spawnNewPinAtCenter() {
        val preset = activePreset ?: return
        if (preset.isLocked) return

        val disp = coordTransformer.getDisplaySize()
        val centerX = disp.x / 2
        val centerY = disp.y / 2

        pushSnapshotToUndo()

        val newPin = ClickPin(
            id = UUID.randomUUID().toString(),
            key = "space",
            label = "Key ${activePinViews.size + 1}",
            x = centerX,
            y = centerY
        )
        preset.clicks.add(newPin)
        spawnClickPinView(newPin, false)
        autoSaveLive()

        openKeyBindingModal(newPin)
    }

    private fun openKeyBindingModal(pin: ClickPin) {
        val dialog = KeyBindingDialog(
            context = this,
            pin = pin,
            onSave = { updatedPin ->
                pushSnapshotToUndo()
                val view = activePinViews.find { it.pin.id == updatedPin.id }
                view?.updatePinData(updatedPin)
                autoSaveLive()
                wsClient.sendLiveKeyUpdate(updatedPin.key, updatedPin.x, updatedPin.y)
            },
            onDelete = { delPin ->
                pushSnapshotToUndo()
                activePreset?.clicks?.removeAll { it.id == delPin.id }
                val view = activePinViews.find { it.pin.id == delPin.id }
                if (view != null) {
                    try { windowManager.removeView(view) } catch (_: Exception) {}
                    activePinViews.remove(view)
                }
                autoSaveLive()
            },
            onDismiss = {}
        )
        dialog.show()
    }

    private fun pushSnapshotToUndo() {
        val preset = activePreset ?: return
        val snapshot = preset.clicks.map { it.copy() }
        undoStack.push(snapshot)
        if (undoStack.size > 20) undoStack.removeLast()
        redoStack.clear()
    }

    private fun performUndo() {
        val preset = activePreset ?: return
        if (undoStack.isEmpty()) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            return
        }
        val currentSnapshot = preset.clicks.map { it.copy() }
        redoStack.push(currentSnapshot)

        val previousSnapshot = undoStack.pop()
        preset.clicks.clear()
        preset.clicks.addAll(previousSnapshot.map { it.copy() })

        renderPresetPins()
        autoSaveLive()
        Toast.makeText(this, "↺ Undone", Toast.LENGTH_SHORT).show()
    }

    private fun performRedo() {
        val preset = activePreset ?: return
        if (redoStack.isEmpty()) {
            Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show()
            return
        }
        val currentSnapshot = preset.clicks.map { it.copy() }
        undoStack.push(currentSnapshot)

        val nextSnapshot = redoStack.pop()
        preset.clicks.clear()
        preset.clicks.addAll(nextSnapshot.map { it.copy() })

        renderPresetPins()
        autoSaveLive()
        Toast.makeText(this, "↻ Redone", Toast.LENGTH_SHORT).show()
    }

    private fun autoSaveLive() {
        val preset = activePreset ?: return
        repository.savePreset(preset)
        val path = "/sdcard/VbridgeBind/preset${preset.info.presetId}/keybinding${preset.activeSlot.id}/profile.json"
        serviceScope.launch {
            InjectorManager.sendIpcCommand("RELOAD_PROFILE: $path")
        }
    }

    private fun toggleJoystick() {
        val preset = activePreset ?: return
        preset.joystick.enabled = !preset.joystick.enabled
        autoSaveLive()

        if (preset.joystick.enabled) {
            if (activeJoystickView == null) {
                val joyView = JoystickPinView(
                    context = this,
                    joystick = preset.joystick,
                    isLocked = preset.isLocked,
                    tetherCanvas = tetherCanvas,
                    onJoystickChanged = { updatedJoy ->
                        preset.joystick = updatedJoy
                        syncActivePresetToHost()
                    },
                    onJoystickReleased = { updatedJoy ->
                        preset.joystick = updatedJoy
                        autoSaveLive()
                        wsClient.sendLiveJoystickUpdate(updatedJoy.centerX, updatedJoy.centerY, updatedJoy.radius)
                    }
                )
                windowManager.addView(joyView, joyView.layoutParamsWm)
                activeJoystickView = joyView
            }
        } else {
            activeJoystickView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
                activeJoystickView = null
            }
        }
    }

    private fun toggleCameraPivot() {
        val preset = activePreset ?: return
        preset.camera.enabled = !preset.camera.enabled
        autoSaveLive()

        if (preset.camera.enabled) {
            if (activeCameraView == null) {
                val camView = CameraPivotPinView(
                    context = this,
                    camera = preset.camera,
                    isLocked = preset.isLocked,
                    tetherCanvas = tetherCanvas,
                    onCameraChanged = { updatedCam ->
                        preset.camera = updatedCam
                        syncActivePresetToHost()
                    },
                    onCameraReleased = { updatedCam ->
                        preset.camera = updatedCam
                        autoSaveLive()
                        wsClient.sendLiveCameraUpdate(updatedCam.pivotCenterX, updatedCam.pivotCenterY, updatedCam.resetRadius)
                    }
                )
                windowManager.addView(camView, camView.layoutParamsWm)
                activeCameraView = camView
            }
        } else {
            activeCameraView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
                activeCameraView = null
            }
        }
    }

    private fun togglePinsVisibility() {
        arePinsVisible = !arePinsVisible
        if (arePinsVisible) {
            renderPresetPins()
            Toast.makeText(this, "HUD Visible", Toast.LENGTH_SHORT).show()
        } else {
            clearAllPinViews()
            Toast.makeText(this, "HUD Hidden", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSlotSwitchDialog() {
        val preset = activePreset ?: return
        val modal = GameWelcomeModal(
            context = this,
            gameName = preset.info.appName,
            preset = preset,
            onSlotSelected = { slot ->
                switchSlot(slot.id)
            },
            onFastSetup = { slot ->
                fastSetupSlot(slot)
            },
            onDismiss = {}
        )
        modal.show()
    }

    private fun showPresetSwitchDialog() {
        val allPresets = repository.listAllPresets()
        if (allPresets.isEmpty()) {
            Toast.makeText(this, "No presets found", Toast.LENGTH_SHORT).show()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener {
                try { windowManager.removeView(this) } catch (_: Exception) {}
            }
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#EE121A26"))
                cornerRadius = 18f * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor("#4DFFFFFF"))
            }
            background = bg
            setOnClickListener { /* prevent dismissal */ }
        }

        val cardWidth = (420 * resources.displayMetrics.density).toInt().coerceAtMost(resources.displayMetrics.widthPixels - 32)
        val cardParams = FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        root.addView(card, cardParams)

        val headerText = TextView(this).apply {
            text = "Select Game Preset"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        card.addView(headerText)

        for (p in allPresets) {
            val isActive = activePreset?.info?.presetId == p.info.presetId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pPad = (10 * resources.displayMetrics.density).toInt()
                setPadding(pPad, pPad, pPad, pPad)
                val rowBg = GradientDrawable().apply {
                    setColor(if (isActive) Color.parseColor("#2600E5FF") else Color.parseColor("#17202E"))
                    cornerRadius = 8f * resources.displayMetrics.density
                    setStroke(
                        (1 * resources.displayMetrics.density).toInt(),
                        if (isActive) Color.parseColor("#00E5FF") else Color.parseColor("#26FFFFFF")
                    )
                }
                background = rowBg
                val lParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
                }
                layoutParams = lParams
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try { windowManager.removeView(root) } catch (_: Exception) {}
                    switchPreset(p.info.presetId, p.info.bindingId)
                    repository.setActivePreset(p, p.info.bindingId)
                    Toast.makeText(this@OverlayService, "Active: ${p.info.name}", Toast.LENGTH_SHORT).show()
                }
            }

            val iconText = TextView(this).apply {
                text = "🎮"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(0, 0, (8 * resources.displayMetrics.density).toInt(), 0)
            }
            row.addView(iconText)

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(this).apply {
                text = p.info.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            textCol.addView(title)

            val sub = TextView(this).apply {
                text = "${p.info.appName} • ${p.activeSlot.name}"
                setTextColor(if (isActive) Color.parseColor("#00E5FF") else Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            }
            textCol.addView(sub)
            row.addView(textCol)

            if (isActive) {
                val badge = TextView(this).apply {
                    text = "ACTIVE"
                    setTextColor(Color.parseColor("#00E676"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                row.addView(badge)
            }

            card.addView(row)
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
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(root, params)
    }

    private fun switchSlot(slotId: Int) {
        val preset = activePreset ?: return
        preset.info.bindingId = slotId
        repository.setActivePreset(preset, slotId)
        floatingBubble?.setBadgeText(preset.activeSlot.name.take(8).uppercase())
        floatingBubble?.setLockState(preset.isLocked)
        undoStack.clear()
        redoStack.clear()
        renderPresetPins()
        syncActivePresetToHost()
        Toast.makeText(this, "Selected: ${preset.activeSlot.name}", Toast.LENGTH_SHORT).show()
    }

    private fun fastSetupSlot(slot: KeybindingSlot) {
        val preset = activePreset ?: return
        val isFreeFire = preset.info.packageName.contains("freefire", ignoreCase = true)
        val template = if (isFreeFire) repository.createFreeFirePreset(preset.info.presetId, slot.id)
                       else repository.createBgmiPreset(preset.info.presetId, slot.id)

        slot.isConfigured = true
        slot.clicks.clear()
        slot.clicks.addAll(template.clicks)
        slot.joystick = template.joystick.copy()
        slot.camera = template.camera.copy()

        repository.savePreset(preset)
        switchSlot(slot.id)
        Toast.makeText(this, "⚡ Fast Setup Applied!", Toast.LENGTH_SHORT).show()
    }

    private fun onGameDetected(packageName: String, gameName: String) {
        val preset = repository.getPresetForPackage(packageName) ?: activePreset ?: return
        activePreset = preset
        repository.setActivePreset(preset, preset.info.bindingId)

        floatingBubble?.setBadgeText(preset.activeSlot.name.take(8).uppercase())
        floatingBubble?.setLockState(preset.isLocked)
        renderPresetPins()

        // Show glassy mirror-like welcoming modal with 3 slots
        val modal = GameWelcomeModal(
            context = this,
            gameName = gameName,
            preset = preset,
            onSlotSelected = { slot ->
                switchSlot(slot.id)
            },
            onFastSetup = { slot ->
                fastSetupSlot(slot)
            },
            onDismiss = {}
        )
        modal.show()
    }

    private fun switchPreset(presetId: Int, bindingId: Int) {
        val loaded = repository.loadPreset(presetId, bindingId)
        if (loaded != null) {
            activePreset = loaded
            repository.setActivePreset(loaded, bindingId)
            floatingBubble?.setBadgeText(loaded.activeSlot.name.take(8).uppercase())
            floatingBubble?.setLockState(loaded.isLocked)
            undoStack.clear()
            redoStack.clear()
            renderPresetPins()
            syncActivePresetToHost()
        }
    }

    private fun syncActivePresetToHost() {
        val preset = activePreset ?: return
        val slot = preset.activeSlot
        val path = "/sdcard/VbridgeBind/preset${preset.info.presetId}/keybinding${slot.id}/"
        val fullProfile = preset.toFullProfileJson()

        wsClient.sendProfileActivated(
            presetId = preset.info.presetId,
            bindingId = slot.id,
            presetName = preset.info.name,
            bindingName = slot.name,
            packageName = preset.info.packageName,
            controllerType = preset.info.controllerType,
            via = preset.info.via,
            path = path,
            profileJson = fullProfile
        )
    }

    private fun clearAllPinViews() {
        for (view in activePinViews) {
            try { windowManager.removeView(view) } catch (_: Exception) {}
        }
        activePinViews.clear()

        activeJoystickView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            activeJoystickView = null
        }

        activeCameraView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            activeCameraView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()

        clearAllPinViews()
        tetherCanvas?.detachFromWindow()
        tetherCanvas = null

        hubMenu?.dismiss()
        hubMenu = null

        floatingBubble?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            floatingBubble = null
        }
    }

    companion object {
        const val ACTION_START_OVERLAY = "com.vbridge.mapper.action.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.vbridge.mapper.action.STOP_OVERLAY"
        const val ACTION_SWITCH_PRESET = "com.vbridge.mapper.action.SWITCH_PRESET"
        const val ACTION_GAME_DETECTED = "com.vbridge.mapper.action.GAME_DETECTED"

        const val EXTRA_PRESET_ID = "extra_preset_id"
        const val EXTRA_BINDING_ID = "extra_binding_id"
        const val EXTRA_GAME_PACKAGE = "extra_game_package"
        const val EXTRA_GAME_NAME = "extra_game_name"

        var isRunning = false
            private set
    }
}
