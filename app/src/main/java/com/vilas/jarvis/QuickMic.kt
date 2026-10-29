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
 * Chhota transparent mic jo kisi bhi app (jaise YouTube) ke upar khulta hai,
 * taaki peeche wali app band na ho.
 */
class QuickMicActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var status: TextView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var finishAfterSpeech = false
    private val main = Handler(Looper.getMainLooper())
    private lateinit var executor: ActionExecutor

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = ActionExecutor(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#33000000"))
            setOnClickListener { finish() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0E1117")); cornerRadius = dp(18).toFloat()
            }
            isClickable = true
        }
        val title = TextView(this).apply {
            text = "MAIRA"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#4FC3F7"))
        }
        status = TextView(this).apply {
            text = "Sun rahi hoon…"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(title)
        card.addView(status)
        root.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(24))
        })
        setContentView(root)

        tts = TextToSpeech(this, this)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5)
        } else listen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
        else { status.text = "Mic permission chahiye."; main.postDelayed({ finish() }, 1500) }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Voice recognition nahi mila. Google app update karo."
            return
        }
        val lang = Brain.lang(this).rec
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { status.text = "Bolo…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "Samajh rahi hoon…" }
                override fun onEvent(t: Int, b: Bundle?) {}
                override fun onPartialResults(b: Bundle?) {
                    b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { status.text = it }
                }
                override fun onError(error: Int) {
                    status.text = "Kuch sunai nahi diya. Phir se tap karo."
                    main.postDelayed({ finish() }, 1500)
                }
                override fun onResults(b: Bundle?) {
                    val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) onError(0) else handle(text)
                }
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        recognizer?.startListening(i)
    }

    private fun handle(text: String) {
        status.text = "\"$text\"\nEk sec…"
        Thread {
            val r = try { Result.success(Brain.plan(this, text)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                r.fold(onSuccess = { onPlan(it) }, onFailure = {
                    status.text = "Error: ${it.message}"
                    main.postDelayed({ finish() }, 3000)
                })
            }
        }.start()
    }

    private fun onPlan(plan: Plan) {
        status.text = plan.reply
        if (plan.actions.isEmpty() || plan.actions.all { it.tool == "ask_user" }) {
            say(plan.speech, finishAfter = true); return
        }
        if (plan.confirmationRequired) {
            say(plan.speech, finishAfter = false)
            AlertDialog.Builder(this)
                .setTitle("Kar doon?")
                .setMessage(plan.reply + "\n\n" + plan.describe())
                .setPositiveButton("Haan") { _, _ -> run(plan, announce = false) }
                .setNegativeButton("Rehne do") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        } else run(plan, announce = true)
    }

    private fun run(plan: Plan, announce: Boolean) {
        val first = if (announce) plan.speech.trim() else ""
        if (first.isNotBlank()) say(first, finishAfter = false)
        Thread {
            val extra = Brain.execute(this, executor, plan,
                log = { w, m -> if (w == "Maira") runOnUiThread { status.text = m } },
                hideSelf = { moveTaskToBack(true) })
            runOnUiThread {
                if (extra.isNotBlank()) say(extra, finishAfter = true, queue = true)
                else if (pendingSpeech != null || (ttsReady && tts?.isSpeaking == true)) finishAfterSpeech = true
                else main.postDelayed({ finish() }, 600)
            }
        }.start()
    }

    // ---------- TTS ----------

    override fun onInit(st: Int) {
        ttsReady = st == TextToSpeech.SUCCESS
        if (!ttsReady) return
        val t = tts ?: return
        val parts = Brain.lang(this).tts.split("-")
        val loc = Locale(parts[0], parts[1])
        t.setLanguage(loc)
        t.setPitch(1.12f)
        t.setSpeechRate(1.05f)
        val saved = Brain.prefs(this).getString("voice_" + Brain.lang(this).tts, null)
        try { t.voices?.firstOrNull { it.name == saved }?.let { t.setVoice(it) } } catch (_: Exception) {}
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (finishAfterSpeech && tts?.isSpeaking != true) main.postDelayed({ finish() }, 300)
            }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) {}
        })
        pendingSpeech?.let { pendingSpeech = null; speakNow(it, false) }
    }

    private fun clean(text: String) =
        text.replace(Regex("[\\p{So}\\p{Cn}*_#`~|<>]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun say(text: String, finishAfter: Boolean, queue: Boolean = false) {
        if (finishAfter) finishAfterSpeech = true
        if (text.isBlank()) { if (finishAfter) main.postDelayed({ finish() }, 800); return }
        if (!ttsReady) { pendingSpeech = text; return }
        speakNow(text, queue)
    }

    private fun speakNow(text: String, queue: Boolean) {
        tts?.speak(clean(text), if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "q" + System.nanoTime())
    }
}
