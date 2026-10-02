package com.cocakova.charon.ssh

import com.cocakova.charon.data.db.KnownHostDao
import com.cocakova.charon.data.db.KnownHostEntity
import org.apache.sshd.agent.SshAgentFactory
import org.apache.sshd.agent.SshAgentServer
import org.apache.sshd.agent.local.AgentServerProxy
import org.apache.sshd.common.FactoryManager
import org.apache.sshd.common.channel.ChannelFactory
import org.apache.sshd.common.session.ConnectionService
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.shell.ShellFactory
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The new channels crossed for real: an in-process OpenSSH-speaking server (Apache
 * MINA sshd) on loopback, and the engine dialing it exactly as the app does —
 * keyboard-interactive with a stored password plus a one-time code, a crossing by
 * way of a jump shore (direct-tcpip), and a lent key the far side signs with.
 */
class SshjEngineCrossingTest {

    private val servers = ArrayList<SshServer>()
    private val met = Collections.synchronizedList(ArrayList<TrustRequest>())

    @After
    fun stop() = servers.forEach { runCatching { it.stop(true) } }

    private val ledger = object : KnownHostDao {
        val rows = Collections.synchronizedList(ArrayList<KnownHostEntity>())
        override suspend fun find(host: String, port: Int, keyType: String) =
            rows.firstOrNull { it.host == host && it.port == port && it.keyType == keyType }
        override suspend fun allFor(host: String, port: Int) = rows.filter { it.host == host && it.port == port }
        override suspend fun allOnce() = rows.toList()
        override suspend fun upsert(entry: KnownHostEntity) {
            rows.removeAll { it.host == entry.host && it.port == entry.port && it.keyType == entry.keyType }
            rows += entry
        }
    }
    private val verifier = KnownHostsVerifier(ledger, fingerprintOf = { "SHA256:test" }) { met += it; true }

