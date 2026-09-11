package com.sshtab.pad

import android.app.Application
import android.util.Log
import com.sshtab.pad.crypto.CryptoBootstrap
import com.sshtab.pad.ssh.SshSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SshPadApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        CryptoBootstrap.install()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e("SshPadApp", "uncaught on ${thread.name}", error)
            if (thread.name.startsWith("ssh") || thread.name.contains("sshd", ignoreCase = true)) {
                SshSessionManager.fail("连接异常: ${error.javaClass.simpleName}: ${error.message}")
            } else {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    companion object {
        lateinit var instance: SshPadApp
            private set
    }
}
