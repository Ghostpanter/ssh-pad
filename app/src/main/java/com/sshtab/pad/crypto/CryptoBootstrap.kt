package com.sshtab.pad.crypto

import java.security.Security
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider

object CryptoBootstrap {
    @Volatile private var ready = false

    fun install() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            // Android ships a stub "BC" that cannot do X25519. Replace it.
            while (Security.getProvider("BC") != null) {
                Security.removeProvider("BC")
            }
            val full = BouncyCastleProvider()
            if (Security.getProvider(full.name) == null) {
                Security.insertProviderAt(full, 1)
            }
            if (Security.getProvider(EdDSASecurityProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(EdDSASecurityProvider(), 2)
            }
            System.setProperty("java.security.egd", "file:/dev/urandom")
            ready = true
        }
    }
}
