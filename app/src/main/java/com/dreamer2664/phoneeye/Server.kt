package com.dreamer2664.phoneeye

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Environment
import android.os.PowerManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * phone-eye — a window into this phone, for an agent that lives in a chat session.
 *
 * The contract is pc-eye's (see ../pc-eye server.py + PC-EYE.md), kept so faithfully
 * that an agent who knows one already knows the other:
 *
 *   GET  /state    -> screen, battery, foreground app, uptime, last action (no capture)
 *   GET  /shot     -> the screen, as an image (+ X-Screen-Full/Sent/Hash, ?changed= -> 304)
 *   POST /act      -> exactly one action: tap/long_press/swipe/scroll/type/paste/key/tap_text
 *   GET  /selftest -> what this phone can and cannot do, reported honestly
 *   + phone-native extensions (/ui, /info, /location, /photo, /files, /shell, ...)
 *
 * And its discipline: the server ALWAYS answers with a JSON sentence; the token
 * (?t= or X-Token or Bearer) is compared in constant time; /act validates strictly,
 * refuses out-of-space coordinates instead of clamping, and reports `delivered`
 * from Android itself; every action is bounded, rate-limited (fail-open), logged
 * without its text, and visible to the human holding the phone.
 *
 * Where Android differs from Windows, the difference is said out loud, never papered
 * over: no cursor exists (act "move" is a 409 with an explanation), typing writes
 * into the *focused* field or refuses, secure screens come back as refusals.
 */
