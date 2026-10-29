package com.vilas.jarvis

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import android.accessibilityservice.AccessibilityService
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Model ke plan ke har action ko Android pe execute karta hai.
 * Har function ek chhota status text return karta hai (log + bolne ke liye).
 */
class ActionExecutor(private val activity: Activity) {

    private val ctx: Context get() = activity

    // Safety: ye apps kabhi open nahi honge (banking / UPI / password managers)
    private val blockedWords = listOf(
        "bank", "pay", "paisa", "upi", "gpay", "phonepe", "paytm", "bhim", "wallet", "sbi", "hdfc", "icici",
        "axis", "kotak", "yono", "imobile", "cred", "password", "authenticator", "lastpass",
        "bitwarden", "1password", "keepass", "gallery vault", "locker"
    )

    private fun isBlocked(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        return blockedWords.any { t.contains(it) }
    }

    fun execute(a: Action): String = try {
        when (a.tool) {
            "set_alarm" -> setAlarm(a.params)
            "create_reminder" -> createReminder(a.params)
            "send_sms" -> sendSms(a.params)
            "make_call" -> makeCall(a.params)
            "open_app" -> openApp(a.params.optString("app_name"))
            "open_url" -> openUrl(a.params.optString("url"))
            "play_music" -> playMusic(a.params)
            "toggle_wifi" -> toggleWifi()
            "toggle_bluetooth" -> toggleBluetooth()
            "set_volume" -> setVolume(a.params)
            "read_battery" -> readBattery()
            "create_calendar_event" -> createEvent(a.params)
            "screen_action" -> screenAction(a.params)
            "media_control" -> mediaControl(a.params.optString("action"))
            "ask_user" -> a.params.optString("question")
            else -> "Ye action samajh nahi aaya: ${a.tool}"
        }
    } catch (e: Exception) {
        "${a.tool} fail hua: ${e.message}"
    }

    // ---------- helpers ----------

    private fun start(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun has(perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun parseTime(t: String): Pair<Int, Int> {
        val p = t.trim().split(":")
        return Pair(p[0].toInt(), p.getOrElse(1) { "0" }.toInt())
    }

    /** "YYYY-MM-DD" ya "today"/"tomorrow" + "HH:mm" ko millis mein badalta hai. */
    private fun toMillis(date: String, time: String): Long {
        val cal = Calendar.getInstance()
        when (date.trim().lowercase(Locale.ROOT)) {
            "", "today", "aaj" -> {}
            "tomorrow", "kal", "udya" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            "day_after_tomorrow", "parso", "parva" -> cal.add(Calendar.DAY_OF_YEAR, 2)
            else -> {
                val d = date.trim().split("-")
                cal.set(d[0].toInt(), d[1].toInt() - 1, d[2].toInt())
            }
        }
        val (h, m) = parseTime(time)
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun lookupNumber(name: String): String? {
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val target = name.trim().lowercase(Locale.ROOT)
        var partial: String? = null
        ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = (c.getString(0) ?: "").lowercase(Locale.ROOT)
                val num = c.getString(1) ?: continue
                if (n == target) return num
                if (partial == null && (n.contains(target) || target.contains(n) && n.length > 2)) partial = num
            }
        }
        return partial
    }

    private fun resolveNumber(p: JSONObject): String? {
        val num = p.optString("phone_number")
        if (num.isNotBlank()) return num
        val name = p.optString("contact_name")
        if (name.isBlank()) return null
        return lookupNumber(name)
    }

    // ---------- tools ----------

    private fun setAlarm(p: JSONObject): String {
        val time = p.optString("time")
        val (h, m) = parseTime(time)
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val label = p.optString("label")
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val days: ArrayList<Int>? = when (p.optString("repeat", "once")) {
            "daily" -> arrayListOf(
                Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY
            )
            "weekdays" -> arrayListOf(
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY
            )
            "weekends" -> arrayListOf(Calendar.SATURDAY, Calendar.SUNDAY)
            else -> null
        }
        if (days != null) i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, days)
        return if (start(i)) "Alarm $time set." else "Clock app nahi mila."
    }

