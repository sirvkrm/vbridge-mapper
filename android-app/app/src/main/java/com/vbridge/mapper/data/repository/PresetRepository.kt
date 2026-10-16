package com.vbridge.mapper.data.repository

import android.content.Context
import android.os.Environment
import android.util.Log
import com.vbridge.mapper.data.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PresetRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("vbridge_prefs", Context.MODE_PRIVATE)

    val baseDir: File
        get() {
            val primary = File("/sdcard/VbridgeBind")
            if (primary.exists() || primary.mkdirs()) {
                return primary
            }
            val ext = File(Environment.getExternalStorageDirectory(), "VbridgeBind")
            if (ext.exists() || ext.mkdirs()) {
                return ext
            }
            return File(context.getExternalFilesDir(null), "VbridgeBind").apply { mkdirs() }
        }

    init {
        ensureSeeded()
    }

    @Synchronized
    fun savePreset(preset: Preset): Boolean {
        return try {
            val pDir = File(baseDir, "preset${preset.info.presetId}")
            if (!pDir.exists()) pDir.mkdirs()

            // Save active slot to its directory
            val activeSlot = preset.activeSlot
            val bDir = File(pDir, "keybinding${activeSlot.id}")
            if (!bDir.exists()) bDir.mkdirs()

            // 1. Save click.json
            val clickFile = File(bDir, "click.json")
            writeFile(clickFile, preset.clicksToJsonArray().toString(2))

            // 2. Save joystick.json
            val joyFile = File(bDir, "joystick.json")
            writeFile(joyFile, preset.joystickToJson().toString(2))

            // 3. Save mouse.json
            val mouseFile = File(bDir, "mouse.json")
            writeFile(mouseFile, preset.cameraToJson().toString(2))

            // 4. Save slot metadata
            val slotMetaFile = File(bDir, "slot_info.json")
            val slotMeta = JSONObject().apply {
                put("id", activeSlot.id)
                put("name", activeSlot.name)
                put("is_configured", activeSlot.isConfigured)
                put("is_locked", activeSlot.isLocked)
                put("pin_size_dp", activeSlot.pinSizeDp)
                put("pin_opacity", activeSlot.pinOpacity.toDouble())
            }
            writeFile(slotMetaFile, slotMeta.toString(2))

            // 5. Save preset_info.json
            preset.info.updatedAt = System.currentTimeMillis()
            val infoFile = File(bDir, "preset_info.json")
            writeFile(infoFile, preset.infoToJson().toString(2))

            // Also save root preset_info.json for quick metadata index
            val rootInfoFile = File(pDir, "preset_info.json")
            writeFile(rootInfoFile, preset.infoToJson().toString(2))

            // 6. Save combined profile.json
            val fullFile = File(bDir, "profile.json")
            writeFile(fullFile, preset.toFullProfileJson().toString(2))

            Log.i(TAG, "Saved preset #${preset.info.presetId} (binding #${activeSlot.id}) to: ${bDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving preset: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun loadPreset(presetId: Int, bindingId: Int = 1): Preset? {
        val pDir = File(baseDir, "preset$presetId")
        if (!pDir.exists() || !pDir.isDirectory) return null

        return try {
            var info = PresetInfo(presetId = presetId, bindingId = bindingId)
            val rootInfoFile = File(pDir, "preset_info.json")
            if (rootInfoFile.exists()) {
                info = PresetInfo.fromJsonObject(JSONObject(readFile(rootInfoFile)))
            } else {
                val b1Info = File(pDir, "keybinding1/preset_info.json")
                if (b1Info.exists()) {
                    info = PresetInfo.fromJsonObject(JSONObject(readFile(b1Info)))
                }
            }
            info.bindingId = bindingId

            val slots = mutableListOf<KeybindingSlot>()
            val bindingDirs = pDir.listFiles { f -> f.isDirectory && f.name.startsWith("keybinding") }
                ?.mapNotNull { f ->
                    val num = f.name.removePrefix("keybinding").toIntOrNull()
                    if (num != null) num to f else null
                }
                ?.sortedBy { it.first }
                ?: emptyList()

            val maxSlotId = maxOf(3, bindingDirs.maxOfOrNull { it.first } ?: 3).coerceAtMost(20)
            val dirMap = bindingDirs.toMap()

            for (slotIdx in 1..maxSlotId) {
                val bDir = dirMap[slotIdx]
                if (bDir != null && bDir.exists()) {
                    slots.add(loadSlot(bDir, slotIdx))
                } else {
                    slots.add(KeybindingSlot.createBlank(slotIdx))
                }
            }

            Preset(info = info, slots = slots)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load preset $presetId / $bindingId: ${e.message}", e)
            null
        }
    }

    private fun loadSlot(bDir: File, slotId: Int): KeybindingSlot {
        var name = "Keybinding #$slotId"
        var isConfigured = true
        var isLocked = false
        var pinSize = 48
        var pinOpacity = 0.85f

        val slotInfoFile = File(bDir, "slot_info.json")
        if (slotInfoFile.exists()) {
            val json = JSONObject(readFile(slotInfoFile))
            name = json.optString("name", name)
            isConfigured = json.optBoolean("is_configured", true)
            isLocked = json.optBoolean("is_locked", false)
            pinSize = json.optInt("pin_size_dp", 48)
            pinOpacity = json.optDouble("pin_opacity", 0.85).toFloat()
        }

        val clicks = mutableListOf<ClickPin>()
        val clickFile = File(bDir, "click.json")
        if (clickFile.exists()) {
            val arr = JSONArray(readFile(clickFile))
            for (i in 0 until arr.length()) {
                clicks.add(ClickPin.fromJsonObject(arr.getJSONObject(i)))
            }
        }

        val joyFile = File(bDir, "joystick.json")
        val joy = if (joyFile.exists()) {
            JoystickPin.fromJsonObject(JSONObject(readFile(joyFile)))
        } else {
            JoystickPin()
        }

        val mouseFile = File(bDir, "mouse.json")
        val cam = if (mouseFile.exists()) {
            CameraPin.fromJsonObject(JSONObject(readFile(mouseFile))).apply {
                enabled = true
            }
        } else {
            CameraPin(enabled = true)
        }

        return KeybindingSlot(
            id = slotId,
            name = name,
            isConfigured = isConfigured,
            isLocked = isLocked,
            pinSizeDp = pinSize,
            pinOpacity = pinOpacity,
            clicks = clicks,
            joystick = joy,
            camera = cam
        )
    }

    @Synchronized
    fun listAllPresets(): List<Preset> {
        val list = mutableListOf<Preset>()
        val root = baseDir
        if (!root.exists()) return list

        val presetDirs = root.listFiles { f -> f.isDirectory && f.name.startsWith("preset") } ?: return list

        for (pDir in presetDirs) {
            val pNum = pDir.name.removePrefix("preset").toIntOrNull() ?: continue
            val loaded = loadPreset(pNum, 1)
            if (loaded != null) {
                list.add(loaded)
            }
        }

        list.sortBy { it.info.presetId }
        return list
    }

    @Synchronized
    fun deletePreset(presetId: Int): Boolean {
        val pDir = File(baseDir, "preset$presetId")
        if (pDir.exists()) {
            return pDir.deleteRecursively()
        }
        return false
    }

    private val _activePresetFlow = MutableStateFlow(
        prefs.getInt(KEY_ACTIVE_PRESET_ID, 1) to prefs.getInt(KEY_ACTIVE_BINDING_ID, 1)
    )
    val activePresetFlow: StateFlow<Pair<Int, Int>> = _activePresetFlow.asStateFlow()

    fun getActivePresetId(): Int = prefs.getInt(KEY_ACTIVE_PRESET_ID, 1)
    fun getActiveBindingId(): Int = prefs.getInt(KEY_ACTIVE_BINDING_ID, 1)

    fun getActivePreset(): Preset {
        val activePid = prefs.getInt(KEY_ACTIVE_PRESET_ID, 1)
        val activeBid = prefs.getInt(KEY_ACTIVE_BINDING_ID, 1)
        val loaded = loadPreset(activePid, activeBid)
        if (loaded != null) return loaded

        val all = listAllPresets()
        if (all.isNotEmpty()) {
            setActivePreset(all[0], activeBid)
            return all[0]
        }

        val defaultP = createFreeFirePreset(1, 1)
        savePreset(defaultP)
        setActivePreset(defaultP, 1)
        return defaultP
    }

    fun setActivePreset(preset: Preset, bindingId: Int = preset.info.bindingId) {
        preset.info.bindingId = bindingId
        prefs.edit()
            .putInt(KEY_ACTIVE_PRESET_ID, preset.info.presetId)
            .putInt(KEY_ACTIVE_BINDING_ID, bindingId)
            .apply()
        _activePresetFlow.value = preset.info.presetId to bindingId
    }

    fun getPresetForPackage(packageName: String): Preset? {
        val all = listAllPresets()
        return all.firstOrNull { preset ->
            preset.info.targetPackages.any { it.equals(packageName, ignoreCase = true) } ||
            preset.info.packageName.equals(packageName, ignoreCase = true)
        }
    }

    fun getNextPresetId(): Int {
        val all = listAllPresets()
        return if (all.isEmpty()) 1 else (all.maxOf { it.info.presetId } + 1)
    }

    private fun ensureSeeded() {
        val all = listAllPresets()
        if (all.isEmpty()) {
            Log.i(TAG, "No presets found in $baseDir. Seeding Free Fire MAX & BGMI presets...")
            val ff = createFreeFirePreset(1, 1)
            val bgmi = createBgmiPreset(2, 1)
            savePreset(ff)
            savePreset(bgmi)
            setActivePreset(ff, 1)
        }
    }

    private fun writeFile(file: File, content: String) {
        FileOutputStream(file).use { out ->
            out.write(content.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
    }

    private fun readFile(file: File): String {
        return file.readText(StandardCharsets.UTF_8)
    }

    fun createFreeFirePreset(presetId: Int = 1, bindingId: Int = 1): Preset {
        val info = PresetInfo(
            presetId = presetId,
            bindingId = bindingId,
            name = "Free Fire Pro",
            packageName = "com.dts.freefiremax",
            appName = "Free Fire MAX",
            targetPackages = mutableListOf("com.dts.freefiremax", "com.dts.freefireth"),
            controllerType = "Python",
            via = "ADB+Root",
            screenWidth = 2340,
            screenHeight = 1080,
            digitizerMaxX = 1080,
            digitizerMaxY = 2340,
            orientation = 90
        )
        val cam = CameraPin(
            enabled = true,
            toggleKey = "`",
            pivotCenterX = 1650,
            pivotCenterY = 540,
            resetRadius = 260,
            sensitivityX = 1.4f,
            sensitivityY = 1.2f
        )
        val joy = JoystickPin(
            enabled = true,
            centerX = 420,
            centerY = 800,
            radius = 140,
            upKey = "w",
            downKey = "s",
            leftKey = "a",
            rightKey = "d",
            sprintKey = "shift"
        )
        val clicks = mutableListOf(
            ClickPin(key = "mouse_left", label = "Fire", x = 1950, y = 750),
            ClickPin(key = "mouse_right", label = "Scope / ADS", x = 1920, y = 420),
            ClickPin(key = "space", label = "Jump", x = 2150, y = 680),
            ClickPin(key = "c", label = "Crouch", x = 1980, y = 920),
            ClickPin(key = "z", label = "Prone", x = 2180, y = 920),
            ClickPin(key = "r", label = "Reload", x = 1820, y = 920),
            ClickPin(key = "f", label = "Loot 1", x = 1520, y = 480),
            ClickPin(key = "g", label = "Loot 2", x = 1520, y = 560),
            ClickPin(key = "1", label = "Primary", x = 1050, y = 960),
            ClickPin(key = "2", label = "Secondary", x = 1300, y = 960),
            ClickPin(key = "3", label = "Melee", x = 1180, y = 860),
            ClickPin(key = "4", label = "Gloo Wall", x = 750, y = 960),
            ClickPin(key = "5", label = "Medkit", x = 620, y = 960),
            ClickPin(key = "tab", label = "Backpack", x = 200, y = 950),
            ClickPin(key = "m", label = "Map", x = 2200, y = 120)
        )
        return Preset.createWithInitialSlot(info, clicks, joy, cam)
    }

    fun createBgmiPreset(presetId: Int = 2, bindingId: Int = 1): Preset {
        val info = PresetInfo(
            presetId = presetId,
            bindingId = bindingId,
            name = "BGMI Esports",
            packageName = "com.pubg.imobile",
            appName = "Battlegrounds Mobile India",
            targetPackages = mutableListOf("com.pubg.imobile", "com.tencent.ig"),
            controllerType = "Python",
            via = "ADB+Root",
            screenWidth = 2340,
            screenHeight = 1080,
            digitizerMaxX = 1080,
            digitizerMaxY = 2340,
            orientation = 90
        )
        val cam = CameraPin(
            enabled = true,
            toggleKey = "`",
            pivotCenterX = 1680,
            pivotCenterY = 540,
            resetRadius = 260,
            sensitivityX = 1.3f,
            sensitivityY = 1.1f
        )
        val joy = JoystickPin(
            enabled = true,
            centerX = 380,
            centerY = 780,
            radius = 150,
            upKey = "w",
            downKey = "s",
            leftKey = "a",
            rightKey = "d",
            sprintKey = "shift"
        )
        val clicks = mutableListOf(
            ClickPin(key = "mouse_left", label = "Fire", x = 1960, y = 740),
            ClickPin(key = "mouse_right", label = "ADS", x = 1920, y = 430),
            ClickPin(key = "space", label = "Jump", x = 2170, y = 670),
            ClickPin(key = "c", label = "Crouch", x = 2000, y = 910),
            ClickPin(key = "z", label = "Prone", x = 2190, y = 910),
            ClickPin(key = "r", label = "Reload", x = 1800, y = 930),
            ClickPin(key = "q", label = "Peek L", x = 1750, y = 380),
            ClickPin(key = "e", label = "Peek R", x = 1860, y = 380),
            ClickPin(key = "f", label = "Loot", x = 1540, y = 470),
            ClickPin(key = "1", label = "Gun 1", x = 1100, y = 950),
            ClickPin(key = "2", label = "Gun 2", x = 1320, y = 950),
            ClickPin(key = "tab", label = "Bag", x = 190, y = 940),
            ClickPin(key = "m", label = "Map", x = 2210, y = 130)
        )
        return Preset.createWithInitialSlot(info, clicks, joy, cam)
    }

    companion object {
        private const val TAG = "PresetRepository"
        private const val KEY_ACTIVE_PRESET_ID = "active_preset_id"
        private const val KEY_ACTIVE_BINDING_ID = "active_binding_id"
    }
}
