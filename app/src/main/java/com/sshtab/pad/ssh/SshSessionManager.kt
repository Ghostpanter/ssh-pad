package com.sshtab.pad.ssh

import android.util.Log
import com.sshtab.pad.crypto.CryptoBootstrap
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.session.SessionHeartbeatController
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.sftp.client.SftpClientFactory

/**
 * Process-scoped SSH session. Lives in the Application / foreground service
 * so Activity recreation or window-size changes never tear the TCP/SSH link.
 */
object SshSessionManager {
    private const val TAG = "SshSessionManager"

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _status = MutableStateFlow("未连接")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _remotePath = MutableStateFlow(".")
    val remotePath: StateFlow<String> = _remotePath.asStateFlow()

    private val _files = MutableStateFlow<List<RemoteEntry>>(emptyList())
    val files: StateFlow<List<RemoteEntry>> = _files.asStateFlow()

    data class RemoteEntry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
    )

    @Volatile private var client: SshClient? = null
    @Volatile private var session: ClientSession? = null
    @Volatile private var shellIn: OutputStream? = null
    @Volatile private var currentProfile: HostProfile? = null
    private val shellAlive = AtomicBoolean(false)

    @Synchronized
    fun connect(profile: HostProfile) {
        CryptoBootstrap.install()
        if (_connected.value && currentProfile == profile) {
            _status.value = "已连接 ${profile.username}@${profile.host}"
            return
        }
        disconnectInternal(keepOutput = true)
        currentProfile = profile
        _status.value = "正在连接 ${profile.host}:${profile.port}…"
        append(">>> 连接 ${profile.username}@${profile.host}:${profile.port}\n")

        val c = SshClient.setUpDefaultClient()
        CoreModuleProperties.IDLE_TIMEOUT.set(c, Duration.ofHours(12))
        CoreModuleProperties.NIO2_READ_TIMEOUT.set(c, Duration.ofHours(12))
        try {
            c.setSessionHeartbeat(
                SessionHeartbeatController.HeartbeatType.IGNORE,
                Duration.ofSeconds(15),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "heartbeat", t)
        }
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(c, Duration.ofSeconds(15))
        CoreModuleProperties.HEARTBEAT_REPLY_WAIT.set(c, Duration.ofSeconds(30))
        c.serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
        c.keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
        try {
            val keep = c.keyExchangeFactories.filter { factory ->
                val n = factory.name.lowercase()
                !n.contains("sntrup") && !n.contains("mlkem")
            }
            if (keep.isNotEmpty()) {
                c.keyExchangeFactories = keep
            }
        } catch (t: Throwable) {
            Log.w(TAG, "kex filter", t)
        }
        c.start()
        client = c

        val sess = c.connect(profile.username, profile.host, profile.port)
            .verify(20_000)
            .session
        sess.addPasswordIdentity(profile.password)
        sess.auth().verify(20_000)
        session = sess

        openShell(sess)
        _connected.value = true
        _status.value = "已连接 ${profile.username}@${profile.host}:${profile.port}"
        append(">>> 已连接。窗口切换不会断开（会话在前台服务中）。\n")
        try {
            listRemote(".")
        } catch (e: Exception) {
            Log.w(TAG, "sftp list after connect", e)
        }
    }

    private fun openShell(sess: ClientSession) {
        val ch = sess.createShellChannel()
        ch.setPtyType("xterm-256color")
        ch.setPtyColumns(120)
        ch.setPtyLines(40)
        ch.open().verify(15_000)
        shellIn = ch.invertedIn
        shellAlive.set(true)
        val out: InputStream = ch.invertedOut
        val err: InputStream = ch.invertedErr
        Thread({ pump(out) }, "ssh-stdout").apply { isDaemon = true }.start()
        Thread({ pump(err) }, "ssh-stderr").apply { isDaemon = true }.start()
        Thread({
            ch.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L)
            shellAlive.set(false)
        }, "ssh-shell-wait").apply { isDaemon = true }.start()
    }

    private fun pump(stream: InputStream) {
        val buf = ByteArray(4096)
        try {
            while (shellAlive.get()) {
                val n = stream.read(buf)
                if (n < 0) break
                if (n > 0) append(String(buf, 0, n, StandardCharsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.w(TAG, "pump ended", e)
        }
    }

    fun sendCommand(line: String) {
        val out = shellIn ?: return
        try {
            out.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
            out.flush()
            append("$ $line\n")
        } catch (e: Exception) {
            append("发送失败: ${e.message}\n")
        }
    }

    fun listRemote(path: String) {
        val sess = session ?: throw IllegalStateException("未连接")
        SftpClientFactory.instance().createSftpClient(sess).use { sftp ->
            val normalized = if (path.isBlank()) "." else path
            val entries = mutableListOf<RemoteEntry>()
            sftp.readDir(normalized).forEach { e ->
                val attrs = e.attributes
                entries += RemoteEntry(
                    name = e.filename,
                    isDirectory = attrs.isDirectory,
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
        val sess = session ?: throw IllegalStateException("未连接")
        val remote = join(_remotePath.value, remoteName)
        SftpClientFactory.instance().createSftpClient(sess).use { sftp ->
            sftp.read(remote).use { input ->
                Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        append("<<< 已下载 $remote -> ${dest.fileName}\n")
    }

    fun upload(local: Path, remoteName: String) {
        val sess = session ?: throw IllegalStateException("未连接")
        val remote = join(_remotePath.value, remoteName)
        SftpClientFactory.instance().createSftpClient(sess).use { sftp ->
            sftp.write(remote).use { output ->
                Files.copy(local, output)
            }
        }
        append(">>> 已上传 ${local.fileName} -> $remote\n")
        listRemote(_remotePath.value)
    }

    fun disconnect() {
        disconnectInternal(keepOutput = false)
        _status.value = "已断开"
        append(">>> 已断开\n")
    }

    private fun disconnectInternal(keepOutput: Boolean) {
        shellAlive.set(false)
        try { shellIn?.close() } catch (_: Exception) {}
        shellIn = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { client?.stop() } catch (_: Exception) {}
        client = null
        _connected.value = false
        if (!keepOutput) _output.value = ""
        _files.value = emptyList()
    }

    private fun join(dir: String, name: String): String {
        if (dir == "." || dir.isBlank()) return name
        return if (dir.endsWith("/")) dir + name else "$dir/$name"
    }

    private val lock = Any()
    fun fail(message: String) {
        _connected.value = false
        _status.value = message
        append("$message\n")
    }

    fun append(text: String) {
        synchronized(lock) {
            val next = _output.value + text
            _output.value = if (next.length > 80_000) next.takeLast(60_000) else next
        }
    }
}
