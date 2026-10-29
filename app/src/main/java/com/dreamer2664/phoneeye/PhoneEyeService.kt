package com.dreamer2664.phoneeye

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import fi.iki.elonen.NanoHTTPD

/**
 * The console window. pc-eye's off switch is a console you close; a phone's is
 * this foreground service with its permanent notification and Stop button.
 *
 * It owns exactly three things for exactly as long as the human left it running:
 * the HTTP server, the tunnels, and a wake lock. START_NOT_STICKY on purpose:
 * if Android kills it, it stays dead and the notification vanishing says so —
 * nothing about phone-eye ever restarts itself behind the owner's back.
 */
class PhoneEyeService : Service() {

    companion object {
        const val ACTION_START = "com.dreamer2664.phoneeye.START"
        const val ACTION_STOP = "com.dreamer2664.phoneeye.STOP"
        private const val NOTIF_ID = 8791
        const val PREFS = "phone-eye"
        const val PREF_DRY_RUN = "dry_run"
    }

    private var server: PhoneEyeServer? = null
    private var tunnels: TunnelManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cleaned = true

    override fun onCreate() {
        super.onCreate()
        EyeLog.init(filesDir)
        Device.ensureChannels(this)
        // NanoHTTPD's temp files (unused by us, created anyway) must land somewhere writable.
        try { System.setProperty("java.io.tmpdir", cacheDir.absolutePath) } catch (e: Exception) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                EyeLog.log("session: STOP pressed by the human — closing the door")
                stopSession()
            }
            else -> startSession()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        if (EyeState.running) return
        cleaned = false

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = Core.newToken()
        EyeState.token = token
        EyeState.dryRun = prefs.getBoolean(PREF_DRY_RUN, false)
        EyeState.running = true
        EyeState.startedAtMs = System.currentTimeMillis()
        EyeState.publicUrl = null
        EyeState.tunnelProvider = "starting…"
        EyeState.tunnelDetail = ""
        EyeState.lanUrl = Device.lanIp(this)?.let { "http://$it:${Core.PORT}" }
        EyeLog.setSessionToken(token)

        // Foreground first: startForegroundService gives ~5s to show ourselves.
        startForegroundWithNotification(buildNotification())

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phoneeye:session").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            EyeLog.log("session: wake lock unavailable (${e.javaClass.simpleName}) — the CPU may sleep between requests")
        }

        try {
            val s = PhoneEyeServer(applicationContext, token)
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
        } catch (e: Exception) {
            EyeLog.log("session: server FAILED to start on :${Core.PORT} — ${e.javaClass.simpleName}: ${e.message} (is another phone-eye already running?)")
            EyeState.tunnelDetail = "server failed to bind port ${Core.PORT}: ${e.message}"
            stopSession()
            return
        }

        EyeLog.log("session: STARTED — server on :${Core.PORT}, read-only=${EyeState.dryRun}, LAN=${EyeState.lanUrl ?: "none"}")
        EyeLog.log("session: the URL + TOKEN on the app screen are the whole connection. They change on every Start.")

        tunnels = TunnelManager(applicationContext, Core.PORT) {
            updateNotification()
            EyeState.notifyChanged()
        }
        tunnels?.start()
        EyeState.notifyChanged()
        updateNotification()
    }

    private fun startForegroundWithNotification(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            // Android 14 throws if a type bit is asserted without its permission.
            // Assert exactly what the human has granted so far; restart picks up more.
            if (Perms.has(this, Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (Perms.has(this, Manifest.permission.ACCESS_FINE_LOCATION) ||
                Perms.has(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            ) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PhoneEyeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val line2 = when {
            EyeState.publicUrl != null -> EyeState.publicUrl!!
            EyeState.tunnelDetail.isNotEmpty() -> EyeState.tunnelDetail.take(140)
            else -> "Tunnel: ${EyeState.tunnelProvider} · LAN: ${EyeState.lanUrl ?: "—"}"
        }
        return Notification.Builder(this, Device.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("phone-eye is ON — the agent can see this phone")
            .setContentText(line2)
            .setStyle(Notification.BigTextStyle().bigText("$line2\nTunnel: ${EyeState.tunnelProvider} · read-only: ${EyeState.dryRun}\nStop ends the session immediately — the URL dies with it."))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_eye), "STOP phone-eye", stop
                ).build()
            )
            .build()
    }

    private fun updateNotification() {
        if (!EyeState.running) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) { }
    }

    private fun stopSession() {
        if (!cleaned) {
            cleaned = true
            try { tunnels?.stop() } catch (e: Exception) { }
            tunnels = null
            try { server?.stop() } catch (e: Exception) { }
            server = null
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
            wakeLock = null
            EyeLog.log("session: STOPPED — server and tunnels are down; that URL + TOKEN will never work again")
            EyeLog.setSessionToken("")
            EyeState.reset()
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        if (!cleaned) {
            // Android killed us. Same cleanup, minus the fiction that stopSelf is needed.
            cleaned = true
            try { tunnels?.stop() } catch (e: Exception) { }
            try { server?.stop() } catch (e: Exception) { }
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
            EyeLog.setSessionToken("")
            EyeState.reset()
        }
        super.onDestroy()
    }
}
