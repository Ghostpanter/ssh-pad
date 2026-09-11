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
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.Vector
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped SSH session (JSch). Lives in the Application / foreground
 * service so window resize never tears the TCP/SSH link.
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

    @Volatile private var jsch: JSch? = null
    @Volatile private var session: Session? = null
    @Volatile private var shell: ChannelShell? = null
    @Volatile private var shellIn: OutputStream? = null
    @Volatile private var currentProfile: HostProfile? = null
    private val shellAlive = AtomicBoolean(false)

    @Synchronized
    fun connect(profile: HostProfile) {
        CryptoBootstrap.install()
        if (_connected.value && currentProfile == profile && session?.isConnected == true) {
            _status.value = "已连接 ${profile.username}@${profile.host}"
            return
        }
        disconnectInternal(keepOutput = true)
        currentProfile = profile
        _status.value = "正在连接 ${profile.host}:${profile.port}…"
        append(">>> 连接 ${profile.username}@${profile.host}:${profile.port}\n")

        val client = JSch()
        jsch = client
        val sess = client.getSession(profile.username, profile.host, profile.port)
        sess.setPassword(profile.password)
        sess.userInfo = passwordUserInfo(profile.password)
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
        sess.setConfig(cfg)
        sess.setServerAliveInterval(15_000)
        sess.setServerAliveCountMax(1_000)
        sess.setDaemonThread(true)
        sess.connect(20_000)
        session = sess

        openShell(sess)
        _connected.value = true
        _status.value = "已连接 ${profile.username}@${profile.host}:${profile.port}"
        append(">>> 已连接。窗口切换不会断开（会话在前台服务中）。\n")
        try {
            listRemote(".")
        } catch (e: Exception) {
            Log.w(TAG, "sftp list after connect", e)
            append("SFTP 列表失败: ${describeError(e)}\n")
        }
    }

    private fun openShell(sess: Session) {
        val ch = sess.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color", 120, 40, 0, 0)
        ch.connect(15_000)
        shell = ch
        shellIn = ch.outputStream
        shellAlive.set(true)
        Thread({ pump(ch.inputStream) }, "ssh-stdout").apply { isDaemon = true }.start()
        Thread({ pump(ch.extInputStream) }, "ssh-stderr").apply { isDaemon = true }.start()
        Thread({
            while (shellAlive.get() && ch.isConnected) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
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
        append("<<< 已下载 $remote -> ${dest.fileName}\n")
    }

    fun upload(local: Path, remoteName: String) {
        val remote = join(_remotePath.value, remoteName)
        withSftp { sftp ->
            Files.newInputStream(local).use { input ->
                sftp.put(input, remote)
            }
        }
        append(">>> 已上传 ${local.fileName} -> $remote\n")
        listRemote(_remotePath.value)
    }

    private fun withSftp(block: (ChannelSftp) -> Unit) {
        val sess = session ?: throw IllegalStateException("未连接")
        if (!sess.isConnected) throw IllegalStateException("会话已断开")
        val ch = sess.openChannel("sftp") as ChannelSftp
        ch.connect(15_000)
        try {
            block(ch)
        } finally {
            try {
                ch.disconnect()
            } catch (_: Exception) {
            }
        }
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
        try { shell?.disconnect() } catch (_: Exception) {}
        shell = null
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
        jsch = null
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

    fun describeError(t: Throwable): String =
        generateSequence(t) { it.cause }
            .map { "${it.javaClass.simpleName}: ${it.message}" }
            .joinToString(" ← ")

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
