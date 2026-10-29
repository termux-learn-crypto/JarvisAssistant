package com.vilas.jarvis

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Offline dimaag: bina API key ke roz ke commands samajhta hai (Hinglish, Hindi, Marathi, English).
 * Samajh na aaye to null deta hai (tab Gemini, agar key ho).
 */
object LocalBrain {

    private val SENSITIVE = setOf("send_sms", "make_call", "screen_action")

    private class Say(val roman: String, val dev: String, val en: String, val mr: String? = null)

    private fun pick(ctx: Context, s: Say): Pair<String, String> = when (Brain.lang(ctx).mode) {
        "Hindi (Devanagari)" -> s.dev to s.dev
        "Marathi" -> (s.mr ?: s.dev) to (s.mr ?: s.dev)
        "English" -> s.en to s.en
        else -> s.roman to s.dev
    }

    private fun plan(ctx: Context, s: Say, vararg actions: Pair<String, JSONObject>): Plan {
        val (reply, speech) = pick(ctx, s)
        val list = actions.map { Action(it.first, it.second) }
        return Plan(list, list.any { it.tool in SENSITIVE }, reply, speech)
    }

    private fun p(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { put(it.first, it.second) } }

    private fun has(t: String, vararg words: String) = words.any { w ->
        if (w.any { it.code > 127 }) t.contains(w) else Regex("(^|[^a-z])" + Regex.escape(w) + "($|[^a-z])").containsMatchIn(t)
    }

