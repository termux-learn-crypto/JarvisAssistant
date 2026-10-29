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
    private val languages = listOf("hi-IN" to "हिंदी", "mr-IN" to "मराठी", "en-IN" to "English")
    private var langIndex: Int
        get() = prefs.getInt("lang", 0)
        set(v) { prefs.edit().putInt("lang", v).apply() }

    private val systemPromptTemplate by lazy {
        assets.open("system_prompt.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = ActionExecutor(this)
        buildUi()
        tts = TextToSpeech(this, this)
        askPermissions()
        log("Jarvis", "Namaste Vilas! Mic dabao ya command type karo.")
        if (apiKey().isBlank()) showSettings()
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
            text = "JARVIS"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#4FC3F7"))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        langBtn = smallButton(languages[langIndex].second) {
            langIndex = (langIndex + 1) % languages.size
            langBtn.text = languages[langIndex].second
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
    private fun model() = (prefs.getString("model", "") ?: "").ifBlank { DEFAULT_MODEL }

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
            hint = "Model (default $DEFAULT_MODEL)"
            setText(prefs.getString("model", ""))
        }
        box.addView(info)
        box.addView(keyField)
        box.addView(modelField)
        AlertDialog.Builder(this)
            .setTitle("Jarvis Settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("api_key", keyField.text.toString().trim())
                    .putString("model", modelField.text.toString().trim())
                    .apply()
                log("Jarvis", "Settings save ho gayi.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Permissions ----------------

    private fun askPermissions() {
        val perms = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    // ---------------- Voice ----------------

    private fun startListening() {
        val lang = languages[langIndex].first
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye…")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            log("Jarvis", "Is phone pe voice input nahi mila. Google app install/update karo.")
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

    private fun applyTtsLanguage() {
        val (code, _) = languages[langIndex]
        val parts = code.split("-")
        tts?.setLanguage(Locale(parts[0], parts[1]))
    }

    private fun speak(text: String) {
        if (ttsReady && text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    // ---------------- Brain ----------------

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isNotEmpty()) {
            input.setText("")
            handleCommand(t)
        }
    }

    private fun buildSystemPrompt(): String {
        val now = Date()
        val dt = SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.ENGLISH).format(now)
        return systemPromptTemplate
            .replace("{{CURRENT_DATETIME}}", dt)
            .replace("{{TIMEZONE}}", TimeZone.getDefault().id)
    }

    private fun handleCommand(text: String) {
        if (busy) return
        val key = apiKey()
        if (key.isBlank()) {
            log("Jarvis", "Pehle settings mein Gemini API key daalo.")
            showSettings()
            return
        }
        log("Aap", text)
        busy = true
        log("Jarvis", "Soch raha hoon…")
        val model = model()
        val prompt = buildSystemPrompt()
        Thread {
            val result = try {
                Result.success(Plan.parse(GeminiClient(key, model).generate(prompt, text)))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                busy = false
                result.fold(
                    onSuccess = { onPlan(it) },
                    onFailure = { log("Jarvis", "Error: ${it.message}") }
                )
            }
        }.start()
    }

    private fun onPlan(plan: Plan) {
        if (plan.confirmationRequired) {
            log("Jarvis", plan.reply)
            speak(plan.reply)
            AlertDialog.Builder(this)
                .setTitle("Confirm karein?")
                .setMessage(plan.reply + "\n\n" + plan.describe())
                .setPositiveButton("Haan") { _, _ -> runActions(plan, announce = false) }
                .setNegativeButton("Nahi") { _, _ -> log("Jarvis", "Theek hai, cancel kar diya.") }
                .setCancelable(false)
                .show()
        } else {
            runActions(plan, announce = true)
        }
    }

    private fun runActions(plan: Plan, announce: Boolean) {
        val spoken = StringBuilder()
        if (announce && plan.reply.isNotBlank()) {
            log("Jarvis", plan.reply)
            spoken.append(plan.reply)
        }
        for (a in plan.actions) {
            val status = executor.execute(a)
            log("  ⚙", status)
            // Battery jaisi info aur ask_user ka sawaal bolkar bhi batao
            if (a.tool == "read_battery" || a.tool == "ask_user") {
                if (!(a.tool == "ask_user" && status == plan.reply)) spoken.append(" ").append(status)
            }
        }
        speak(spoken.toString().trim())
    }

    companion object {
        private const val REQ_SPEECH = 101
        private const val REQ_PERMS = 102
        private const val DEFAULT_MODEL = "gemini-2.5-flash"
    }
}
