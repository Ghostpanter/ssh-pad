package com.sshtab.pad

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.sshtab.pad.crypto.CryptoBootstrap
import com.sshtab.pad.log.SessionLog
import com.sshtab.pad.service.SessionClient
import com.sshtab.pad.service.SshSessionService
import com.sshtab.pad.ui.AppSettings
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
        AppSettings.init(this)
        SessionLog.init(File(filesDir, "logs"))
        SessionLog.event("app onCreate pid=${Process.myPid()} proc=${processName()}")
        if (!isSessionProcess()) {
            SessionClient.bind(this)
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
                    SshSessionService.ensureRunning(this@SshPadApp)
                }
            })
        }
    }

    companion object {
        lateinit var instance: SshPadApp
            private set

        fun processName(): String {
            if (Build.VERSION.SDK_INT >= 28) return getProcessName()
            return try {
                Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentProcessName")
                    .invoke(null) as String
            } catch (_: Exception) {
                "com.sshtab.pad"
            }
        }

        fun isSessionProcess(): Boolean = processName().endsWith(":session")
    }
}
