package com.sshtab.pad.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.sshtab.pad.ssh.AuthMethod
import com.sshtab.pad.ssh.FileEntry
import com.sshtab.pad.ssh.HostProfile
import com.sshtab.pad.ssh.ServerStats
import com.sshtab.pad.ssh.SessionInfo
import com.sshtab.pad.ssh.TransportKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionClient {
    private const val TAG = "SessionClient"

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _held = MutableStateFlow(false)
    val held: StateFlow<Boolean> = _held.asStateFlow()

    private val _status = MutableStateFlow("未连接")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _kind = MutableStateFlow(TransportKind.SSH)
    val kind: StateFlow<TransportKind> = _kind.asStateFlow()

    private val _remotePath = MutableStateFlow(".")
    val remotePath: StateFlow<String> = _remotePath.asStateFlow()

    private val _files = MutableStateFlow<List<FileEntry>>(emptyList())
    val files: StateFlow<List<FileEntry>> = _files.asStateFlow()

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

    private val _banner = MutableStateFlow("")
    val banner: StateFlow<String> = _banner.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _stats = MutableStateFlow<Map<String, ServerStats>>(emptyMap())
    val stats: StateFlow<Map<String, ServerStats>> = _stats.asStateFlow()

    @Volatile var lastDisconnectReason: String = ""
        private set

    private val sinks = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    private val resets = CopyOnWriteArrayList<() -> Unit>()
    private val pending = ConcurrentHashMap<Int, CountDownLatch>()
    private val pendingError = ConcurrentHashMap<Int, String>()
    private val reqId = AtomicInteger(1)

    @Volatile private var outgoing: Messenger? = null
    private var appCtx: Context? = null
    private var bound = false

    private val worker = HandlerThread("session-client").apply { start() }
    private val incoming = Messenger(object : Handler(worker.looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                SessionIpc.MSG_OUTPUT -> {
                    val bytes = msg.data.getByteArray(SessionIpc.EXTRA_BYTES) ?: return
                    sinks.forEach { sink ->
                        try { sink(bytes) } catch (_: Exception) {}
                    }
                }
                SessionIpc.MSG_STATUS -> {
                    val text = msg.data.getString(SessionIpc.EXTRA_TEXT) ?: return
                    val on = msg.data.getBoolean(SessionIpc.EXTRA_CONNECTED)
                    val kindName = msg.data.getString(SessionIpc.EXTRA_KIND) ?: TransportKind.SSH.name
                    val sid = msg.data.getString(SessionIpc.EXTRA_SID)
                    if (sid != null) _activeId.value = sid
                    _status.value = text
                    _connected.value = on
                    _held.value = _sessions.value.isNotEmpty() || on
                    _kind.value = runCatching { TransportKind.valueOf(kindName) }.getOrDefault(TransportKind.SSH)
                    if (!on && (text.contains("失败") || text.contains("连接已断开"))) {
                        lastDisconnectReason = text
                        _banner.value = text
                    }
                }
                SessionIpc.MSG_SESSIONS -> {
                    val raw = msg.data.getString(SessionIpc.EXTRA_TEXT) ?: ""
                    val list = raw.lineSequence().filter { it.isNotBlank() }.map { line ->
                        val p = line.split('\t')
                        SessionInfo(
                            id = p.getOrElse(0) { "" },
                            title = p.getOrElse(1) { "" },
                            status = p.getOrElse(2) { "" },
                            connected = p.getOrElse(3) { "0" } == "1",
                            kind = runCatching { TransportKind.valueOf(p.getOrElse(4) { "SSH" }) }
                                .getOrDefault(TransportKind.SSH),
                        )
                    }.toList()
                    _sessions.value = list
                    _held.value = list.isNotEmpty()
                    val aid = msg.data.getString(SessionIpc.EXTRA_SID)
                    if (aid != null) _activeId.value = aid
                    if (list.isEmpty()) {
                        _connected.value = false
                        _status.value = "未连接"
                    }
                }
                SessionIpc.MSG_STATS -> {
                    val raw = msg.data.getString(SessionIpc.EXTRA_TEXT) ?: ""
                    val map = mutableMapOf<String, ServerStats>()
                    raw.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                        val p = line.split('\t')
                        if (p.size >= 7) {
                            map[p[0]] = ServerStats(
                                load = p[1],
                                cpuPercent = p[2].toIntOrNull() ?: -1,
                                memUsedKb = p[3].toLongOrNull() ?: 0,
                                memTotalKb = p[4].toLongOrNull() ?: 0,
                                rxBps = p[5].toLongOrNull() ?: 0,
                                txBps = p[6].toLongOrNull() ?: 0,
                            )
                        }
                    }
                    _stats.value = map
                }
                SessionIpc.MSG_FILES -> {
                    _remotePath.value = msg.data.getString(SessionIpc.EXTRA_PATH) ?: "."
                    val raw = msg.data.getString(SessionIpc.EXTRA_TEXT) ?: ""
                    _files.value = raw.lineSequence().filter { it.isNotBlank() }.map { line ->
                        val p = line.split('\t')
                        FileEntry(
                            name = p.getOrElse(0) { "" },
                            isDirectory = p.getOrElse(1) { "0" } == "1",
                            size = p.getOrElse(2) { "0" }.toLongOrNull() ?: 0L,
                        )
                    }.toList()
                }
                SessionIpc.MSG_RESET -> {
                    resets.forEach { r ->
                        try { r() } catch (_: Exception) {}
                    }
                }
                SessionIpc.MSG_LOG -> {
                    _log.value = msg.data.getString(SessionIpc.EXTRA_TEXT) ?: ""
                }
                SessionIpc.MSG_REPLY -> {
                    val id = msg.arg1
                    if (!msg.data.getBoolean(SessionIpc.EXTRA_OK, true)) {
                        pendingError[id] = msg.data.getString(SessionIpc.EXTRA_ERROR) ?: "失败"
                    }
                    pending.remove(id)?.countDown()
                }
            }
        }
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            outgoing = Messenger(service)
            bound = true
            send(SessionIpc.MSG_SUBSCRIBE)
            send(SessionIpc.MSG_GET_LOG)
            send(SessionIpc.MSG_ENSURE)
            Log.i(TAG, "bound to session process")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            outgoing = null
            bound = false
            Log.w(TAG, "session process disconnected")
            appCtx?.let { bind(it) }
        }
    }

    fun bind(context: Context) {
        appCtx = context.applicationContext
        if (bound && outgoing != null) return
        val i = Intent(context, SshSessionService::class.java)
        try {
            context.applicationContext.bindService(
                i, conn,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "bind", t)
        }
    }

    fun attachSink(sink: (ByteArray) -> Unit) {
        if (!sinks.contains(sink)) sinks.add(sink)
    }

    fun resyncTerminal() {
        send(SessionIpc.MSG_SUBSCRIBE)
    }

    fun onTerminalReset(reset: () -> Unit): () -> Unit {
        resets.add(reset)
        return { resets.remove(reset) }
    }

    fun detachSink(sink: (ByteArray) -> Unit) {
        sinks.remove(sink)
    }

    fun connect(context: Context, profile: HostProfile) {
        _held.value = true
        _status.value = "正在连接 ${profile.host}:${profile.port}…"
        bind(context)
        SshSessionService.startConnect(context, profile)
    }

    fun switchSession(id: String) {
        send(SessionIpc.MSG_SWITCH, Bundle().apply { putString(SessionIpc.EXTRA_SID, id) })
    }

    fun closeSession(id: String) {
        send(SessionIpc.MSG_CLOSE, Bundle().apply { putString(SessionIpc.EXTRA_SID, id) })
    }

    fun disconnect(context: Context) {
        val id = _activeId.value
        if (id != null) closeSession(id)
        else {
            send(SessionIpc.MSG_DISCONNECT)
            context.startService(
                Intent(context, SshSessionService::class.java).setAction(SshSessionService.ACTION_DISCONNECT)
            )
        }
    }

    fun writeUtf8(text: String) {
        val b = Bundle().apply { putByteArray(SessionIpc.EXTRA_BYTES, text.toByteArray()) }
        send(SessionIpc.MSG_WRITE, b)
    }

    fun resize(cols: Int, rows: Int) {
        val b = Bundle().apply {
            putInt(SessionIpc.EXTRA_COLS, cols)
            putInt(SessionIpc.EXTRA_ROWS, rows)
        }
        send(SessionIpc.MSG_RESIZE, b)
    }

    fun listRemote(path: String) {
        val b = Bundle().apply { putString(SessionIpc.EXTRA_PATH, path) }
        send(SessionIpc.MSG_LIST, b)
    }

    fun mkdir(name: String) {
        send(SessionIpc.MSG_MKDIR, Bundle().apply { putString(SessionIpc.EXTRA_NAME, name) })
    }

    fun deleteRemote(name: String, isDir: Boolean) {
        send(
            SessionIpc.MSG_DELETE,
            Bundle().apply {
                putString(SessionIpc.EXTRA_NAME, name)
                putBoolean(SessionIpc.EXTRA_IS_DIR, isDir)
            },
        )
    }

    fun download(remoteName: String, dest: Path) {
        val id = reqId.incrementAndGet()
        val latch = CountDownLatch(1)
        pending[id] = latch
        val cache = java.io.File(appCtx?.cacheDir, "sftp-dl-$id.bin")
        val b = Bundle().apply {
            putString(SessionIpc.EXTRA_NAME, remoteName)
            putString(SessionIpc.EXTRA_PATH, cache.absolutePath)
        }
        send(SessionIpc.MSG_DOWNLOAD, b, id)
        if (!latch.await(60, TimeUnit.SECONDS)) throw IllegalStateException("下载超时")
        pendingError.remove(id)?.let { throw IllegalStateException(it) }
        Files.copy(cache.toPath(), dest, StandardCopyOption.REPLACE_EXISTING)
    }

    fun upload(local: Path, remoteName: String) {
        val id = reqId.incrementAndGet()
        val latch = CountDownLatch(1)
        pending[id] = latch
        val b = Bundle().apply {
            putString(SessionIpc.EXTRA_NAME, remoteName)
            putString(SessionIpc.EXTRA_PATH, local.toAbsolutePath().toString())
        }
        send(SessionIpc.MSG_UPLOAD, b, id)
        if (!latch.await(60, TimeUnit.SECONDS)) throw IllegalStateException("上传超时")
        pendingError.remove(id)?.let { throw IllegalStateException(it) }
    }

    fun fail(message: String) {
        lastDisconnectReason = message
        _banner.value = message
        _status.value = message
        send(SessionIpc.MSG_EVENT, Bundle().apply { putString(SessionIpc.EXTRA_TEXT, message) })
    }

    fun clearBanner() {
        _banner.value = ""
    }

    fun refreshLog() {
        send(SessionIpc.MSG_GET_LOG)
    }

    fun clearLog() {
        send(SessionIpc.MSG_CLEAR_LOG)
    }

    private fun send(what: Int, data: Bundle? = null, arg1: Int = 0) {
        val m = outgoing ?: return
        try {
            val msg = Message.obtain(null, what, arg1, 0).apply {
                if (data != null) this.data = data
                replyTo = incoming
            }
            m.send(msg)
        } catch (t: Throwable) {
            Log.w(TAG, "send $what", t)
        }
    }
}
