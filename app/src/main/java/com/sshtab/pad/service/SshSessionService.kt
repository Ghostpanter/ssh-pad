package com.sshtab.pad.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sshtab.pad.MainActivity
import com.sshtab.pad.R
import com.sshtab.pad.ssh.HostProfile
import com.sshtab.pad.ssh.SshSessionManager
import com.sshtab.pad.ssh.TransportKind
import kotlin.concurrent.thread

class SshSessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForegroundSafely()
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
        }
        try {
            when (intent?.action) {
                ACTION_CONNECT -> {
                    acquireWakeLock()
                    val host = intent.getStringExtra(EXTRA_HOST)
                    val kindName = intent.getStringExtra(EXTRA_KIND) ?: TransportKind.SSH.name
                    val kind = runCatching { TransportKind.valueOf(kindName) }.getOrDefault(TransportKind.SSH)
                    val user = intent.getStringExtra(EXTRA_USER) ?: ""
                    if (host.isNullOrBlank()) {
                        SshSessionManager.fail("主机为空")
                        return START_NOT_STICKY
                    }
                    if (kind == TransportKind.SSH && user.isBlank()) {
                        SshSessionManager.fail("主机或用户名为空")
                        return START_NOT_STICKY
                    }
                    val profile = HostProfile(
                        name = intent.getStringExtra(EXTRA_NAME) ?: user.ifBlank { host },
                        host = host,
                        port = intent.getIntExtra(EXTRA_PORT, if (kind == TransportKind.TELNET) 23 else 22),
                        username = user,
                        password = intent.getStringExtra(EXTRA_PASS) ?: "",
                        kind = kind,
                    )
                    thread(name = "ssh-connect", isDaemon = true) {
                        try {
                            SshSessionManager.connect(profile)
                        } catch (t: Throwable) {
                            Log.e(TAG, "connect failed", t)
                            SshSessionManager.fail("连接失败: ${SshSessionManager.describeError(t)}")
                        }
                    }
                }
                ACTION_DISCONNECT -> {
                    SshSessionManager.disconnect()
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Throwable) {
                    }
                    stopSelf()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onStartCommand", t)
            SshSessionManager.fail("服务异常: ${t.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
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

    private fun startInForegroundSafely() {
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
            .setSmallIcon(R.drawable.ic_stat_ssh)
            .setContentIntent(pi)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val errors = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                return
            } catch (t: Throwable) {
                errors += t.javaClass.simpleName
            }
            try {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notification)
                return
            } catch (t: Throwable) {
                errors += t.javaClass.simpleName
            }
        } else {
            startForeground(NOTIF_ID, notification)
        }
        Log.w(TAG, "foreground not started: $errors")
    }

    companion object {
        private const val TAG = "SshSessionService"
        const val ACTION_CONNECT = "com.sshtab.pad.CONNECT"
        const val ACTION_DISCONNECT = "com.sshtab.pad.DISCONNECT"
        const val EXTRA_NAME = "name"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_KIND = "kind"
        private const val CHANNEL_ID = "ssh_session"
        private const val NOTIF_ID = 17

        fun startConnect(context: Context, profile: HostProfile) {
            val intent = Intent(context, SshSessionService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_NAME, profile.name)
                putExtra(EXTRA_HOST, profile.host)
                putExtra(EXTRA_PORT, profile.port)
                putExtra(EXTRA_USER, profile.username)
                putExtra(EXTRA_PASS, profile.password)
                putExtra(EXTRA_KIND, profile.kind.name)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                Log.w(TAG, "startForegroundService failed, fallback startService", t)
                try {
                    context.startService(intent)
                } catch (t2: Throwable) {
                    Log.e(TAG, "startService failed", t2)
                    thread(name = "ssh-connect", isDaemon = true) {
                        try {
                            SshSessionManager.connect(profile)
                        } catch (t3: Throwable) {
                            SshSessionManager.fail(
                                "连接失败: ${SshSessionManager.describeError(t3)}"
                            )
                        }
                    }
                }
            }
        }
    }
}
