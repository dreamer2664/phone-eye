package com.dreamer2664.phoneeye

import android.content.Context

/**
 * The tunnel supervisor: try providers in order, keep the winner alive, and never
 * leave a dead URL on the screen.
 *
 * Order (mirrors pc-eye first, then the no-binary fallbacks):
 *   1. Cloudflare quick tunnel  → https://*.trycloudflare.com   (needs the binary)
 *   2. localhost.run (SSH)       → https://*.lhr.life            (pure Java)
 *   3. serveo.net (SSH)          → https://*.serveousercontent.com
 *   4. LAN only — said out loud: an agent outside this network cannot reach the
 *      phone, exactly like pc-eye's launcher when its tunnel fails. Retried every
 *      minute while the session runs, because phone networks change under you.
 *
 * A tunnel that dies mid-session (tower handover, Wi-Fi change) is restarted with
 * backoff up to MAX_RESTARTS times. A restart means a NEW random URL — quick
 * tunnels don't do stable names for free — so EyeState changes and the human
 * re-pastes. That is stated in PHONE-EYE.md, not hidden.
 */
class TunnelManager(
    private val ctx: Context,
    private val port: Int,
    private val onStateChanged: () -> Unit
) {
    @Volatile private var running = false
    @Volatile private var worker: Thread? = null
    @Volatile private var cloudflare: CloudflareQuickTunnel? = null
    @Volatile private var ssh: SshReverseTunnel? = null

    companion object {
        private const val MAX_RESTARTS = 12
    }

    fun start() {
        if (running) return
        running = true
        worker = Thread({ supervise() }, "phoneeye-tunnels").apply { isDaemon = true }
        worker?.start()
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        try { cloudflare?.stop() } catch (e: Exception) { }
        try { ssh?.stop() } catch (e: Exception) { }
        cloudflare = null
        ssh = null
    }

    private fun publish(url: String?, provider: String, detail: String) {
        EyeState.publicUrl = url
        EyeState.tunnelProvider = provider
        EyeState.tunnelDetail = detail
        onStateChanged()
    }

    private fun anyAlive(): Boolean =
        (cloudflare?.isAlive() == true) || (ssh?.isAlive() == true)

    private fun sleepWhileRunning(ms: Long) {
        var left = ms
        while (running && left > 0) {
            val slice = minOf(left, 500L)
            try { Thread.sleep(slice) } catch (e: InterruptedException) { return }
            left -= slice
        }
    }

    private fun supervise() {
        var restarts = 0
        while (running) {
            val up = tryProviders()
            if (!running) break
            if (up) {
                restarts = 0
                while (running && anyAlive()) sleepWhileRunning(3_000)
                if (!running) break
                restarts++
                EyeLog.log("tunnel: connection lost (restart $restarts/$MAX_RESTARTS) — the old URL is dead, fetching a new one")
                publish(null, "reconnecting", "tunnel dropped; fetching a new URL (attempt $restarts/$MAX_RESTARTS)")
                cleanupProviders()
                if (restarts > MAX_RESTARTS) {
                    publish(
                        null, "none (LAN only)",
                        "the tunnel kept dying (${MAX_RESTARTS} restarts). Stopped retrying; LAN URL still works on this Wi-Fi. Press Stop, then Start, to try a fresh session."
                    )
                    EyeLog.log("tunnel: gave up after $MAX_RESTARTS restarts; LAN only until the session is restarted")
                    return
                }
                sleepWhileRunning(10_000)
            } else {
                // All providers failed this round. LAN-only, said plainly, retried on
                // a slow timer: a phone that walks back into good coverage heals itself.
                sleepWhileRunning(60_000)
            }
        }
    }

    private fun cleanupProviders() {
        try { cloudflare?.stop() } catch (e: Exception) { }
        try { ssh?.stop() } catch (e: Exception) { }
        cloudflare = null
        ssh = null
    }

    private fun tryProviders(): Boolean {
        val failures = mutableListOf<String>()

        // 1. Cloudflare
        publish(null, "cloudflare…", "asking Cloudflare for a quick tunnel")
        try {
            val cf = CloudflareQuickTunnel(ctx, port) { reason -> EyeLog.log("tunnel: $reason") }
            val u = cf.start()
            cloudflare = cf
            publish(u, "cloudflare (trycloudflare.com)", "")
            return true
        } catch (e: Refusal) {
            failures.add("cloudflare: ${e.message}")
            EyeLog.log("tunnel: cloudflare failed — ${e.message}")
        } catch (e: Exception) {
            failures.add("cloudflare: ${e.javaClass.simpleName}: ${e.message}")
            EyeLog.log("tunnel: cloudflare failed — ${e.javaClass.simpleName}: ${e.message}")
        }
        if (!running) return false

        // 2. localhost.run
        publish(null, "localhost.run…", "Cloudflare failed; trying the SSH fallback")
        try {
            val t = SshReverseTunnel.localhostRun(port) { reason -> EyeLog.log("tunnel: $reason") }
            val u = t.start()
            ssh = t
            publish(u, "localhost.run (SSH fallback)", "Cloudflare failed; this URL comes from localhost.run")
            return true
        } catch (e: Refusal) {
            failures.add("localhost.run: ${e.message}")
            EyeLog.log("tunnel: localhost.run failed — ${e.message}")
        } catch (e: Exception) {
            failures.add("localhost.run: ${e.javaClass.simpleName}: ${e.message}")
            EyeLog.log("tunnel: localhost.run failed — ${e.javaClass.simpleName}: ${e.message}")
        }
        if (!running) return false

        // 3. serveo.net
        publish(null, "serveo.net…", "trying the second SSH fallback")
        try {
            val t = SshReverseTunnel.serveo(port) { reason -> EyeLog.log("tunnel: $reason") }
            val u = t.start()
            ssh = t
            publish(u, "serveo.net (SSH fallback)", "Cloudflare + localhost.run failed; this URL comes from serveo.net. Browsers may see a one-click warning page — API clients do not.")
            return true
        } catch (e: Refusal) {
            failures.add("serveo: ${e.message}")
            EyeLog.log("tunnel: serveo failed — ${e.message}")
        } catch (e: Exception) {
            failures.add("serveo: ${e.javaClass.simpleName}: ${e.message}")
            EyeLog.log("tunnel: serveo failed — ${e.javaClass.simpleName}: ${e.message}")
        }

        // 4. LAN only — honest, like pc-eye's launcher.
        val lan = EyeState.lanUrl
        publish(
            null, "none (LAN only)",
            "NO public tunnel could be established, so an agent outside this network CANNOT reach the phone. " +
            "On this Wi-Fi the LAN URL still works${if (lan != null) " ($lan)" else ""}. Retrying every minute. " +
            "Why: " + failures.joinToString(" | ")
        )
        EyeLog.log("tunnel: ALL providers failed — LAN only, retrying every minute")
        failures.forEach { EyeLog.log("tunnel:   $it") }
        return false
    }
}
