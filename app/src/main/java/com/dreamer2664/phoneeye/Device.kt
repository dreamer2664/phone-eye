package com.dreamer2664.phoneeye

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.graphics.Point
import android.view.Display
import android.view.Surface
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Everything the phone will let an unprivileged app do, each behind its own
 * permission, each reporting honest Android sentences when refused.
 *
 * Two pc-eye rules run through this file:
 *  - a capability that is off is a REFUSAL with the name of the switch, never an
 *    empty success (an "ok" with nothing inside it is the ok-shaped lie);
 *  - the file tools live inside named roots. There is no ../ cleverness that
 *    escapes them — the canonical-path sandbox is unit-tested in Core.
 */
object Perms {
    fun has(ctx: Context, perm: String): Boolean =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun require(ctx: Context, perm: String, whatFor: String) {
        if (!has(ctx, perm)) {
            val short = perm.substringAfterLast('.')
            throw Refusal(
                409,
                "$whatFor needs the $short permission, which this phone has not granted to phone-eye. " +
                "Ask the human: phone-eye app → \"Grant permissions\" (or Settings → Apps → phone-eye → Permissions). " +
                "Then call /selftest to watch it go green."
            )
        }
    }
}

object Device {

    fun iso(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }

    fun versionName(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: Core.VERSION
    } catch (e: Exception) {
        Core.VERSION
    }

    // ---------------------------------------------------------------- display

