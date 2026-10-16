package com.vbridge.mapper.data.model

import org.json.JSONArray
import org.json.JSONObject

data class PresetInfo(
    var presetId: Int = 1,
    var bindingId: Int = 1,
    var name: String = "Preset#1 - FreeFire Pro",
    var packageName: String = "com.dts.freefiremax",
    var appName: String = "Free Fire MAX",
    var targetPackages: MutableList<String> = mutableListOf("com.dts.freefiremax"),
    var controllerType: String = "Python",
    var via: String = "ADB+Root",
    var screenWidth: Int = 2340,
    var screenHeight: Int = 1080,
    var digitizerMaxX: Int = 1080,
    var digitizerMaxY: Int = 2340,
    var orientation: Int = 90,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun toJsonObject(): JSONObject {
        val pkgsArr = JSONArray()
        for (pkg in targetPackages) {
            pkgsArr.put(pkg)
        }
        return JSONObject().apply {
            put("preset_id", presetId)
            put("binding_id", bindingId)
            put("name", name)
            put("package_name", packageName)
            put("app_name", appName)
            put("target_packages", pkgsArr)
            put("controller_type", controllerType)
            put("via", via)
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
            put("digitizer_max_x", digitizerMaxX)
            put("digitizer_max_y", digitizerMaxY)
            put("orientation", orientation)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): PresetInfo {
            val pkgs = mutableListOf<String>()
            val pkgsArr = json.optJSONArray("target_packages")
            if (pkgsArr != null) {
                for (i in 0 until pkgsArr.length()) {
                    pkgs.add(pkgsArr.optString(i))
                }
            }
            val mainPkg = json.optString("package_name", "com.dts.freefiremax")
            if (pkgs.isEmpty() && mainPkg.isNotEmpty()) {
                pkgs.add(mainPkg)
            }

            return PresetInfo(
                presetId = json.optInt("preset_id", 1),
                bindingId = json.optInt("binding_id", 1),
                name = json.optString("name", "Default Preset"),
                packageName = mainPkg,
                appName = json.optString("app_name", "Free Fire MAX"),
                targetPackages = pkgs,
                controllerType = json.optString("controller_type", "Python"),
                via = json.optString("via", "ADB+Root"),
                screenWidth = json.optInt("screen_width", 2340),
                screenHeight = json.optInt("screen_height", 1080),
                digitizerMaxX = json.optInt("digitizer_max_x", 1080),
                digitizerMaxY = json.optInt("digitizer_max_y", 2340),
                orientation = json.optInt("orientation", 90),
                createdAt = json.optLong("created_at", System.currentTimeMillis()),
                updatedAt = json.optLong("updated_at", System.currentTimeMillis())
            )
        }
    }
}
