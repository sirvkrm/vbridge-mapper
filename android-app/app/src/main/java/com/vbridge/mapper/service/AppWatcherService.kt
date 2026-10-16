package com.vbridge.mapper.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vbridge.mapper.R
import com.vbridge.mapper.VBridgeApplication
import com.vbridge.mapper.ui.MainActivity
import com.vbridge.mapper.utils.PermissionHelper
import kotlinx.coroutines.*

class AppWatcherService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastForegroundPackage: String? = null
    private var pollingJob: Job? = null

    // Recognized games map (package -> Game Name)
    private val knownGames = mapOf(
        "com.dts.freefiremax" to "Free Fire MAX",
        "com.dts.freefireth" to "Free Fire",
        "com.pubg.imobile" to "BGMI (Battlegrounds Mobile)",
        "com.tencent.ig" to "PUBG Mobile Global",
        "com.activision.callofduty.shooter" to "Call of Duty: Mobile"
    )

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        startForegroundMonitoring()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, VBridgeApplication.CHANNEL_GAME_DETECTION)
            .setContentTitle("VBridge Game Watcher Active")
            .setContentText("Monitoring game launches for auto preset switching")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(NOTIFICATION_ID_WATCHER, notification)
    }

    private fun startForegroundMonitoring() {
        pollingJob = serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            while (isActive) {
                if (PermissionHelper.hasUsageStatsPermission(this@AppWatcherService) && usageStatsManager != null) {
                    val currentPkg = getForegroundPackage(usageStatsManager)
                    if (currentPkg != null && currentPkg != lastForegroundPackage) {
                        lastForegroundPackage = currentPkg
                        val gameName = knownGames[currentPkg]
                        if (gameName != null) {
                            Log.i(TAG, "🎯 Game launched: $gameName ($currentPkg)")
                            notifyGameLaunched(currentPkg, gameName)
                        }
                    }
                }
                delay(1200) // Check every 1.2s (negligible CPU/battery impact)
            }
        }
    }

    private fun getForegroundPackage(usageStatsManager: UsageStatsManager): String? {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 10000

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var lastResumedPkg: String? = null

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastResumedPkg = event.packageName
            }
        }
        return lastResumedPkg
    }

    private fun notifyGameLaunched(packageName: String, gameName: String) {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_GAME_DETECTED
            putExtra(OverlayService.EXTRA_GAME_PACKAGE, packageName)
            putExtra(OverlayService.EXTRA_GAME_NAME, gameName)
        }
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        pollingJob?.cancel()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "AppWatcherService"
        private const val NOTIFICATION_ID_WATCHER = 1002

        var isRunning = false
            private set
    }
}
