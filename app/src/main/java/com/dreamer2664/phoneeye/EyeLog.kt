package com.dreamer2664.phoneeye

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * phone-eye's console + pceye.log in one: a ring buffer the UI and GET /log read,
 * plus an append-only file (phoneeye.log in the app's private files).
 *
 * Redaction rules inherited from pc-eye and kept strict:
 *  - the session token is scrubbed from every line, even if a caller slips it in;
 *  - typed/pasted text is logged as a length, never as content (callers use
 *    [describeNoText]); file contents and shell OUTPUT are never logged at all.
 * A log the owner can safely show someone is a log they will actually read.
 */
object EyeLog {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    @Volatile private var file: File? = null
    @Volatile private var token: String = ""
    @Volatile var revision: Long = 0
        private set

    fun init(filesDir: File) {
        try {
            file = File(filesDir, "phoneeye.log")
        } catch (e: Exception) {
            file = null // console-only is degraded, not fatal; /selftest reports it
        }
    }

    fun setSessionToken(t: String) {
        token = t
    }

    fun nowIso(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    @Synchronized
    fun log(line: String) {
        var clean = line
        val t = token
        if (t.isNotEmpty() && clean.contains(t)) clean = clean.replace(t, "<token>")
        val stamped = "${nowIso()} $clean"
        lines.addLast(stamped)
        while (lines.size > MAX_LINES) lines.removeFirst()
        revision++
        try {
            file?.appendText(stamped + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            // A full disk must not kill the request that was being logged.
        }
    }

    @Synchronized
    fun last(n: Int): List<String> = lines.takeLast(n.coerceAtMost(MAX_LINES))

    @Synchronized
    fun clearScreen() {
        lines.clear()
        revision++
    }

    fun logFilePath(): String? = file?.absolutePath

    /** True only if a real write succeeds — /selftest's log check, like pc-eye's probe. */
    fun probeWritable(): Boolean {
        val f = file ?: return false
        return try {
            f.appendText("${nowIso()} selftest log-write probe\n", Charsets.UTF_8)
            f.exists()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * pc-eye's args_show: render an action's arguments for the log with any
     * text-bearing field replaced by its length. Transparency without a transcript.
     */
    fun describeNoText(fields: Map<String, Any?>): String =
        fields.entries.joinToString(" ") { (k, v) ->
            if (k == "text") "$k=<${v?.toString()?.length ?: 0} chars>" else "$k=$v"
        }
}

/**
 * The one shared run-state (service writes, activity + server read). Same process,
 * so a plain object with @Volatile fields beats IPC theatre. The activity polls at
 * 1 Hz; listeners exist only to make tunnel changes feel instant.
 */
object EyeState {
    @Volatile var running: Boolean = false
    @Volatile var token: String = ""
    @Volatile var publicUrl: String? = null
    @Volatile var tunnelProvider: String = "none"
    @Volatile var tunnelDetail: String = ""
    @Volatile var lanUrl: String? = null
    @Volatile var startedAtMs: Long = 0L
    @Volatile var dryRun: Boolean = false

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: () -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }

    fun notifyChanged() {
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { try { it() } catch (e: Exception) { /* a dead view must not kill the service */ } }
    }

    fun reset() {
        running = false
        token = ""
        publicUrl = null
        tunnelProvider = "none"
        tunnelDetail = ""
        lanUrl = null
        startedAtMs = 0L
        notifyChanged()
    }
}
