package com.vbridge.mapper.data.model

import org.json.JSONArray
import org.json.JSONObject

data class Preset(
    var info: PresetInfo = PresetInfo(),
    val slots: MutableList<KeybindingSlot> = mutableListOf()
) {
    init {
        ensureThreeSlots()
    }

    private fun ensureThreeSlots() {
        while (slots.size < 3) {
            val nextId = slots.size + 1
            slots.add(KeybindingSlot.createBlank(nextId))
        }
    }

    fun addNewSlot(): KeybindingSlot? {
        if (slots.size >= 20) return null
        val nextId = (slots.maxOfOrNull { it.id } ?: 0) + 1
        val newSlot = KeybindingSlot.createBlank(nextId).apply {
            name = "Slot #$nextId"
        }
        slots.add(newSlot)
        return newSlot
    }

    val activeSlot: KeybindingSlot
        get() {
            ensureThreeSlots()
            return slots.find { it.id == info.bindingId } ?: slots[0]
        }

    val clicks: MutableList<ClickPin>
        get() = activeSlot.clicks

    var joystick: JoystickPin
        get() = activeSlot.joystick
        set(value) { activeSlot.joystick = value }

    var camera: CameraPin
        get() = activeSlot.camera
        set(value) { activeSlot.camera = value }

    var isLocked: Boolean
        get() = activeSlot.isLocked
        set(value) { activeSlot.isLocked = value }

    fun clicksToJsonArray(): JSONArray {
        val arr = JSONArray()
        for (click in clicks) {
            arr.put(click.toJsonObject())
        }
        return arr
    }

    fun joystickToJson(): JSONObject = joystick.toJsonObject()

    fun cameraToJson(): JSONObject = camera.toJsonObject()

    fun infoToJson(): JSONObject = info.toJsonObject()

    /**
     * Converts to the unified format consumed by the Windows Trajectory Client
     * (matching profiles/freefire.json & profiles/bgmi.json).
     */
    fun toFullProfileJson(): JSONObject {
        val root = JSONObject()
        root.put("app_name", info.appName)
        root.put("package_name", info.packageName)
        root.put("controller_type", info.controllerType)
        root.put("via", info.via)

        val display = JSONObject().apply {
            put("screen_width", info.screenWidth)
            put("screen_height", info.screenHeight)
            put("digitizer_max_x", info.digitizerMaxX)
            put("digitizer_max_y", info.digitizerMaxY)
            put("orientation", info.orientation)
        }
        root.put("display", display)
        root.put("camera", camera.toJsonObject())
        root.put("joystick", joystick.toJsonObject())

        val mappingsArr = JSONArray()
        for (c in clicks) {
            val item = JSONObject().apply {
                put("key", c.key)
                put("label", c.label)
                put("x", c.x)
                put("y", c.y)
                put("type", c.type)
                put("size_dp", c.sizeDp)
                put("opacity", c.opacity.toDouble())
            }
            mappingsArr.put(item)
        }
        root.put("key_mappings", mappingsArr)
        return root
    }

    fun copy(
        newPresetId: Int = info.presetId,
        newBindingId: Int = info.bindingId,
        newName: String = info.name
    ): Preset {
        val newInfo = info.copy(
            presetId = newPresetId,
            bindingId = newBindingId,
            name = newName,
            updatedAt = System.currentTimeMillis()
        )
        val newSlots = slots.map { slot ->
            slot.copy(
                clicks = slot.clicks.map { it.copy(id = java.util.UUID.randomUUID().toString()) }.toMutableList(),
                joystick = slot.joystick.copy(),
                camera = slot.camera.copy()
            )
        }.toMutableList()
        return Preset(newInfo, newSlots)
    }

    companion object {
        fun createWithInitialSlot(
            info: PresetInfo,
            clicks: MutableList<ClickPin>,
            joystick: JoystickPin,
            camera: CameraPin
        ): Preset {
            val slot1 = KeybindingSlot(
                id = info.bindingId,
                name = "Keybinding #1",
                isConfigured = true,
                isLocked = false,
                clicks = clicks,
                joystick = joystick,
                camera = camera
            )
            val slot2 = KeybindingSlot.createBlank(2)
            val slot3 = KeybindingSlot.createBlank(3)
            return Preset(info, mutableListOf(slot1, slot2, slot3))
        }
    }
}