    private fun server(configure: SshServer.() -> Unit): SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider()
        commandFactory = CommandFactory { _, command -> Reply("ran: $command\n") }
        passwordAuthenticator = null
        publickeyAuthenticator = null
        keyboardInteractiveAuthenticator = null
        configure()
        start()
    }.also { servers += it }

    @Test(timeout = 60_000)
    fun `keyboard-interactive takes the stored password once and asks the traveller for the code`() {
        val target = server {
            keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
                override fun generateChallenge(s: ServerSession, user: String, lang: String?, sub: String?) =
                    InteractiveChallenge().apply {
                        interactionName = "PAM"
                        interactionInstruction = "two factors"
                        addPrompt("Password: ", false)
                        addPrompt("Verification code: ", false)
                    }

                override fun authenticate(s: ServerSession, user: String, responses: List<String>) =
                    user == "ferry" && responses == listOf("obol", "424242")
            }
        }
        val asked = Collections.synchronizedList(ArrayList<Challenge>())
        val out = SshjEngine().execOnce(
            ConnectConfig(host = "127.0.0.1", port = target.port, username = "ferry", password = "obol"),
            "uptime",
            verifier,
        ) { c: Challenge -> asked += c; "424242" }
        assertEquals("ran: uptime", out.trim())
        // The stored password answered its own prompt; only the code reached the traveller.
        assertEquals(listOf("Verification code: "), asked.map { it.prompt })
        assertEquals("PAM", asked.single().name)
        assertEquals("two factors", asked.single().instruction)
    }

    @Test(timeout = 60_000)
    fun `a refused answer turns the crossing back`() {
        val target = server {
            keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
                override fun generateChallenge(s: ServerSession, user: String, lang: String?, sub: String?) =
                    InteractiveChallenge().apply { addPrompt("Verification code: ", false) }

                override fun authenticate(s: ServerSession, user: String, responses: List<String>) =
                    responses == listOf("424242")
            }
        }
        val failed = runCatching {
            SshjEngine().execOnce(
                ConnectConfig(host = "127.0.0.1", port = target.port, username = "ferry"),
                "uptime",
                verifier,
            ) { _: Challenge -> null } // the traveller turned back
        }
        assertTrue(failed.isFailure)
    }

    @Test(timeout = 60_000)
    fun `the crossing goes by way of the jump shore`() {
        val dialed = Collections.synchronizedList(ArrayList<SshdSocketAddress>())
        val jump = server {
            passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == "keeper" && p == "gate" }
            forwardingFilter = object : AcceptAllForwardingFilter() {
                override fun canConnect(type: TcpForwardingFilter.Type, address: SshdSocketAddress, session: Session) =
                    super.canConnect(type, address, session).also { dialed += address }
            }
        }
        val target = server {
            passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == "ferry" && p == "obol" }
        }
        val out = SshjEngine().execOnce(
            ConnectConfig(
                host = "127.0.0.1", port = target.port, username = "ferry", password = "obol",
                jump = ConnectConfig(host = "127.0.0.1", port = jump.port, username = "keeper", password = "gate"),
            ),
            "hostname",
            verifier,
        )
        assertEquals("ran: hostname", out.trim())
        assertEquals(listOf(target.port), dialed.map { it.port })
        // Each shore met the ferryman on its own terms.
        assertEquals(setOf(jump.port, target.port), met.map { it.port }.toSet())
    }

    @Test(timeout = 60_000)
    fun `the lent key signs for the far shore and nothing more`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val agents = CapturingAgents()
        val probe = AgentProbe(agents)
        val target = server {
            publickeyAuthenticator = PublickeyAuthenticator { u, k, _ -> u == "ferry" && k.encoded.contentEquals(keys.public.encoded) }
            forwardingFilter = AcceptAllForwardingFilter()
            agentFactory = agents
            shellFactory = ShellFactory { probe }
        }
        val session = TerminalSession("agent")
        val conn = SshjEngine().connectShell(
            ConnectConfig(
                host = "127.0.0.1", port = target.port, username = "ferry",
                privateKeyPem = rsaPem(keys), agentForwarding = true, autoReconnect = false,
            ),
            session,
            verifier,
        )
        try {
            assertTrue("the far shore never asked the agent", probe.done.await(30, TimeUnit.SECONDS))
            assertNull(probe.failure.get()?.stackTraceToString(), probe.failure.get())
            assertEquals(listOf(keys.public.encoded.toList()), probe.identities.get().map { it.encoded.toList() })
            val (algorithm, signature) = probe.signature.get()
            assertEquals("rsa-sha2-256", algorithm)
            val check = Signature.getInstance("SHA256withRSA").apply {
                initVerify(keys.public); update(AgentProbe.TOLL)
            }
            assertTrue("the signature holds", check.verify(signature))
            assertTrue("the traveller is told the key was lent", session.keyLent.value > 0)
        } finally {
            conn.disconnect()
        }
    }

    private fun rsaPem(keys: KeyPair): String {
        val pkcs1 = PrivateKeyInfo.getInstance(keys.private.encoded).parsePrivateKey().toASN1Primitive().encoded
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs1)
        return "-----BEGIN RSA PRIVATE KEY-----\n$body\n-----END RSA PRIVATE KEY-----\n"
    }

    /** A one-line answer for an exec request. */
    private class Reply(private val text: String) : Command {
        private var out: OutputStream? = null
        private var exit: ExitCallback? = null
        override fun setInputStream(`in`: InputStream?) = Unit
        override fun setOutputStream(out: OutputStream?) { this.out = out }
        override fun setErrorStream(err: OutputStream?) = Unit
        override fun setExitCallback(callback: ExitCallback?) { exit = callback }
        override fun start(channel: ChannelSession, env: Environment) {
            out!!.write(text.toByteArray()); out!!.flush()
            exit!!.onExit(0)
        }
        override fun destroy(channel: ChannelSession) = Unit
    }

    /** The server's side of agent forwarding, kept where the probe can reach it. */
    private class CapturingAgents : SshAgentFactory {
        val proxy = AtomicReference<AgentServerProxy?>()
        override fun getChannelForwardingFactories(manager: FactoryManager): List<ChannelFactory> = emptyList()
        override fun createClient(session: Session?, manager: FactoryManager) = proxy.get()!!.createClient()
        override fun createServer(service: ConnectionService): SshAgentServer = AgentServerProxy(service).also { proxy.set(it) }
    }

    /** A shell that, once started, asks the lent agent for its keys and one signature. */
    private class AgentProbe(private val agents: CapturingAgents) : Command {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val identities = AtomicReference<List<PublicKey>>(emptyList())
        val signature = AtomicReference<Pair<String, ByteArray>>()
        override fun setInputStream(`in`: InputStream?) = Unit
        override fun setOutputStream(out: OutputStream?) = Unit
        override fun setErrorStream(err: OutputStream?) = Unit
        override fun setExitCallback(callback: ExitCallback?) = Unit
        override fun start(channel: ChannelSession, env: Environment) {
            thread(isDaemon = true) {
                try {
                    val agent = agents.proxy.get()!!.createClient()
                    val ids = agent.identities.map { it.key }
                    identities.set(ids)
                    val signed = agent.sign(channel.session, ids.first(), "rsa-sha2-256", TOLL)
                    signature.set(signed.key to signed.value)
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    done.countDown()
                }
            }
        }
        override fun destroy(channel: ChannelSession) = Unit

        companion object {
            val TOLL = "the toll for the crossing".toByteArray()
        }
    }
}
