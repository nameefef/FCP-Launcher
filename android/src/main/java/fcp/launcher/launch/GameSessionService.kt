// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.launch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fcp.core.launch.LaunchLog
import fcp.launcher.MainActivity
import fcp.launcher.R
import fcp.shared.PRODUCT_NAME

/**
 * Stays in the foreground while the Minecraft process is running so Android
 * keeps this process, its log pumps, and the child JVM alive.
 */
class GameSessionService : Service() {
    private var generation = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val version = intent?.getStringExtra(EXTRA_VERSION) ?: "Minecraft"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(PRODUCT_NAME)
            .setContentText(getString(R.string.notification_running, version))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val running = LaunchSession.current
        if (running == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val generation = ++this.generation
        Thread {
            val code = try {
                running.awaitExit()
            } catch (e: Exception) {
                LaunchLog.append(running.logFile, "等待进程结束时出错：${e.message}\n")
                -1
            }
            LaunchLog.append(running.logFile, "进程退出，代码 $code\n")
            if (generation == this.generation) {
                LaunchSession.clearIfCurrent(running)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.apply {
            name = "fcp-game-wait"
            isDaemon = true
            start()
        }
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_VERSION = "version"
        private const val CHANNEL_ID = "fcp_game"
        private const val NOTIFICATION_ID = 11
    }
}
