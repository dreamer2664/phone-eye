package com.dreamer2664.phoneeye

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthNone
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The fallback tunnels: SSH remote-port-forwards to the free, no-account services
 * localhost.run and serveo.net (the `ssh -R 80:localhost:PORT ...` trick), done in
 * pure Java with sshj. They exist because they need NO native binary and NO Go DNS:
 * if cloudflared cannot run on a phone, these usually can.
 *
 * Each service prints its public URL on the session banner (plus, in localhost.run's
 * case, an ANSI QR code of impressive uselessness to a chat agent). We open a shell,
 * request the forward, and read lines until the provider's URL shape appears.
 * Banner formats were captured live from both services in October 2026; if a
 * provider changes its banner, this fails with a sentence saying exactly that,
 * and the raw lines are in the log to fix the regex from.
 *
 * Host-key honesty: PromiscuousVerifier accepts any host key. These are ephemeral,
 * TLS-terminating public relays (like trycloudflare, they see the HTTP after their
 * edge); the phone-eye TOKEN is what protects the phone — see docs/ARCHITECTURE.md.
 */
class SshReverseTunnel(
    val providerName: String,
    private val host: String,
    private val user: String,
    private val localPort: Int,
    private val urlPattern: Regex,
    private val rejectHosts: Set<String>,
    private val onExit: (String) -> Unit
) {
    companion object {
        @Volatile private var bcReady = false

        /**
         * Android ships a cut-down Bouncy Castle under the name "BC". sshj needs the
         * real one, so swap ours to the front before any SSHClient exists.
         */
        @Synchronized
        fun ensureBouncyCastle() {
            if (bcReady) return
            try {
                Security.removeProvider("BC")
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            } catch (e: Exception) {
                EyeLog.log("tunnel/ssh: Bouncy Castle provider swap failed (${e.javaClass.simpleName}) — SSH tunnels may fail")
            }
            bcReady = true
        }

        fun localhostRun(localPort: Int, onExit: (String) -> Unit) = SshReverseTunnel(
            providerName = "localhost.run",
            host = "localhost.run",
            user = "nokey",
            localPort = localPort,
            urlPattern = Regex("""https://[a-z0-9]+\.lhr\.life"""),
            rejectHosts = setOf("admin.lhr.life"),
            onExit = onExit
        )

        fun serveo(localPort: Int, onExit: (String) -> Unit) = SshReverseTunnel(
            providerName = "serveo.net",
            host = "serveo.net",
            user = "serveo",
            localPort = localPort,
            urlPattern = Regex("""https://[a-z0-9-]+\.(serveousercontent\.com|serveo\.net)"""),
            rejectHosts = setOf("console.serveo.net"),
            onExit = onExit
        )
    }

    @Volatile private var client: SSHClient? = null
    @Volatile private var session: Session? = null
    @Volatile private var stopping = false
    @Volatile var url: String? = null
        private set

    fun isAlive(): Boolean {
        val c = client ?: return false
        return try { c.isConnected && c.isAuthenticated } catch (e: Exception) { false }
    }

    fun start(timeoutMs: Long = 45_000): String {
        ensureBouncyCastle()
        stopping = false
        val deadline = System.currentTimeMillis() + maxOf(timeoutMs, 15_000)

        // These services are variations on "any auth is fine": try, in order, the
        // ways OpenSSH would get in, each with a fresh connection, and keep the
        // sentences from all of them for the final refusal.
        var lastFailure = "no connection was attempted"
        val keyPair: KeyPair by lazy {
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        }
        val strategies = listOf<Pair<String, (SSHClient) -> Unit>>(
            "none" to { ssh -> ssh.auth(user, AuthNone()) },
            "publickey" to { ssh ->
                val kp = keyPair
                ssh.authPublickey(user, object : KeyProvider {
                    override fun getPrivate(): PrivateKey = kp.private
                    override fun getPublic(): PublicKey = kp.public
                    override fun getType(): KeyType = KeyType.RSA
                })
            },
            "password(empty)" to { ssh -> ssh.authPassword(user, "") }
        )

        for ((name, auth) in strategies) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 3_000) break
            try {
                EyeLog.log("tunnel/ssh: trying $providerName as $user (auth: $name) ...")
                return connectOnce(auth, remaining)
            } catch (e: Refusal) {
                lastFailure = e.message ?: lastFailure
                EyeLog.log("tunnel/ssh: $providerName auth=$name failed — $lastFailure")
                disconnectQuietly()
            } catch (e: Exception) {
                lastFailure = "${e.javaClass.simpleName}: ${e.message}"
                EyeLog.log("tunnel/ssh: $providerName auth=$name failed — $lastFailure")
                disconnectQuietly()
            }
            if (stopping) break
        }
        throw Refusal(
            503,
            "$providerName would not open an SSH tunnel (tried none / publickey / empty-password). Last failure: $lastFailure"
        )
    }

    private fun connectOnce(auth: (SSHClient) -> Unit, timeoutMs: Long): String {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connectTimeout = 15_000
        ssh.timeout = 30_000
        ssh.connect(host, 22)
        auth(ssh)
        if (!ssh.isAuthenticated) {
            try { ssh.disconnect() } catch (e: Exception) { }
            throw Refusal(503, "$providerName did not accept that authentication")
        }
        client = ssh

        val sess = ssh.startSession()
        session = sess

        val foundLatch = CountDownLatch(1)
        @Volatile var foundUrl: String? = null
        val onLine: (String) -> Unit = { raw ->
            val line = stripAnsi(raw)
            if (foundUrl == null) {
                val m = urlPattern.find(line)
                if (m != null) {
                    val candidate = m.value
                    val candidateHost = candidate.removePrefix("https://").substringBefore('/')
                    if (candidateHost !in rejectHosts) {
                        foundUrl = candidate
                        foundLatch.countDown()
                    }
                }
            }
            if (line.isNotBlank()) EyeLog.log("tunnel/ssh[$providerName]> ${line.take(220)}")
        }

        // Open the shell BEFORE requesting the forward: the banner that carries the
        // URL is printed to the session, and a reader that arrives late loses it.
        var shell: Session.Shell? = null
        try {
            sess.allocateDefaultPTY()
        } catch (e: Exception) {
            EyeLog.log("tunnel/ssh: $providerName refused a PTY (${e.javaClass.simpleName}) — continuing without one")
        }
        try {
            shell = sess.startShell()
        } catch (e: Exception) {
            EyeLog.log("tunnel/ssh: $providerName refused a shell (${e.javaClass.simpleName}) — its URL may never be printed")
        }
        shell?.let { sh ->
            startLineReader(sh.inputStream, onLine)
            startLineReader(sh.errorStream, onLine)
        }

        val forward = ssh.remotePortForwarder.bind(
            RemotePortForwarder.Forward(80),
            SocketForwardingConnectListener(InetSocketAddress("127.0.0.1", localPort))
        )
        EyeLog.log("tunnel/ssh: $providerName forward bound (requested :80, service answered :${forward.port}); waiting for its URL banner ...")

        val announced = foundLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val u = foundUrl
        if (!announced || u == null) {
            disconnectQuietly()
            throw Refusal(
                503,
                "$providerName accepted the forward but never printed a public URL within ${timeoutMs / 1000}s. " +
                "If the service changed its banner format, the lines it DID print are in the on-phone log (GET /log) — " +
                "that is where the regex in SshTunnel.kt gets fixed from."
            )
        }
        url = u
        EyeLog.log("tunnel/ssh: UP via $providerName $u")

        // Watchdog: a dead TCP connection must become a state change, not a stale URL.
        Thread({
            while (!stopping && client === ssh) {
                try { Thread.sleep(3_000) } catch (e: InterruptedException) { break }
                val alive = try { ssh.isConnected && ssh.isAuthenticated } catch (e: Exception) { false }
                if (!alive) {
                    val wasUp = url
                    url = null
                    if (!stopping && wasUp != null) {
                        EyeLog.log("tunnel/ssh: $providerName connection dropped")
                        onExit("$providerName SSH connection dropped")
                    }
                    break
                }
            }
        }, "phoneeye-ssh-watch").apply { isDaemon = true }.start()

        return u
    }

    private fun startLineReader(stream: InputStream, onLine: (String) -> Unit) {
        Thread({
            try {
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { onLine(it) }
                }
            } catch (e: Exception) { }
        }, "phoneeye-ssh-lines").apply { isDaemon = true }.start()
    }

    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private fun stripAnsi(s: String): String = ansi.replace(s, "").replace("\r", "").trim()

    private fun disconnectQuietly() {
        try { session?.close() } catch (e: Exception) { }
        session = null
        try { client?.disconnect() } catch (e: Exception) { }
        try { client?.close() } catch (e: Exception) { }
        client = null
    }

    fun stop() {
        stopping = true
        url = null
        disconnectQuietly()
    }
}
