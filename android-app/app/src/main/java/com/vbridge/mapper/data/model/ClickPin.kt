package com.vbridge.mapper.data.model

import org.json.JSONObject
import java.util.UUID

data class ClickPin(
    val id: String = UUID.randomUUID().toString(),
    var key: String = "space",
    var label: String = "Key",
    var x: Int = 0,
    var y: Int = 0,
    var type: String = "click",
    var sizeDp: Int = 48,
    var opacity: Float = 0.85f
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("key", key)
            put("label", label)
            put("x", x)
            put("y", y)
            put("type", type)
            put("size_dp", sizeDp)
            put("opacity", opacity.toDouble())
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): ClickPin {
            return ClickPin(
                id = json.optString("id", UUID.randomUUID().toString()),
                key = json.optString("key", "space"),
                label = json.optString("label", "Key"),
                x = json.optInt("x", 0),
                y = json.optInt("y", 0),
                type = json.optString("type", "click"),
                sizeDp = json.optInt("size_dp", 48),
                opacity = json.optDouble("opacity", 0.85).toFloat()
            )
        }
    }
}
