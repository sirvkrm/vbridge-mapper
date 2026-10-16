package com.vbridge.mapper.data.model

import org.json.JSONArray
import org.json.JSONObject

data class CameraPin(
    var enabled: Boolean = true,
    var toggleKey: String = "`",
    var pivotCenterX: Int = 1650,
    var pivotCenterY: Int = 540,
    var resetRadius: Int = 260,
    var sensitivityX: Float = 1.4f,
    var sensitivityY: Float = 1.2f
) {
    fun toJsonObject(): JSONObject {
        val pivotArr = JSONArray().apply {
            put(pivotCenterX)
            put(pivotCenterY)
        }
        return JSONObject().apply {
            put("enabled", enabled)
            put("toggle_key", toggleKey)
            put("pivot_center", pivotArr)
            put("reset_radius", resetRadius)
            put("sensitivity_x", sensitivityX.toDouble())
            put("sensitivity_y", sensitivityY.toDouble())
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): CameraPin {
            val pivotArr = json.optJSONArray("pivot_center")
            val pX = pivotArr?.optInt(0) ?: 1650
            val pY = pivotArr?.optInt(1) ?: 540

            return CameraPin(
                enabled = json.optBoolean("enabled", true),
                toggleKey = json.optString("toggle_key", "`"),
                pivotCenterX = pX,
                pivotCenterY = pY,
                resetRadius = json.optInt("reset_radius", 260),
                sensitivityX = json.optDouble("sensitivity_x", 1.4).toFloat(),
                sensitivityY = json.optDouble("sensitivity_y", 1.2).toFloat()
            )
        }
    }
}