    fun displayRealSize(ctx: Context): Pair<Int, Int> {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(p)
        if (p.x > 0 && p.y > 0) return p.x to p.y
        val m = ctx.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    fun rotationDegrees(ctx: Context): Int {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return when (dm.getDisplay(Display.DEFAULT_DISPLAY).rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    fun screenOn(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    fun locked(ctx: Context): Boolean =
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /** Best-effort screen wake (to the lockscreen — waking is fine, unlocking is the human's job). */
    fun wake(ctx: Context): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "phoneeye:wake"
            )
            wl.acquire(1_500)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- info / state pieces

    fun batteryJson(ctx: Context): JSONObject {
        val out = JSONObject()
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) {
            out.put("known", false)
            return out
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        out.put("known", true)
        if (level >= 0 && scale > 0) out.put("percent", Math.round(level * 100.0 / scale))
        out.put(
            "charging",
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        )
        out.put(
            "status", when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                else -> "unknown"
            }
        )
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (temp != Int.MIN_VALUE) out.put("temperature_c", temp / 10.0)
        return out
    }

    @Suppress("DEPRECATION")
    fun lanIp(ctx: Context): String? {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networks = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
            for (n in networks.distinct()) {
                val lp = cm.getLinkProperties(n) ?: continue
                for (la in lp.linkAddresses) {
                    val addr = la.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun infoJson(ctx: Context): JSONObject {
        val out = JSONObject()
        out.put("manufacturer", Build.MANUFACTURER ?: "")
        out.put("model", Build.MODEL ?: "")
        out.put("device", Build.DEVICE ?: "")
        out.put("android_release", Build.VERSION.RELEASE ?: "")
        out.put("sdk", Build.VERSION.SDK_INT)
        out.put("abi", Build.SUPPORTED_ABIS?.firstOrNull() ?: "")
        val (w, h) = displayRealSize(ctx)
        out.put("screen", JSONArray().put(w).put(h))
        out.put("rotation_deg", rotationDegrees(ctx))
        out.put("density_dpi", ctx.resources.displayMetrics.densityDpi)
        out.put("screen_on", screenOn(ctx))
        out.put("locked", locked(ctx))
        out.put("battery", batteryJson(ctx))
        out.put("uptime_phone_s", SystemClock.elapsedRealtime() / 1000)
        out.put("app_version", versionName(ctx))
        try {
            val ext = StatFs(Environment.getExternalStorageDirectory().path)
            out.put("storage_shared", JSONObject().put("total_bytes", ext.totalBytes).put("free_bytes", ext.availableBytes))
        } catch (e: Exception) { }
        try {
            val intFs = StatFs(ctx.filesDir.path)
            out.put("storage_app", JSONObject().put("total_bytes", intFs.totalBytes).put("free_bytes", intFs.availableBytes))
        } catch (e: Exception) { }
        out.put("network", networkJson(ctx))
        out.put("lan_url", lanIp(ctx)?.let { "http://$it:${Core.PORT}" } ?: JSONObject.NULL)
        out.put("all_files_access", Environment.isExternalStorageManager())
        out.put("accessibility_bound", PhoneAccessibilityService.get() != null)
        out.put("foreground_package", PhoneAccessibilityService.get()?.foregroundPackage() ?: JSONObject.NULL)
        return out
    }

    private fun networkJson(ctx: Context): JSONObject {
        val out = JSONObject()
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nw = cm.activeNetwork
            if (nw == null) {
                out.put("connected", false)
                return out
            }
            out.put("connected", true)
            val caps = cm.getNetworkCapabilities(nw)
            if (caps != null) {
                out.put(
                    "transport", when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                        else -> "other"
                    }
                )
                out.put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                val ti = caps.transportInfo
                if (ti is WifiInfo) {
                    val ssid = try { ti.ssid?.trim('"') } catch (e: Exception) { null }
                    out.put("wifi_ssid", ssid ?: JSONObject.NULL)
                    try { out.put("wifi_rssi", ti.rssi) } catch (e: Exception) { }
                }
            }
            val lp = cm.getLinkProperties(nw)
            if (lp != null) {
                val ips = JSONArray()
                lp.linkAddresses.forEach { la -> la.address.hostAddress?.let { ips.put(it) } }
                out.put("ips", ips)
                val dns = JSONArray()
                lp.dnsServers.forEach { d -> d.hostAddress?.let { dns.put(it) } }
                out.put("dns", dns)
            }
        } catch (e: Exception) {
            out.put("error", "${e.javaClass.simpleName}: ${e.message}")
        }
        return out
    }

    // ---------------------------------------------------------------- location

    fun locationJson(ctx: Context, timeoutMs: Long): JSONObject {
        val fine = Perms.has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = Perms.has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            Perms.require(ctx, Manifest.permission.ACCESS_FINE_LOCATION, "/location") // throws the sentence
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = try { lm.getProviders(true) } catch (e: Exception) { emptyList() }
        val usable = enabled.filter { it != LocationManager.PASSIVE_PROVIDER }
        if (usable.isEmpty()) {
            throw Refusal(503, "every location provider is off on this phone — ask the human to turn Location ON (quick-settings tile), then retry")
        }
        var best: Location? = null
        try {
            for (p in usable) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
        } catch (e: SecurityException) {
            throw Refusal(409, "location permission was revoked while reading — see /selftest")
        }
        val freshEnough = best != null && (System.currentTimeMillis() - best!!.time) < 60_000
        var fresh = false
        if (!freshEnough && timeoutMs > 0) {
            // One live fix, bounded. GPS indoors may simply not answer: then the
            // last-known fix (with its age printed) is the honest answer.
            val order = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            val provider = order.firstOrNull { usable.contains(it) } ?: usable.first()
            val latch = CountDownLatch(1)
            @Volatile var got: Location? = null
            val thread = HandlerThread("phoneeye-loc")
            thread.start()
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) { got = loc; latch.countDown() }
                override fun onProviderEnabled(p: String) { }
                override fun onProviderDisabled(p: String) { }
            }
            try {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, thread.looper)
                if (latch.await(timeoutMs, TimeUnit.MILLISECONDS) && got != null) {
                    best = got
                    fresh = true
                }
            } catch (e: SecurityException) {
                throw Refusal(409, "location permission was revoked while reading — see /selftest")
            } finally {
                try { lm.removeUpdates(listener) } catch (e: Exception) { }
                thread.quitSafely()
            }
        }
        val loc = best ?: throw Refusal(
            503,
            "no location fix is available (no last-known position and no live fix within ${timeoutMs / 1000}s). " +
            "Indoors GPS is often silent — a human near a window, or Location accuracy → High, is the fix."
        )
        val out = JSONObject()
        out.put("lat", loc.latitude)
        out.put("lon", loc.longitude)
        if (loc.hasAccuracy()) out.put("accuracy_m", loc.accuracy.toDouble())
        if (loc.hasAltitude()) out.put("altitude_m", loc.altitude)
        if (loc.hasSpeed()) out.put("speed_mps", loc.speed.toDouble())
        if (loc.hasBearing()) out.put("bearing_deg", loc.bearing.toDouble())
        out.put("provider", loc.provider ?: "")
        out.put("time", iso(loc.time))
        out.put("age_s", (System.currentTimeMillis() - loc.time) / 1000)
        out.put("live_fix", fresh)
        out.put("coarse_only", !fine)
        return out
    }

