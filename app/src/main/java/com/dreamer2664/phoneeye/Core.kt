package com.dreamer2664.phoneeye

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Pure-JVM core: no Android imports, so every rule here is unit-tested on a laptop
 * (app/src/test). This file carries the discipline pc-eye learned the hard way:
 * tokens compared in constant time, coordinates converted — never guessed — and a
 * rate limiter that fails OPEN so a broken limiter can never lock the owner out.
 */
object Core {
    const val VERSION = "1.0.0"
    const val PORT = 8791 // pc-eye is 8790; the phone is 8791. A matched pair, on purpose.

    /** 32 hex chars = 128 bits from SecureRandom (pc-eye: secrets.token_hex(16)). */
    fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Constant-time compare (pc-eye: hmac.compare_digest). Empty given == never ok. */
    fun tokenEquals(given: String?, actual: String): Boolean {
        if (given.isNullOrEmpty() || actual.isEmpty()) return false
        return MessageDigest.isEqual(
            given.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * The coordinate conversion from PC-EYE.md, as one testable formula:
     *   real = seen * full / sent
     * An agent who taps in the downscaled image's pixels instead of converting is
     * repeating pc-eye's most expensive bug. Rounds to nearest, never clamps:
     * clamping is how a wrong click learns to look like success.
     */
    fun convertCoord(seen: Int, sent: Int, full: Int): Int {
        require(sent > 0 && full > 0) { "sent and full sizes must be positive (sent=$sent full=$full)" }
        return Math.round(seen.toDouble() * full.toDouble() / sent.toDouble()).toInt()
    }

    /** /shot?changed= support (pc-eye: content_hash). 16 hex chars of SHA-256. */
    fun contentHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

/** A refusal with an HTTP code and a sentence. The server always answers — see Server.kt. */
class Refusal(val code: Int, message: String) : Exception(message)

/**
 * Token bucket, pc-eye's RateBucket in Kotlin. Sustained rate + burst, and on any
 * internal error take() returns true: a limiter is a seatbelt, not a steering lock.
 */
class RateBucket(private val capacity: Double, private val refillPerSec: Double) {
    private var tokens: Double = capacity
    private var lastNanos: Long = System.nanoTime()

    @Synchronized
    fun take(cost: Double = 1.0): Boolean {
        return try {
            val now = System.nanoTime()
            val elapsedSec = (now - lastNanos) / 1_000_000_000.0
            lastNanos = now
            if (elapsedSec > 0) tokens = minOf(capacity, tokens + elapsedSec * refillPerSec)
            if (tokens >= cost) {
                tokens -= cost
                true
            } else {
                false
            }
        } catch (e: Exception) {
            true // fail open, like pc-eye: the limiter must never break the phone's owner
        }
    }
}

/** File-root sandbox checks on canonical path STRINGS, so they are unit-testable. */
object PathSandbox {
    /** True when [canonicalChild] is [canonicalRoot] itself or underneath it. */
    fun isInside(canonicalChild: String, canonicalRoot: String): Boolean {
        if (canonicalChild == canonicalRoot) return true
        val rootWithSep = if (canonicalRoot.endsWith("/")) canonicalRoot else "$canonicalRoot/"
        return canonicalChild.startsWith(rootWithSep)
    }

    fun isInsideAny(canonicalChild: String, canonicalRoots: Collection<String>): Boolean =
        canonicalRoots.any { isInside(canonicalChild, it) }
}

/**
 * The trycloudflare quick-tunnel run token: base64 of {"a":account_tag,"t":id,"s":secret}.
 * Built by hand (not org.json) so it is pure JVM and unit-tested; all three values are
 * server-issued tokens (hex/base64/uuid), but quotes/backslashes are escaped anyway —
 * a hand-rolled JSON writer that assumes clean input is a bug waiting for input.
 */
object RunToken {
    fun encode(accountTag: String, tunnelId: String, secret: String): String {
        fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val json = """{"a":"${esc(accountTag)}","t":"${esc(tunnelId)}","s":"${esc(secret)}"}"""
        return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }
}
