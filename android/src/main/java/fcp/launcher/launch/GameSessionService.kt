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
import android.os.Process
import androidx.core.app.NotificationCompat
import fcp.launcher.MainActivity
import fcp.launcher.R
import fcp.shared.PRODUCT_NAME
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the in-process JVM. Declared with `android:process=":game"` so
 * `JLI_Launch` and the JVM's `exit()` take down this process and leave the
 * launcher UI running. A foreground notification keeps Android from treating
 * the game as a cached background process.
 */
class GameSessionService : Service() {
    private val launching = AtomicBoolean(false)

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
        if (intent?.action == ACTION_STOP) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            stopSelf()
            Process.killProcess(Process.myPid())
            return START_NOT_STICKY
        }
        val version = intent?.getStringExtra(EXTRA_VERSION) ?: "Minecraft"
        startInForeground(version)
        val specPath = intent?.getStringExtra(EXTRA_SPEC)
        if (specPath.isNullOrBlank() || !File(specPath).isFile) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Process.killProcess(Process.myPid())
            return START_NOT_STICKY
        }
        if (!launching.compareAndSet(false, true)) {
            return START_NOT_STICKY
        }
        Thread {
            try {
                InProcessJvm.run(File(specPath))
            } finally {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {
                }
                stopSelf()
                Process.killProcess(Process.myPid())
            }
        }.apply {
            name = "fcp-jli"
            start()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(version: String) {
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
    }

    companion object {
        const val EXTRA_VERSION = "version"
        const val EXTRA_SPEC = "spec"
        const val ACTION_LAUNCH = "fcp.launcher.launch.LAUNCH"
        const val ACTION_STOP = "fcp.launcher.launch.STOP"
        private const val CHANNEL_ID = "fcp_game"
        private const val NOTIFICATION_ID = 11
    }
}
