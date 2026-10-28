package com.jarvis.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import com.jarvis.assistant.model.Action
import com.jarvis.assistant.model.ActionPlan
import java.util.Calendar
import java.util.Locale

class ActionExecutor(private val context: Context) {
    
    fun executePlan(plan: ActionPlan): String {
        if (plan.actions.isEmpty()) {
            return plan.reply
        }
        
        for (action in plan.actions) {
            try {
                executeAction(action)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return plan.reply
    }
    
    private fun executeAction(action: Action) {
        when (action.tool) {
            "set_alarm" -> setAlarm(action.parameters)
            "create_reminder" -> createReminder(action.parameters)
            "send_sms" -> sendSms(action.parameters)
            "make_call" -> makeCall(action.parameters)
            "open_app" -> openApp(action.parameters)
            "open_url" -> openUrl(action.parameters)
            "play_music" -> playMusic(action.parameters)
            "toggle_wifi" -> toggleWifi(action.parameters)
            "toggle_bluetooth" -> toggleBluetooth(action.parameters)
            "set_volume" -> setVolume(action.parameters)
            "read_battery" -> readBattery()
            "create_calendar_event" -> createCalendarEvent(action.parameters)
            "screen_action" -> screenAction(action.parameters)
            "ask_user" -> askUser(action.parameters)
        }
    }
    
    private fun setAlarm(params: Map<String, Any>) {
        val time = params["time"] as? String ?: return
        val label = params["label"] as? String ?: "Alarm"
        val repeat = params["repeat"] as? String ?: "once"
        
        val parts = time.split(":")
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            if (repeat == "daily") {
                putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(
                    Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                    Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
                ))
            } else if (repeat == "weekdays") {
                putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(
                    Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                    Calendar.THURSDAY, Calendar.FRIDAY
                ))
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    private fun createReminder(params: Map<String, Any>) {
        val text = params["text"] as? String ?: return
        val date = params["date"] as? String ?: return
        val time = params["time"] as? String ?: return
        
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, "Reminder: $text")
            putExtra(CalendarContract.Events.DESCRIPTION, text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    private fun sendSms(params: Map<String, Any>) {
        val contactName = params["contact_name"] as? String
        val phoneNumber = params["phone_number"] as? String
        val message = params["message"] as? String ?: ""
        
        val number = phoneNumber ?: getContactNumber(contactName ?: "")
        if (number.isEmpty()) return
        
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$number")
            putExtra("sms_body", message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    private fun makeCall(params: Map<String, Any>) {
        val contactName = params["contact_name"] as? String
        val phoneNumber = params["phone_number"] as? String
        
        val number = phoneNumber ?: getContactNumber(contactName ?: "")
        if (number.isEmpty()) return
        
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    private fun getContactNumber(name: String): String {
        if (name.isEmpty()) return ""
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")
        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        var number = ""
        cursor?.use {
            if (it.moveToFirst()) {
                number = it.getString(0)
            }
        }
        return number
    }
    
    private fun openApp(params: Map<String, Any>) {
        val appName = params["app_name"] as? String ?: return
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(getPackageName(appName))
        if (intent != null) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }
    
    private fun getPackageName(appName: String): String {
        return when (appName.lowercase()) {
            "chrome", "browser" -> "com.android.chrome"
            "youtube" -> "com.google.android.youtube"
            "whatsapp" -> "com.whatsapp"
            "instagram" -> "com.instagram.android"
            "gmail" -> "com.google.android.gm"
            "maps", "google maps" -> "com.google.android.apps.maps"
            "phone", "dialer" -> "com.android.dialer"
            "messages", "sms" -> "com.google.android.apps.messaging"
            "camera" -> "com.android.camera"
            "settings" -> "com.android.settings"
            else -> appName
        }
    }
    
    private fun openUrl(params: Map<String, Any>) {
        val url = params["url"] as? String ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    private fun playMusic(params: Map<String, Any>) {
        val songName = params["song_name"] as? String ?: ""
        val query = if (songName.isNotEmpty()) songName else "music"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    private fun toggleWifi(params: Map<String, Any>) {
        val state = params["state"] as? String ?: return
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            wifiManager.isWifiEnabled = (state == "on")
        }
    }
    
    private fun toggleBluetooth(params: Map<String, Any>) {
        val state = params["state"] as? String ?: return
        // Bluetooth toggle requires BLUETOOTH_CONNECT permission and API specific handling
    }
    
    private fun setVolume(params: Map<String, Any>) {
        val level = (params["level"] as? Double)?.toInt() ?: (params["level"] as? Int) ?: 50
        val type = params["type"] as? String ?: "media"
        // Volume control implementation
    }
    
    private fun readBattery() {
        // Battery info
    }
    
    private fun createCalendarEvent(params: Map<String, Any>) {
        val title = params["title"] as? String ?: return
        val date = params["date"] as? String ?: return
        val startTime = params["start_time"] as? String ?: return
        val endTime = params["end_time"] as? String ?: return
        
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.EVENT_LOCATION, params["location"] as? String ?: "")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    private fun screenAction(params: Map<String, Any>) {
        // Requires accessibility service - placeholder
    }
    
    private fun askUser(params: Map<String, Any>) {
        // Will be handled by UI
    }
}