    private fun createReminder(p: JSONObject): String {
        val text = p.optString("text", "Reminder")
        val time = p.optString("time")
        val millis = toMillis(p.optString("date", "today"), time)
        if (millis <= System.currentTimeMillis()) return "Ye time nikal chuka hai, reminder set nahi kiya."
        ReminderReceiver.ensureChannel(ctx)
        ReminderReceiver.schedule(ctx, text, millis)
        return "Reminder set: $text (${p.optString("date")} $time)"
    }

    private fun sendSms(p: JSONObject): String {
        val msg = p.optString("message")
        val who = p.optString("contact_name").ifBlank { p.optString("phone_number") }
        val number = resolveNumber(p)
            ?: return "\"$who\" ka number contacts mein nahi mila."
        return if (has(Manifest.permission.SEND_SMS)) {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            val parts = sms.divideMessage(msg)
            sms.sendMultipartTextMessage(number, null, parts, null, null)
            "SMS bhej diya: $who"
        } else {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", msg)
            if (start(i)) "SMS app khol diya, Send dabao." else "SMS app nahi mila."
        }
    }

    private fun makeCall(p: JSONObject): String {
        val who = p.optString("contact_name").ifBlank { p.optString("phone_number") }
        val number = resolveNumber(p)
            ?: return "\"$who\" ka number contacts mein nahi mila."
        val uri = Uri.parse("tel:$number")
        return if (has(Manifest.permission.CALL_PHONE)) {
            if (start(Intent(Intent.ACTION_CALL, uri))) "Call laga rahi hoon: $who" else "Call nahi ho paya."
        } else {
            if (start(Intent(Intent.ACTION_DIAL, uri))) "Dialer khol diya: $who" else "Dialer nahi mila."
        }
    }

