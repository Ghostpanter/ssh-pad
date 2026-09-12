package com.sshtab.pad.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.sshtab.pad.ssh.HostProfile
import com.sshtab.pad.ssh.SshSessionManager
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

/**
 * UI 进程入口。真正的 SSH 跑在 `:session` 进程里，切走应用只可能冻 UI，
 * 会话进程靠媒体保活 + 前台服务继续收 watch 输出并缓存。
 */
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

    private val _files = MutableStateFlow<List<SshSessionManager.RemoteEntry>>(emptyList())
    val files: StateFlow<List<SshSessionManager.RemoteEntry>> = _files.asStateFlow()

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

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
                    _status.value = text
                    _connected.value = on
                    if (on) _held.value = true
                    else if (text.contains("已断开") || text.contains("连接失败") || text.startsWith("请填写")) {
                        _held.value = false
                    }
                    _kind.value = runCatching { TransportKind.valueOf(kindName) }.getOrDefault(TransportKind.SSH)
                    if (!on && text.contains("断开")) lastDisconnectReason = text
                }
                SessionIpc.MSG_FILES -> {
                    _remotePath.value = msg.data.getString(SessionIpc.EXTRA_PATH) ?: "."
                    val raw = msg.data.getString(SessionIpc.EXTRA_TEXT) ?: ""
                    _files.value = raw.lineSequence().filter { it.isNotBlank() }.map { line ->
                        val p = line.split('\t')
                        SshSessionManager.RemoteEntry(
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

    fun disconnect(context: Context) {
        _held.value = false
        send(SessionIpc.MSG_DISCONNECT)
        context.startService(
            Intent(context, SshSessionService::class.java).setAction(SshSessionService.ACTION_DISCONNECT)
        )
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
        _status.value = message
        _connected.value = false
        _held.value = false
    }

    fun refreshLog() {
        send(SessionIpc.MSG_GET_LOG)
    }

    fun clearLog() {
        _log.value = ""
        send(SessionIpc.MSG_GET_LOG)
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
