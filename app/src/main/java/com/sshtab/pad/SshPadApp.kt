package com.sshtab.pad

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.sshtab.pad.crypto.CryptoBootstrap
import com.sshtab.pad.log.SessionLog
import com.sshtab.pad.service.SshSessionService
import com.sshtab.pad.ssh.SshSessionManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SshPadApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        CryptoBootstrap.install()
        SessionLog.init(File(filesDir, "logs"))
        SessionLog.event("app onCreate")
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) {
                SshSessionService.ensureRunning(this@SshPadApp)
            }
            override fun onActivityStopped(activity: Activity) {
                SshSessionService.ensureRunning(this@SshPadApp)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                SessionLog.event("activity destroyed ${activity.javaClass.simpleName}")
                SshSessionService.ensureRunning(this@SshPadApp)
            }
        })
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e("SshPadApp", "uncaught on ${thread.name}", error)
            SessionLog.event("uncaught ${thread.name}: ${error.javaClass.simpleName}: ${error.message}")
            if (thread.name.startsWith("ssh") || thread.name.startsWith("session") || thread.name.startsWith("telnet")) {
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
