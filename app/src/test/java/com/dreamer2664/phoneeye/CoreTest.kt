package com.dreamer2664.phoneeye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * phone-eye's contract tests for the pure rules. They run on a laptop in CI —
 * the parts that cannot run there (a real tap landing on glass) are what
 * /selftest and delivered=true reporting exist for, same division as pc-eye.
 */
class CoreTest {

    @Test
    fun tokenIs32HexAndRandom() {
        val a = Core.newToken()
        val b = Core.newToken()
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(a, b)
    }

    @Test
    fun tokenCompareIsExact() {
        assertTrue(Core.tokenEquals("abc123", "abc123"))
        assertFalse(Core.tokenEquals("abc124", "abc123"))
        assertFalse(Core.tokenEquals("abc12", "abc123"))
        assertFalse(Core.tokenEquals("", "abc123"))
        assertFalse(Core.tokenEquals(null, "abc123"))
    }

    @Test
    fun coordinateConversionMatchesPcEyeExample() {
        // PC-EYE.md's worked example: image 1440x810 of a 1920x1080 screen,
        // target seen at (700,300) must be tapped at (933,400).
        assertEquals(933, Core.convertCoord(700, sent = 1440, full = 1920))
        assertEquals(400, Core.convertCoord(300, sent = 810, full = 1080))
        // A phone-flavoured one: 1080x2400 screen sent at 540x1200, seen (100,1000).
        assertEquals(200, Core.convertCoord(100, sent = 540, full = 1080))
        assertEquals(2000, Core.convertCoord(1000, sent = 1200, full = 2400))
        // 1:1 is the identity.
        assertEquals(517, Core.convertCoord(517, sent = 1080, full = 1080))
    }

    @Test
    fun contentHashIsStableAndSensitive() {
        val a = Core.contentHash("frame-A".toByteArray())
        assertEquals(a, Core.contentHash("frame-A".toByteArray()))
        assertNotEquals(a, Core.contentHash("frame-B".toByteArray()))
        assertEquals(16, a.length)
    }

    @Test
    fun rateBucketBurstsThenRefuses() {
        val bucket = RateBucket(capacity = 3.0, refillPerSec = 0.0)
        assertTrue(bucket.take())
        assertTrue(bucket.take())
        assertTrue(bucket.take())
        assertFalse(bucket.take())
    }

    @Test
    fun runTokenEncodesAccountIdSecret() {
        // Same fixture shape as the trycloudflare API: account_tag / id / secret.
        val token = RunToken.encode("acct", "tid", "c2VjcmV0")
        val json = String(Base64.getDecoder().decode(token), Charsets.UTF_8)
        assertEquals("""{"a":"acct","t":"tid","s":"c2VjcmV0"}""", json)
    }

    @Test
    fun pathSandboxKeepsChildrenInAndStrangersOut() {
        assertTrue(PathSandbox.isInside("/storage/emulated/0/DCIM/a.jpg", "/storage/emulated/0"))
        assertTrue(PathSandbox.isInside("/storage/emulated/0", "/storage/emulated/0"))
        assertFalse(PathSandbox.isInside("/storage/emulated/0evil/x", "/storage/emulated/0"))
        assertFalse(PathSandbox.isInside("/data/data/com.bank/files", "/storage/emulated/0"))
        // Canonicalisation happens before this check in Device.resolvePath: a ".."
        // path that canonically escapes must fail here, which is the whole point.
        assertFalse(PathSandbox.isInside("/storage/emulated/secret", "/storage/emulated/0"))
        assertTrue(
            PathSandbox.isInsideAny(
                "/data/data/com.dreamer2664.phoneeye/files/phoneeye.log",
                listOf("/storage/emulated/0", "/data/data/com.dreamer2664.phoneeye/files")
            )
        )
    }

    @Test
    fun versionConstantIsSemverShaped() {
        assertTrue(Core.VERSION.matches(Regex("""\d+\.\d+\.\d+""")))
    }
}
