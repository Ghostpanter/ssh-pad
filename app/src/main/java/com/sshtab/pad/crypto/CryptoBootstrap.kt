package com.sshtab.pad.crypto

import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

object CryptoBootstrap {
    @Volatile private var ready = false

    fun install() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            while (Security.getProvider("BC") != null) {
                Security.removeProvider("BC")
            }
            val full = BouncyCastleProvider()
            if (Security.getProvider(full.name) == null) {
                Security.insertProviderAt(full, 1)
            }
            ready = true
        }
    }
}
