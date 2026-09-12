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
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sshtab.pad.MainActivity
import com.sshtab.pad.R
import com.sshtab.pad.log.SessionLog
import com.sshtab.pad.ssh.AuthMethod
import com.sshtab.pad.ssh.HostProfile
import com.sshtab.pad.ssh.SessionHub
import com.sshtab.pad.ssh.TransportKind
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class SshSessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val keepAliveRunning = AtomicBoolean(false)
    @Volatile private var keepAliveThread: Thread? = null
    private var player: KeepAlivePlayer? = null
    private var floatBubble: KeepAliveFloat? = null
    private val ipcThread = HandlerThread("session-ipc").apply {
        start()
        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
    }
    private val listeners = CopyOnWriteArrayList<Messenger>()
    private val messenger = Messenger(object : Handler(ipcThread.looper) {
        override fun handleMessage(msg: Message) = handleIpc(msg)
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onCreate() {
        super.onCreate()
        SessionLog.event("session process onCreate pid=${Process.myPid()}")
        SessionHub.outputSink = { sid, bytes ->
            if (sid == SessionHub.activeId) broadcastOutput(bytes)
        }
        SessionHub.onChange = {
            broadcastSessions()
            broadcastStatus()
            broadcastFiles()
            broadcastStats()
            updateNotification()
        }
        player = KeepAlivePlayer(this)
        floatBubble = KeepAliveFloat(this)
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
                    SessionLog.event("FGS ensure, live=${SessionHub.anyLive()} n=${SessionHub.list().size}")
                    if (SessionHub.anyLive()) {
                        startKeepAliveLoop()
                        player?.start()
                        floatBubble?.show()
                    }
                }
                null -> {
                    SessionLog.event("FGS sticky restart")
                    if (!SessionHub.anyLive()) {
                        loadSavedProfile()?.let { openSession(it) }
                    } else {
                        startKeepAliveLoop()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onStartCommand", t)
            SessionHub.active()?.fail("服务异常: ${t.message}")
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
        if (SessionHub.anyLive()) startKeepAliveLoop()
    }

    override fun onDestroy() {
        SessionLog.event("service onDestroy live=${SessionHub.anyLive()} n=${SessionHub.list().size}")
        val live = SessionHub.anyLive()
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
            SessionLog.event("connect rejected: empty host")
            return
        }
        if (kind == TransportKind.SSH && user.isBlank()) {
            SessionLog.event("connect rejected: empty user")
            return
        }
        val auth = runCatching {
            AuthMethod.valueOf(intent.getStringExtra(EXTRA_AUTH) ?: AuthMethod.PASSWORD.name)
        }.getOrDefault(AuthMethod.PASSWORD)
        val profile = HostProfile(
            name = intent.getStringExtra(EXTRA_NAME) ?: user.ifBlank { host },
            host = host,
            port = intent.getIntExtra(EXTRA_PORT, if (kind == TransportKind.TELNET) 23 else 22),
            username = user,
            password = intent.getStringExtra(EXTRA_PASS) ?: "",
            kind = kind,
            auth = auth,
            privateKey = intent.getStringExtra(EXTRA_KEY) ?: "",
            passphrase = intent.getStringExtra(EXTRA_PASSPHRASE) ?: "",
        )
        saveProfile(profile)
        openSession(profile)
    }

    private fun openSession(profile: HostProfile) {
        startKeepAliveLoop()
        thread(name = "session-connect", isDaemon = false) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
                SessionHub.open(profile)
                player?.start()
                floatBubble?.show()
                sendReset(null)
                replayScrollback(null)
                try { SessionHub.refreshAllStats() } catch (_: Throwable) {}
                broadcastSessions()
                broadcastStatus()
                broadcastFiles()
                broadcastStats()
                updateNotification()
            } catch (t: Throwable) {
                Log.e(TAG, "connect failed", t)
                SessionLog.event("connect failed: ${t.javaClass.simpleName}: ${t.message}")
                SessionHub.active()?.fail("连接失败: ${t.message}")
            }
        }
    }

    private fun handleDisconnect() {
        clearSavedProfile()
        SessionHub.closeAll()
        stopKeepAliveLoop()
        player?.stop()
        floatBubble?.hide()
        broadcastSessions()
        broadcastStatus()
        releaseLocks()
        releaseNetwork()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    private fun maybeStopIfIdle() {
        if (SessionHub.anyLive() || SessionHub.list().isNotEmpty()) {
            broadcastSessions()
            broadcastStatus()
            updateNotification()
            return
        }
        handleDisconnect()
    }

    private fun startKeepAliveLoop() {
        if (!keepAliveRunning.compareAndSet(false, true)) return
        val t = Thread({
            var lastKeep = 0L
            var lastStats = 0L
            var lastLog = 0L
            while (keepAliveRunning.get()) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                try {
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!keepAliveRunning.get()) break
                val now = android.os.SystemClock.elapsedRealtime()
                val dt = now - t0
                if (dt > 20_000L) {
                    SessionLog.event("keepalive freeze dt=${dt}ms — restart audio/FGS")
                    try {
                        startInForegroundSafely()
                        player?.start()
                        floatBubble?.show()
                    } catch (t: Throwable) {
                        SessionLog.event("unfreeze recover: ${t.message}")
                    }
                }
                try {
                    if (SessionHub.anyLive()) {
                        if (now - lastKeep >= KEEPALIVE_MS) {
                            lastKeep = now
                            val ok = SessionHub.keepAliveAll()
                            if (now - lastLog >= 16_000L || dt > 20_000L) {
                                lastLog = now
                                SessionLog.event("keepalive tick live=$ok n=${SessionHub.list().size} dt=${dt}ms")
                                updateNotification()
                                broadcastStatus()
                            }
                        }
                        val intervalMs = com.sshtab.pad.ui.AppSettings.statusSecNow().coerceIn(3, 30) * 1000L
                        if (now - lastStats >= intervalMs) {
                            lastStats = now
                            try { SessionHub.refreshAllStats() } catch (_: Throwable) {}
                            broadcastStats()
                        }
                    }
                } catch (t: Throwable) {
                    SessionLog.event("keepalive error: ${t.javaClass.simpleName}: ${t.message}")
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
            SessionLog.event("holdNetwork requestNetwork wifi ok")
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
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        SessionLog.event("defaultNetwork available $network")
                    }
                }
                if (Build.VERSION.SDK_INT >= 24) {
                    cm.registerDefaultNetworkCallback(cb)
                    networkCallback = cb
                    SessionLog.event("holdNetwork fallback registerDefaultNetworkCallback")
                }
            } catch (t2: Throwable) {
                SessionLog.event("holdNetwork fallback failed: ${t2.message}")
            }
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
            val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            try {
                startForeground(NOTIF_ID, notification, special or media)
                SessionLog.event("startForeground specialUse|mediaPlayback ok")
                return
            } catch (t: Throwable) {
                errors += "combo:${t.javaClass.simpleName}:${t.message}"
            }
            try {
                startForeground(NOTIF_ID, notification, media)
                SessionLog.event("startForeground mediaPlayback ok")
                return
            } catch (t: Throwable) {
                errors += "media:${t.javaClass.simpleName}"
            }
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
                        NotificationManager.IMPORTANCE_LOW,
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
        val status = SessionHub.active()?.status?.value ?: "会话保活中"
        val n = SessionHub.list().size
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (n > 1) "SSH 会话 ×$n" else "SSH 会话运行中")
            .setContentText(if (SessionHub.anyLive()) status else getString(R.string.session_notification))
            .setSmallIcon(R.drawable.ic_stat_ssh)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
        return builder.build()
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

    private fun handleIpc(msg: Message) {
        val replyTo = msg.replyTo
        if (replyTo != null && !listeners.contains(replyTo)) listeners.add(replyTo)
        try {
            when (msg.what) {
                SessionIpc.MSG_SUBSCRIBE -> {
                    sendReset(replyTo)
                    replayScrollback(replyTo)
                    broadcastSessions()
                    broadcastStatus()
                    broadcastFiles()
                    broadcastStats()
                    broadcastLog(replyTo)
                }
                SessionIpc.MSG_UNSUBSCRIBE -> {
                    msg.replyTo?.let { listeners.remove(it) }
                }
                SessionIpc.MSG_CONNECT -> {
                    val b = msg.data
                    val kind = runCatching {
                        TransportKind.valueOf(b.getString(SessionIpc.EXTRA_KIND) ?: "SSH")
                    }.getOrDefault(TransportKind.SSH)
                    val auth = runCatching {
                        AuthMethod.valueOf(b.getString(SessionIpc.EXTRA_AUTH) ?: "PASSWORD")
                    }.getOrDefault(AuthMethod.PASSWORD)
                    val profile = HostProfile(
                        name = b.getString(SessionIpc.EXTRA_NAME) ?: "",
                        host = b.getString(SessionIpc.EXTRA_HOST) ?: "",
                        port = b.getInt(SessionIpc.EXTRA_PORT, 22),
                        username = b.getString(SessionIpc.EXTRA_USER) ?: "",
                        password = b.getString(SessionIpc.EXTRA_PASS) ?: "",
                        kind = kind,
                        auth = auth,
                        privateKey = b.getString(SessionIpc.EXTRA_KEY) ?: "",
                        passphrase = b.getString(SessionIpc.EXTRA_PASSPHRASE) ?: "",
                    )
                    saveProfile(profile)
                    openSession(profile)
                }
                SessionIpc.MSG_DISCONNECT -> handleDisconnect()
                SessionIpc.MSG_SWITCH -> {
                    val sid = msg.data.getString(SessionIpc.EXTRA_SID) ?: return
                    SessionHub.switchTo(sid)
                    sendReset(null)
                    replayScrollback(null)
                    broadcastSessions()
                    broadcastStatus()
                    broadcastFiles()
                }
                SessionIpc.MSG_CLOSE -> {
                    val sid = msg.data.getString(SessionIpc.EXTRA_SID) ?: return
                    SessionHub.close(sid)
                    sendReset(null)
                    replayScrollback(null)
                    maybeStopIfIdle()
                }
                SessionIpc.MSG_CLEAR_LOG -> {
                    SessionLog.clear()
                    broadcastLog(null)
                }
                SessionIpc.MSG_EVENT -> {
                    val text = msg.data.getString(SessionIpc.EXTRA_TEXT) ?: return
                    SessionLog.event(text)
                    broadcastLog(null)
                }
                SessionIpc.MSG_WRITE -> {
                    val bytes = msg.data.getByteArray(SessionIpc.EXTRA_BYTES) ?: return
                    SessionHub.active()?.write(bytes)
                }
                SessionIpc.MSG_RESIZE -> {
                    SessionHub.active()?.resize(
                        msg.data.getInt(SessionIpc.EXTRA_COLS),
                        msg.data.getInt(SessionIpc.EXTRA_ROWS),
                    )
                }
                SessionIpc.MSG_LIST -> {
                    try {
                        SessionHub.active()?.listRemote(msg.data.getString(SessionIpc.EXTRA_PATH) ?: ".")
                        broadcastFiles()
                    } catch (t: Throwable) {
                        SessionLog.event("list failed: ${t.message}")
                    }
                }
                SessionIpc.MSG_MKDIR -> {
                    try {
                        SessionHub.active()?.mkdir(msg.data.getString(SessionIpc.EXTRA_NAME) ?: return)
                        broadcastFiles()
                    } catch (t: Throwable) {
                        SessionLog.event("mkdir failed: ${t.message}")
                    }
                }
                SessionIpc.MSG_DELETE -> {
                    try {
                        SessionHub.active()?.deleteRemote(
                            msg.data.getString(SessionIpc.EXTRA_NAME) ?: return,
                            msg.data.getBoolean(SessionIpc.EXTRA_IS_DIR),
                        )
                        broadcastFiles()
                    } catch (t: Throwable) {
                        SessionLog.event("delete failed: ${t.message}")
                    }
                }
                SessionIpc.MSG_DOWNLOAD -> {
                    val id = msg.arg1
                    try {
                        val name = msg.data.getString(SessionIpc.EXTRA_NAME) ?: error("name")
                        val dest = File(msg.data.getString(SessionIpc.EXTRA_PATH)!!).toPath()
                        SessionHub.active()?.download(name, dest) ?: error("无会话")
                        reply(replyTo, id, true, null)
                    } catch (t: Throwable) {
                        reply(replyTo, id, false, t.message)
                    }
                }
                SessionIpc.MSG_UPLOAD -> {
                    val id = msg.arg1
                    try {
                        val name = msg.data.getString(SessionIpc.EXTRA_NAME) ?: error("name")
                        val src = File(msg.data.getString(SessionIpc.EXTRA_PATH)!!).toPath()
                        SessionHub.active()?.upload(src, name) ?: error("无会话")
                        broadcastFiles()
                        reply(replyTo, id, true, null)
                    } catch (t: Throwable) {
                        reply(replyTo, id, false, t.message)
                    }
                }
                SessionIpc.MSG_GET_LOG -> broadcastLog(replyTo)
                SessionIpc.MSG_ENSURE -> {
                    startInForegroundSafely()
                    acquireLocks()
                    if (SessionHub.anyLive()) {
                        player?.start()
                        floatBubble?.show()
                        startKeepAliveLoop()
                    }
                    broadcastSessions()
                    broadcastStatus()
                }
            }
        } catch (t: Throwable) {
            SessionLog.event("ipc ${msg.what}: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun sendReset(to: Messenger?) {
        val targets = if (to != null) listOf(to) else listeners.toList()
        for (m in targets) {
            try {
                m.send(Message.obtain(null, SessionIpc.MSG_RESET))
            } catch (_: Throwable) {
            }
        }
    }

    private fun replayScrollback(to: Messenger?) {
        val chunks = SessionHub.active()?.snapshotScrollback().orEmpty()
        SessionLog.event("replay ${chunks.size} chunks to UI")
        val targets = if (to != null) listOf(to) else listeners.toList()
        for (chunk in chunks) {
            for (m in targets) {
                try {
                    val msg = Message.obtain(null, SessionIpc.MSG_OUTPUT)
                    msg.data = Bundle().apply { putByteArray(SessionIpc.EXTRA_BYTES, chunk) }
                    m.send(msg)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun broadcastOutput(bytes: ByteArray) {
        val dead = mutableListOf<Messenger>()
        for (m in listeners) {
            try {
                val msg = Message.obtain(null, SessionIpc.MSG_OUTPUT)
                msg.data = Bundle().apply { putByteArray(SessionIpc.EXTRA_BYTES, bytes) }
                m.send(msg)
            } catch (_: Throwable) {
                dead += m
            }
        }
        listeners.removeAll(dead)
    }

    private fun broadcastStatus() {
        val s = SessionHub.active()
        val dead = mutableListOf<Messenger>()
        for (m in listeners) {
            try {
                val msg = Message.obtain(null, SessionIpc.MSG_STATUS)
                msg.data = Bundle().apply {
                    putString(SessionIpc.EXTRA_TEXT, s?.status?.value ?: "未连接")
                    putBoolean(SessionIpc.EXTRA_CONNECTED, s?.connected?.value == true)
                    putString(SessionIpc.EXTRA_KIND, (s?.kind?.value ?: TransportKind.SSH).name)
                    putString(SessionIpc.EXTRA_SID, SessionHub.activeId)
                }
                m.send(msg)
            } catch (_: Throwable) {
                dead += m
            }
        }
        listeners.removeAll(dead)
    }

    private fun broadcastSessions() {
        val dead = mutableListOf<Messenger>()
        val text = SessionHub.encodeInfos()
        for (m in listeners) {
            try {
                val msg = Message.obtain(null, SessionIpc.MSG_SESSIONS)
                msg.data = Bundle().apply {
                    putString(SessionIpc.EXTRA_TEXT, text)
                    putString(SessionIpc.EXTRA_SID, SessionHub.activeId)
                }
                m.send(msg)
            } catch (_: Throwable) {
                dead += m
            }
        }
        listeners.removeAll(dead)
    }

    private fun broadcastStats() {
        val dead = mutableListOf<Messenger>()
        val text = SessionHub.encodeStats()
        for (m in listeners) {
            try {
                val msg = Message.obtain(null, SessionIpc.MSG_STATS)
                msg.data = Bundle().apply { putString(SessionIpc.EXTRA_TEXT, text) }
                m.send(msg)
            } catch (_: Throwable) {
                dead += m
            }
        }
        listeners.removeAll(dead)
    }

    private fun broadcastFiles() {
        val s = SessionHub.active()
        val text = s?.files?.value.orEmpty().joinToString("\n") {
            "${it.name}\t${if (it.isDirectory) 1 else 0}\t${it.size}"
        }
        val dead = mutableListOf<Messenger>()
        for (m in listeners) {
            try {
                val msg = Message.obtain(null, SessionIpc.MSG_FILES)
                msg.data = Bundle().apply {
                    putString(SessionIpc.EXTRA_PATH, s?.remotePath?.value ?: ".")
                    putString(SessionIpc.EXTRA_TEXT, text)
                }
                m.send(msg)
            } catch (_: Throwable) {
                dead += m
            }
        }
        listeners.removeAll(dead)
    }

    private fun broadcastLog(target: Messenger?) {
        val snap = SessionLog.snapshot()
        val targets = if (target != null) listOf(target) else listeners
        for (m in targets) {
            try {
                val msg = Message.obtain(null, SessionIpc.MSG_LOG)
                msg.data = Bundle().apply { putString(SessionIpc.EXTRA_TEXT, snap) }
                m.send(msg)
            } catch (_: Throwable) {
            }
        }
    }

    private fun reply(to: Messenger?, id: Int, ok: Boolean, error: String?) {
        if (to == null) return
        try {
            val msg = Message.obtain(null, SessionIpc.MSG_REPLY, id, 0)
            msg.data = Bundle().apply {
                putBoolean(SessionIpc.EXTRA_OK, ok)
                putString(SessionIpc.EXTRA_ERROR, error)
            }
            to.send(msg)
        } catch (_: Throwable) {
        }
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
        const val EXTRA_AUTH = "auth"
        const val EXTRA_KEY = "key"
        const val EXTRA_PASSPHRASE = "passphrase"
        private const val CHANNEL_ID = "ssh_keep_plain"
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
                putExtra(EXTRA_AUTH, profile.auth.name)
                putExtra(EXTRA_KEY, profile.privateKey)
                putExtra(EXTRA_PASSPHRASE, profile.passphrase)
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
                            SessionHub.open(profile)
                        } catch (t3: Throwable) {
                            SessionLog.event("connect failed: ${t3.message}")
                        }
                    }
                }
            }
            requestUnrestrictedBackground(context)
        }

        fun ensureRunning(context: Context) {
            if (!SessionHub.anyLive()) return
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
