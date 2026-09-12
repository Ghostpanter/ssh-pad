package com.sshtab.pad.ssh

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.sshtab.pad.crypto.CryptoBootstrap
import com.sshtab.pad.log.SessionLog
import com.sshtab.pad.net.ProtectedSocket
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.Vector
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.apache.commons.net.telnet.EchoOptionHandler
import org.apache.commons.net.telnet.SuppressGAOptionHandler
import org.apache.commons.net.telnet.TelnetClient
import org.apache.commons.net.telnet.TerminalTypeOptionHandler
import org.apache.commons.net.telnet.WindowSizeOptionHandler

/**
 * Process-scoped session. All socket I/O runs on [io] so Compose / WebView
 * never hits NetworkOnMainThreadException. Terminal bytes are pushed to
 * registered sinks (xterm.js), not a Compose TextField.
 */
object SshSessionManager {
    private const val TAG = "SshSessionManager"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "session-io").apply { isDaemon = false }
    }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _status = MutableStateFlow("未连接")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _kind = MutableStateFlow(TransportKind.SSH)
    val kind: StateFlow<TransportKind> = _kind.asStateFlow()

    private val _remotePath = MutableStateFlow(".")
    val remotePath: StateFlow<String> = _remotePath.asStateFlow()

    private val _files = MutableStateFlow<List<RemoteEntry>>(emptyList())
    val files: StateFlow<List<RemoteEntry>> = _files.asStateFlow()

    data class RemoteEntry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
    )

    @Volatile private var jsch: JSch? = null
    @Volatile private var session: Session? = null
    @Volatile private var shell: ChannelShell? = null
    @Volatile private var telnet: TelnetClient? = null
    @Volatile private var outStream: OutputStream? = null
    @Volatile private var currentProfile: HostProfile? = null
    @Volatile private var cols: Int = 120
    @Volatile private var rows: Int = 40
    private val alive = AtomicBoolean(false)
    private val reconnects = AtomicInteger(0)

    private val sinks = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    private val scrollback = ArrayDeque<ByteArray>()
    private var scrollbackBytes = 0
    private const val SCROLLBACK_MAX = 1024 * 1024

    fun attachSink(sink: (ByteArray) -> Unit) {
        if (!sinks.contains(sink)) sinks.add(sink)
        val replay: List<ByteArray>
        synchronized(scrollback) {
            replay = scrollback.toList()
        }
        SessionLog.event("terminal attach, replay ${replay.size} chunks")
        replay.forEach { chunk ->
            try { sink(chunk) } catch (e: Exception) { Log.w(TAG, "replay", e) }
        }
    }

    fun detachSink(sink: (ByteArray) -> Unit) {
        sinks.remove(sink)
    }

    fun snapshotScrollback(): List<ByteArray> = synchronized(scrollback) { scrollback.toList() }

    @Synchronized
    fun connect(profile: HostProfile) {
        CryptoBootstrap.install()
        if (_connected.value && currentProfile == profile && isLive()) {
            _status.value = statusLine(profile)
            return
        }
        disconnectInternal()
        currentProfile = profile
        _kind.value = profile.kind
        _status.value = "正在连接 ${profile.host}:${profile.port}…"
        SessionLog.event("connect ${profile.kind} ${profile.host}:${profile.port} user=${profile.username}")
        emitLocal("\r\n\u001b[36mconnecting ${profile.host}:${profile.port} (${profile.kind})…\u001b[0m\r\n")
        when (profile.kind) {
            TransportKind.SSH -> connectSsh(profile)
            TransportKind.TELNET -> connectTelnet(profile)
        }
    }

    private fun connectSsh(profile: HostProfile) {
        val client = JSch()
        jsch = client
        val sess = client.getSession(profile.username, profile.host, profile.port)
        sess.setPassword(profile.password)
        sess.userInfo = passwordUserInfo(profile.password)
        sess.setSocketFactory(ProtectedSocket.jschFactory(profile.host))
        val cfg = Properties()
        cfg["kex"] = listOf(
            "ecdh-sha2-nistp256",
            "ecdh-sha2-nistp384",
            "ecdh-sha2-nistp521",
            "curve25519-sha256",
            "curve25519-sha256@libssh.org",
            "diffie-hellman-group14-sha256",
            "diffie-hellman-group14-sha1",
        ).joinToString(",")
        cfg["server_host_key"] = listOf(
            "rsa-sha2-256",
            "rsa-sha2-512",
            "ssh-rsa",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521",
            "ssh-ed25519",
        ).joinToString(",")
        cfg["StrictHostKeyChecking"] = "no"
        cfg["HashKnownHosts"] = "no"
        cfg["PreferredAuthentications"] = "password,keyboard-interactive"
        cfg["MaxAuthTries"] = "3"
        cfg["TCPKeepAlive"] = "yes"
        sess.setConfig(cfg)
        sess.setServerAliveInterval(8_000)
        sess.setServerAliveCountMax(10_000)
        sess.setDaemonThread(false)
        sess.connect(20_000)
        session = sess
        hardenSocket(sess)
        reconnects.set(0)

        val ch = sess.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color", cols, rows, cols * 8, rows * 16)
        ch.connect(15_000)
        shell = ch
        outStream = ch.outputStream
        alive.set(true)
        _connected.value = true
        _status.value = statusLine(profile)
        emitLocal("\u001b[32mconnected. type in the terminal.\u001b[0m\r\n")
        SessionLog.event("ssh connected ${profile.username}@${profile.host}:${profile.port}")
        Thread({ pump(ch.inputStream, fatalOnEof = true, label = "ssh-stdout") }, "ssh-stdout").apply { isDaemon = false }.start()
        Thread({ pump(ch.extInputStream, fatalOnEof = false, label = "ssh-stderr") }, "ssh-stderr").apply { isDaemon = false }.start()
        try {
            listRemote(".")
        } catch (e: Exception) {
            Log.w(TAG, "sftp list after connect", e)
        }
    }

    private fun connectTelnet(profile: HostProfile) {
        val client = TelnetClient()
        client.connectTimeout = 20_000
        client.defaultTimeout = 0
        client.setReaderThread(true)
        client.setSocketFactory(ProtectedSocket.javaxFactory(profile.host))
        try {
            client.addOptionHandler(EchoOptionHandler(false, false, true, false))
            client.addOptionHandler(SuppressGAOptionHandler(true, true, true, true))
            client.addOptionHandler(TerminalTypeOptionHandler("xterm-256color", false, false, true, false))
            client.addOptionHandler(WindowSizeOptionHandler(cols, rows, true, true, true, true))
        } catch (e: Exception) {
            Log.w(TAG, "telnet options", e)
        }
        client.connect(profile.host, profile.port)
        try {
            client.setKeepAlive(true)
            client.setTcpNoDelay(true)
            client.setSoTimeout(0)
            val field = client.javaClass.superclass?.getDeclaredField("_socket_")
            field?.isAccessible = true
            val sock = field?.get(client) as? java.net.Socket
            if (sock != null) ProtectedSocket.applyTcpKeepalive(sock)
        } catch (e: Exception) {
            Log.w(TAG, "telnet socket opts", e)
        }
        telnet = client
        outStream = client.outputStream
        alive.set(true)
        reconnects.set(0)
        _connected.value = true
        _status.value = statusLine(profile)
        emitLocal("\u001b[32mtelnet connected. login inside the terminal.\u001b[0m\r\n")
        SessionLog.event("telnet connected ${profile.host}:${profile.port}")
        Thread({ pump(client.inputStream, fatalOnEof = true, label = "telnet") }, "telnet-stdout").apply { isDaemon = false }.start()
    }

    private fun pump(stream: InputStream, fatalOnEof: Boolean, label: String) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
        } catch (_: Throwable) {
        }
        val buf = ByteArray(4096)
        var detail = "$label EOF"
        try {
            while (alive.get()) {
                val n = stream.read(buf)
                if (n < 0) {
                    SessionLog.event("$label EOF session=${session?.isConnected} telnet=${telnet?.isConnected}")
                    break
                }
                if (n > 0) emit(buf.copyOf(n))
            }
        } catch (e: Exception) {
            detail = "$label ${e.javaClass.simpleName}: ${e.message}"
            SessionLog.event("$label ended: ${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "pump $label ended", e)
        }
        if (alive.get() && fatalOnEof) {
            recoverOrFail(detail)
        }
    }

    /**
     * stdout 关掉时：若 SSH 会话还在只重开 PTY。
     * 会话也死了（国行冻进程后 TCP 被 NAT 掐）则自动重连，避免回到登录页。
     */
    private fun recoverOrFail(detail: String) {
        val sess = session
        if (sess?.isConnected == true) {
            SessionLog.event("stdout died ($detail) but SSH session live — reopen PTY")
            try {
                reopenShell()
                return
            } catch (t: Throwable) {
                SessionLog.event("reopen shell failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        if (requestAutoReconnect(detail)) return
        fail("连接已断开 ($detail)")
    }

    fun requestAutoReconnect(reason: String): Boolean {
        val profile = currentProfile ?: return false
        val n = reconnects.incrementAndGet()
        if (n > 5) {
            SessionLog.event("auto-reconnect exhausted after $reason")
            return false
        }
        SessionLog.event("auto-reconnect $n/5 after $reason")
        _status.value = "连接中断，正在重连 ($n/5)…"
        emitLocal("\r\n\u001b[33mconnection lost ($reason), reconnecting $n/5…\u001b[0m\r\n")
        Thread({
            try {
                Thread.sleep(800L * n)
                connect(profile)
            } catch (t: Throwable) {
                SessionLog.event("auto-reconnect failed: ${describeError(t)}")
                fail("重连失败: ${describeError(t)}")
            }
        }, "session-reconnect").apply { isDaemon = false }.start()
        return true
    }

    @Synchronized
    private fun reopenShell() {
        val sess = session ?: throw IllegalStateException("no session")
        if (!sess.isConnected) throw IllegalStateException("session dead")
        try { shell?.disconnect() } catch (_: Exception) {}
        val ch = sess.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color", cols, rows, cols * 8, rows * 16)
        ch.connect(15_000)
        shell = ch
        outStream = ch.outputStream
        alive.set(true)
        _connected.value = true
        emitLocal("\r\n\u001b[33mshell reopened (session kept)\u001b[0m\r\n")
        SessionLog.event("shell reopened cols=$cols rows=$rows")
        Thread({ pump(ch.inputStream, fatalOnEof = true, label = "ssh-stdout") }, "ssh-stdout").apply { isDaemon = false }.start()
        Thread({ pump(ch.extInputStream, fatalOnEof = false, label = "ssh-stderr") }, "ssh-stderr").apply { isDaemon = false }.start()
    }

    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        io.execute {
            try {
                val out = outStream ?: return@execute
                out.write(data)
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "write", e)
                SessionLog.event("write error: ${describeError(e)}")
                if (!isLive()) fail("发送失败: ${describeError(e)}")
            }
        }
    }

    fun writeUtf8(text: String) {
        write(text.toByteArray(StandardCharsets.UTF_8))
    }

    /** Extra keys / leftover API. Never runs on the UI thread. */
    fun sendCommand(line: String) {
        writeUtf8(line + "\r")
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols < 2 || newRows < 1) return
        cols = newCols
        rows = newRows
        io.execute {
            try {
                shell?.setPtySize(newCols, newRows, newCols * 8, newRows * 16)
            } catch (e: Exception) {
                Log.w(TAG, "pty resize", e)
            }
        }
    }

    fun listRemote(path: String) {
        withSftp { sftp ->
            val normalized = if (path.isBlank()) "." else path
            val entries = mutableListOf<RemoteEntry>()
            @Suppress("UNCHECKED_CAST")
            val listing = sftp.ls(normalized) as Vector<ChannelSftp.LsEntry>
            listing.forEach { e ->
                val attrs: SftpATTRS = e.attrs
                entries += RemoteEntry(
                    name = e.filename,
                    isDirectory = attrs.isDir,
                    size = attrs.size,
                )
            }
            _remotePath.value = normalized
            _files.value = entries.sortedWith(
                compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
            )
        }
    }

    fun download(remoteName: String, dest: Path) {
        val remote = join(_remotePath.value, remoteName)
        withSftp { sftp ->
            sftp.get(remote).use { input ->
                Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    fun upload(local: Path, remoteName: String) {
        val remote = join(_remotePath.value, remoteName)
        withSftp { sftp ->
            Files.newInputStream(local).use { input ->
                sftp.put(input, remote)
            }
        }
        listRemote(_remotePath.value)
    }

    private fun withSftp(block: (ChannelSftp) -> Unit) {
        val sess = session ?: throw IllegalStateException("当前不是 SSH 会话")
        if (!sess.isConnected) throw IllegalStateException("会话已断开")
        val ch = sess.openChannel("sftp") as ChannelSftp
        ch.connect(15_000)
        try {
            block(ch)
        } finally {
            try { ch.disconnect() } catch (_: Exception) {}
        }
    }

    fun disconnect() {
        disconnectInternal()
        _status.value = "已断开"
        SessionLog.disconnect("user disconnect")
        emitLocal("\r\n\u001b[33mdisconnected\u001b[0m\r\n")
    }

    private fun disconnectInternal() {
        alive.set(false)
        try { outStream?.close() } catch (_: Exception) {}
        outStream = null
        try { shell?.disconnect() } catch (_: Exception) {}
        shell = null
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
        jsch = null
        try { telnet?.disconnect() } catch (_: Exception) {}
        telnet = null
        _connected.value = false
        _files.value = emptyList()
    }

    fun keepAliveOnce(): Boolean {
        val sess = session
        if (sess != null) {
            if (!sess.isConnected) return false
            sess.sendKeepAliveMsg()
            return true
        }
        val tn = telnet
        if (tn != null) {
            if (!tn.isConnected) return false
            val out = tn.outputStream
            io.execute {
                try {
                    out.write(byteArrayOf(0xFF.toByte(), 0xF1.toByte()))
                    out.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "telnet nop", e)
                }
            }
            return true
        }
        return false
    }

    fun isLive(): Boolean =
        session?.isConnected == true || telnet?.isConnected == true

    private fun hardenSocket(sess: Session) {
        try {
            val field = sess.javaClass.declaredFields.firstOrNull {
                it.type == java.net.Socket::class.java || it.name.equals("socket", true)
            } ?: sess.javaClass.superclass?.declaredFields?.firstOrNull {
                it.type == java.net.Socket::class.java
            }
            field?.isAccessible = true
            val sock = field?.get(sess) as? java.net.Socket ?: return
            ProtectedSocket.applyTcpKeepalive(sock)
            SessionLog.event("hardened jsch socket $sock")
        } catch (e: Exception) {
            Log.w(TAG, "hardenSocket", e)
        }
    }

    private fun statusLine(profile: HostProfile): String = when (profile.kind) {
        TransportKind.SSH -> "SSH ${profile.username}@${profile.host}:${profile.port}"
        TransportKind.TELNET -> "TELNET ${profile.host}:${profile.port}"
    }

    private fun join(dir: String, name: String): String {
        if (dir == "." || dir.isBlank()) return name
        return if (dir.endsWith("/")) dir + name else "$dir/$name"
    }

    fun fail(message: String) {
        if (alive.get()) disconnectInternal()
        _connected.value = false
        _status.value = message
        SessionLog.disconnect(message)
        emitLocal("\r\n\u001b[31m$message\u001b[0m\r\n")
    }

    fun describeError(t: Throwable): String =
        generateSequence(t) { it.cause }
            .map { "${it.javaClass.simpleName}: ${it.message}" }
            .joinToString(" ← ")

    private fun emitLocal(text: String) {
        emit(text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun emit(chunk: ByteArray) {
        SessionLog.incoming(chunk)
        synchronized(scrollback) {
            scrollback.addLast(chunk)
            scrollbackBytes += chunk.size
            while (scrollbackBytes > SCROLLBACK_MAX && scrollback.isNotEmpty()) {
                scrollbackBytes -= scrollback.removeFirst().size
            }
        }
        sinks.forEach { sink ->
            try { sink(chunk) } catch (e: Exception) { Log.w(TAG, "sink", e) }
        }
    }

    private fun passwordUserInfo(password: String): UserInfo =
        object : UserInfo, UIKeyboardInteractive {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String = password
            override fun promptPassword(message: String?) = true
            override fun promptPassphrase(message: String?) = false
            override fun promptYesNo(message: String?) = true
            override fun showMessage(message: String?) {}
            override fun promptKeyboardInteractive(
                destination: String?,
                name: String?,
                instruction: String?,
                prompt: Array<out String>?,
                echo: BooleanArray?,
            ): Array<String> = Array(prompt?.size ?: 1) { password }
        }
}