    // ---------------------------------------------------------------- shell (as the app, never root)

    fun shell(ctx: Context, cmd: String, timeoutSec: Long): JSONObject {
        val cap = 256 * 1024
        val pb = ProcessBuilder(listOf("sh", "-c", cmd))
        pb.directory(ctx.filesDir)
        pb.environment()["HOME"] = ctx.filesDir.absolutePath
        pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
        val proc = pb.start()

        fun drain(stream: java.io.InputStream, sink: ByteArrayOutputStream, truncated: BooleanArray): Thread {
            val t = Thread {
                try {
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        synchronized(sink) {
                            if (sink.size() < cap) {
                                val take = minOf(n, cap - sink.size())
                                sink.write(buf, 0, take)
                                if (take < n) truncated[0] = true
                            } else truncated[0] = true
                        }
                    }
                } catch (e: Exception) { }
            }
            t.isDaemon = true
            return t
        }

        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val outTrunc = booleanArrayOf(false)
        val errTrunc = booleanArrayOf(false)
        val tOut = drain(proc.inputStream, outBuf, outTrunc)
        val tErr = drain(proc.errorStream, errBuf, errTrunc)
        tOut.start(); tErr.start()
        try { proc.outputStream.close() } catch (e: Exception) { }

        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            try { proc.destroyForcibly() } catch (e: Exception) { }
        }
        tOut.join(1_000); tErr.join(1_000)

        val out = JSONObject()
        out.put("exit", if (finished) try { proc.exitValue() } catch (e: Exception) { JSONObject.NULL } else JSONObject.NULL)
        out.put("timed_out", !finished)
        out.put("stdout", String(outBuf.toByteArray(), Charsets.UTF_8))
        out.put("stderr", String(errBuf.toByteArray(), Charsets.UTF_8))
        out.put("stdout_truncated", outTrunc[0])
        out.put("stderr_truncated", errTrunc[0])
        out.put(
            "runs_as",
            "the phone-eye app itself (uid of the app, no root, SELinux app sandbox). /data, other apps and most of /system are closed to it — a Permission denied here is Android working as designed, not a phone-eye bug."
        )
        return out
    }

    // ---------------------------------------------------------------- files (sandboxed roots)

    data class NamedRoot(val name: String, val file: File)

    @Suppress("DEPRECATION") // getExternalStorageDirectory: deprecated, still the honest name of the sdcard root
    fun roots(ctx: Context): List<NamedRoot> = listOfNotNull(
        NamedRoot("internal", ctx.filesDir),
        try { ctx.getExternalFilesDir(null)?.let { NamedRoot("app-external", it) } } catch (e: Exception) { null },
        NamedRoot("sdcard", Environment.getExternalStorageDirectory())
    )

    fun rootsJson(ctx: Context): JSONArray = JSONArray().apply {
        roots(ctx).forEach { r ->
            put(JSONObject().put("name", r.name).put("path", r.file.absolutePath).put("readable", r.file.canRead()))
        }
    }

    fun resolvePath(ctx: Context, userPath: String): File {
        val roots = roots(ctx)
        val sdcard = roots.firstOrNull { it.name == "sdcard" }?.file
        val base = if (sdcard != null && sdcard.canRead()) sdcard else ctx.filesDir
        val raw = userPath.trim()
        val candidate = when {
            raw.isEmpty() -> base
            File(raw).isAbsolute -> File(raw)
            else -> File(base, raw)
        }
        val canonical = candidate.canonicalFile
        val rootPaths = roots.map { it.file.canonicalPath }
        if (!PathSandbox.isInsideAny(canonical.path, rootPaths)) {
            throw Refusal(
                409,
                "path \"$userPath\" resolves to ${canonical.path}, outside phone-eye's allowed roots " +
                "(${roots.joinToString { "${it.name}=${it.file.absolutePath}" }}). The sandbox refusing is the feature: " +
                "work inside a root, or ask the human for All-files access if sdcard reads come back empty."
            )
        }
        return canonical
    }

    fun listJson(file: File): JSONObject {
        if (!file.exists()) throw Refusal(404, "no such file or directory: ${file.path} (GET /files with no path lists the roots)")
        val out = JSONObject()
        out.put("path", file.absolutePath)
        if (file.isFile) {
            out.put("type", "file")
            out.put("size_bytes", file.length())
            out.put("modified", iso(file.lastModified()))
            return out
        }
        val children = file.listFiles()
            ?: throw Refusal(
                503,
                "Android would not list ${file.path} — on shared storage that usually means All-files access is off " +
                "or media permission was denied. /selftest names the missing switch."
            )
        val sorted = children.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) })
        val arr = JSONArray()
        sorted.take(1000).forEach { c ->
            arr.put(
                JSONObject()
                    .put("name", c.name)
                    .put("type", if (c.isDirectory) "dir" else "file")
                    .put("size_bytes", if (c.isFile) c.length() else JSONObject.NULL)
                    .put("modified", iso(c.lastModified()))
            )
        }
        out.put("type", "dir")
        out.put("entries", arr)
        out.put("count", sorted.size)
        out.put("truncated", sorted.size > 1000)
        return out
    }

    // ---------------------------------------------------------------- apps / open

    fun appsJson(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val infos = pm.getInstalledApplications(0)
        val arr = JSONArray()
        infos.take(2000)
            .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.US) }
            .forEach { ai ->
                arr.put(
                    JSONObject()
                        .put("label", ai.loadLabel(pm).toString())
                        .put("package", ai.packageName)
                        .put("system", (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                        .put("launchable", pm.getLaunchIntentForPackage(ai.packageName) != null)
                )
            }
        return arr
    }

    fun openTarget(ctx: Context, pkg: String?, url: String?): JSONObject {
        val intent = when {
            !url.isNullOrBlank() -> Intent(Intent.ACTION_VIEW, Uri.parse(url))
            !pkg.isNullOrBlank() -> ctx.packageManager.getLaunchIntentForPackage(pkg)
                ?: throw Refusal(404, "package \"$pkg\" has no launchable activity — is it installed and enabled? GET /apps lists what is.")
            else -> throw Refusal(400, "/open needs exactly one of {\"package\": ...} or {\"url\": ...}")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            throw Refusal(
                503,
                "Android refused to start it (${e.javaClass.simpleName}: ${e.message}). Background activity starts are " +
                "restricted on many phones — if the phone is locked or phone-eye was just swipe-killed, ask the human " +
                "to unlock; otherwise take a /shot, go home, and tap the icon with /act like a finger would."
            )
        }
        return JSONObject()
            .put("requested", true)
            .put("package", pkg ?: JSONObject.NULL)
            .put("url", url ?: JSONObject.NULL)
            .put("note", "start requested, not proven — Android can silently defer background starts. Verify with /shot.")
    }

    // ---------------------------------------------------------------- small senses

    fun clipboardGet(ctx: Context): JSONObject {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx)?.toString() else null
        if (text == null) {
            throw Refusal(
                503,
                "the clipboard reads empty from here. Two honest possibilities: it IS empty, or Android 10+ blocked a " +
                "background read (only the foreground app may read the clipboard). To distinguish them, ask the human " +
                "to bring phone-eye to the front and retry."
            )
        }
        return JSONObject().put("text", text).put("chars", text.length)
    }

    fun clipboardSet(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("phone-eye", text))
    }

    fun toast(ctx: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    const val CHANNEL_RUNNING = "phoneeye_running"
    const val CHANNEL_AGENT = "phoneeye_agent"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RUNNING, "phone-eye session", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_AGENT, "Agent messages", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun notifyAgent(ctx: Context, title: String, text: String) {
        ensureChannels(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = Notification.Builder(ctx, CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt() + 1000, n)
    }

    fun vibrate(ctx: Context, ms: Long) {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun torch(ctx: Context, on: Boolean): JSONObject {
        val cam = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var chosen: String? = null
        for (id in cam.cameraIdList) {
            val ch = cam.getCameraCharacteristics(id)
            val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) { chosen = id; break }
            if (hasFlash && chosen == null) chosen = id
        }
        val id = chosen ?: throw Refusal(503, "this phone reports no camera flash unit, so there is no torch to switch")
        cam.setTorchMode(id, on)
        return JSONObject().put("on", on).put("camera_id", id)
    }

    // ---------------------------------------------------------------- people (read-only, permission-gated)

    private fun queryRows(
        ctx: Context, uri: Uri, projection: Array<String>, names: List<String>,
        sortOrder: String?, limit: Int
    ): JSONArray {
        val arr = JSONArray()
        val cursor: Cursor? = ctx.contentResolver.query(uri, projection, null, null, sortOrder)
            ?: throw Refusal(503, "the content provider returned no cursor for $uri")
        cursor.use { c ->
            var count = 0
            while (c.moveToNext() && count < limit) {
                val row = JSONObject()
                projection.forEachIndexed { i, col ->
                    val idx = c.getColumnIndex(col)
                    if (idx < 0) return@forEachIndexed
                    val name = names.getOrNull(i) ?: col
                    when (c.getType(idx)) {
                        Cursor.FIELD_TYPE_INTEGER -> row.put(name, c.getLong(idx))
                        Cursor.FIELD_TYPE_FLOAT -> row.put(name, c.getDouble(idx))
                        Cursor.FIELD_TYPE_STRING -> row.put(name, c.getString(idx) ?: JSONObject.NULL)
                        Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                        else -> row.put(name, JSONObject.NULL)
                    }
                }
                arr.put(row)
                count++
            }
        }
        return arr
    }

    fun contacts(ctx: Context, limit: Int): JSONArray {
        Perms.require(ctx, Manifest.permission.READ_CONTACTS, "/contacts")
        return queryRows(
            ctx, ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            listOf("name", "number"),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC", limit
        )
    }

    fun sms(ctx: Context, limit: Int): JSONArray {
        Perms.require(ctx, Manifest.permission.READ_SMS, "/sms")
        return queryRows(
            ctx, Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
            listOf("address", "body", "date_ms", "type", "read"),
            Telephony.Sms.DATE + " DESC", limit
        )
    }

    fun callLog(ctx: Context, limit: Int): JSONArray {
        Perms.require(ctx, Manifest.permission.READ_CALL_LOG, "/calllog")
        return queryRows(
            ctx, CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
            listOf("number", "name", "date_ms", "duration_s", "type"),
            CallLog.Calls.DATE + " DESC", limit
        )
    }
}
