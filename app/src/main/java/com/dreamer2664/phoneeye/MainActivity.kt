package com.dreamer2664.phoneeye

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The human's one screen: START/STOP, the two lines for the agent, the one-time
 * setup checklist, and the live log. Everything refreshes at 1 Hz from EyeState —
 * boring, and therefore always true.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var startStopButton: Button
    private lateinit var urlText: TextView
    private lateinit var tokenText: TextView
    private lateinit var tunnelText: TextView
    private lateinit var localText: TextView
    private lateinit var readonlySwitch: Switch
    private lateinit var accStatus: TextView
    private lateinit var permStatus: TextView
    private lateinit var filesStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var logText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var lastLogRevision = -1L

    private val stateListener: () -> Unit = { runOnUiThread { refresh() } }
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        EyeLog.init(filesDir)
        Device.ensureChannels(this)

        statusText = findViewById(R.id.statusText)
        startStopButton = findViewById(R.id.startStopButton)
        urlText = findViewById(R.id.urlText)
        tokenText = findViewById(R.id.tokenText)
        tunnelText = findViewById(R.id.tunnelText)
        localText = findViewById(R.id.localText)
        readonlySwitch = findViewById(R.id.readonlySwitch)
        accStatus = findViewById(R.id.accStatus)
        permStatus = findViewById(R.id.permStatus)
        filesStatus = findViewById(R.id.filesStatus)
        batteryStatus = findViewById(R.id.batteryStatus)
        logText = findViewById(R.id.logText)

        findViewById<TextView>(R.id.versionText).text =
            "phone-eye ${Device.versionName(this)} · github.com/dreamer2664/phone-eye · port ${Core.PORT} · gives its agent PHONE-EYE.md"

        val prefs = getSharedPreferences(PhoneEyeService.PREFS, Context.MODE_PRIVATE)
        EyeState.dryRun = prefs.getBoolean(PhoneEyeService.PREF_DRY_RUN, false)
        readonlySwitch.isChecked = EyeState.dryRun
        readonlySwitch.setOnCheckedChangeListener { _, checked ->
            EyeState.dryRun = checked
            prefs.edit().putBoolean(PhoneEyeService.PREF_DRY_RUN, checked).apply()
            EyeLog.log("read-only mode ${if (checked) "ON (agent can look, never touch)" else "OFF (agent can act)"} — set by the human in the app")
            Toast.makeText(this, if (checked) "Read-only ON: the agent can look, never touch" else "Read-only OFF", Toast.LENGTH_SHORT).show()
        }

        startStopButton.setOnClickListener {
            if (EyeState.running) {
                startService(Intent(this, PhoneEyeService::class.java).setAction(PhoneEyeService.ACTION_STOP))
            } else {
                startForegroundService(Intent(this, PhoneEyeService::class.java).setAction(PhoneEyeService.ACTION_START))
            }
        }

        findViewById<Button>(R.id.copyBothButton).setOnClickListener {
            val url = EyeState.publicUrl
            val token = EyeState.token
            when {
                !EyeState.running || token.isEmpty() ->
                    Toast.makeText(this, "Press Start first — there are no lines to copy yet", Toast.LENGTH_LONG).show()
                url == null ->
                    Toast.makeText(
                        this,
                        "The public tunnel is not up yet (watch the Tunnel line). Copying now would hand the agent a URL that cannot work.",
                        Toast.LENGTH_LONG
                    ).show()
                else -> {
                    copy("phone-eye connection", "URL:   $url\nTOKEN: $token")
                    Toast.makeText(this, "Copied. Paste it ONLY into the chat that owns this session.", Toast.LENGTH_LONG).show()
                }
            }
        }
        findViewById<Button>(R.id.copyUrlButton).setOnClickListener {
            val url = EyeState.publicUrl
            if (url == null) Toast.makeText(this, "No public URL yet", Toast.LENGTH_SHORT).show()
            else copy("phone-eye URL", url)
        }
        findViewById<Button>(R.id.copyTokenButton).setOnClickListener {
            if (EyeState.token.isEmpty()) Toast.makeText(this, "Press Start first", Toast.LENGTH_SHORT).show()
            else copy("phone-eye TOKEN", EyeState.token)
        }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            EyeLog.clearScreen()
            lastLogRevision = -1
            refresh()
        }

        findViewById<Button>(R.id.accButton).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Installed apps → \"phone-eye hand\" → turn ON → Allow", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Open Settings → Accessibility yourself; this ROM moved it", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.permButton).setOnClickListener {
            requestPermissions(permissionList().toTypedArray(), 4242)
        }
        findViewById<Button>(R.id.filesButton).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    Toast.makeText(this, "Settings → Apps → phone-eye → All files access", Toast.LENGTH_LONG).show()
                }
            }
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    Toast.makeText(this, "Settings → Apps → phone-eye → Battery → Unrestricted", Toast.LENGTH_LONG).show()
                }
            }
        }

        EyeState.addListener(stateListener)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        EyeState.removeListener(stateListener)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (EyeState.running) {
            Toast.makeText(this, "Tip: Stop, then Start, so the session picks up new camera/location rights", Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun copy(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun permissionList(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms
    }

    private fun accessibilityEnabledInSettings(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return list.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun refresh() {
        val running = EyeState.running
        statusText.text = if (running) "● RUNNING — the agent can reach this phone" else "○ Stopped — nothing can reach this phone"
        statusText.setTextColor(Color.parseColor(if (running) "#1B7A3D" else "#1A1A1A"))
        startStopButton.text = if (running) "■  STOP phone-eye (the URL dies instantly)" else "▶  START phone-eye"

        urlText.text = "URL:   " + when {
            !running -> "(press Start)"
            EyeState.publicUrl != null -> EyeState.publicUrl
            else -> "(tunnel connecting… see below)"
        }
        tokenText.text = "TOKEN: " + if (running && EyeState.token.isNotEmpty()) EyeState.token else "(press Start)"

        tunnelText.text = buildString {
            append("Tunnel: ").append(EyeState.tunnelProvider)
            if (EyeState.tunnelDetail.isNotEmpty()) append("\n").append(EyeState.tunnelDetail)
        }
        localText.text = if (running) {
            "LAN: ${EyeState.lanUrl ?: "(no Wi-Fi address)"} · Local: http://127.0.0.1:${Core.PORT}"
        } else "LAN: —"

        if (readonlySwitch.isChecked != EyeState.dryRun) readonlySwitch.isChecked = EyeState.dryRun

        // Checklist, the same facts /selftest reports to the agent.
        val accBound = PhoneAccessibilityService.get() != null
        val accOn = accessibilityEnabledInSettings()
        accStatus.text = "1. Accessibility (tap/swipe/screenshots): " + when {
            accBound -> "ON ✓ (eyes + hand live)"
            accOn -> "enabled in Settings, still binding…"
            else -> "OFF ✗ — without it the agent is blind and handless"
        }
        accStatus.setTextColor(Color.parseColor(if (accBound) "#1B7A3D" else "#B00020"))

        val perms = permissionList()
        val granted = perms.filter { Perms.has(this, it) }
        val missing = (perms - granted.toSet()).map { it.substringAfterLast('.') }
        permStatus.text = "2. Permissions (camera / location / media / contacts…): ${granted.size}/${perms.size} granted" +
            if (missing.isEmpty()) " ✓" else " — missing: ${missing.joinToString(", ")} (only those tools refuse)"
        permStatus.setTextColor(Color.parseColor(if (missing.isEmpty()) "#1B7A3D" else "#9A6700"))

        val allFiles = Environment.isExternalStorageManager()
        filesStatus.text = "3. All-files access (agent file tools beyond media): " + if (allFiles) "ON ✓" else "OFF (agent still gets app + granted media files)"
        filesStatus.setTextColor(Color.parseColor(if (allFiles) "#1B7A3D" else "#9A6700"))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val unrestricted = pm.isIgnoringBatteryOptimizations(packageName)
        batteryStatus.text = "4. Battery: allow running in background: " + if (unrestricted) "Unrestricted ✓" else "Restricted — Android may freeze phone-eye mid-session ✗"
        batteryStatus.setTextColor(Color.parseColor(if (unrestricted) "#1B7A3D" else "#B00020"))

        if (EyeLog.revision != lastLogRevision) {
            lastLogRevision = EyeLog.revision
            logText.text = EyeLog.last(120).joinToString("\n").ifEmpty { "(no log yet — press Start)" }
        }
    }
}
