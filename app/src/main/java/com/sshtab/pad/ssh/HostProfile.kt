package com.sshtab.pad.ssh

enum class TransportKind {
    SSH,
    TELNET,
}

data class HostProfile(
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String,
    val kind: TransportKind = TransportKind.SSH,
)
