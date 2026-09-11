package com.sshtab.pad.ssh

data class HostProfile(
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String,
)
