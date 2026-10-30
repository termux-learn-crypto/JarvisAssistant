package com.vilas.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

/** v5.0: "Hey Maira" model app ke andar hi hai (assets/wake), download ki zaroorat nahi. */
object WakeModel {
    fun ready(ctx: Context) = true
}

/** Hamesha chalne wali service: "Hey Maira" sunte hi quick mic khulta hai. */
class WakeService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var det: HeyMairaDetector? = null
    @Volatile private var loop: Thread? = null
    @Volatile private var running = false
    private var paused = false
    private var lastTrigger = 0L
    private var lastShown = 0L
    private var notifBuilder: Notification.Builder? = null

    private fun showHeard(t: String) {
        val now = System.currentTimeMillis()
        if (now - lastShown < 1200) return
        lastShown = now
        val b = notifBuilder ?: return
        b.setContentText("Suna: $t — bolo \"Hey Maira\"")
        getSystemService(NotificationManager::class.java).notify(NID, b.build())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Hey Maira", NotificationManager.IMPORTANCE_MIN))
        val stopPi = PendingIntent.getService(this, 7, Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openPi = PendingIntent.getActivity(this, 8, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Maira sun rahi hai")
            .setContentText("Bolo \"Hey Maira\"")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Band karo", stopPi).build())
            .setOnlyAlertOnce(true)
        notifBuilder = nb
        val n = nb.build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NID, n)
        run {
            val f = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            }
            registerReceiver(screenRx, f); screenRxOn = true
        }
        Thread {
            try {
                det = HeyMairaDetector(this)
                main.post { if (!paused) listen() }
            } catch (e: Exception) {
                main.post { stopSelf() }
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Brain.prefs(this).edit().putBoolean("wake", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private var screenRxOn = false
    private var screenOff = false
    private val screenRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> if (!Brain.prefs(this@WakeService).getBoolean("wake_screenoff", true)) { screenOff = true; stopMic() }
                Intent.ACTION_SCREEN_ON -> { screenOff = false; if (!paused) main.postDelayed({ listen() }, 300) }
            }
        }
    }

    override fun onDestroy() {
        if (screenRxOn) try { unregisterReceiver(screenRx) } catch (_: Exception) {}
        instance = null
        stopMic()
        det?.close(); det = null
        super.onDestroy()
    }

    /** Apna mic loop: AudioRecord -> HeyMairaDetector (har 80 ms score). */
    private fun listen() {
        val d = det ?: return
        if (loop != null || paused || screenOff) return
        running = true
        loop = Thread {
            var rec: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            try {
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val size = maxOf(min, 8192)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mic")
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
                // NoiseSuppressor kuch phones pe awaaz bigaadta hai; model khud noise ke saath train hai, isliye band
                d.reset()
                rec.startRecording()
                val buf = ShortArray(1280)
                var th = HeyMairaDetector.threshold(this)
                var frames = 0
                var peak = 0f
                var hits = 0
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val s = d.feed(buf, n)
                    if (s < 0f) continue
                    frames++
                    if (s > peak) peak = s
                    if (s >= th) hits++ else hits = 0
                    if (hits >= 2) {   // 2 lagataar frames (160 ms): galat trigger kam
                        val shown = s
                        main.post { showHeard("Hey Maira ✓ (%.2f)".format(shown)) }
                        main.post { trigger() }
                        hits = 0
                        d.reset()
                    }
                    if (frames % 25 == 0) {          // har 2 sec: debug + threshold refresh
                        if (peak >= 0.05f) {
                            val p = peak; val t = th
                            main.post { showHeard("%.2f (chahiye %.2f)".format(p, t)) }
                        }
                        peak = 0f
                        th = HeyMairaDetector.threshold(this)
                    }
                }
            } catch (e: Exception) {
                if (running && !paused) main.postDelayed({ loop = null; listen() }, 3000)
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release(); aec?.release(); ns?.release()
            }
        }.also { it.start() }
    }

    private fun stopMic() {
        running = false
        val t = loop; loop = null
        if (t != null && t != Thread.currentThread()) try { t.join(600) } catch (_: Exception) {}
    }

    private fun trigger() {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 2500) return
        lastTrigger = now
        paused = true
        stopMic()
        main.postDelayed({
            val i = Intent(this, QuickMicActivity::class.java).putExtra("wake", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            // Background se activity kholna: accessibility service ke context se allowed hai
            val launched = try {
                val a = MairaAccessibilityService.instance
                if (a != null) { a.startActivity(i); true } else { startActivity(i); true }
            } catch (_: Exception) { try { startActivity(i); true } catch (_: Exception) { false } }
            // Backup: full-screen notification (agar Android ne activity rok di)
            main.postDelayed({ if (QuickMicActivity.open == 0) wakeNotify(i) }, 1200)
            if (!launched) wakeNotify(i)
        }, 150)
        // Agar quick mic na khule (permission), to 20s baad phir sunna shuru
        main.postDelayed({ if (paused && QuickMicActivity.open == 0) resumeMic() }, 20000)
    }

    private fun wakeNotify(i: Intent) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH2, "Hey Maira alert", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(this, 9, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH2)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Haan, bolo?")
            .setContentText("Maira sun rahi hai, yahan dabao")
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(15000)
            .build()
        try { nm.notify(NID + 1, n) } catch (_: Exception) {}
    }

    fun pauseMic() { main.post { paused = true; stopMic() } }

    fun resumeMic() { main.postDelayed({ paused = false; listen() }, 700) }

    companion object {
        private const val CH = "maira_wake"
        private const val CH2 = "maira_wake_alert"
        private const val NID = 4242
        private const val ACTION_STOP = "com.vilas.jarvis.WAKE_STOP"
        @Volatile var instance: WakeService? = null

        fun start(ctx: Context) {
            if (!WakeModel.ready(ctx)) return
            ctx.startForegroundService(Intent(ctx, WakeService::class.java))
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WakeService::class.java)) }
    }
}
