package com.vbridge.mapper.data.model

import org.json.JSONArray
import org.json.JSONObject

data class JoystickPin(
    var enabled: Boolean = true,
    var centerX: Int = 420,
    var centerY: Int = 800,
    var radius: Int = 140,
    var upKey: String = "w",
    var downKey: String = "s",
    var leftKey: String = "a",
    var rightKey: String = "d",
    var sprintKey: String = "shift"
) {
    fun toJsonObject(): JSONObject {
        val keysObj = JSONObject().apply {
            put(upKey, "UP")
            put(downKey, "DOWN")
            put(leftKey, "LEFT")
            put(rightKey, "RIGHT")
        }
        val centerArr = JSONArray().apply {
            put(centerX)
            put(centerY)
        }
        return JSONObject().apply {
            put("enabled", enabled)
            put("center", centerArr)
            put("radius", radius)
            put("keys", keysObj)
            put("sprint_key", sprintKey)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): JoystickPin {
            val centerArr = json.optJSONArray("center")
            val cX = centerArr?.optInt(0) ?: 420
            val cY = centerArr?.optInt(1) ?: 800

            val keysObj = json.optJSONObject("keys")
            var uKey = "w"
            var dKey = "s"
            var lKey = "a"
            var rKey = "d"

            if (keysObj != null) {
                keysObj.keys().forEach { k ->
                    when (keysObj.optString(k).uppercase()) {
                        "UP" -> uKey = k
                        "DOWN" -> dKey = k
                        "LEFT" -> lKey = k
                        "RIGHT" -> rKey = k
                    }
                }
            }

            return JoystickPin(
                enabled = json.optBoolean("enabled", true),
                centerX = cX,
                centerY = cY,
                radius = json.optInt("radius", 140),
                upKey = uKey,
                downKey = dKey,
                leftKey = lKey,
                rightKey = rKey,
                sprintKey = json.optString("sprint_key", "shift")
            )
        }
    }
}
