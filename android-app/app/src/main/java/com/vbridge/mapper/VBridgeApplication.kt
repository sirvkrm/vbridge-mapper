package com.vbridge.mapper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.vbridge.mapper.data.repository.PresetRepository
import com.vbridge.mapper.network.WebSocketBridgeClient

class VBridgeApplication : Application() {

    lateinit var presetRepository: PresetRepository
        private set

    lateinit var wsClient: WebSocketBridgeClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        presetRepository = PresetRepository(this)
        wsClient = WebSocketBridgeClient(this)
        wsClient.onSyncRequested = {
            try {
                val active = presetRepository.getActivePreset()
                val slot = active.activeSlot
                val path = "/sdcard/VbridgeBind/preset${active.info.presetId}/keybinding${slot.id}/"
                wsClient.sendProfileActivated(
                    presetId = active.info.presetId,
                    bindingId = slot.id,
                    presetName = active.info.name,
                    bindingName = slot.name,
                    packageName = active.info.packageName,
                    controllerType = active.info.controllerType,
                    via = active.info.via,
                    path = path,
                    profileJson = active.toFullProfileJson()
                )
            } catch (e: Exception) {
                android.util.Log.e("VBridgeApp", "Failed to sync active preset on request: ${e.message}", e)
            }
        }
        wsClient.connect()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val overlayChannel = NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }

            val gameChannel = NotificationChannel(
                CHANNEL_GAME_DETECTION,
                "Game Detection HUD",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when a registered game is opened"
            }

            manager?.createNotificationChannel(overlayChannel)
            manager?.createNotificationChannel(gameChannel)
        }
    }

    companion object {
        const val CHANNEL_OVERLAY = "vbridge_overlay_service_channel"
        const val CHANNEL_GAME_DETECTION = "vbridge_game_detection_channel"
        const val NOTIFICATION_ID_OVERLAY = 1001

        lateinit var instance: VBridgeApplication
            private set
    }
}
