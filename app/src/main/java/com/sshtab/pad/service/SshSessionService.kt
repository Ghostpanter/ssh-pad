package com.sshtab.pad.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sshtab.pad.MainActivity
import com.sshtab.pad.R
import com.sshtab.pad.log.SessionLog
import com.sshtab.pad.ssh.HostProfile
import com.sshtab.pad.ssh.SshSessionManager
import com.sshtab.pad.ssh.TransportKind
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class SshSessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val keepAliveRunning = AtomicBoolean(false)
    @Volatile private var keepAliveThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = binder

    private val binder = object : android.os.Binder() {}

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForegroundSafely()
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
        }
        acquireLocks()
        holdNetwork()
        try {
            when (intent?.action) {
                ACTION_CONNECT -> handleConnect(intent)
                ACTION_DISCONNECT -> handleDisconnect()
                ACTION_ENSURE -> {
                    SessionLog.event("FGS ensure, connected=${SshSessionManager.connected.value} live=${SshSessionManager.isLive()}")
                    if (SshSessionManager.connected.value || SshSessionManager.isLive()) {
                        startKeepAliveLoop()
                    }
                }
                null -> {
                    SessionLog.event("FGS sticky restart")
                    if (!SshSessionManager.isLive()) {
                        loadSavedProfile()?.let { reconnect(it) }
                    } else {
                        startKeepAliveLoop()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onStartCommand", t)
            SshSessionManager.fail("服务异常: ${t.message}")
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        SessionLog.event("FGS onTimeout startId=$startId — re-promote specialUse, NOT stopping")
        startInForegroundSafely()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        SessionLog.event("FGS onTimeout startId=$startId type=$fgsType — re-promote specialUse, NOT stopping")
        startInForegroundSafely()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        SessionLog.event("task removed, keeping session")
        startInForegroundSafely()
        acquireLocks()
        if (SshSessionManager.connected.value) startKeepAliveLoop()
    }

    override fun onDestroy() {
        SessionLog.event("service onDestroy connected=${SshSessionManager.connected.value} live=${SshSessionManager.isLive()}")
        val live = SshSessionManager.connected.value || SshSessionManager.isLive()
        stopKeepAliveLoop()
        releaseLocks()
        releaseNetwork()
        super.onDestroy()
        if (live) {
            try {
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(applicationContext, SshSessionService::class.java).setAction(ACTION_ENSURE),
                )
                SessionLog.event("requested FGS restart after onDestroy")
            } catch (t: Throwable) {
                SessionLog.event("FGS restart failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun handleConnect(intent: Intent) {
        val host = intent.getStringExtra(EXTRA_HOST)
        val kindName = intent.getStringExtra(EXTRA_KIND) ?: TransportKind.SSH.name
        val kind = runCatching { TransportKind.valueOf(kindName) }.getOrDefault(TransportKind.SSH)
        val user = intent.getStringExtra(EXTRA_USER) ?: ""
        if (host.isNullOrBlank()) {
            SshSessionManager.fail("主机为空")
            return
        }
        if (kind == TransportKind.SSH && user.isBlank()) {
            SshSessionManager.fail("主机或用户名为空")
            return
        }
        val profile = HostProfile(
            name = intent.getStringExtra(EXTRA_NAME) ?: user.ifBlank { host },
            host = host,
            port = intent.getIntExtra(EXTRA_PORT, if (kind == TransportKind.TELNET) 23 else 22),
            username = user,
            password = intent.getStringExtra(EXTRA_PASS) ?: "",
            kind = kind,
        )
        saveProfile(profile)
        reconnect(profile)
    }

    private fun reconnect(profile: HostProfile) {
        startKeepAliveLoop()
        thread(name = "session-connect", isDaemon = false) {
            try {
                SshSessionManager.connect(profile)
                updateNotification()
            } catch (t: Throwable) {
                Log.e(TAG, "connect failed", t)
                SshSessionManager.fail("连接失败: ${SshSessionManager.describeError(t)}")
            }
        }
    }

    private fun handleDisconnect() {
        clearSavedProfile()
        stopKeepAliveLoop()
        SshSessionManager.disconnect()
        releaseLocks()
        releaseNetwork()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    private fun startKeepAliveLoop() {
        if (!keepAliveRunning.compareAndSet(false, true)) return
        val t = Thread({
            var ticks = 0
            while (keepAliveRunning.get()) {
                try {
                    Thread.sleep(KEEPALIVE_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!keepAliveRunning.get()) break
                ticks++
                try {
                    if (SshSessionManager.connected.value) {
                        val ok = SshSessionManager.keepAliveOnce()
                        if (ticks % 8 == 0) {
                            SessionLog.event("keepalive tick live=$ok")
                            updateNotification()
                        }
                        if (!ok && !SshSessionManager.isLive()) {
                            SshSessionManager.fail("连接已断开 (keepalive)")
                        }
                    }
                } catch (t: Throwable) {
                    SessionLog.event("keepalive error: ${t.javaClass.simpleName}: ${t.message}")
                    if (!SshSessionManager.isLive()) {
                        SshSessionManager.fail("连接已断开: ${t.javaClass.simpleName}")
                    }
                }
            }
        }, "session-keepalive")
        t.isDaemon = false
        t.priority = Thread.NORM_PRIORITY
        keepAliveThread = t
        t.start()
    }

    private fun stopKeepAliveLoop() {
        keepAliveRunning.set(false)
        keepAliveThread?.interrupt()
        keepAliveThread = null
    }

    private fun acquireLocks() {
        try {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sshpad:cpu").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wakelock", t)
        }
        try {
            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "sshpad:wifi",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wifilock", t)
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wakeLock = null
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wifiLock = null
    }

    private fun holdNetwork() {
        if (networkCallback != null) return
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            // 不要要求 INTERNET：局域网 SSH（192.168.x）所在 Wi‑Fi 可能没有该 capability。
            // 也不要绑到 VPN，否则切到后台时 VPN 一抖会话就断。
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    SessionLog.event("holdNetwork wifi available $network")
                }
                override fun onLost(network: Network) {
                    SessionLog.event("holdNetwork wifi lost $network")
                }
            }
            cm.requestNetwork(request, cb)
            networkCallback = cb
            try {
                val eth = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build()
                cm.requestNetwork(eth, cb)
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            Log.w(TAG, "holdNetwork", t)
            SessionLog.event("holdNetwork failed: ${t.message}")
        }
    }

    private fun releaseNetwork() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {
        }
    }

    private fun startInForegroundSafely() {
        val notification = buildNotification()
        val errors = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 34) {
            val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            try {
                startForeground(NOTIF_ID, notification, special)
                SessionLog.event("startForeground specialUse ok")
                return
            } catch (t: Throwable) {
                errors += "special:${t.javaClass.simpleName}:${t.message}"
            }
            try {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notification)
                SessionLog.event("startForeground no-type fallback ok")
                return
            } catch (t: Throwable) {
                errors += "plain:${t.javaClass.simpleName}"
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
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
            return
        }
        Log.w(TAG, "foreground not started: $errors")
        SessionLog.event("startForeground FAILED $errors")
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "notify", t)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.session_channel),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        setShowBadge(false)
                        description = "保持 SSH / Telnet 会话在后台不断开"
                    }
                )
            }
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val status = SshSessionManager.status.value
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (SshSessionManager.connected.value) "后台保活中 · $status" else getString(R.string.session_notification))
            .setSmallIcon(R.drawable.ic_stat_ssh)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun saveProfile(profile: HostProfile) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RECONNECT, true)
            .putString(KEY_HOST, profile.host)
            .putInt(KEY_PORT, profile.port)
            .putString(KEY_USER, profile.username)
            .putString(KEY_PASS, profile.password)
            .putString(KEY_KIND, profile.kind.name)
            .putString(KEY_NAME, profile.name)
            .apply()
    }

    private fun clearSavedProfile() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RECONNECT, false)
            .remove(KEY_PASS)
            .apply()
    }

    private fun loadSavedProfile(): HostProfile? {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!p.getBoolean(KEY_RECONNECT, false)) return null
        val host = p.getString(KEY_HOST, null) ?: return null
        val kind = runCatching {
            TransportKind.valueOf(p.getString(KEY_KIND, TransportKind.SSH.name)!!)
        }.getOrDefault(TransportKind.SSH)
        return HostProfile(
            name = p.getString(KEY_NAME, host) ?: host,
            host = host,
            port = p.getInt(KEY_PORT, if (kind == TransportKind.TELNET) 23 else 22),
            username = p.getString(KEY_USER, "") ?: "",
            password = p.getString(KEY_PASS, "") ?: "",
            kind = kind,
        )
    }

    companion object {
        private const val TAG = "SshSessionService"
        const val ACTION_CONNECT = "com.sshtab.pad.CONNECT"
        const val ACTION_DISCONNECT = "com.sshtab.pad.DISCONNECT"
        const val ACTION_ENSURE = "com.sshtab.pad.ENSURE"
        const val EXTRA_NAME = "name"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_KIND = "kind"
        private const val CHANNEL_ID = "ssh_session"
        private const val NOTIF_ID = 17
        private const val PREFS = "session"
        private const val KEY_RECONNECT = "reconnect"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
        private const val KEY_KIND = "kind"
        private const val KEY_NAME = "name"
        private const val KEEPALIVE_MS = 8_000L

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
                    thread(name = "session-connect", isDaemon = false) {
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
            requestUnrestrictedBackground(context)
        }

        fun ensureRunning(context: Context) {
            if (!SshSessionManager.connected.value && !SshSessionManager.isLive()) return
            val intent = Intent(context, SshSessionService::class.java).setAction(ACTION_ENSURE)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                SessionLog.event("ensureRunning FGS failed: ${t.message}")
                try {
                    context.startService(intent)
                } catch (t2: Throwable) {
                    SessionLog.event("ensureRunning startService failed: ${t2.message}")
                }
            }
        }

        fun requestUnrestrictedBackground(context: Context) {
            if (Build.VERSION.SDK_INT < 23) return
            try {
                val pm = context.getSystemService(POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "battery opt prompt", t)
                try {
                    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(fallback)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
