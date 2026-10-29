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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
    @Volatile private var loop: Thread? = null
    @Volatile private var running = false
    private var paused = false
    private var lastTrigger = 0L

    // Vosk ka chhota model "Maira" ko aksar "mirror"/"mira" likhta hai (test kiya), aur "mera" ko bhi "mirror".
    // Isliye "Hey/Hi/OK" ke saath wala hi pakka maante hain; "easy" mode mein sirf naam bhi chalega.
    private val wake = Regex(
        "\\b(hey|hi|hay|him|hm|hmm|okay|ok|oh|hello|he)\\s+(mirror|myra|mira|moira|mayra|maira|maya|maria|mara|my\\s+ra)\\b" +
        "|\\b(mirror|mira|myra|moira)\\s+(soon|suno|sun|soono|listen)\\b" +
        "|^\\s*(the|a)\\s+(mirror|myra|mira|moira)\\b"
    )
    private val easyWake = Regex("\\b(mirror|myra|mira|moira|mayra|maira)\\b")
    private var lastShown = 0L
    private var notifBuilder: Notification.Builder? = null

    private fun showHeard(t: String) {
        val now = System.currentTimeMillis()
        if (now - lastShown < 1200) return
        lastShown = now
        val b = notifBuilder ?: return
        b.setContentText("Suna: \"$t\" — bolo \"Hey Maira\"")
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

    /** Apna mic loop: VOICE_COMMUNICATION + echo cancel, taaki phone ka apna gaana/video wake word na bane. */
    private fun listen() {
        val m = model ?: return
        if (loop != null || paused) return
        running = true
        loop = Thread {
            var rec: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            var vr: Recognizer? = null
            try {
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val size = maxOf(min, 8192)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mic")
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
                vr = Recognizer(m, 16000f)
                rec.startRecording()
                val buf = ByteArray(4096)
                var sinceReset = 0
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (vr.acceptWaveForm(buf, n)) { check(vr.result, "text"); sinceReset = 0 }
                    else check(vr.partialResult, "partial")
                    sinceReset += n
                    if (sinceReset > 16000 * 2 * 8) { vr.reset(); sinceReset = 0 }  // lambi baat-cheet/gaana: har 8s reset
                }
            } catch (e: Exception) {
                if (running && !paused) main.postDelayed({ loop = null; listen() }, 3000)
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release(); aec?.release(); ns?.release(); vr?.close()
            }
        }.also { it.start() }
    }

    private fun stopMic() {
        running = false
        val t = loop; loop = null
        if (t != null && t != Thread.currentThread()) try { t.join(600) } catch (_: Exception) {}
    }

    private fun check(json: String?, key: String) {
        val t = try { JSONObject(json ?: return).optString(key) } catch (_: Exception) { return }
        if (t.isBlank()) return
        val low = t.lowercase()
        main.post { showHeard(low) }
        val easy = Brain.prefs(this).getBoolean("wake_easy", false)
        if (wake.containsMatchIn(low) || (easy && easyWake.containsMatchIn(low))) main.post { trigger() }
    }

    private fun trigger() {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 2500) return
        lastTrigger = now
        paused = true
        stopMic()
        main.postDelayed({
            try {
                startActivity(Intent(this, QuickMicActivity::class.java).putExtra("wake", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (_: Exception) { resumeMic() }
        }, 150)
        // Agar quick mic na khule (permission), to 20s baad phir sunna shuru
        main.postDelayed({ if (paused && QuickMicActivity.open == 0) resumeMic() }, 20000)
    }

    fun pauseMic() { main.post { paused = true; stopMic() } }

    fun resumeMic() { main.postDelayed({ paused = false; listen() }, 700) }

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
