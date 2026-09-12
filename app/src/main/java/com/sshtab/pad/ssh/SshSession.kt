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

class SshSession(
    val id: String,
    private val onBytes: (String, ByteArray) -> Unit,
) {
    private val tag = "SshSession-$id"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "session-io-$id").apply { isDaemon = false }
    }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _status = MutableStateFlow("未连接")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _kind = MutableStateFlow(TransportKind.SSH)
    val kind: StateFlow<TransportKind> = _kind.asStateFlow()

    private val _remotePath = MutableStateFlow(".")
    val remotePath: StateFlow<String> = _remotePath.asStateFlow()

    private val _files = MutableStateFlow<List<FileEntry>>(emptyList())
    val files: StateFlow<List<FileEntry>> = _files.asStateFlow()

    @Volatile var currentProfile: HostProfile? = null
        private set
    @Volatile private var jsch: JSch? = null
    @Volatile private var session: Session? = null
    @Volatile private var shell: ChannelShell? = null
    @Volatile private var telnet: TelnetClient? = null
    @Volatile private var outStream: OutputStream? = null
    @Volatile private var cols: Int = 120
    @Volatile private var rows: Int = 40
    private val alive = AtomicBoolean(false)
    private val reconnects = AtomicInteger(0)

    private val sinks = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    private val scrollback = ArrayDeque<ByteArray>()
    private var scrollbackBytes = 0

    companion object {
        private const val SCROLLBACK_MAX = 1024 * 1024
    }

    fun attachSink(sink: (ByteArray) -> Unit) {
        if (!sinks.contains(sink)) sinks.add(sink)
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
        SessionHub.notifyChange()
        SessionLog.event("[$id] connect ${profile.kind} ${profile.host}:${profile.port} user=${profile.username} auth=${profile.auth}")
        emitLocal("\r\n\u001b[36mconnecting ${profile.host}:${profile.port} (${profile.kind})…\u001b[0m\r\n")
        when (profile.kind) {
            TransportKind.SSH -> connectSsh(profile)
            TransportKind.TELNET -> connectTelnet(profile)
        }
        SessionHub.notifyChange()
    }

    private fun connectSsh(profile: HostProfile) {
        val client = JSch()
        jsch = client
        if (profile.auth == AuthMethod.KEY && profile.privateKey.isNotBlank()) {
            val keyBytes = profile.privateKey.toByteArray(StandardCharsets.UTF_8)
            val pp = profile.passphrase.takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8)
            client.addIdentity("key-$id", keyBytes, null, pp)
        }
        val sess = client.getSession(profile.username, profile.host, profile.port)
        if (profile.password.isNotEmpty()) sess.setPassword(profile.password)
        sess.userInfo = passwordUserInfo(profile.password, profile.passphrase)
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
        cfg["PreferredAuthentications"] = if (profile.auth == AuthMethod.KEY)
            "publickey,keyboard-interactive,password"
        else
            "password,keyboard-interactive"
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
        SessionLog.event("[$id] ssh connected ${profile.username}@${profile.host}:${profile.port}")
        Thread({ pump(ch.inputStream, fatalOnEof = true, label = "ssh-stdout") }, "ssh-stdout-$id").apply { isDaemon = false }.start()
        Thread({ pump(ch.extInputStream, fatalOnEof = false, label = "ssh-stderr") }, "ssh-stderr-$id").apply { isDaemon = false }.start()
        try { listRemote(".") } catch (e: Exception) { Log.w(tag, "sftp list after connect", e) }
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
            Log.w(tag, "telnet options", e)
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
            Log.w(tag, "telnet socket opts", e)
        }
        telnet = client
        outStream = client.outputStream
        alive.set(true)
        reconnects.set(0)
        _connected.value = true
        _status.value = statusLine(profile)
        emitLocal("\u001b[32mtelnet connected. login inside the terminal.\u001b[0m\r\n")
        SessionLog.event("[$id] telnet connected ${profile.host}:${profile.port}")
        Thread({ pump(client.inputStream, fatalOnEof = true, label = "telnet") }, "telnet-$id").apply { isDaemon = false }.start()
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
                    SessionLog.event("[$id] $label EOF session=${session?.isConnected} telnet=${telnet?.isConnected}")
                    break
                }
                if (n > 0) emit(buf.copyOf(n))
            }
        } catch (e: Exception) {
            detail = "$label ${e.javaClass.simpleName}: ${e.message}"
            SessionLog.event("[$id] $label ended: ${e.javaClass.simpleName}: ${e.message}")
            Log.w(tag, "pump $label ended", e)
        }
        if (alive.get() && fatalOnEof) recoverOrFail(detail)
    }

    private fun recoverOrFail(detail: String) {
        val sess = session
        if (sess?.isConnected == true) {
            SessionLog.event("[$id] stdout died ($detail) but SSH session live — reopen PTY")
            try {
                reopenShell()
                return
            } catch (t: Throwable) {
                SessionLog.event("[$id] reopen shell failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        if (requestAutoReconnect(detail)) return
        fail("连接已断开 ($detail)")
    }

    fun requestAutoReconnect(reason: String): Boolean {
        val profile = currentProfile ?: return false
        val n = reconnects.incrementAndGet()
        if (n > 5) {
            SessionLog.event("[$id] auto-reconnect exhausted after $reason")
            return false
        }
        SessionLog.event("[$id] auto-reconnect $n/5 after $reason")
        _status.value = "连接中断，正在重连 ($n/5)…"
        SessionHub.notifyChange()
        emitLocal("\r\n\u001b[33mconnection lost ($reason), reconnecting $n/5…\u001b[0m\r\n")
        Thread({
            try {
                Thread.sleep(800L * n)
                connect(profile)
            } catch (t: Throwable) {
                SessionLog.event("[$id] auto-reconnect failed: ${describeError(t)}")
                fail("重连失败: ${describeError(t)}")
            }
        }, "reconnect-$id").apply { isDaemon = false }.start()
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
        SessionLog.event("[$id] shell reopened cols=$cols rows=$rows")
        Thread({ pump(ch.inputStream, fatalOnEof = true, label = "ssh-stdout") }, "ssh-stdout-$id").apply { isDaemon = false }.start()
        Thread({ pump(ch.extInputStream, fatalOnEof = false, label = "ssh-stderr") }, "ssh-stderr-$id").apply { isDaemon = false }.start()
        SessionHub.notifyChange()
    }

    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        io.execute {
            try {
                val out = outStream ?: return@execute
                out.write(data)
                out.flush()
            } catch (e: Exception) {
                Log.w(tag, "write", e)
                SessionLog.event("[$id] write error: ${describeError(e)}")
                if (!isLive()) fail("发送失败: ${describeError(e)}")
            }
        }
    }

    fun writeUtf8(text: String) {
        write(text.toByteArray(StandardCharsets.UTF_8))
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols < 2 || newRows < 1) return
        cols = newCols
        rows = newRows
        io.execute {
            try {
                shell?.setPtySize(newCols, newRows, newCols * 8, newRows * 16)
            } catch (e: Exception) {
                Log.w(tag, "pty resize", e)
            }
        }
    }

    fun listRemote(path: String) {
        withSftp { sftp ->
            val normalized = if (path.isBlank()) "." else path
            val entries = mutableListOf<FileEntry>()
            @Suppress("UNCHECKED_CAST")
            val listing = sftp.ls(normalized) as Vector<ChannelSftp.LsEntry>
            listing.forEach { e ->
                val attrs: SftpATTRS = e.attrs
                entries += FileEntry(
                    name = e.filename,
                    isDirectory = attrs.isDir,
                    size = attrs.size,
                )
            }
            _remotePath.value = normalized
            _files.value = entries.sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
            )
        }
        SessionHub.notifyChange()
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

    fun mkdir(name: String) {
        val dir = join(_remotePath.value, name)
        withSftp { it.mkdir(dir) }
        listRemote(_remotePath.value)
    }

    fun deleteRemote(name: String, isDir: Boolean) {
        val path = join(_remotePath.value, name)
        withSftp { sftp ->
            if (isDir) sftp.rmdir(path) else sftp.rm(path)
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
        SessionLog.disconnect("[$id] user disconnect")
        emitLocal("\r\n\u001b[33mdisconnected\u001b[0m\r\n")
        SessionHub.notifyChange()
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
                    Log.w(tag, "telnet nop", e)
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
            SessionLog.event("[$id] hardened jsch socket $sock")
        } catch (e: Exception) {
            Log.w(tag, "hardenSocket", e)
        }
    }

    private fun statusLine(profile: HostProfile): String = profile.title()

    private fun join(dir: String, name: String): String {
        if (dir == "." || dir.isBlank()) return name
        return if (dir.endsWith("/")) dir + name else "$dir/$name"
    }

    fun fail(message: String) {
        if (alive.get()) disconnectInternal()
        _connected.value = false
        _status.value = message
        SessionLog.disconnect("[$id] $message")
        emitLocal("\r\n\u001b[31m$message\u001b[0m\r\n")
        SessionHub.notifyChange()
        Thread({
            try { Thread.sleep(8_000) } catch (_: InterruptedException) { return@Thread }
            if (isLive() || connected.value) return@Thread
            SessionLog.event("[$id] remove failed session from UI, kept in log")
            SessionHub.drop(id)
        }, "drop-fail-$id").apply { isDaemon = true }.start()
    }

    fun discard() {
        disconnectInternal()
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
        onBytes(id, chunk)
        sinks.forEach { sink ->
            try { sink(chunk) } catch (e: Exception) { Log.w(tag, "sink", e) }
        }
    }

    private fun passwordUserInfo(password: String, passphrase: String): UserInfo =
        object : UserInfo, UIKeyboardInteractive {
            override fun getPassphrase(): String = passphrase
            override fun getPassword(): String = password
            override fun promptPassword(message: String?) = password.isNotEmpty()
            override fun promptPassphrase(message: String?) = passphrase.isNotEmpty()
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
