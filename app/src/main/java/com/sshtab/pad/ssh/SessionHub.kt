package com.sshtab.pad.ssh

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 多会话：每个 SSH/Telnet 连接是独立的 [SshSession]，互不断开。
 */
object SessionHub {
    private val lock = Any()
    private val sessions = linkedMapOf<String, SshSession>()
    @Volatile var activeId: String? = null
        private set
    @Volatile var outputSink: ((String, ByteArray) -> Unit)? = null
    @Volatile var onChange: (() -> Unit)? = null

    fun list(): List<SshSession> = synchronized(lock) { sessions.values.toList() }

    fun infos(): List<SessionInfo> = list().map {
        SessionInfo(
            id = it.id,
            title = it.currentProfile?.title() ?: it.id,
            status = it.status.value,
            connected = it.connected.value,
            kind = it.kind.value,
        )
    }

    fun get(id: String?): SshSession? = synchronized(lock) { id?.let { sessions[it] } }

    fun active(): SshSession? = get(activeId)

    fun open(profile: HostProfile): SshSession {
        val id = UUID.randomUUID().toString().replace("-", "").take(10)
        val session = SshSession(id) { sid, bytes ->
            outputSink?.invoke(sid, bytes)
        }
        synchronized(lock) {
            sessions[id] = session
            activeId = id
        }
        session.connect(profile)
        notifyChange()
        return session
    }

    fun switchTo(id: String): SshSession? {
        val s = get(id) ?: return null
        activeId = id
        notifyChange()
        return s
    }

    fun close(id: String) {
        val s = synchronized(lock) { sessions.remove(id) } ?: return
        try { s.disconnect() } catch (_: Throwable) {}
        if (activeId == id) {
            activeId = synchronized(lock) { sessions.keys.lastOrNull() }
        }
        notifyChange()
    }

    /** 连接失败后从列表拿掉，不断开日志。 */
    fun drop(id: String) {
        val s = synchronized(lock) { sessions.remove(id) } ?: return
        try { s.discard() } catch (_: Throwable) {}
        if (activeId == id) {
            activeId = synchronized(lock) { sessions.keys.lastOrNull() }
        }
        notifyChange()
    }

    fun closeActive() {
        activeId?.let { close(it) }
    }

    fun closeAll() {
        val all = synchronized(lock) {
            val copy = sessions.values.toList()
            sessions.clear()
            activeId = null
            copy
        }
        all.forEach { try { it.disconnect() } catch (_: Throwable) {} }
        notifyChange()
    }

    fun anyLive(): Boolean = list().any { it.isLive() || it.connected.value }

    fun keepAliveAll(): Boolean {
        var any = false
        list().forEach { s ->
            if (s.connected.value || s.isLive()) {
                try {
                    if (s.keepAliveOnce()) any = true
                    else if (!s.isLive()) s.requestAutoReconnect("keepalive")
                } catch (_: Throwable) {
                    if (!s.isLive()) s.requestAutoReconnect("keepalive")
                }
            }
        }
        return any
    }

    fun encodeInfos(): String = infos().joinToString("\n") {
        listOf(it.id, it.title, it.status, if (it.connected) "1" else "0", it.kind.name)
            .joinToString("\t")
    }

    fun encodeStats(): String = list().joinToString("\n") {
        val st = it.stats.value
        listOf(
            it.id,
            st.load,
            st.cpuPercent.toString(),
            st.memUsedKb.toString(),
            st.memTotalKb.toString(),
            st.rxBps.toString(),
            st.txBps.toString(),
        ).joinToString("\t")
    }

    fun refreshAllStats() {
        list().forEach { s ->
            try {
                if (s.isLive() && s.kind.value == TransportKind.SSH) s.refreshStats()
            } catch (_: Throwable) {
            }
        }
    }

    fun notifyChange() {
        try { onChange?.invoke() } catch (_: Throwable) {}
    }
}
