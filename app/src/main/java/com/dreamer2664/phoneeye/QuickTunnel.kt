package com.dreamer2664.phoneeye

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A Cloudflare quick tunnel (*.trycloudflare.com, no account) from an Android app —
 * the same tunnel pc-eye's launcher opens, with the dance Android forces on it:
 *
 * cloudflared is a static Go binary, and Go's resolver looks for /etc/resolv.conf,
 * which does not exist on Android. So `cloudflared tunnel --url ...` (what pc-eye
 * runs on Windows) dies on DNS before it starts. The fix, proven in the wild:
 *
 *  1. KOTLIN asks https://api.trycloudflare.com/tunnel for a tunnel (DNS works here);
 *  2. KOTLIN resolves region1/region2.v2.argotunnel.com and passes raw IPs via --edge;
 *  3. cloudflared runs from nativeLibraryDir (the only exec-able place, Android 10+),
 *     with the run token in TUNNEL_TOKEN (environment, never argv — argv is in `ps`).
 *
 * Recipe credits: ashokvarmamatta's "Cloudflare Tunnels on Android" guide and
 * Thinai PR #72 (quick_tunnel.dart). phone-eye's version is Kotlin, with pc-eye's
 * error sentences wrapped around every failure. See docs/ARCHITECTURE.md.
 */
