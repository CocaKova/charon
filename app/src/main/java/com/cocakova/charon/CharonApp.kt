package com.cocakova.charon

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class CharonApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installBouncyCastle()
    }

    private fun installBouncyCastle() {
        // Android ships a stripped-down BouncyCastle under the same provider name "BC",
        // which shadows the real one and breaks sshj's modern algorithms (ed25519,
        // chacha20-poly1305, openssh-key-v1 parsing). Swap in the full provider first.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
