package com.cocakova.charon

import android.app.Application
import com.cocakova.charon.data.db.CharonDb
import com.cocakova.charon.data.repository.CommandHistory
import com.cocakova.charon.data.repository.HostVault
import com.cocakova.charon.data.repository.KeyVault
import com.cocakova.charon.ssh.SessionManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class CharonApp : Application() {

    // Manual DI, Keryx-style: process-wide singletons wired here.
    lateinit var db: CharonDb
        private set
    lateinit var hostVault: HostVault
        private set
    lateinit var keyVault: KeyVault
        private set
    lateinit var sessionManager: SessionManager
        private set
    lateinit var commandHistory: CommandHistory
        private set

    override fun onCreate() {
        super.onCreate()
        installBouncyCastle()
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
        db = CharonDb.build(this)
        keyVault = KeyVault(db.identities())
        hostVault = HostVault(db.hosts(), keyVault)
        commandHistory = CommandHistory(this)
        sessionManager = SessionManager(this, db.hosts(), db.knownHosts(), commandHistory)
    }

    private fun installBouncyCastle() {
        // Android ships a stripped-down BouncyCastle under the same provider name "BC",
        // which shadows the real one and breaks sshj's modern algorithms (ed25519,
        // chacha20-poly1305, openssh-key-v1 parsing). Swap in the full provider first.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
