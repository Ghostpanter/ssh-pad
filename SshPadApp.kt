package com.sshtab.pad

import android.app.Application
import com.sshtab.pad.crypto.CryptoBootstrap

class SshPadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CryptoBootstrap.install()
    }
}