    private fun openApp(name: String): String {
        if (name.isBlank()) return "App ka naam nahi mila."
        if (isBlocked(name)) return "Ye app safety ke liye main nahi kholti, yaar."
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val target = name.trim().lowercase(Locale.ROOT)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT) == target }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT).contains(target) }
            ?: apps.firstOrNull { target.contains(it.loadLabel(pm).toString().lowercase(Locale.ROOT)) }
            ?: return "\"$name\" app phone mein nahi mila."
        val label = match.loadLabel(pm).toString()
        if (isBlocked(label) || isBlocked(match.activityInfo.packageName)) {
            return "Ye app safety ke liye main nahi kholti, yaar."
        }
        val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "\"$label\" khul nahi paya."
        start(i)
        return "$label khol diya."
    }

    private fun openUrl(url: String): String {
        if (!url.startsWith("https://")) return "Sirf https links kholti hoon."
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "Link khol diya." else "Browser nahi mila."
    }

    private fun playMusic(p: JSONObject): String {
        val q = listOf(p.optString("song_name"), p.optString("artist_name"), p.optString("playlist_name"))
            .filter { it.isNotBlank() }.joinToString(" ").trim()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (q.isBlank()) {
            // Bina naam ke: pehle jo player last tha use resume karo
            mediaControl("play")
            try { Thread.sleep(900) } catch (_: InterruptedException) {}
            if (am.isMusicActive) return "Gaana chala diya."
        }
        val query = q.ifBlank { "latest hindi songs" }
        // YouTube search se pehla video nikaal ke seedha chalao (watch link autoplay hota hai)
        val id = firstYoutubeVideoId(query)
        val url = if (id != null) "https://www.youtube.com/watch?v=$id"
                  else "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        val yt = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val ytPkg = "com.google.android.youtube"
        val hasYt = try { ctx.packageManager.getPackageInfo(ytPkg, 0); true } catch (_: Exception) { false }
        if (hasYt) yt.setPackage(ytPkg)
        if (start(yt) || start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            return if (id != null) "YouTube pe chala diya: $query" else "YouTube pe search khol diya: $query"
        }
        // Aakhri koshish: phone ka music app
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query)
        return if (start(i)) "Music app mein chala rahi hoon: $query" else "Koi music app ya YouTube nahi mila."
    }

    private fun firstYoutubeVideoId(query: String): String? = try {
        val conn = java.net.URL("https://www.youtube.com/results?search_query=" + Uri.encode(query))
            .openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36")
        conn.setRequestProperty("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8")
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
    } catch (_: Exception) { null }

    private fun toggleWifi(): String {
        // Android 10+ apps ko seedha WiFi on/off nahi karne deta, isliye panel kholte hain
        val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI)
        else Intent(Settings.ACTION_WIFI_SETTINGS)
        return if (start(i)) "WiFi panel khol diya, wahan se on/off karo." else "WiFi settings nahi khuli."
    }

    private fun toggleBluetooth(): String {
        return if (start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
            "Bluetooth settings khol di, wahan se on/off karo." else "Bluetooth settings nahi khuli."
    }

    private fun setVolume(p: JSONObject): String {
        val level = p.optInt("level", 50).coerceIn(0, 100)
        val stream = when (p.optString("type", "media")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(stream)
        return try {
            am.setStreamVolume(stream, Math.round(max * level / 100f), AudioManager.FLAG_SHOW_UI)
            "Volume $level% kar diya."
        } catch (e: SecurityException) {
            "Do Not Disturb on hai, isliye ring volume nahi badla."
        }
    }

    private fun readBattery(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return "Battery $pct% hai" + if (charging) ", charging ho raha hai." else "."
    }

    private fun mediaControl(action: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val (code, done) = when (action.lowercase(Locale.ROOT)) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY to "Play kar diya."
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Pause kar diya."
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "Play/Pause kar diya."
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT to "Agla gaana."
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Pichla gaana."
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP to "Band kar diya."
            else -> return "Media action samajh nahi aaya: $action"
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return done
    }

    private fun screenAction(p: JSONObject): String {
        val action = p.optString("action")
        val appName = p.optString("app_name")
        val text = p.optString("text")
        if (action == "open") return openApp(appName)

        val svc = MairaAccessibilityService.instance
        if (svc == null) {
            start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return "Screen control ke liye Settings > Accessibility mein Maira on karo (ek baar)."
        }
        // Safety: banking/UPI/password apps ki screen pe kuch nahi karna
        val pkg = svc.currentPackage()
        if (isBlocked(pkg)) return "Is app mein screen control safety ke liye band hai."

        return when (action) {
            "click" -> svc.click(text)
            "type_text" -> svc.typeText(text)
            "press_enter" -> svc.pressEnter()
            "scroll_down" -> svc.scroll(true)
            "scroll_up" -> svc.scroll(false)
            "go_back" -> svc.global(AccessibilityService.GLOBAL_ACTION_BACK, "Back kar diya.")
            "home" -> svc.global(AccessibilityService.GLOBAL_ACTION_HOME, "Home pe aa gaye.")
            "recents" -> svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps khol diye.")
            "notifications" -> svc.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications khol di.")
            else -> "Screen action samajh nahi aaya: $action"
        }
    }

    private fun createEvent(p: JSONObject): String {
        val date = p.optString("date")
        val begin = toMillis(date, p.optString("start_time", "09:00"))
        val endStr = p.optString("end_time")
        val end = if (endStr.isNotBlank()) toMillis(date, endStr) else begin + 60 * 60 * 1000
        val i = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, p.optString("title", "Event"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        val loc = p.optString("location")
        if (loc.isNotBlank()) i.putExtra(CalendarContract.Events.EVENT_LOCATION, loc)
        return if (start(i)) "Calendar mein event khol diya, Save dabao." else "Calendar app nahi mila."
    }
}
