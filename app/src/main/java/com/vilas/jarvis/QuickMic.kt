package com.vilas.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

object QuickMic {
    private const val CHANNEL = "maira_quick"
    private const val NOTIF_ID = 77

    fun intent(ctx: Context) = Intent(ctx, QuickMicActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun pending(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 7, intent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Notification shade mein "Maira se bolo" — kisi bhi app ke upar se mic. */
    fun refreshNotification(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!Brain.prefs(ctx).getBoolean("quickmic", true)) { nm.cancel(NOTIF_ID); return }
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Maira quick mic", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Maira se bolo")
            .setContentText("Tap karo, gaana chalta rahega")
            .setContentIntent(pending(ctx))
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        nm.notify(NOTIF_ID, n)
    }
}

/** Quick Settings tile: notification panel se seedha Maira. */
class MairaTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(QuickMic.pending(this))
        else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(QuickMic.intent(this))
        }
    }
}

/**
 * Chhota mic card jo kisi bhi app ke upar neeche khulta hai. Card ke bahar ke touch aur
 * Maira ke tap peeche wali app tak jaate hain. Mouse/keyboard ke liye lagatar sunti hai jab tak "bas" na bolo.
 */
class QuickMicActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var status: TextView
    private lateinit var heard: TextView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var afterSpeech: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var executor: ActionExecutor
    private var session = false      // lagatar sunne wala mode
    private var live = false         // Baatcheet (Live jaisa) mode: bina "bas" ke lagatar baat
    @Volatile private var working = false
    private var barge: BargeIn? = null
    private var misses = 0
    private var closing = false
    private var focusReq: android.media.AudioFocusRequest? = null
    private val audio by lazy { getSystemService(android.media.AudioManager::class.java) }

    /** Jab tak main sun/bol rahi hoon, chal raha gaana/video ruka rahe. */
    private fun holdFocus() {
        if (focusReq != null) return
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val r = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs).setOnAudioFocusChangeListener { }.build()
        if (audio.requestAudioFocus(r) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusReq = r
    }

    private fun releaseFocus() {
        focusReq?.let { audio.abandonAudioFocusRequest(it) }
        focusReq = null
    }

    private val PLAY_ACTS = setOf("play", "play_pause", "toggle", "next", "previous", "prev")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        open++
        WakeService.instance?.pauseMic()
        executor = ActionExecutor(this)
        setFinishOnTouchOutside(false)
        window.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            setGravity(Gravity.BOTTOM)
            setLayout(MATCH_PARENT, WRAP_CONTENT)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE0E1117")); cornerRadius = dp(18).toFloat()
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "MAIRA"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#4FC3F7"))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val close = TextView(this).apply {
            text = "  ✕  "
            textSize = 18f
            setTextColor(Color.LTGRAY)
            setOnClickListener { endSession() }
        }
        top.addView(title); top.addView(close)
        status = TextView(this).apply { text = "Sun rahi hoon…"; textSize = 16f; setTextColor(Color.WHITE); setPadding(0, dp(6), 0, 0) }
        heard = TextView(this).apply { textSize = 13f; setTextColor(Color.GRAY) }
        card.addView(top); card.addView(status); card.addView(heard)
        val wrap = FrameLayout(this).apply { setPadding(dp(10), 0, dp(10), dp(16)); addView(card) }
        setContentView(wrap)

        live = Brain.prefs(this).getBoolean("live", true)
        if (live) session = true
        holdFocus()
        tts = TextToSpeech(this, this)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5)
        } else if (intent?.getBooleanExtra("wake", false) == true) {
            status.text = "Haan bolo…"
            val m = Brain.lang(this).mode
            say(when (m) { "English" -> "Yes?"; "Marathi" -> "हां, बोल?"; else -> "हाँ, बोलो?" }) { listen() }
        } else listen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
        else { status.text = "Mic permission chahiye."; main.postDelayed({ finish() }, 1500) }
    }

    override fun onDestroy() {
        closing = true
        stopBarge()
        recognizer?.destroy()
        tts?.shutdown()
        releaseFocus()
        open = (open - 1).coerceAtLeast(0)
        if (open == 0) WakeService.instance?.resumeMic()
        super.onDestroy()
    }

    companion object { @Volatile var open = 0 }

    private fun endSession() {
        Agent.cancel = true
        closing = true
        session = false
        MairaAccessibilityService.instance?.let { svc -> try { svc.labels("hide", 0) } catch (_: Exception) {} }
        finish()
    }

    /** Ek kaam khatam: session mein phir suno, warna band. */
    private fun next() {
        if (closing) return
        // Echo Guard: Maira ki awaaz khatam hone ke 450 ms baad hi mic khulta hai
        if (session) main.postDelayed({ if (!closing) listen() }, 450) else main.postDelayed({ finish() }, 500)
    }

    private fun listen() {
        if (closing) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Voice recognition nahi mila. Google app update karo."
            return
        }
        recognizer?.destroy()
        stopBarge()
        holdFocus()
        val lang = Brain.lang(this).rec
        status.text = if (live) "Bolo… (beech mein tok bhi sakte ho)" else if (session) "Bolo… (band karne ke liye \"bas\")" else "Sun rahi hoon…"
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "Samajh rahi hoon…" }
                override fun onEvent(t: Int, b: Bundle?) {}
                override fun onPartialResults(b: Bundle?) {
                    b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { heard.text = it }
                }
                override fun onError(error: Int) {
                    if (closing) return
                    if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == 13) {
                        status.text = "Offline voice pack nahi hai. Google app > Settings > Voice > Offline speech recognition mein Hindi aur English (India) download karo."
                        main.postDelayed({ endSession() }, 6000); return
                    }
                    misses++
                    if (session && misses < (if (live) 3 else 4)) { listen(); return }
                    if (live) { status.text = "Theek hai, jab chaho \"Hey Maira\" bolna."; main.postDelayed({ endSession() }, 900); return }
                    status.text = "Kuch sunai nahi diya."
                    main.postDelayed({ endSession() }, 1200)
                }
                override fun onResults(b: Bundle?) {
                    val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) onError(0) else { misses = 0; handle(text) }
                }
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !Brain.online(this))
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        recognizer?.startListening(i)
    }

    private val stopWords = Regex("(?i)^\\s*(bas|bas karo|bas kar|stop|stop listening|ruk jao|ho gaya|band karo|बस|बस करो|रुक जाओ|हो गया|थांब|पुरे)\\s*$")

    private fun handle(text: String) {
        heard.text = text
        if (stopWords.containsMatchIn(text)) {
            status.text = "Theek hai, bye!"
            closing = true
            say(if (Brain.lang(this).mode == "English") "Okay." else "ठीक है।") { endSession() }
            return
        }
        status.text = "Ek sec…"
        lastHeard = text
        Thread {
            val r = try { Result.success(Brain.plan(this, text)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                r.fold(onSuccess = { onPlan(it) }, onFailure = {
                    status.text = "Error: ${it.message}"
                    next()
                })
            }
        }.start()
    }

    private val SESSION_TOOLS = setOf("mouse", "gesture", "labels", "keyboard", "screen_action", "read_screen")

    private var lastHeard = ""

    private fun onPlan(plan: Plan) {
        Brain.remember(lastHeard, plan.reply.ifBlank { plan.speech })
        if (plan.actions.any { it.tool in SESSION_TOOLS }) session = true
        if (plan.reply.isNotBlank()) status.text = plan.reply
        if (plan.actions.isEmpty() || plan.actions.all { it.tool == "ask_user" }) {
            say(plan.speech) { next() }; return
        }
        if (plan.confirmationRequired) {
            say(plan.speech, then = null)
            AlertDialog.Builder(this)
                .setTitle("Kar doon?")
                .setMessage(plan.reply + "\n\n" + plan.describe())
                .setPositiveButton("Haan") { _, _ -> run(plan, announce = false) }
                .setNegativeButton("Rehne do") { _, _ -> next() }
                .setCancelable(false)
                .show()
        } else run(plan, announce = true)
    }

    private fun run(plan: Plan, announce: Boolean) {
        // Gaana chalana/agla ho to focus chhod do, taaki player khul ke baje
        if (plan.actions.any { it.tool == "play_music" || it.tool == "open_url" ||
                (it.tool == "media_control" && it.params.optString("action") in PLAY_ACTS) }) releaseFocus()
        val first = if (announce) plan.speech.trim() else ""
        working = true
        if (first.isNotBlank()) say(first, then = null)
        Thread {
            val extra = Brain.execute(this, executor, plan,
                log = { w, m -> runOnUiThread { if (w == "Maira") status.text = m else heard.text = m.trim() } },
                hideSelf = { })
            runOnUiThread {
                working = false
                when {
                    extra.isNotBlank() -> say(extra, queue = true) { next() }
                    pendingSpeech != null || (ttsReady && tts?.isSpeaking == true) -> afterSpeech = { next() }
                    else -> next()
                }
            }
        }.start()
    }

    // ---------- TTS ----------

    override fun onInit(st: Int) {
        ttsReady = st == TextToSpeech.SUCCESS
        if (!ttsReady) return
        val t = tts ?: return
        val parts = Brain.lang(this).tts.split("-")
        t.setLanguage(Locale(parts[0], parts[1]))
        t.setPitch(1.12f)
        t.setSpeechRate(1.05f)
        val saved = Brain.prefs(this).getString("voice_" + Brain.lang(this).tts, null)
        try { t.voices?.firstOrNull { it.name == saved }?.let { t.setVoice(it) } } catch (_: Exception) {}
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                main.post {
                    if (tts?.isSpeaking != true) { stopBarge(); val cb = afterSpeech; afterSpeech = null; cb?.invoke() }
                }
            }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) {
                main.post { val cb = afterSpeech; afterSpeech = null; cb?.invoke() }
            }
        })
        pendingSpeech?.let { pendingSpeech = null; speakNow(it, false) }
    }

    private fun clean(text: String) =
        text.replace(Regex("[\\p{So}\\p{Cn}*_#`~|<>]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun say(text: String, queue: Boolean = false, then: (() -> Unit)?) {
        if (then != null) afterSpeech = then
        if (text.isBlank()) { if (then != null) { afterSpeech = null; then() }; return }
        if (!ttsReady) { pendingSpeech = text; return }
        speakNow(text, queue)
    }

    private fun speakNow(text: String, queue: Boolean) {
        tts?.speak(clean(text), if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "q" + System.nanoTime())
        startBarge()
    }

    // ---------- Beech mein tokna (barge-in) ----------

    private fun startBarge() {
        if (!live || closing || working || barge != null) return
        if (!Brain.prefs(this).getBoolean("bargein", true)) return
        if (afterSpeech == null) return   // sirf tab jab bolne ke baad sunna hi hai
        barge = BargeIn { main.post { onBarge() } }.also { it.start() }
    }

    private fun stopBarge() { barge?.stop(); barge = null }

    private fun onBarge() {
        if (closing || working) return
        barge = null
        afterSpeech = null
        try { tts?.stop() } catch (_: Exception) {}
        status.text = "Haan, bolo…"
        main.postDelayed({ if (!closing) listen() }, 120)
    }
}