class CloudflareQuickTunnel(
    private val ctx: Context,
    private val port: Int,
    private val onExit: (String) -> Unit
) {
    @Volatile private var process: Process? = null
    @Volatile private var stopping: Boolean = false
    @Volatile var url: String? = null
        private set

    private data class Provisioned(val hostname: String, val id: String, val accountTag: String, val secret: String)

    fun isAlive(): Boolean = process?.isAlive == true

    fun start(timeoutMs: Long = 45_000): String {
        stopProcess()
        stopping = false

        val binary = File(ctx.applicationInfo.nativeLibraryDir, "libcloudflared.so")
        if (!binary.exists()) {
            throw Refusal(
                503,
                "cloudflared is not inside this APK (built with -PnoCloudflared, or the fetch was skipped). " +
                "The Cloudflare tunnel cannot run; SSH and LAN fallbacks still apply. Rebuild without the flag for trycloudflare URLs."
            )
        }

        EyeLog.log("tunnel/cloudflare: requesting a quick tunnel from api.trycloudflare.com ...")
        val provisioned = provision()
        EyeLog.log("tunnel/cloudflare: assigned ${provisioned.hostname}")

        val edges = resolveEdges()
        if (edges.isEmpty()) {
            throw Refusal(
                503,
                "could not resolve region1/region2.v2.argotunnel.com from the app — this network's DNS is broken or filtered. " +
                "A captive-portal Wi-Fi sign-in page does exactly this. Join a healthy network and restart phone-eye."
            )
        }
        EyeLog.log("tunnel/cloudflare: edges ${edges.joinToString(", ")}")

        val args = mutableListOf(binary.absolutePath, "tunnel", "--no-autoupdate")
        edges.forEach { e -> args.add("--edge"); args.add(e) }
        args.addAll(listOf("--protocol", "http2", "run", "--url", "http://127.0.0.1:$port"))

        val pb = ProcessBuilder(args)
        pb.directory(ctx.cacheDir)
        val env = pb.environment()
        env["TUNNEL_TOKEN"] = RunToken.encode(provisioned.accountTag, provisioned.id, provisioned.secret)
        env["HOME"] = ctx.filesDir.absolutePath
        env["TMPDIR"] = ctx.cacheDir.absolutePath
        // Go's x509 looks in /etc/ssl/certs; Android's public CA store lives here.
        if (File("/system/etc/security/cacerts").isDirectory) {
            env["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
        }
        pb.redirectErrorStream(false)

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            throw Refusal(
                503,
                "could not execute cloudflared on this phone (${e.javaClass.simpleName}: ${e.message}). " +
                "Usual causes: a 32-bit-only device (phone-eye ships arm64), or a vendor ROM with extra exec restrictions. " +
                "The SSH fallback tunnels do not exec anything and may still work."
            )
        }
        process = proc

        val ready = CountDownLatch(1)
        @Volatile var lastError = ""

        Thread({
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line -> EyeLog.log("cloudflared> ${line.take(300)}") }
                }
            } catch (e: Exception) { }
        }, "phoneeye-cf-out").apply { isDaemon = true }.start()

        Thread({
            try {
                proc.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        EyeLog.log("cloudflared> ${line.take(300)}")
                        if (line.contains("Registered tunnel connection")) ready.countDown()
                        if (line.contains(" ERR ")) lastError = line.take(300)
                    }
                }
            } catch (e: Exception) { }
        }, "phoneeye-cf-err").apply { isDaemon = true }.start()

        Thread({
            val code = try { proc.waitFor() } catch (e: Exception) { -1 }
            ready.countDown() // unblock a waiter; it will check isAlive and fail honestly
            if (!stopping && process === proc) {
                val wasUp = url
                url = null
                process = null
                val reason = "cloudflared exited (code $code)${if (lastError.isNotEmpty()) ": $lastError" else ""}"
                EyeLog.log("tunnel/cloudflare: $reason")
                if (wasUp != null) onExit(reason)
            }
        }, "phoneeye-cf-exit").apply { isDaemon = true }.start()

        val got = ready.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!got || !proc.isAlive || !hasRegistered(lastError, proc)) {
            // hasRegistered distinguishes "counted down because registered" from
            // "counted down because the process died": if it died, isAlive is false.
            val alive = proc.isAlive
            if (!got || !alive) {
                stopProcess()
                throw Refusal(
                    503,
                    "cloudflared did not register a tunnel connection within ${timeoutMs / 1000}s" +
                    (if (lastError.isNotEmpty()) ". Last error it printed: $lastError" else "") +
                    ". The full cloudflared output is in the on-phone log (GET /log). " +
                    "Port 7844 blocked by this network, or a proxy in the way, are the usual causes."
                )
            }
        }

        val u = "https://${provisioned.hostname}"
        url = u
        EyeLog.log("tunnel/cloudflare: UP $u")
        return u
    }

    private fun hasRegistered(lastError: String, proc: Process): Boolean = proc.isAlive

    private fun provision(): Provisioned {
        val conn = URL("https://api.trycloudflare.com/tunnel").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("User-Agent", "cloudflared/phone-eye")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(0)
        val code = conn.responseCode
        val body = try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        } catch (e: Exception) {
            ""
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
        if (code !in 200..299) {
            throw Refusal(
                503,
                "api.trycloudflare.com answered HTTP $code to the tunnel request. Quick tunnels need plain outbound " +
                "HTTPS — a proxy, a firewall, or a captive Wi-Fi portal will do exactly this."
            )
        }
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw Refusal(503, "api.trycloudflare.com answered something that is not JSON (a Wi-Fi sign-in page in disguise?)")
        }
        val result = root.optJSONObject("result")
            ?: throw Refusal(503, "trycloudflare answered without a tunnel (success=${root.opt("success")}). Try again in a minute.")
        val hostname = result.optString("hostname")
        val id = result.optString("id")
        val accountTag = result.optString("account_tag")
        val secret = result.optString("secret")
        if (hostname.isEmpty() || id.isEmpty() || accountTag.isEmpty() || secret.isEmpty()) {
            // Key NAMES only — the secret itself never enters a message or a log.
            val keys = mutableListOf<String>()
            result.keys().forEach { keys.add(it) }
            throw Refusal(503, "trycloudflare's answer is missing tunnel fields (got keys: ${keys.joinToString(",")}) — Cloudflare may have changed the quick-tunnel API")
        }
        return Provisioned(hostname, id, accountTag, secret)
    }

    private fun resolveEdges(): List<String> {
        val edges = mutableListOf<String>()
        for (host in listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")) {
            try {
                val ips = InetAddress.getAllByName(host).filterIsInstance<Inet4Address>()
                ips.firstOrNull()?.hostAddress?.let { edges.add("$it:7844") }
            } catch (e: Exception) {
                EyeLog.log("tunnel/cloudflare: could not resolve $host (${e.javaClass.simpleName})")
            }
        }
        return edges
    }

    private fun stopProcess() {
        val proc = process ?: return
        process = null
        try {
            proc.destroy()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
        } catch (e: Exception) { }
    }

    fun stop() {
        stopping = true
        url = null
        stopProcess()
    }
}