class PhoneEyeServer(
    private val ctx: Context,
    private val token: String
) : NanoHTTPD(Core.PORT) {

    companion object {
        // Source of truth for tools/check_docs.py — docs may not drift from this list.
        val ROUTES = listOf(
            "/", "/state", "/shot", "/act", "/selftest",
            "/info", "/ui", "/location", "/photo",
            "/clipboard", "/files", "/download", "/upload", "/delete",
            "/apps", "/open", "/shell",
            "/toast", "/notify", "/vibrate", "/torch",
            "/contacts", "/sms", "/calllog", "/log"
        )
        private val POST_ROUTES = setOf(
            "/act", "/clipboard", "/upload", "/delete", "/open",
            "/shell", "/toast", "/notify", "/vibrate", "/torch"
        )

        // action -> (required, optional). pc-eye keeps this beside the code so an action
        // cannot exist in docs but not implementation; check_docs enforces the mirror.
        val ACTIONS: Map<String, Pair<List<String>, List<String>>> = linkedMapOf(
            "tap" to (listOf("x", "y") to listOf("duration_ms")),
            "click" to (listOf("x", "y") to listOf("duration_ms")),
            "long_press" to (listOf("x", "y") to listOf("duration_ms")),
            "swipe" to (listOf("x", "y", "x2", "y2") to listOf("duration_ms")),
            "drag" to (listOf("x", "y", "x2", "y2") to listOf("duration_ms")),
            "scroll" to (emptyList<String>() to listOf("clicks", "x", "y")),
            "type" to (listOf("text") to emptyList()),
            "paste" to (listOf("text") to emptyList()),
            "key" to (listOf("key") to listOf("times")),
            "tap_text" to (listOf("text") to listOf("index"))
        )
        val KEYS = listOf(
            "back", "home", "recents", "notifications", "quick_settings", "power",
            "lock", "enter", "wake", "volume_up", "volume_down"
        )

        private const val REQ_BODY_MAX = 1_000_000
        private const val UPLOAD_MAX = 25 * 1024 * 1024
        private const val MAX_TYPE_CHARS = 20_000
        private const val MAX_PASTE_CHARS = 200_000
        private const val MAX_KEY_TIMES = 100
    }

    private val startMs = System.currentTimeMillis()
    private val inputLock = Object()
    private val actBucket = RateBucket(30.0, 120.0 / 60.0)     // 120/min sustained, burst 30
    private val shotBucket = RateBucket(60.0, 180.0 / 60.0)    // 180/min sustained, burst 60
    private val heavyBucket = RateBucket(10.0, 30.0 / 60.0)    // shell/upload: 30/min

    @Volatile
    private var last: JSONObject = JSONObject()
        .put("action", JSONObject.NULL).put("at", JSONObject.NULL).put("args", JSONObject.NULL)

    // ----------------------------------------------------------------------------------
    // plumbing
    // ----------------------------------------------------------------------------------

    override fun serve(session: IHTTPSession): Response {
        return try {
            dispatch(session)
        } catch (e: Refusal) {
            json(e.code, errorJson(e.message ?: "refused"))
        } catch (e: Exception) {
            // A bug must still be an ANSWER: an agent cannot tell a dead phone from a crash.
            EyeLog.log("ERROR ${session.method} ${session.uri}: ${e.javaClass.simpleName}: ${e.message}")
            json(500, errorJson("internal error: ${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    private fun status(code: Int): Response.Status = when (code) {
        200 -> Response.Status.OK
        204 -> Response.Status.NO_CONTENT
        304 -> Response.Status.NOT_MODIFIED
        400 -> Response.Status.BAD_REQUEST
        401 -> Response.Status.UNAUTHORIZED
        403 -> Response.Status.FORBIDDEN
        404 -> Response.Status.NOT_FOUND
        409 -> Response.Status.CONFLICT
        413 -> Response.Status.PAYLOAD_TOO_LARGE
        415 -> Response.Status.UNSUPPORTED_MEDIA_TYPE
        429 -> Response.Status.TOO_MANY_REQUESTS
        503 -> Response.Status.SERVICE_UNAVAILABLE
        else -> Response.Status.INTERNAL_ERROR
    }

    private fun json(code: Int, obj: JSONObject): Response {
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        val r = newFixedLengthResponse(status(code), "application/json; charset=utf-8", bytes.inputStream(), bytes.size.toLong())
        r.addHeader("Cache-Control", "no-store")
        r.addHeader("X-Content-Type-Options", "nosniff")
        return r
    }

    private fun bytes(code: Int, data: ByteArray, mime: String, headers: Map<String, String> = emptyMap()): Response {
        val r = newFixedLengthResponse(status(code), mime, data.inputStream(), data.size.toLong())
        r.addHeader("Cache-Control", "no-store")
        r.addHeader("X-Content-Type-Options", "nosniff")
        headers.forEach { (k, v) -> r.addHeader(k, v) }
        return r
    }

    private fun errorJson(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    private fun baseOk(): JSONObject = JSONObject()
        .put("ok", true)
        .put("version", Core.VERSION)
        .put("dry_run", EyeState.dryRun)
        .put("at", EyeLog.nowIso())

    private fun hdr(session: IHTTPSession, name: String): String? =
        session.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun tokenOk(session: IHTTPSession): Boolean {
        val given = hdr(session, "x-token")
            ?: session.parms["t"]
            ?: hdr(session, "authorization")?.removePrefix("Bearer ")?.trim()
        return Core.tokenEquals(given, token)
    }

    private fun requireAuth(session: IHTTPSession) {
        if (!tokenOk(session)) {
            throw Refusal(401, "bad or missing token — add ?t=TOKEN, or the X-Token header (the TOKEN line in the phone-eye app)")
        }
    }

    /** pc-eye's /act browser guard, for every POST: JSON only, never cross-origin. */
    private fun postGuard(session: IHTTPSession, requireJsonBody: Boolean) {
        if (requireJsonBody) {
            val ctype = (hdr(session, "content-type") ?: "").substringBefore(';').trim().lowercase()
            if (ctype != "application/json") {
                throw Refusal(
                    415,
                    "Content-Type must be application/json — got '${ctype.ifEmpty { "nothing" }}'. " +
                    "A web page can send anything else without asking permission first, so phone-eye does not accept it. Real clients set the header."
                )
            }
        }
        val origin = hdr(session, "origin")
        if (!origin.isNullOrEmpty()) {
            val host = (hdr(session, "host") ?: "").substringBefore(':')
            val originHost = try {
                java.net.URI(origin).host ?: ""
            } catch (e: Exception) { "" }
            if (originHost.isEmpty() || originHost != host) {
                throw Refusal(403, "cross-origin POST is refused — a browser page on this phone must not be able to drive it just because it saw the URL")
            }
        }
    }

    private fun readBody(session: IHTTPSession, max: Int): ByteArray {
        val lenRaw = hdr(session, "content-length")?.trim()
        val len = lenRaw?.toLongOrNull() ?: throw Refusal(400, "Content-Length must be a number (got '${lenRaw}')")
        if (len < 0) throw Refusal(400, "Content-Length must not be negative")
        if (len > max) throw Refusal(413, "body of $len bytes exceeds $max — one action cannot need that much")
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var remaining = len
        val input = session.inputStream
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun bodyJson(session: IHTTPSession): JSONObject {
        val text = readBody(session, REQ_BODY_MAX).toString(Charsets.UTF_8)
        return try {
            JSONObject(if (text.isBlank()) "{}" else text)
        } catch (e: Exception) {
            throw Refusal(400, "body must be a JSON object, e.g. {\"action\":\"tap\",\"x\":10,\"y\":10}")
        }
    }

    // -- strict field parsing (pc-eye parses at the door; a typo must be a 400, not a crash)

    private fun keysOf(body: JSONObject): Set<String> {
        val s = mutableSetOf<String>()
        body.keys().forEach { s.add(it) }
        return s
    }

    private fun validateFields(what: String, body: JSONObject, required: List<String>, optional: List<String>, alsoAllow: Set<String> = emptySet()) {
        val missing = required.filter { k ->
            !body.has(k) || body.isNull(k) || (body.opt(k) is String && (body.opt(k) as String).isEmpty())
        }
        if (missing.isNotEmpty()) throw Refusal(400, "$what needs $missing")
        val allowed = required.toSet() + optional.toSet() + alsoAllow
        val extra = keysOf(body) - allowed
        if (extra.isNotEmpty()) throw Refusal(400, "$what has no field(s) ${extra.sorted()} — a typo must not ride along to the OS")
    }

    private fun bodyInt(body: JSONObject, name: String, lo: Int, hi: Int): Int {
        val v = body.opt(name) ?: throw Refusal(400, "$name is required — an integer between $lo and $hi")
        val n: Long = when (v) {
            is Boolean -> throw Refusal(400, "$name must be an integer — got a boolean ($v)")
            is Int -> v.toLong()
            is Long -> v
            is Double -> {
                if (!v.isFinite()) throw Refusal(400, "$name must be finite — got $v")
                if (v % 1.0 != 0.0) throw Refusal(400, "$name must be a whole number — got $v (fractional pixels are refused, not truncated)")
                v.toLong()
            }
            is String -> v.trim().toLongOrNull()
                ?: throw Refusal(400, "$name must be an integer — got \"$v\"")
            else -> throw Refusal(400, "$name must be an integer — got ${v.javaClass.simpleName}")
        }
        if (n < lo || n > hi) throw Refusal(400, "$name must be between $lo and $hi — got $n")
        return n.toInt()
    }

    private fun bodyIntOpt(body: JSONObject, name: String, default: Int, lo: Int, hi: Int): Int =
        if (!body.has(name) || body.isNull(name)) default else bodyInt(body, name, lo, hi)

    private fun bodyStr(body: JSONObject, name: String, maxLen: Int): String {
        val v = body.opt(name) ?: throw Refusal(400, "$name is required — a string")
        if (v !is String) throw Refusal(400, "$name must be a string — got ${v.javaClass.simpleName}")
        if (v.length > maxLen) throw Refusal(400, "$name is ${v.length} characters; the cap is $maxLen (split it, or use paste for long text)")
        return v
    }

    private fun bodyBool(body: JSONObject, name: String): Boolean {
        val v = body.opt(name) ?: throw Refusal(400, "$name is required — true or false")
        if (v !is Boolean) throw Refusal(400, "$name must be true or false — got $v")
        return v
    }

    private fun qInt(session: IHTTPSession, name: String, default: Int, lo: Int, hi: Int): Int {
        val raw = session.parms[name] ?: return default
        val n = raw.trim().toIntOrNull()
            ?: throw Refusal(400, "$name must be an integer — got \"$raw\"")
        if (n < lo || n > hi) throw Refusal(400, "$name must be between $lo and $hi — got $n")
        return n
    }

    private fun recordLast(action: String, argsShow: Map<String, Any?>) {
        val argsJson = JSONObject()
        argsShow.forEach { (k, v) ->
            if (k == "text") argsJson.put(k, "${v?.toString()?.length ?: 0} chars") else argsJson.put(k, v ?: JSONObject.NULL)
        }
        last = JSONObject()
            .put("action", action)
            .put("at", EyeLog.nowIso())
            .put("dry_run", EyeState.dryRun)
            .put("args", argsJson)
    }

    private fun didJson(argsShow: Map<String, Any?>): JSONObject {
        val did = JSONObject()
        argsShow.forEach { (k, v) ->
            if (k == "text") did.put(k, "${v?.toString()?.length ?: 0} chars") else did.put(k, v ?: JSONObject.NULL)
        }
        return did
    }

    /** Read-only mode for pure-effect calls: validate, record, report — never perform. */
    private fun dryRunReply(what: String, argsShow: Map<String, Any?>): JSONObject? {
        if (!EyeState.dryRun) return null
        recordLast(what, argsShow)
        EyeLog.log("$what ${EyeLog.describeNoText(argsShow)} -> DRY-RUN (recorded, not performed)")
        return baseOk()
            .put("action", what)
            .put("did", didJson(argsShow))
            .put("note", "read-only mode is ON in the phone-eye app: this was recorded, NOT performed on the phone")
    }

    private fun requireHand(code: Int): PhoneAccessibilityService =
        PhoneAccessibilityService.get() ?: throw Refusal(code, PhoneAccessibilityService.ENABLE_HINT)

    // ----------------------------------------------------------------------------------
    // dispatch
    // ----------------------------------------------------------------------------------

    private fun dispatch(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri

        if (method == Method.GET && (uri == "/" || uri == "/index.html")) {
            val up = (System.currentTimeMillis() - startMs) / 1000
            val text = "phone-eye ${Core.VERSION} is running on ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}).\n" +
                "Agent endpoints need the token: /state /shot /act /selftest (+ phone extras, see PHONE-EYE.md)\n" +
                "Uptime ${up}s. Tunnel: ${EyeState.tunnelProvider}. Stop: the Stop button in the app owns this phone.\n"
            return bytes(200, text.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
        }
        if (uri == "/favicon.ico") return bytes(204, ByteArray(0), "image/x-icon")

        if (method == Method.GET) {
            requireAuth(session)
            return when (uri) {
                "/state" -> json(200, stateJson())
                "/shot" -> shot(session)
                "/selftest" -> json(200, selftestJson())
                "/info" -> json(200, baseOk().put("info", Device.infoJson(ctx)))
                "/ui" -> json(200, uiJson(session))
                "/location" -> {
                    val timeoutS = qInt(session, "timeout_s", 8, 0, 30)
                    json(200, baseOk().put("location", Device.locationJson(ctx, timeoutS * 1000L)))
                }
                "/photo" -> photo(session)
                "/clipboard" -> json(200, baseOk().put("clipboard", Device.clipboardGet(ctx)))
                "/files" -> {
                    val path = session.parms["path"] ?: ""
                    val file = Device.resolvePath(ctx, path)
                    json(200, baseOk().put("listing", Device.listJson(file)).put("roots", Device.rootsJson(ctx)))
                }
                "/download" -> download(session)
                "/apps" -> {
                    val apps = Device.appsJson(ctx)
                    json(200, baseOk().put("apps", apps).put("count", apps.length()))
                }
                "/contacts" -> json(200, baseOk().put("contacts", Device.contacts(ctx, qInt(session, "limit", 50, 1, 500))))
                "/sms" -> json(200, baseOk().put("sms", Device.sms(ctx, qInt(session, "limit", 50, 1, 500))))
                "/calllog" -> json(200, baseOk().put("calls", Device.callLog(ctx, qInt(session, "limit", 50, 1, 500))))
                "/log" -> {
                    val n = qInt(session, "n", 100, 1, 400)
                    val lines = JSONArray()
                    EyeLog.last(n).forEach { lines.put(it) }
                    json(200, baseOk().put("lines", lines).put("log_file", EyeLog.logFilePath() ?: JSONObject.NULL))
                }
                else -> throw Refusal(404, "no route $uri — routes are ${ROUTES.joinToString(" ")}")
            }
        }

        if (method == Method.POST) {
            if (uri !in POST_ROUTES) throw Refusal(404, "no route $uri — POST routes are ${POST_ROUTES.sorted().joinToString(" ")}")
            postGuard(session, requireJsonBody = uri != "/upload")
            requireAuth(session)
            return when (uri) {
                "/act" -> act(session)
                "/clipboard" -> {
                    val body = bodyJson(session)
                    validateFields("/clipboard", body, listOf("text"), emptyList())
                    val text = bodyStr(body, "text", MAX_PASTE_CHARS)
                    dryRunReply("clipboard_set", mapOf("text" to text))?.let { return json(200, it) }
                    Device.clipboardSet(ctx, text)
                    EyeLog.log("clipboard set (${text.length} chars)")
                    json(200, baseOk().put("set", true).put("chars", text.length)
                        .put("note", "clipboard set. Nothing was pasted anywhere — use /act paste into a focused field, or /act key flows, and verify with /shot"))
                }
                "/upload" -> upload(session)
                "/delete" -> {
                    val body = bodyJson(session)
                    validateFields("/delete", body, listOf("path"), emptyList())
                    val path = bodyStr(body, "path", 4096)
                    val file = Device.resolvePath(ctx, path)
                    if (!file.exists()) throw Refusal(404, "no such file or directory: ${file.path}")
                    dryRunReply("delete", mapOf("path" to file.path))?.let { return json(200, it) }
                    if (file.isDirectory && (file.list()?.isNotEmpty() == true)) {
                        throw Refusal(409, "${file.path} is a non-empty directory — phone-eye never deletes recursively via the API. Delete the contents one by one, on purpose.")
                    }
                    val wasDir = file.isDirectory
                    if (!file.delete()) throw Refusal(503, "Android refused to delete ${file.path} (permission or a file in use)")
                    EyeLog.log("delete ${file.path} -> ok")
                    json(200, baseOk().put("deleted", file.path).put("type", if (wasDir) "dir" else "file"))
                }
                "/open" -> {
                    val body = bodyJson(session)
                    validateFields("/open", body, emptyList(), listOf("package", "url"))
                    val pkg = if (body.has("package") && !body.isNull("package")) bodyStr(body, "package", 256) else null
                    val url = if (body.has("url") && !body.isNull("url")) bodyStr(body, "url", 4096) else null
                    if ((pkg == null) == (url == null)) throw Refusal(400, "/open needs exactly one of {\"package\"} or {\"url\"} — got ${if (pkg != null && url != null) "both" else "neither"}")
                    val show = mapOf<String, Any?>("package" to pkg, "url" to url)
                    dryRunReply("open", show)?.let { return json(200, it) }
                    val opened = Device.openTarget(ctx, pkg, url)
                    EyeLog.log("open pkg=$pkg url=$url -> requested")
                    json(200, baseOk().put("open", opened))
                }
                "/shell" -> shell(session)
                "/toast" -> {
                    val body = bodyJson(session)
                    validateFields("/toast", body, listOf("text"), emptyList())
                    val text = bodyStr(body, "text", 500)
                    dryRunReply("toast", mapOf("text" to text))?.let { return json(200, it) }
                    Device.toast(ctx, text)
                    EyeLog.log("toast (${text.length} chars) -> shown")
                    json(200, baseOk().put("shown", true))
                }
                "/notify" -> {
                    val body = bodyJson(session)
                    validateFields("/notify", body, listOf("text"), listOf("title"))
                    val title = if (body.has("title") && !body.isNull("title")) bodyStr(body, "title", 100) else "phone-eye agent"
                    val text = bodyStr(body, "text", 2000)
                    dryRunReply("notify", mapOf("title" to title, "text" to text))?.let { return json(200, it) }
                    Device.notifyAgent(ctx, title, text)
                    EyeLog.log("notify (${text.length} chars) -> posted")
                    json(200, baseOk().put("posted", true))
                }
                "/vibrate" -> {
                    val body = bodyJson(session)
                    validateFields("/vibrate", body, listOf("ms"), emptyList())
                    val ms = bodyInt(body, "ms", 1, 5000)
                    dryRunReply("vibrate", mapOf("ms" to ms))?.let { return json(200, it) }
                    Device.vibrate(ctx, ms.toLong())
                    EyeLog.log("vibrate ${ms}ms -> ok")
                    json(200, baseOk().put("vibrated_ms", ms))
                }
                "/torch" -> {
                    val body = bodyJson(session)
                    validateFields("/torch", body, listOf("on"), emptyList())
                    val on = bodyBool(body, "on")
                    dryRunReply("torch", mapOf("on" to on))?.let { return json(200, it) }
                    val r = Device.torch(ctx, on)
                    EyeLog.log("torch on=$on -> ok")
                    json(200, baseOk().put("torch", r))
                }
                else -> throw Refusal(404, "no route $uri")
            }
        }

        throw Refusal(404, "no route ${method.name} $uri — routes are ${ROUTES.joinToString(" ")}")
    }

    // ----------------------------------------------------------------------------------
    // core four: /state /shot /selftest /act
    // ----------------------------------------------------------------------------------

    private fun stateJson(): JSONObject {
        val (w, h) = Device.displayRealSize(ctx)
        val acc = PhoneAccessibilityService.get()
        val coord = JSONObject()
            .put("consistent", true)
            .put("space", JSONArray().put(w).put(h))
            .put(
                "detail",
                "screenshots and gestures both use physical display pixels on Android — there is no DPI split like pc-eye's Windows bug. " +
                "The trap that remains is downscaling: convert with the X-Screen-Full / X-Screen-Sent headers on every /shot."
            )
        return baseOk()
            .put("device", android.os.Build.MODEL ?: "")
            .put("os", "Android ${android.os.Build.VERSION.RELEASE} (sdk ${android.os.Build.VERSION.SDK_INT})")
            .put("screen", JSONArray().put(w).put(h))
            .put("rotation_deg", Device.rotationDegrees(ctx))
            .put("screen_on", Device.screenOn(ctx))
            .put("locked", Device.locked(ctx))
            .put("accessibility_bound", acc != null)
            .put("capture_ok", acc != null)
            .put("capture_why", if (acc == null) PhoneAccessibilityService.ENABLE_HINT else JSONObject.NULL)
            .put("foreground_package", acc?.foregroundPackage() ?: JSONObject.NULL)
            .put("battery", Device.batteryJson(ctx))
            .put("uptime_s", (System.currentTimeMillis() - startMs) / 1000)
            .put("last", last)
            .put("coord_space", coord)
            .put(
                "tunnel", JSONObject()
                    .put("provider", EyeState.tunnelProvider)
                    .put("url", EyeState.publicUrl ?: JSONObject.NULL)
                    .put("lan_url", EyeState.lanUrl ?: JSONObject.NULL)
            )
    }

    private fun shot(session: IHTTPSession): Response {
        if (!shotBucket.take()) {
            return json(
                429, errorJson(
                    "more than 180 shots/min — you are polling faster than a human can act (and Android throttles " +
                    "accessibility screenshots besides). Watching something load? Use /shot?changed=HASH and pay a header, not a frame."
                ).put("retry_after_s", 2)
            )
        }
        val acc = requireHand(503)
        if (!Device.screenOn(ctx)) {
            throw Refusal(503, "the screen is off, so there is nothing to show you (a black frame would be a lie about what the apps are doing). POST /act {\"action\":\"key\",\"key\":\"wake\"} turns it on to the lockscreen; unlocking is the human's job — never tap a PIN you were not given.")
        }
        val maxWidth = qInt(session, "max", 1080, 0, 4320)
        val quality = qInt(session, "quality", 72, 1, 95)
        val fmt = (session.parms["fmt"] ?: "jpeg").lowercase()
        if (fmt !in listOf("jpeg", "png")) throw Refusal(400, "fmt must be jpeg or png — got '$fmt'")
        val changed = session.parms["changed"]

        val data = synchronized(inputLock) { acc.captureShot(maxWidth, quality, png = fmt == "png") }
        val headers = mapOf(
            "X-Screen-Full" to "${data.fullW}x${data.fullH}",
            "X-Screen-Sent" to "${data.sentW}x${data.sentH}",
            "X-Screen-Hash" to data.hash
        )
        if (!changed.isNullOrEmpty() && changed == data.hash) {
            EyeLog.log("shot unchanged (${data.hash.take(8)})")
            return bytes(304, ByteArray(0), "text/plain", headers)
        }
        EyeLog.log("shot ${data.sentW}x${data.sentH} ${data.bytes.size}B full=${data.fullW}x${data.fullH}")
        return bytes(200, data.bytes, data.mime, headers)
    }

    private fun selftestJson(): JSONObject {
        val checks = JSONArray()
        var coreOk = true
        fun chk(name: String, ok: Boolean, detail: String, core: Boolean) {
            checks.put(JSONObject().put("name", name).put("ok", ok).put("detail", detail.take(400)).put("core", core))
            if (core) coreOk = coreOk && ok
        }
        if (EyeState.dryRun) {
            checks.put(
                JSONObject().put("name", "TEST-MODE").put("ok", true).put("core", false)
                    .put("detail", "read-only mode is ON in the app: /act and other mutations are recorded, NOT performed. Every ok you get for an action is a protocol pass, not a touch.")
            )
        }
        chk("server", true, "phone-eye ${Core.VERSION} answering on :${Core.PORT}", core = true)
        chk("token", token.length >= 16, "${token.length} chars", core = true)
        val url = EyeState.publicUrl
        chk(
            "tunnel", url != null,
            if (url != null) "${EyeState.tunnelProvider}: $url"
            else "NO public tunnel — ${EyeState.tunnelDetail.ifEmpty { "still connecting or LAN only" }}",
            core = true
        )
        val acc = PhoneAccessibilityService.get()
        chk("accessibility", acc != null, if (acc != null) "bound (eyes + hand live)" else PhoneAccessibilityService.ENABLE_HINT, core = true)
        val screenOn = Device.screenOn(ctx)
        chk("screen-on", screenOn, if (screenOn) "display is interactive" else "display is OFF — /act key=wake, then a human unlocks", core = true)
        val locked = Device.locked(ctx)
        chk("unlocked", !locked, if (!locked) "no keyguard in the way" else "keyguard is LOCKED — you can screenshot the lockscreen but must not enter credentials; ask the human to unlock", core = true)
        // Capture, really exercised — the only honest test, like pc-eye.
        if (acc != null && screenOn) {
            try {
                val probe = synchronized(inputLock) { acc.captureShot(320, 40, png = false) }
                chk("capture", probe.bytes.size > 1000, "${probe.sentW}x${probe.sentH} ${probe.bytes.size}B (probe frame)", core = true)
            } catch (e: Refusal) {
                chk("capture", false, e.message ?: "capture refused", core = true)
            } catch (e: Exception) {
                chk("capture", false, "${e.javaClass.simpleName}: ${e.message}", core = true)
            }
        } else {
            chk("capture", false, if (acc == null) "not attempted: accessibility off" else "not attempted: screen off", core = true)
        }
        val (w, h) = Device.displayRealSize(ctx)
        chk("coordinate-space", w > 0 && h > 0, "eyes and hand share physical pixels (${w}x${h}); /shot headers are ground truth per frame", core = true)
        chk("log-writable", EyeLog.probeWritable(), EyeLog.logFilePath() ?: "no log file", core = true)

        // Capabilities: red here disables one tool, not the connection.
        chk("camera-permission", Perms.has(ctx, Manifest.permission.CAMERA), "needed by /photo", core = false)
        val locFine = Perms.has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val locCoarse = Perms.has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        chk("location-permission", locFine || locCoarse, when { locFine -> "fine (GPS-grade)"; locCoarse -> "coarse only (~city-block)"; else -> "denied — /location will refuse" }, core = false)
        chk("all-files-access", Environment.isExternalStorageManager(), "needed for /files beyond app + media areas", core = false)
        val people = listOf(
            "contacts" to Manifest.permission.READ_CONTACTS,
            "sms" to Manifest.permission.READ_SMS,
            "calllog" to Manifest.permission.READ_CALL_LOG
        )
        chk(
            "people-permissions", people.all { Perms.has(ctx, it.second) },
            people.joinToString(", ") { "${it.first}=${if (Perms.has(ctx, it.second)) "granted" else "DENIED"}" },
            core = false
        )
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        chk(
            "battery-unrestricted", pm.isIgnoringBatteryOptimizations(ctx.packageName),
            "without this, Android may freeze phone-eye mid-session (the tunnel dies quietly)",
            core = false
        )
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        chk("notifications", nm.areNotificationsEnabled(), "the running-session notification (the visible off switch)", core = false)
        chk("foreground-service", EyeState.running, "the service that owns this server", core = false)

        return baseOk().put("ok", coreOk).put("checks", checks)
            .put("note", "ok = every CORE check green. Non-core reds disable single tools (photo, location, files...), not the session. Report all reds out loud, core or not.")
    }

    private fun act(session: IHTTPSession): Response {
        if (!actBucket.take()) {
            return json(
                429, errorJson(
                    "more than 120 actions/min — a loop is spinning on this phone's screen. Stop, take one /shot, and think before the next action."
                ).put("retry_after_s", 5)
            )
        }
        val body = bodyJson(session)
        val actionRaw = body.opt("action")
        val action = if (actionRaw is String) actionRaw else ""
        if (action == "move") {
            throw Refusal(
                409,
                "phones have no cursor to move — 'move' is a pc-eye action, and pretending to hover would be a lie. " +
                "Use tap (with coordinates from a /shot you converted) or tap_text (find it by words). " +
                "Phone-eye's aiming proof is different from pc-eye's cursor check: /act reports delivered=true only when " +
                "Android confirms the gesture completed, and your next /shot is the verdict."
            )
        }
        val spec = ACTIONS[action] ?: throw Refusal(
            400, "unknown action '$action' — actions are ${ACTIONS.keys.sorted()}"
        )
        validateFields(action, body, spec.first, spec.second, alsoAllow = setOf("action"))

        val (w, h) = Device.displayRealSize(ctx)
        fun coord(name: String, limit: Int, axis: String): Int {
            val v = bodyInt(body, name, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
            if (v < 0 || v >= limit) {
                throw Refusal(
                    409,
                    "($name=$v) is outside the screen's $axis span (0..${limit - 1} of ${w}x${h}) — a clamped tap would silently land somewhere else, " +
                    "so phone-eye refuses. If you measured from a downscaled /shot, convert: real = seen * full / sent (X-Screen-Full / X-Screen-Sent)."
                )
            }
            return v
        }

        // Parse + bound everything at the door, before touching the phone.
        val args = linkedMapOf<String, Any?>()
        var x = 0; var y = 0; var x2 = 0; var y2 = 0
        var durationMs = 0
        var text = ""
        var keyName = ""
        var times = 1
        var clicks = -3
        var index = 0
        when (action) {
            "tap", "click" -> {
                x = coord("x", w, "x"); y = coord("y", h, "y")
                durationMs = bodyIntOpt(body, "duration_ms", 60, 30, 2000)
                args.putAll(mapOf("x" to x, "y" to y, "duration_ms" to durationMs))
            }
            "long_press" -> {
                x = coord("x", w, "x"); y = coord("y", h, "y")
                durationMs = bodyIntOpt(body, "duration_ms", 600, 200, 5000)
                args.putAll(mapOf("x" to x, "y" to y, "duration_ms" to durationMs))
            }
            "swipe", "drag" -> {
                x = coord("x", w, "x"); y = coord("y", h, "y")
                x2 = coord("x2", w, "x"); y2 = coord("y2", h, "y")
                durationMs = bodyIntOpt(body, "duration_ms", 300, 50, 5000)
                args.putAll(mapOf("x" to x, "y" to y, "x2" to x2, "y2" to y2, "duration_ms" to durationMs))
            }
            "scroll" -> {
                clicks = bodyIntOpt(body, "clicks", -3, -100, 100)
                if (clicks == 0) throw Refusal(400, "scroll clicks must be at least one notch either way — got 0. Omit the field for the default of -3.")
                val hasX = body.has("x") && !body.isNull("x")
                val hasY = body.has("y") && !body.isNull("y")
                if (hasX != hasY) throw Refusal(400, "scroll takes x and y together (the point to scroll at), or neither for screen centre")
                x = if (hasX) coord("x", w, "x") else w / 2
                y = if (hasY) coord("y", h, "y") else h / 2
                args.putAll(mapOf("clicks" to clicks, "x" to x, "y" to y))
            }
            "type" -> { text = bodyStr(body, "text", MAX_TYPE_CHARS); args["text"] = text }
            "paste" -> { text = bodyStr(body, "text", MAX_PASTE_CHARS); args["text"] = text }
            "key" -> {
                keyName = bodyStr(body, "key", 64).lowercase().trim()
                if (keyName !in KEYS) throw Refusal(400, "unknown key '$keyName' — keys are $KEYS. Arbitrary key events do not exist without root; these global actions are the honest set.")
                times = bodyIntOpt(body, "times", 1, 1, MAX_KEY_TIMES)
                args.putAll(mapOf("key" to keyName, "times" to times))
            }
            "tap_text" -> {
                text = bodyStr(body, "text", 200)
                index = bodyIntOpt(body, "index", 0, 0, 50)
                args.putAll(mapOf("text" to text, "index" to index))
            }
        }

        dryRunReply(action, args)?.let { return json(200, it) }

        // Keys that live below the accessibility layer.
        if (action == "key" && keyName in listOf("wake", "volume_up", "volume_down")) {
            val done = when (keyName) {
                "wake" -> {
                    var ok = false
                    repeat(times) { ok = Device.wake(ctx) || ok }
                    ok
                }
                else -> {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val dir = if (keyName == "volume_up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    repeat(times) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) }
                    true
                }
            }
            if (!done) throw Refusal(503, "key $keyName could not be performed on this phone")
            recordLast(action, args)
            EyeLog.log("act key ${EyeLog.describeNoText(args)} -> ok")
            return json(200, baseOk().put("action", action).put("did", didJson(args)).put("delivered", true))
        }

        val acc = requireHand(409)
        if (!Device.screenOn(ctx)) {
            throw Refusal(409, "the screen is off — a gesture now would land on a sleeping phone and be reported as done, which is exactly the ok-shaped lie phone-eye refuses to tell. POST /act {\"action\":\"key\",\"key\":\"wake\"} first; unlocking is the human's job.")
        }

        val out = baseOk().put("action", action)
        synchronized(inputLock) {
            when (action) {
                "tap", "click", "long_press" -> {
                    if (!acc.tap(x, y, durationMs.toLong())) throw Refusal(409, "Android cancelled the gesture (a real finger, or another gesture, got there first) — the tap did NOT land. Look again and retry once.")
                    out.put("delivered", true)
                }
                "swipe", "drag" -> {
                    if (!acc.swipe(x, y, x2, y2, durationMs.toLong())) throw Refusal(409, "Android cancelled the swipe — it did NOT happen. Look again and retry once.")
                    out.put("delivered", true)
                }
                "scroll" -> {
                    // A scroll IS a swipe here: same gesture everywhere, no scrollable-node lottery.
                    val dist = (h * 0.09 * kotlin.math.abs(clicks)).toInt().coerceAtMost((h * 0.62).toInt()).coerceAtLeast(40)
                    val targetY = if (clicks < 0) (y - dist).coerceAtLeast(0) else (y + dist).coerceAtMost(h - 1)
                    if (!acc.swipe(x, y, x, targetY, 280)) throw Refusal(409, "Android cancelled the scroll swipe — nothing scrolled. Look again and retry once.")
                    out.put("delivered", true).put("swiped_to_y", targetY)
                }
                "type" -> {
                    if (!acc.setFocusedText(text, append = true)) {
                        throw Refusal(409, "no editable field is focused, so the text went nowhere — it was NOT typed. Tap the field first (a /shot shows the cursor), then type.")
                    }
                    out.put("delivered", true).put("typed_chars", text.length)
                }
                "paste" -> {
                    Device.clipboardSet(ctx, text)
                    if (!acc.pasteFocused()) {
                        throw Refusal(409, "the clipboard now holds your text but nothing was pasted — no editable field is focused, so the text was NOT pasted into any app. Tap the field first, then paste again.")
                    }
                    out.put("delivered", true).put("pasted", true).put("pasted_chars", text.length)
                }
                "key" -> {
                    if (keyName == "enter") {
                        repeat(times) {
                            if (!acc.setFocusedText("\n", append = true)) {
                                throw Refusal(409, "enter needs a focused editable field on Android (there is no raw Enter key without root) — tap the field first, or use tap on the app's Send button")
                            }
                        }
                    } else {
                        val global = when (keyName) {
                            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                            "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                            "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                            else -> throw Refusal(400, "unknown key '$keyName'")
                        }
                        repeat(times) { i ->
                            if (!acc.global(global)) throw Refusal(409, "Android refused the '$keyName' global action (accessibility may have been toggled off mid-session — check /selftest)")
                            if (i < times - 1) Thread.sleep(120)
                        }
                    }
                    out.put("delivered", true)
                }
                "tap_text" -> {
                    val r = acc.tapText(text, index)
                    out.put("delivered", true)
                        .put("matched", r.matched)
                        .put("tapped_at", JSONArray().put(r.cx).put(r.cy))
                        .put("via", r.via)
                }
            }
        }
        out.put("did", didJson(args))
        out.put("screen", JSONArray().put(w).put(h))
        recordLast(action, args)
        EyeLog.log("act $action ${EyeLog.describeNoText(args)} -> ok")
        return json(200, out)
    }

    // ----------------------------------------------------------------------------------
    // extensions
    // ----------------------------------------------------------------------------------

    private fun uiJson(session: IHTTPSession): JSONObject {
        val acc = requireHand(503)
        val maxNodes = qInt(session, "max_nodes", 1500, 1, 5000)
        val dump = acc.dumpUi(maxNodes)
        val nodes = JSONArray()
        dump.nodes.forEach { n ->
            nodes.put(
                JSONObject()
                    .put("depth", n.depth)
                    .put("text", n.text)
                    .put("desc", n.desc)
                    .put("class", n.className)
                    .put("id", n.viewId)
                    .put("bounds", JSONArray().put(n.bounds[0]).put(n.bounds[1]).put(n.bounds[2]).put(n.bounds[3]))
                    .put("center", JSONArray().put((n.bounds[0] + n.bounds[2]) / 2).put((n.bounds[1] + n.bounds[3]) / 2))
                    .put("clickable", n.clickable)
                    .put("editable", n.editable)
                    .put("scrollable", n.scrollable)
                    .put("focused", n.focused)
                    .put("enabled", n.enabled)
                    .put("visible", n.visible)
            )
        }
        EyeLog.log("ui dump ${dump.nodes.size} nodes pkg=${dump.pkg}")
        return baseOk().put("package", dump.pkg).put("count", dump.nodes.size)
            .put("truncated", dump.truncated).put("nodes", nodes)
    }

    private fun photo(session: IHTTPSession): Response {
        if (!shotBucket.take()) {
            return json(429, errorJson("photo/shot budget exhausted (180/min shared) — slow down").put("retry_after_s", 2))
        }
        val camera = (session.parms["camera"] ?: "back").lowercase()
        if (camera !in listOf("back", "front")) throw Refusal(400, "camera must be back or front — got '$camera'")
        Perms.require(ctx, Manifest.permission.CAMERA, "/photo")
        val jpeg = PhotoGrabber.capture(ctx, front = camera == "front")
        EyeLog.log("photo camera=$camera ${jpeg.size}B")
        return bytes(200, jpeg, "image/jpeg", mapOf("X-Photo-Camera" to camera, "X-Photo-Bytes" to jpeg.size.toString()))
    }

    private fun download(session: IHTTPSession): Response {
        val path = session.parms["path"] ?: throw Refusal(400, "/download needs ?path=... (GET /files lists what exists)")
        val file = Device.resolvePath(ctx, path)
        if (!file.exists()) throw Refusal(404, "no such file: ${file.path}")
        if (file.isDirectory) throw Refusal(409, "${file.path} is a directory — /download moves single files; GET /files?path=... lists it")
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "txt", "log", "md" -> "text/plain; charset=utf-8"
            "json" -> "application/json"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
        val stream = FileInputStream(file)
        val r = newFixedLengthResponse(Response.Status.OK, mime, stream, file.length())
        r.addHeader("Cache-Control", "no-store")
        r.addHeader("Content-Disposition", "attachment; filename=\"${file.name.replace("\"", "")}\"")
        EyeLog.log("download ${file.path} (${file.length()}B)")
        return r
    }

    private fun upload(session: IHTTPSession): Response {
        val path = session.parms["path"] ?: throw Refusal(400, "/upload needs ?path=... with the raw file bytes as the request body")
        val target = Device.resolvePath(ctx, path)
        if (target.isDirectory) throw Refusal(409, "${target.path} is an existing directory — give the full destination file path")
        if (EyeState.dryRun) {
            // An upload's reply carries a hash of bytes on disk; faking it would be a lie
            // with a checksum on it. Refuse instead of recording.
            throw Refusal(409, "read-only mode is ON: /upload is refused rather than pretend-stored (its receipt includes a SHA-256 of bytes that would not exist). Turn read-only off in the app to write files.")
        }
        if (!heavyBucket.take()) return json(429, errorJson("upload/shell budget exhausted (30/min) — slow down").put("retry_after_s", 5))
        val parent = target.parentFile ?: throw Refusal(400, "path has no parent directory: ${target.path}")
        if (!parent.exists()) parent.mkdirs()
        if (!parent.isDirectory) throw Refusal(409, "cannot create parent directory ${parent.path} (storage permission? see /selftest all-files-access)")
        val data = readBody(session, UPLOAD_MAX)
        val part = File(parent, target.name + ".part-" + (System.currentTimeMillis() % 100000))
        try {
            part.writeBytes(data)
            if (!part.renameTo(target)) {
                target.writeBytes(data)
                part.delete()
            }
        } catch (e: Exception) {
            try { part.delete() } catch (e2: Exception) { }
            throw Refusal(503, "writing ${target.path} failed: ${e.javaClass.simpleName}: ${e.message} (All-files access off, or storage full, are the usual causes)")
        }
        EyeLog.log("upload ${target.path} (${data.size}B) -> ok")
        return json(
            200, baseOk()
                .put("path", target.path)
                .put("size_bytes", data.size)
                .put("sha256", Core.sha256Hex(data))
        )
    }

    private fun shell(session: IHTTPSession): Response {
        val body = bodyJson(session)
        validateFields("/shell", body, listOf("cmd"), listOf("timeout_s"))
        val cmd = bodyStr(body, "cmd", MAX_TYPE_CHARS)
        val timeoutS = bodyIntOpt(body, "timeout_s", 10, 1, 60)
        if (EyeState.dryRun) {
            throw Refusal(409, "read-only mode is ON: /shell is refused, not simulated — shell output would be fabricated evidence, and shell is write-capable besides. Read-only agents use the GET routes.")
        }
        if (!heavyBucket.take()) return json(429, errorJson("shell/upload budget exhausted (30/min) — slow down and batch commands with ; or &&").put("retry_after_s", 5))
        val result = Device.shell(ctx, cmd, timeoutS.toLong())
        // The command is logged for the human watching (truncated); its OUTPUT never is.
        EyeLog.log("shell (${cmd.length} chars) ${cmd.replace("\n", " ").take(120)} -> exit=${result.opt("exit")}")
        return json(200, baseOk().put("shell", result))
    }
}
