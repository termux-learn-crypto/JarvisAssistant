package com.vilas.jarvis

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.accessibilityservice.AccessibilityService

/** Maira brain ke naye tools (timer, torch, brightness, screenshot, photo, settings toggles). */
object ExtraActions {
    private fun start(ctx: Context, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); true
    } catch (_: Exception) { false }

    fun run(ctx: Context, a: Action): String? {
        val p = a.params
        return when (a.tool) {
            "set_timer" -> {
                val s = p.optInt("seconds", 60).coerceAtLeast(1)
                val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "Maira timer")
                if (start(ctx, i)) "Timer ${s / 60} min ${s % 60} sec ka laga diya." else "Timer wali clock app nahi mili."
            }
            "toggle_flashlight" -> {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return "Is phone mein torch nahi mili."
                val on = p.optString("state", "on") != "off"
                cm.setTorchMode(id, on); if (on) "Torch on." else "Torch off."
            }
            "set_brightness" -> {
                if (!Settings.System.canWrite(ctx)) {
                    start(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                    return "Brightness badalne ke liye Maira ko \"Modify system settings\" ki permission do, phir dobara bolo."
                }
                val cr = ctx.contentResolver
                if (p.optString("auto") == "true") {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                    "Auto brightness on."
                } else {
                    val lv = p.optInt("level", 50).coerceIn(0, 100)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (lv * 255 / 100).coerceAtLeast(1))
                    "Brightness $lv% kar di."
                }
            }
            "take_screenshot" -> {
                val svc = MairaAccessibilityService.instance
                    ?: return "Screenshot ke liye Settings > Accessibility mein Maira on karo."
                if (Build.VERSION.SDK_INT < 28) return "Is Android version pe screenshot nahi le sakti."
                svc.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot le liya.")
            }
            "cancel_alarm" -> {
                val svc = MairaAccessibilityService.instance
                if (svc != null) {
                    val all = p.optString("all") == "true"
                    val tt = p.optString("time")
                    val amb = tt.endsWith("?")
                    val hm = tt.trimEnd('?').split(":")
                    val hh = hm.getOrNull(0)?.toIntOrNull(); val mn = hm.getOrNull(1)?.toIntOrNull() ?: 0
                    if (all || hh != null) {
                        val n = svc.clockAlarmsOff(hh, mn, !amb, all)
                        try { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (_: Exception) {}
                        if (n > 0) return if (all) "$n alarm Clock me band kar diye. [debug: ${svc.dbgRows}]" else "Clock me alarm band kar diya. [debug: ${svc.dbgRows}]"
                        if (n == 0) return (if (all) "Koi chalu alarm nahi mila." else "Clock me ${tt.trimEnd('?')} ka chalu alarm nahi mila.") + " [debug: ${svc.lastDebug} | rows: ${svc.dbgRows}]"
                    }
                }
                if (svc == null) {
                    try { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    return "Alarm band nahi hua: Maira ki Accessibility service band ho gayi hai (phone ne band kar di). Settings > Accessibility > Maira ON karo. Dobara band na ho isliye Maira ko Autostart ON aur Battery saver 'No restrictions' do."
                }
                val i = Intent(AlarmClock.ACTION_DISMISS_ALARM)
                val t = p.optString("time")
                if (p.optString("all") == "true") {
                    i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
                } else if (t.isNotBlank()) {
                    val amb = t.endsWith("?")
                    val hm = t.trimEnd('?').split(":")
                    val h = hm[0].toInt(); val m = hm.getOrElse(1) { "0" }.toInt()
                    i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                    i.putExtra(AlarmClock.EXTRA_HOUR, (h % 12).let { if (it == 0) 12 else it })
                    i.putExtra(AlarmClock.EXTRA_MINUTES, m)
                    if (!amb) i.putExtra(AlarmClock.EXTRA_IS_PM, h >= 12)
                }
                if (start(ctx, i)) "Alarm band karne ke liye Clock ko bhej diya." else "Clock app alarm band nahi kar payi."
            }
            "enable_alarm" -> {
                val svc = MairaAccessibilityService.instance
                val all = p.optString("all") == "true"
                val tt = p.optString("time")
                val amb = tt.endsWith("?")
                val hm = tt.trimEnd('?').split(":")
                val hh = hm.getOrNull(0)?.toIntOrNull(); val mn = hm.getOrNull(1)?.toIntOrNull() ?: 0
                if (svc == null) {
                    try { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    return "Alarm chalu nahi hua: Maira ki Accessibility service band hai. Settings > Accessibility > Maira ON karo."
                }
                val n = if (all || hh != null) svc.clockAlarmsOff(hh, mn, !amb, all, wantOn = true) else 0
                try { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (_: Exception) {}
                if (n > 0) return if (all) "$n alarm Clock me chalu kar diye." else "Clock me purana alarm wapas chalu kar diya."
                if (all) return "Koi band alarm nahi mila."
                // purana alarm nahi mila -> naya laga do
                val st = p.optString("set_time").ifBlank { tt.trimEnd('?') }.split(":")
                val sh = st[0].toIntOrNull() ?: return "Time samajh nahi aaya."
                val sm = st.getOrNull(1)?.toIntOrNull() ?: 0
                val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, sh).putExtra(AlarmClock.EXTRA_MINUTES, sm).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (start(ctx, i)) String.format(java.util.Locale.ROOT, "Purana alarm nahi mila, naya %02d:%02d ka laga diya.", sh, sm) else "Alarm nahi laga paayi."
            }
            "take_photo" -> if (start(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) "Camera khol diya." else "Camera nahi khula."
            "toggle_dnd" -> {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) {
                    start(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    return "DND ke liye Maira ko \"Do Not Disturb access\" do, phir dobara bolo."
                }
                val on = p.optString("state", "on") != "off"
                nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                if (on) "DND on." else "DND off."
            }
            "toggle_rotation" -> {
                if (!Settings.System.canWrite(ctx)) {
                    start(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                    return "Auto rotate ke liye \"Modify system settings\" permission do, phir dobara bolo."
                }
                val on = p.optString("state", "on") != "off"
                Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
                if (on) "Auto rotate on." else "Auto rotate off."
            }
            "toggle_airplane" -> { start(ctx, Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)); "Airplane mode ki setting khol di, wahan switch dabao." }
            "toggle_location" -> { start(ctx, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); "Location ki setting khol di, wahan switch dabao." }
            "toggle_mobile_data" -> {
                val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
                start(ctx, i); "Mobile data ka panel khol diya, wahan switch dabao."
            }
            "toggle_hotspot" -> {
                val i = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
                if (!start(ctx, i)) start(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS))
                "Hotspot ki setting khol di, wahan switch dabao."
            }
            else -> null
        }
    }
}
