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
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** "Hey Maira" sunne wala chhota offline model (Vosk, ~37 MB, ek baar download). */
object WakeModel {
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
    private const val DIR = "vosk-model-small-en-in-0.4"

    fun root(ctx: Context) = File(ctx.filesDir, "vosk")
    fun path(ctx: Context) = File(root(ctx), DIR)
    fun ready(ctx: Context) = File(root(ctx), "done").exists() && File(path(ctx), "am/final.mdl").exists()

    @Volatile var downloading = false

    /** Background thread pe download + unzip. progress(0..100), done(error ya null). */
    fun download(ctx: Context, progress: (Int) -> Unit, done: (String?) -> Unit) {
        if (downloading) return
        downloading = true
        Thread {
            var err: String? = null
            try {
                val r = root(ctx); r.deleteRecursively(); r.mkdirs()
                val c = URL(URL_ZIP).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 30000
                val total = c.contentLengthLong.takeIf { it > 0 } ?: 37_573_330L
                var read = 0L
                var lastPct = -1
                val counting = object : java.io.FilterInputStream(c.inputStream.buffered()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            read += n
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                        return n
                    }
                }
                ZipInputStream(counting).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val out = File(r, e.name)
                        if (!out.canonicalPath.startsWith(r.canonicalPath)) continue
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { z.copyTo(it) }
                        }
                    }
                }
                File(r, "done").writeText("ok")
            } catch (e: Exception) {
                err = e.message ?: "download fail"
            }
            downloading = false
            done(err)
        }.start()
    }
}

/** Hamesha chalne wali service: "Hey Maira" sunte hi quick mic khulta hai. */
class WakeService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var paused = false
    private var lastTrigger = 0L

    private val wake = Regex(
        "\\b(hey|hi|hello|ok|okay|hay)\\s+(maira|myra|mira|meera|mayra|maya|moira|mera|maria|nyra|myrah|mara|maira's|maiora)\\b"
    )

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
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Maira sun rahi hai")
            .setContentText("Bolo \"Hey Maira\"")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Band karo", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NID, n)
        Thread {
            try {
                model = Model(WakeModel.path(this).absolutePath)
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

    override fun onDestroy() {
        instance = null
        stopMic()
        model?.close(); model = null
        super.onDestroy()
    }

    private fun listen() {
        val m = model ?: return
        if (speech != null || paused) return
        try {
            val rec = Recognizer(m, 16000f)
            speech = SpeechService(rec, 16000f).also { it.startListening(listener) }
        } catch (e: Exception) {
            speech = null
            main.postDelayed({ listen() }, 3000)   // mic busy ho to thodi der baad
        }
    }

    private fun stopMic() {
        try { speech?.stop(); speech?.shutdown() } catch (_: Exception) {}
        speech = null
    }

    private fun check(json: String?, key: String) {
        val t = try { JSONObject(json ?: return).optString(key) } catch (_: Exception) { return }
        if (t.isBlank()) return
        if (wake.containsMatchIn(t.lowercase())) trigger()
    }

    private fun trigger() {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 2500) return
        lastTrigger = now
        paused = true
        stopMic()
        main.postDelayed({
            try {
                startActivity(Intent(this, QuickMicActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (_: Exception) { resumeMic() }
        }, 150)
        // Agar quick mic na khule (permission), to 20s baad phir sunna shuru
        main.postDelayed({ if (paused && QuickMicActivity.open == 0) resumeMic() }, 20000)
    }

    fun pauseMic() { main.post { paused = true; stopMic() } }

    fun resumeMic() { main.postDelayed({ paused = false; listen() }, 700) }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(h: String?) = check(h, "partial")
        override fun onResult(h: String?) = check(h, "text")
        override fun onFinalResult(h: String?) = check(h, "text")
        override fun onError(e: Exception?) { stopMic(); if (!paused) main.postDelayed({ listen() }, 2000) }
        override fun onTimeout() { stopMic(); if (!paused) listen() }
    }

    companion object {
        private const val CH = "maira_wake"
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