/**
 * Maira bol rahi ho tab mic pe nazar: user zor se bole to Maira chup.
 * Pehle ~300 ms mein speaker ki goonj (echo) ka level naapta hai, phir usse kaafi tez awaaz 200 ms tak mile to tokna maana.
 * VOICE_COMMUNICATION + echo canceller, taaki Maira ki apni awaaz se na ruke.
 */
class BargeIn(private val onHit: () -> Unit) {
    @Volatile private var run = true
    private var th: Thread? = null

    fun start() {
        th = Thread {
            var rec: android.media.AudioRecord? = null
            var aec: android.media.audiofx.AcousticEchoCanceler? = null
            try {
                val sr = 16000
                val min = android.media.AudioRecord.getMinBufferSize(sr, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                rec = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096) * 2)
                if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) return@Thread
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable())
                    aec = android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
                rec.startRecording()
                val buf = ShortArray(800)   // 50 ms
                var frames = 0
                var base = 0.0
                var hits = 0
                while (run) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sq = 0.0
                    for (i in 0 until n) sq += buf[i].toDouble() * buf[i]
                    val rms = Math.sqrt(sq / n)
                    frames++
                    if (frames <= 6) { base = maxOf(base, rms); continue }   // echo ka level
                    if (rms > maxOf(base * 3.0, 1100.0)) hits++ else hits = 0
                    if (hits >= 4) { run = false; onHit(); break }
                }
            } catch (_: Exception) {
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release(); aec?.release()
            }
        }.also { it.start() }
    }

    fun stop() {
        run = false
        th?.let { if (it != Thread.currentThread()) try { it.join(300) } catch (_: Exception) {} }
        th = null
    }
}
