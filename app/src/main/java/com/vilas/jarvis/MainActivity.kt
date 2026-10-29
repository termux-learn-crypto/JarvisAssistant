package com.vilas.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var langBtn: Button
    private lateinit var executor: ActionExecutor
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false

    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }
    private val languages = Brain.languages
    private var langIndex: Int
        get() = prefs.getInt("lang2", 0).coerceIn(0, languages.size - 1)
        set(v) { prefs.edit().putInt("lang2", v).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = ActionExecutor(this)
        buildUi()
        tts = TextToSpeech(this, this)
        askPermissions()
        log("Maira", "Hey Vilas! Main Maira. Mic dabao aur bindaas bolo.")
        if (apiKey().isBlank()) showSettings()
        QuickMic.refreshNotification(this)
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1117"))
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "MAIRA"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#4FC3F7"))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        langBtn = smallButton(languages[langIndex].label) {
            langIndex = (langIndex + 1) % languages.size
            langBtn.text = languages[langIndex].label
            applyTtsLanguage()
        }
        val settingsBtn = smallButton("⚙") { showSettings() }
        header.addView(title)
        header.addView(langBtn)
        header.addView(settingsBtn)

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#E6E6E6"))
            textSize = 16f
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) }
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        input = EditText(this).apply {
            hint = "Command likho…"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
            }
        }
        inputRow.addView(input)
        inputRow.addView(smallButton("➤") { sendTyped() })

        val mic = Button(this).apply {
            text = "🎤  Bolo"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(64)).apply { topMargin = dp(10) }
            setOnClickListener { startListening() }
        }

        root.addView(header)
        root.addView(scroll)
        root.addView(inputRow)
        root.addView(mic)
        setContentView(root)
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun log(who: String, msg: String) {
        logView.append("$who: $msg\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------------- Settings ----------------

    private fun apiKey() = prefs.getString("api_key", "") ?: ""
    private fun model() = (prefs.getString("model", "") ?: "").ifBlank { Brain.DEFAULT_MODEL }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val info = TextView(this).apply {
            text = "Gemini API key aistudio.google.com/apikey se free milti hai. Key sirf is phone pe save hogi."
        }
        val keyField = EditText(this).apply {
            hint = "Gemini API key"
            setText(apiKey())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val modelField = EditText(this).apply {
            hint = "Model (default ${Brain.DEFAULT_MODEL})"
            setText(prefs.getString("model", ""))
        }
        val voiceBtn = Button(this).apply {
            isAllCaps = false
            text = "Awaaz badlo (${voiceIndexLabel()})"
            setOnClickListener {
                nextVoice()
                text = "Awaaz badlo (${voiceIndexLabel()})"
            }
        }
        box.addView(info)
        box.addView(keyField)
        box.addView(modelField)
        box.addView(voiceBtn)
        val skipBox = android.widget.CheckBox(this).apply {
            text = "YouTube ads khud skip karo"
            isChecked = prefs.getBoolean("autoskip", true)
        }
        val micBox = android.widget.CheckBox(this).apply {
            text = "Notification mein quick mic dikhao"
            isChecked = prefs.getBoolean("quickmic", true)
        }
        val accBtn = Button(this).apply {
            isAllCaps = false
            text = "Screen control on karo (Accessibility)"
            setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        box.addView(skipBox)
        box.addView(micBox)
        box.addView(accBtn)
        AlertDialog.Builder(this)
            .setTitle("Maira Settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("api_key", keyField.text.toString().trim())
                    .putString("model", modelField.text.toString().trim())
                    .putBoolean("autoskip", skipBox.isChecked)
                    .putBoolean("quickmic", micBox.isChecked)
                    .apply()
                QuickMic.refreshNotification(this)
                log("Maira", "Settings save ho gayi.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Permissions ----------------

    private fun askPermissions() {
        val perms = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    // ---------------- Voice ----------------

    private fun startListening() {
        val lang = languages[langIndex].rec
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye…")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            log("Maira", "Is phone pe voice input nahi mila. Google app install/update karo.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) handleCommand(text)
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) applyTtsLanguage()
    }

    private fun ttsLocale(): Locale {
        val parts = languages[langIndex].tts.split("-")
        return Locale(parts[0], parts[1])
    }

    /** Is language ki installed voices, achhi quality pehle. */
    private fun voicesForLang(): List<Voice> {
        val loc = ttsLocale()
        val all = try { tts?.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        return all.filter {
            it.locale.language == loc.language &&
                !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
    }

    private fun voicePrefKey() = "voice_" + languages[langIndex].tts

    private fun applyTtsLanguage() {
        val t = tts ?: return
        t.setLanguage(ttsLocale())
        // Dost jaisi awaaz: thodi upar pitch, normal se thodi tez
        t.setPitch(1.12f)
        t.setSpeechRate(1.05f)
        val saved = prefs.getString(voicePrefKey(), null)
        val voices = voicesForLang()
        val v = voices.firstOrNull { it.name == saved }
            ?: voices.firstOrNull { it.name.contains("female", true) }
            ?: voices.firstOrNull()
        if (v != null) t.setVoice(v)
    }

    private fun voiceIndexLabel(): String {
        val voices = voicesForLang()
        if (voices.isEmpty()) return "default"
        val cur = tts?.voice?.name
        val i = voices.indexOfFirst { it.name == cur }
        return "${if (i >= 0) i + 1 else 1}/${voices.size}"
    }

    private fun nextVoice() {
        val voices = voicesForLang()
        if (voices.isEmpty()) return
        val cur = tts?.voice?.name
        val i = voices.indexOfFirst { it.name == cur }
        val v = voices[(i + 1) % voices.size]
        tts?.setVoice(v)
        prefs.edit().putString(voicePrefKey(), v.name).apply()
        speak(if (isIndicTts()) "हे! मैं मायरा। ये आवाज़ कैसी लगी?" else "Hey! I am Maira. How does this voice sound?")
    }

    private fun speak(text: String) {
        if (ttsReady && text.isNotBlank()) tts?.speak(clean(text), TextToSpeech.QUEUE_FLUSH, null, "maira")
    }

    /** Emoji aur symbols hatao taki TTS ajeeb na padhe. */
    private fun clean(text: String): String =
        text.replace(Regex("[\\p{So}\\p{Cn}*_#`~|<>]"), " ").replace(Regex("\\s+"), " ").trim()

    // ---------------- Brain ----------------

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isNotEmpty()) {
            input.setText("")
            handleCommand(t)
        }
    }

    private fun handleCommand(text: String) {
        if (busy) return
        val key = apiKey()
        if (key.isBlank()) {
            log("Maira", "Pehle settings mein Gemini API key daalo.")
            showSettings()
            return
        }
        log("Aap", text)
        busy = true
        log("Maira", "Ek sec…")
        Thread {
            val result = try {
                Result.success(Brain.plan(this, text))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                busy = false
                result.fold(
                    onSuccess = { onPlan(it) },
                    onFailure = { log("Maira", "Error: ${it.message}") }
                )
            }
        }.start()
    }

    private fun onPlan(plan: Plan) {
        if (plan.confirmationRequired) {
            log("Maira", plan.reply)
            speak(plan.speech)
            AlertDialog.Builder(this)
                .setTitle("Kar doon?")
                .setMessage(plan.reply + "\n\n" + plan.describe())
                .setPositiveButton("Haan, kar do") { _, _ -> runActions(plan, announce = false) }
                .setNegativeButton("Rehne do") { _, _ -> log("Maira", "Ok, nahi karti.") }
                .setCancelable(false)
                .show()
        } else {
            runActions(plan, announce = true)
        }
    }

    private fun runActions(plan: Plan, announce: Boolean) {
        if (announce && plan.reply.isNotBlank()) log("Maira", plan.reply)
        val speakFirst = if (announce) plan.speech.trim() else ""
        val leaves = plan.actions.any { it.tool == "screen_action" || it.tool == "read_screen" || it.tool == "open_app" }
        // App badalne se pehle bol do, warna awaaz kat sakti hai
        if (leaves && speakFirst.isNotBlank()) speak(speakFirst)
        Thread {
            val extra = Brain.execute(this, executor, plan,
                log = { w, m -> runOnUiThread { log(w, m) } },
                hideSelf = { moveTaskToBack(true) })
            runOnUiThread {
                speak(if (leaves) extra else (speakFirst + " " + extra).trim())
            }
        }.start()
    }

    private fun isIndicTts() = languages[langIndex].tts != "en-IN"

    companion object {
        private const val REQ_SPEECH = 101
        private const val REQ_PERMS = 102
    }
}
