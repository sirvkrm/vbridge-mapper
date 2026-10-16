package com.vbridge.mapper.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.asImageBitmap

data class AppEntry(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isGame: Boolean
)

object InstalledAppHelper {

    private val KNOWN_GAME_PKGS = setOf(
        "com.dts.freefiremax",
        "com.dts.freefireth",
        "com.pubg.imobile",
        "com.tencent.ig",
        "com.activision.callofduty.shooter",
        "com.epicgames.fortnite",
        "com.ea.gp.apexmobile",
        "com.riotgames.league.wildrift"
    )

    fun getInstalledApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolved = pm.queryIntentActivities(mainIntent, 0)
        val list = mutableListOf<AppEntry>()
        val seen = mutableSetOf<String>()

        for (resolveInfo in resolved) {
            val pkg = resolveInfo.activityInfo.packageName
            if (seen.contains(pkg)) continue
            seen.add(pkg)

            val label = resolveInfo.loadLabel(pm).toString()
            val icon = try {
                resolveInfo.loadIcon(pm)
            } catch (_: Exception) {
                null
            }

            val isGame = KNOWN_GAME_PKGS.contains(pkg) ||
                    label.contains("game", ignoreCase = true) ||
                    label.contains("free fire", ignoreCase = true) ||
                    label.contains("bgmi", ignoreCase = true) ||
                    label.contains("pubg", ignoreCase = true)

            list.add(AppEntry(packageName = pkg, appName = label, icon = icon, isGame = isGame))
        }

        // Sort games first, then alphabetically
        return list.sortedWith(compareByDescending<AppEntry> { it.isGame }.thenBy { it.appName.lowercase() })
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }

    fun drawableToImageBitmap(drawable: Drawable?, width: Int = 120, height: Int = 120): androidx.compose.ui.graphics.ImageBitmap? {
        if (drawable == null) return null
        return try {
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}