    private fun strip(t: String, words: List<String>): String {
        var s = " $t "
        for (w in words.sortedByDescending { it.length }) {
            s = if (w.any { it.code > 127 }) s.replace(w, " ")
            else s.replace(Regex("(?<=[^a-z])" + Regex.escape(w) + "(?=[^a-z])"), " ")
        }
        return s.replace(Regex("[,.!?।]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private val FILLER = listOf(
        "maira", "mayra", "hey", "ok", "please", "plz", "zara", "jara", "yaar", "yar", "mujhe", "mere", "liye", "mera",
        "मायरा", "ज़रा", "जरा", "प्लीज़", "प्लीज", "मुझे", "मेरे", "लिए", "यार"
    )

    fun parse(ctx: Context, raw: String): Plan? {
        val t = " " + devDigits(raw).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim() + " "
        if (t.isBlank()) return null

        // ---- WiFi / Bluetooth ----
        if (has(t, "wifi", "wi-fi", "वाईफाई", "वायफाय", "वाई-फाई")) {
            val on = !has(t, "off", "band", "बंद")
            return plan(ctx, Say("WiFi panel khol rahi hoon.", "वाईफाई पैनल खोल रही हूं।", "Opening WiFi panel.", "वायफाय पॅनल उघडते."),
                "toggle_wifi" to p("state" to if (on) "on" else "off"))
        }
        if (has(t, "bluetooth", "ब्लूटूथ")) {
            val on = !has(t, "off", "band", "बंद")
            return plan(ctx, Say("Bluetooth settings khol rahi hoon.", "ब्लूटूथ सेटिंग्स खोल रही हूं।", "Opening Bluetooth settings.", "ब्लूटूथ सेटिंग्स उघडते."),
                "toggle_bluetooth" to p("state" to if (on) "on" else "off"))
        }

        // ---- Battery ----
        if (has(t, "battery", "बैटरी", "बॅटरी", "charge kitna", "charging")) {
            return plan(ctx, Say("", "", ""), "read_battery" to JSONObject())
        }

        // ---- Volume ----
        if (has(t, "volume", "वॉल्यूम", "आवाज़", "आवाज", "awaaz", "awaz", "aawaz") && !has(t, "badlo", "बदलो")) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val num = Regex("(\\d{1,3})").find(t)?.groupValues?.get(1)?.toInt()
            val level = when {
                num != null -> num.coerceIn(0, 100)
                has(t, "mute", "म्यूट", "zero", "शून्य") -> 0
                has(t, "full", "max", "पूरा", "फुल", "poora", "pura") -> 100
                has(t, "kam", "kum", "down", "low", "कम", "धीरे", "dheere", "dhire") -> (cur - 20).coerceAtLeast(0)
                has(t, "badha", "badhao", "tez", "up", "zyada", "jyada", "बढ़ाओ", "बढ़ा", "तेज", "ज़्यादा", "वाढव") -> (cur + 20).coerceAtMost(100)
                else -> return null
            }
            return plan(ctx, Say("Volume $level% kar diya.", "वॉल्यूम $level परसेंट कर दिया।", "Volume set to $level percent.", "व्हॉल्यूम $level टक्के केला."),
                "set_volume" to p("level" to level, "type" to "media"))
        }

        // ---- Time / date ----
        if (has(t, "kitne baje", "kitna baja", "time kya", "time batao", "what time", "टाइम", "कितने बजे", "समय", "किती वाजले", "kiti vajle", "samay")) {
            val c = Calendar.getInstance()
            val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
            val h12 = if (h % 12 == 0) 12 else h % 12
            val mm = String.format(Locale.ENGLISH, "%02d", m)
            val part = when (h) { in 4..11 -> "subah"; in 12..15 -> "dopahar"; in 16..19 -> "shaam"; else -> "raat" }
            val partDev = when (h) { in 4..11 -> "सुबह"; in 12..15 -> "दोपहर"; in 16..19 -> "शाम"; else -> "रात" }
            return plan(ctx, Say("Abhi $part ke $h12:$mm baje hain.", "अभी $partDev के $h12 बजकर $m मिनट हुए हैं।",
                "It's $h12:$mm ${if (h < 12) "AM" else "PM"}.", "आता $h12 वाजून $m मिनिटे झाली आहेत."))
        }
        if (has(t, "date", "tarikh", "tareekh", "तारीख", "डेट", "तारीख़", "आज कौन सा दिन", "aaj kaun sa din")) {
            val c = Calendar.getInstance()
            val d = java.text.SimpleDateFormat("d MMMM yyyy, EEEE", Locale.ENGLISH).format(c.time)
            val dHi = java.text.SimpleDateFormat("d MMMM yyyy, EEEE", Locale("hi", "IN")).format(c.time)
            return plan(ctx, Say("Aaj $d hai.", "आज $dHi है।", "Today is $d.", "आज $dHi आहे."))
        }

        // ---- Read screen / notifications ----
        if (has(t, "padho", "padh", "पढ़ो", "पढ़", "पढो", "वाच", "read", "sunao") &&
            has(t, "message", "messages", "msg", "notification", "notifications", "मैसेज", "संदेश", "नोटिफिकेशन", "whatsapp", "व्हाट्सएप", "व्हॉट्सॲप")) {
            val app = when {
                has(t, "whatsapp", "व्हाट्सएप", "व्हॉट्सॲप") -> "WhatsApp"
                has(t, "instagram", "इंस्टाग्राम") -> "Instagram"
                has(t, "telegram", "टेलीग्राम") -> "Telegram"
                has(t, "sms", "एसएमएस") -> "Messages"
                else -> ""
            }
            return plan(ctx, Say("Ruko, dekhti hoon kya aaya hai.", "रुको, देखती हूं क्या आया है।", "Let me check your messages.", "थांब, बघते काय आलंय."),
                "read_notifications" to p("app_name" to app))
        }
        if (has(t, "screen", "स्क्रीन", "page", "पेज") && has(t, "padho", "padh", "पढ़ो", "पढ़", "पढो", "read", "वाच", "kya likha", "क्या लिखा", "summary")) {
            return plan(ctx, Say("Dekhti hoon screen pe kya hai.", "देखती हूं स्क्रीन पे क्या है।", "Reading the screen.", "स्क्रीनवर काय आहे बघते."),
                "read_screen" to p("question" to raw))
        }

        // ---- Navigation ----
        if (has(t, "home screen", "home jao", "go home", "होम")) return plan(ctx, Say("", "", ""), "screen_action" to p("action" to "home", "app_name" to ""))
        if (has(t, "back jao", "peeche jao", "piche jao", "go back", "वापस जाओ", "पीछे जाओ", "मागे जा")) return plan(ctx, Say("", "", ""), "screen_action" to p("action" to "go_back", "app_name" to ""))
        if (has(t, "skip ad", "ad skip", "ads skip", "एड स्किप", "ऐड स्किप")) return plan(ctx, Say("Skip daba rahi hoon.", "स्किप दबा रही हूं।", "Skipping the ad."), "screen_action" to p("action" to "click", "text" to "Skip", "app_name" to "YouTube"))

        // ---- Alarm ----
        if (has(t, "alarm", "alarum", "अलार्म", "अलार्म", "उठा", "utha dena", "jaga dena", "जगा देना")) {
            val time = parseTime(t) ?: return askPlan(ctx, Say("Alarm kitne baje lagaun?", "अलार्म कितने बजे लगाऊं?", "What time should I set the alarm?", "अलार्म किती वाजता लावू?"))
            val repeat = if (has(t, "roz", "daily", "har din", "रोज़", "रोज", "हर दिन", "दररोज")) "daily" else "once"
            val sp = speakTime(time)
            return plan(ctx, Say("Done, $time ka alarm laga diya.", "हो गया, $sp का अलार्म लगा दिया।", "Alarm set for $time.", "झालं, $sp चा अलार्म लावला."),
                "set_alarm" to p("time" to time, "repeat" to repeat))
        }

        // ---- Reminder ----
        if (has(t, "yaad dila", "yaad dilana", "remind", "reminder", "याद दिला", "रिमाइंड", "रिमाइंडर", "आठवण")) {
            val time = parseTime(t) ?: return askPlan(ctx, Say("Kitne baje yaad dilaun?", "कितने बजे याद दिलाऊं?", "What time should I remind you?", "किती वाजता आठवण करून देऊ?"))
            val date = dateFor(t, time)
            var what = strip(t, FILLER + TIME_WORDS + listOf("yaad", "dilana", "dila", "dena", "do", "remind", "reminder", "me", "set", "karo", "kar",
                "ki", "ke", "ko", "ka", "baje", "bje", "pe", "par", "याद", "दिलाना", "दिला", "देना", "दो", "रिमाइंड", "रिमाइंडर", "करो", "की", "के", "को", "बजे", "पे", "आठवण", "करून", "दे"))
            what = what.replace(Regex("\\d{1,2}([:.]\\d{2})?"), " ").replace(Regex("\\s+"), " ").trim()
            if (what.isBlank()) what = "Reminder"
            return plan(ctx, Say("Theek hai, $time pe yaad dila dungi: $what", "ठीक है, ${speakTime(time)} पे याद दिला दूंगी।", "Okay, I'll remind you at $time: $what", "ठीक आहे, ${speakTime(time)} ला आठवण करून देईन."),
                "create_reminder" to p("text" to what, "date" to date, "time" to time))
        }

        // ---- Call ----
        if (has(t, "call", "kol", "कॉल", "फोन लगाओ", "phone lagao", "phone karo", "फोन करो", "फोन लाव")) {
            val num = Regex("\\+?\\d[\\d ]{7,14}\\d").find(t)?.value?.replace(" ", "")
            val name = strip(t, FILLER + listOf("call", "kol", "karo", "kar", "do", "lagao", "laga", "phone", "ko", "la", "kara", "lav",
                "कॉल", "करो", "कर", "दो", "लगाओ", "फोन", "को", "ला", "लाव", "करा"))
            if (num == null && name.isBlank()) return askPlan(ctx, Say("Kisko call karun?", "किसको कॉल करूं?", "Whom should I call?", "कोणाला कॉल करू?"))
            val prm = if (num != null) p("phone_number" to num) else p("contact_name" to name)
            val who = num ?: name
            return plan(ctx, Say("$who ko call laga doon?", "$who को कॉल लगा दूं?", "Call $who?", "$who ला कॉल लावू?"), "make_call" to prm)
        }

        // ---- SMS: "<naam> ko message bhejo ki <text>" ----
        Regex("^\\s*(?:maira\\s+)?(.+?)\\s+(?:ko|को|la|ला)\\s+(?:message|msg|sms|मैसेज|मेसेज|संदेश)\\s*(?:bhejo|bhej do|karo|kar do|भेजो|भेज दो|करो|पाठव)?\\s*(?:ki|ke|that|कि|की|के)?\\s+(.+)$")
            .find(t)?.let { m ->
                val name = strip(m.groupValues[1], FILLER)
                val msg = m.groupValues[2].trim().replace(Regex("\\s+(bhejo|bhej do|भेजो|भेज दो)\\s*$"), "")
                if (name.isNotBlank() && msg.isNotBlank()) {
                    return plan(ctx, Say("$name ko bhejun: \"$msg\"?", "$name को ये मैसेज भेज दूं?", "Send to $name: \"$msg\"?", "$name ला हा मेसेज पाठवू?"),
                        "send_sms" to p("contact_name" to name, "message" to msg))
                }
            }

        // ---- Keyboard ----
        val typeM = Regex("(?:^|\\s)(?:likho|likh do|type karo|type|लिखो|लिख दो|टाइप करो|लिहा)\\s+(.+)$").find(t)
            ?: Regex("^\\s*(.+?)\\s+(?:likho|likh do|type karo|लिखो|लिख दो|टाइप करो|लिहा)\\s*$").find(t)
        if (typeM != null && !has(t, "message bhejo", "मैसेज भेजो")) {
            val txt = typeM.groupValues[1].trim()
            if (txt.isNotBlank()) return plan(ctx, Say("", "", ""), "keyboard" to p("action" to "type", "text" to txt))
        }
        if (has(t, "sab mitao", "sab delete", "clear karo", "clear", "saaf karo", "सब मिटाओ", "सब डिलीट", "क्लियर")) return plan(ctx, Say("", "", ""), "keyboard" to p("action" to "clear"))
        if (has(t, "shabd mitao", "word delete", "word mitao", "शब्द मिटाओ")) return plan(ctx, Say("", "", ""), "keyboard" to p("action" to "delete_word", "count" to (num(t) ?: 1)))
        if (has(t, "mitao", "backspace", "delete", "मिटाओ", "डिलीट", "बैकस्पेस", "पुसा")) return plan(ctx, Say("", "", ""), "keyboard" to p("action" to "backspace", "count" to (num(t) ?: 1)))
        if (has(t, "select all", "sab select", "सब सेलेक्ट", "सिलेक्ट ऑल")) return plan(ctx, Say("", "", ""), "keyboard" to p("action" to "select_all"))
        if (has(t, "copy", "कॉपी")) return plan(ctx, Say("", "", ""), "keyboard" to p("action" to "copy"))
        if (has(t, "paste", "पेस्ट")) return plan(ctx, Say("", "", ""), "keyboard" to p("action" to "paste"))
        if (has(t, "cut karo", "कट करो")) return plan(ctx, Say("", "", ""), "keyboard" to p("action" to "cut"))
        if (has(t, "enter", "एंटर")) return plan(ctx, Say("", "", ""), "keyboard" to p("action" to "enter"))
        if (has(t, "space", "स्पेस")) return plan(ctx, Say("", "", ""), "keyboard" to p("action" to "space"))
        if (has(t, "agla box", "next box", "next field", "अगला बॉक्स")) return plan(ctx, Say("", "", ""), "keyboard" to p("action" to "next_field"))

        // ---- Number labels ----
        if (has(t, "number dikhao", "numbers dikhao", "label dikhao", "labels dikhao", "show numbers", "नंबर दिखाओ", "नंबर दाखव"))
            return plan(ctx, Say("", "", ""), "labels" to p("action" to "show"))
        if (has(t, "number hatao", "numbers hatao", "label hatao", "hide numbers", "नंबर हटाओ"))
            return plan(ctx, Say("", "", ""), "labels" to p("action" to "hide"))
        val tapWords = arrayOf("dabao", "daba do", "dabaao", "click", "tap", "press", "दबाओ", "दबा दो", "क्लिक", "टैप", "दाब")
        val longP = has(t, "long press", "der tak", "देर तक", "लॉन्ग प्रेस")
        val n = num(t)
        if (n != null && (tapWords.any { has(t, it) } || longP || t.trim() == n.toString()) && !has(t, "volume", "alarm", "call"))
            return plan(ctx, Say("", "", ""), "labels" to p("action" to if (longP) "long_press" else "tap", "number" to n))

        // ---- Mouse ----
        if (has(t, "mouse", "cursor", "माउस", "कर्सर")) {
            if (has(t, "band", "hatao", "off", "hide", "बंद", "हटाओ")) return plan(ctx, Say("", "", ""), "mouse" to p("action" to "hide"))
            if (!dirOf(t).isNotEmpty() && !tapWords.any { has(t, it) }) return plan(ctx, Say("", "", ""), "mouse" to p("action" to "show"))
        }
        if (has(t, "double click", "double tap", "डबल क्लिक", "डबल टैप")) return plan(ctx, Say("", "", ""), "mouse" to p("action" to "double_tap"))
        if (longP) return plan(ctx, Say("", "", ""), "mouse" to p("action" to "long_press"))
        if (has(t, "beech mein", "center", "बीच में", "सेंटर")) return plan(ctx, Say("", "", ""), "mouse" to p("action" to "goto", "position" to "center"))
        val d = dirOf(t)
        if (d.isNotEmpty() && has(t, "swipe", "स्वाइप")) return plan(ctx, Say("", "", ""), "gesture" to p("action" to "swipe_$d"))
        if (d.isNotEmpty() && has(t, "scroll", "स्क्रॉल")) {
            // content neeche dekhna = ungli upar le jao
            val sw = when (d) { "down" -> "swipe_up"; "up" -> "swipe_down"; "left" -> "swipe_right"; else -> "swipe_left" }
            return plan(ctx, Say("", "", ""), "gesture" to p("action" to sw))
        }
        val playish = has(t, "chalao", "lagao", "bajao", "play", "sunao", "चलाओ", "लगाओ", "बजाओ", "सुनाओ", "लाव", "वाजव")
        if (d.isNotEmpty() && !playish && t.trim().split(" ").size <= 4) {
            val amt = when { has(t, "thoda", "thodasa", "थोड़ा", "जरा सा", "little") -> "small"; has(t, "zyada", "jyada", "bahut", "ज़्यादा", "ज्यादा", "बहुत", "more") -> "large"; else -> "medium" }
            return plan(ctx, Say("", "", ""), "mouse" to p("action" to "move", "direction" to d, "amount" to amt))
        }
        if (tapWords.any { has(t, it) }) {
            val label = strip(t, FILLER + tapWords.toList() + listOf("pe", "par", "ko", "karo", "kar", "do", "button", "mouse", "cursor", "पे", "पर", "को", "करो", "बटन", "माउस", "कर्सर"))
            if (label.isBlank()) return plan(ctx, Say("", "", ""), "mouse" to p("action" to "tap"))
            return plan(ctx, Say("$label daba doon?", "$label दबा दूं?", "Tap $label?"), "screen_action" to p("action" to "click", "text" to label, "app_name" to ""))
        }

        // ---- Media controls ----
        if (has(t, "next", "agla", "agle", "agli", "अगला", "अगले", "पुढचं", "पुढचा", "skip")) {
            return plan(ctx, Say("Lo, agla.", "लो, अगला।", "Next one.", "हे घे, पुढचं."), "media_control" to p("action" to "next"))
        }
        if (has(t, "previous", "pichla", "pichhla", "pichle", "पिछला", "पिछले", "मागचं", "मागचा", "peeche wala")) {
            return plan(ctx, Say("Pichla laga diya.", "पिछला लगा दिया।", "Previous one.", "मागचं लावलं."), "media_control" to p("action" to "previous"))
        }
        if (has(t, "pause", "roko", "rok do", "ruko", "ruk ja", "stop", "रोको", "रोक दो", "रुको", "पॉज", "थांबव", "थांब") ||
            (has(t, "band", "बंद") && has(t, "gaana", "gana", "song", "music", "video", "गाना", "गाणं", "म्यूजिक"))) {
            return plan(ctx, Say("Rok diya.", "रोक दिया।", "Paused.", "थांबवलं."), "media_control" to p("action" to "pause"))
        }
        if (has(t, "resume", "continue", "phir se chalao", "wapas chalao", "फिर से चलाओ", "दोबारा चलाओ")) {
            return plan(ctx, Say("Chala diya.", "चला दिया।", "Resumed.", "चालू केलं."), "media_control" to p("action" to "play"))
        }

        // ---- Search ----
        if (has(t, "search", "dhoondo", "dhundo", "सर्च", "ढूंढो", "शोध")) {
            val yt = has(t, "youtube", "यूट्यूब")
            val q = strip(t, FILLER + listOf("google", "youtube", "pe", "par", "per", "on", "search", "karo", "kar", "do", "dhoondo", "dhundo",
                "गूगल", "यूट्यूब", "पे", "पर", "सर्च", "करो", "ढूंढो", "शोध", "वर"))
            if (q.isNotBlank()) {
                val url = if (yt) "https://www.youtube.com/results?search_query=" + Uri.encode(q)
                          else "https://www.google.com/search?q=" + Uri.encode(q)
                return plan(ctx, Say("$q search kar rahi hoon.", "$q सर्च कर रही हूं।", "Searching $q.", "$q शोधते."), "open_url" to p("url" to url))
            }
        }

        // ---- Play music ----
        val playWords = listOf("play", "chalao", "chala do", "chala", "lagao", "laga do", "bajao", "baja do", "sunao", "suna do", "lav", "laav",
            "चलाओ", "चला दो", "लगाओ", "लगा दो", "बजाओ", "बजा दो", "सुनाओ", "प्ले", "लाव", "वाजव")
        if (playWords.any { has(t, it) }) {
            val q = strip(t, FILLER + playWords + listOf("youtube", "pe", "par", "per", "on", "gaana", "gana", "gaane", "gane", "song", "songs", "music",
                "koi", "ek", "karo", "kar", "do", "wala", "wale", "please", "ka", "ki", "ke", "का", "की", "के", "यूट्यूब", "पे", "पर", "गाना", "गाने", "गाणं", "गाणी", "सॉन्ग", "म्यूजिक",
                "कोई", "एक", "करो", "वाला", "वाले"))
            val prm = if (q.isBlank()) JSONObject() else p("song_name" to q)
            return plan(ctx, Say(if (q.isBlank()) "Lo, gaana chala rahi hoon!" else "Lo, $q chala rahi hoon!",
                if (q.isBlank()) "लो, गाना चला रही हूं!" else "लो, $q चला रही हूं!",
                if (q.isBlank()) "Playing some music!" else "Playing $q!", if (q.isBlank()) "हे घे, गाणं लावते!" else "हे घे, $q लावते!"),
                "play_music" to prm)
        }

        // ---- Open app ----
        if (has(t, "kholo", "khol do", "khol", "open", "start karo", "chalu karo", "खोलो", "खोल दो", "ओपन", "उघड", "उघडा", "चालू करो")) {
            val name = strip(t, FILLER + listOf("kholo", "khol", "do", "open", "karo", "kar", "start", "chalu", "app", "application", "ko",
                "खोलो", "खोल", "दो", "ओपन", "करो", "उघड", "उघडा", "चालू", "ऐप", "एप", "को"))
            if (name.isNotBlank()) return plan(ctx, Say("$name khol rahi hoon.", "$name खोल रही हूं।", "Opening $name.", "$name उघडते."), "open_app" to p("app_name" to name))
        }

        // ---- Chit-chat ----
        if (t.trim().split(" ").size <= 4 && has(t, "hi", "hello", "hey", "namaste", "हाय", "हेलो", "नमस्ते", "kaise ho", "kaisi ho", "कैसी हो", "कसा आहेस", "कशी आहेस")) {
            return plan(ctx, Say("Main badhiya! Bolo kya karna hai?", "मैं बढ़िया! बोलो क्या करना है?", "I'm great! What can I do?", "मी मस्त! बोल काय करायचं?"))
        }
        return null
    }

    private fun num(t: String): Int? = Regex("(?<![\\d:])(\\d{1,3})(?![\\d:])").find(t)?.groupValues?.get(1)?.toInt()

    private fun dirOf(t: String): String = when {
        has(t, "upar", "up", "ऊपर", "उपर") -> "up"
        has(t, "neeche", "niche", "down", "नीचे", "खाली") -> "down"
        has(t, "left", "baaye", "baayein", "bayen", "बाएं", "बाये", "लेफ्ट", "डावीकडे", "डावीकडे") -> "left"
        has(t, "right", "daaye", "daayein", "dayen", "दाएं", "दाये", "राइट", "उजवीकडे") -> "right"
        else -> ""
    }

    private fun askPlan(ctx: Context, s: Say): Plan {
        val (reply, speech) = pick(ctx, s)
        return Plan(listOf(Action("ask_user", p("question" to reply))), false, reply, speech)
    }

    private fun devDigits(s: String): String {
        val sb = StringBuilder()
        for (c in s) sb.append(if (c in '०'..'९') ('0' + (c - '०')) else c)
        return sb.toString()
    }

    private val TIME_WORDS = listOf("subah", "sube", "savere", "morning", "am", "pm", "shaam", "sham", "evening", "raat", "rat", "night",
        "dopahar", "dopehar", "afternoon", "kal", "aaj", "tomorrow", "today", "parso", "saade", "sade", "sawa", "paune", "minute", "minutes",
        "ghante", "ghanta", "baad", "after", "in", "सुबह", "शाम", "रात", "दोपहर", "कल", "आज", "परसों", "साढ़े", "सवा", "पौने", "मिनट", "घंटे", "बाद",
        "सकाळी", "संध्याकाळी", "रात्री", "उद्या", "वाजता")

    /** "7 baje", "saade 6", "6:30 pm", "10 minute baad" → "HH:mm" */
    fun parseTime(t: String): String? {
        val now = Calendar.getInstance()
        Regex("(\\d{1,3})\\s*(minute|minutes|min|mins|मिनट|मिनिट)").find(t)?.let {
            if (has(t, "baad", "after", "in", "बाद", "नंतर", "mein", "में")) {
                now.add(Calendar.MINUTE, it.groupValues[1].toInt())
                return fmt(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            }
        }
        Regex("(\\d{1,2})\\s*(ghante|ghanta|hour|hours|घंटे|घंटा|तास)").find(t)?.let {
            if (has(t, "baad", "after", "in", "बाद", "नंतर", "mein", "में")) {
                now.add(Calendar.HOUR_OF_DAY, it.groupValues[1].toInt())
                return fmt(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            }
        }
        val m = Regex("(\\d{1,2})(?:\\s*[:.]\\s*(\\d{2}))?").find(t) ?: return null
        var h = m.groupValues[1].toInt()
        var min = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        if (h > 23 || min > 59) return null
        when {
            has(t, "saade", "sade", "साढ़े", "साडे") -> min = 30
            has(t, "sawa", "सवा") -> min = 15
            has(t, "paune", "पौने", "पावणे") -> { min = 45; h = (h + 23) % 24 }
        }
        val pm = has(t, "pm", "p.m.", "shaam", "sham", "evening", "dopahar", "dopehar", "afternoon", "शाम", "दोपहर", "संध्याकाळी", "दुपारी")
        val night = has(t, "raat", "night", "रात", "रात्री")
        val am = has(t, "am", "a.m.", "subah", "sube", "savere", "morning", "सुबह", "सकाळी", "पहाटे")
        if (h <= 12) {
            when {
                pm -> if (h < 12) h += 12
                night -> if (h in 5..11) h += 12 else if (h == 12) h = 0
                am -> if (h == 12) h = 0
                else -> {
                    // Period na bataya ho to agla aane wala time lo
                    val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    if (h < 12 && h * 60 + min <= nowMin && (h + 12) * 60 + min > nowMin) h += 12
                }
            }
        }
        return fmt(h, min)
    }

    private fun fmt(h: Int, m: Int) = String.format(Locale.ENGLISH, "%02d:%02d", h, m)

    private fun dateFor(t: String, time: String): String {
        val c = Calendar.getInstance()
        when {
            has(t, "parso", "परसों", "day after tomorrow") -> c.add(Calendar.DAY_OF_YEAR, 2)
            has(t, "kal", "tomorrow", "कल", "udya", "उद्या") -> c.add(Calendar.DAY_OF_YEAR, 1)
            else -> {
                val (h, m) = time.split(":").map { it.toInt() }
                val nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                if (h * 60 + m <= nowMin) c.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(c.time)
    }

    /** "07:30" → "सुबह साढ़े सात बजे" jaisa, TTS ke liye */
    private fun speakTime(time: String): String {
        val (h, m) = time.split(":").map { it.toInt() }
        val part = when (h) { in 4..11 -> "सुबह"; in 12..15 -> "दोपहर"; in 16..19 -> "शाम"; else -> "रात" }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return when (m) {
            0 -> "$part $h12 बजे"
            30 -> "$part साढ़े $h12 बजे"
            15 -> "$part सवा $h12 बजे"
            45 -> "$part पौने ${if (h12 == 12) 1 else h12 + 1} बजे"
            else -> "$part $h12 बजकर $m मिनट"
        }
    }
}
