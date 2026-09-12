package com.sshtab.pad.ssh

enum class TransportKind {
    SSH,
    TELNET,
}

enum class AuthMethod {
    PASSWORD,
    KEY,
}

data class HostProfile(
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
    val kind: TransportKind = TransportKind.SSH,
    val auth: AuthMethod = AuthMethod.PASSWORD,
    val privateKey: String = "",
    val passphrase: String = "",
) {
    fun title(): String = when (kind) {
        TransportKind.SSH -> "${username.ifBlank { "user" }}@${host}:${port}"
        TransportKind.TELNET -> "telnet://$host:$port"
    }
}

data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
)

data class SessionInfo(
    val id: String,
    val title: String,
    val status: String,
    val connected: Boolean,
    val kind: TransportKind,
)
