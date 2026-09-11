package com.sshtab.pad.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.sshtab.pad.MainActivity
import com.sshtab.pad.R
import com.sshtab.pad.ssh.HostProfile
import com.sshtab.pad.ssh.SshSessionManager
import kotlin.concurrent.thread

class SshSessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startInForeground()
                acquireWakeLock()
                val profile = HostProfile(
                    name = intent.getStringExtra(EXTRA_NAME) ?: "",
                    host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY,
                    port = intent.getIntExtra(EXTRA_PORT, 22),
                    username = intent.getStringExtra(EXTRA_USER) ?: return START_NOT_STICKY,
                    password = intent.getStringExtra(EXTRA_PASS) ?: "",
                )
                thread(name = "ssh-connect") {
                    try {
                        SshSessionManager.connect(profile)
                    } catch (e: Exception) {
                        val msg = e.message ?: e.javaClass.simpleName
                        android.util.Log.e("SshSessionService", "connect failed", e)
                        SshSessionManager.fail("连接失败: $msg")
                    }
                }
            }
            ACTION_DISCONNECT -> {
                SshSessionManager.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sshpad:session").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.session_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.session_notification))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.sshtab.pad.CONNECT"
        const val ACTION_DISCONNECT = "com.sshtab.pad.DISCONNECT"
        const val EXTRA_NAME = "name"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        private const val CHANNEL_ID = "ssh_session"
        private const val NOTIF_ID = 17
    }
}
