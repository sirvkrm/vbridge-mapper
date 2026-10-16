package com.vbridge.mapper.data.model

import org.json.JSONArray
import org.json.JSONObject

data class KeybindingSlot(
    val id: Int = 1,
    var name: String = "Slot #$id",
    var isConfigured: Boolean = false,
    var isLocked: Boolean = false,
    var pinSizeDp: Int = 48,
    var pinOpacity: Float = 0.85f,
    val clicks: MutableList<ClickPin> = mutableListOf(),
    var joystick: JoystickPin = JoystickPin(),
    var camera: CameraPin = CameraPin()
) {
    fun toJsonObject(): JSONObject {
        val clickArr = JSONArray()
        for (c in clicks) {
            clickArr.put(c.toJsonObject())
        }
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("is_configured", isConfigured)
            put("is_locked", isLocked)
            put("pin_size_dp", pinSizeDp)
            put("pin_opacity", pinOpacity.toDouble())
            put("clicks", clickArr)
            put("joystick", joystick.toJsonObject())
            put("camera", camera.toJsonObject())
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): KeybindingSlot {
            val id = json.optInt("id", 1)
            val name = json.optString("name", "Slot #$id")
            val isConfigured = json.optBoolean("is_configured", false)
            val isLocked = json.optBoolean("is_locked", false)
            val pinSizeDp = json.optInt("pin_size_dp", 48)
            val pinOpacity = json.optDouble("pin_opacity", 0.85).toFloat()

            val clicks = mutableListOf<ClickPin>()
            val clickArr = json.optJSONArray("clicks")
            if (clickArr != null) {
                for (i in 0 until clickArr.length()) {
                    val obj = clickArr.optJSONObject(i) ?: continue
                    clicks.add(ClickPin.fromJsonObject(obj))
                }
            }

            val joy = json.optJSONObject("joystick")?.let { JoystickPin.fromJsonObject(it) } ?: JoystickPin()
            val cam = json.optJSONObject("camera")?.let { CameraPin.fromJsonObject(it) } ?: CameraPin()

            return KeybindingSlot(
                id = id,
                name = name,
                isConfigured = isConfigured,
                isLocked = isLocked,
                pinSizeDp = pinSizeDp,
                pinOpacity = pinOpacity,
                clicks = clicks,
                joystick = joy,
                camera = cam
            )
        }

        fun createBlank(id: Int): KeybindingSlot {
            return KeybindingSlot(
                id = id,
                name = "Slot #$id",
                isConfigured = false,
                isLocked = false,
                clicks = mutableListOf(),
                joystick = JoystickPin(enabled = false),
                camera = CameraPin(enabled = false)
            )
        }
    }
}
