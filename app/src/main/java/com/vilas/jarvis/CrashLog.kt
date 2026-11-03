package com.vilas.jarvis

import android.content.Context
import java.io.File

object CrashLog {
    @Volatile private var done = false
    fun install(ctx: Context) {
        if (done) return; done = true
        val f = File(ctx.applicationContext.filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { f.writeText(java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ROOT).format(java.util.Date()) + " " + e.toString().take(200) + " @ " + e.stackTrace.take(3).joinToString(" < ")) } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
    fun last(ctx: Context): String = try { File(ctx.applicationContext.filesDir, "crash.txt").takeIf { it.exists() }?.readText()?.take(300) ?: "" } catch (_: Throwable) { "" }
    fun accEnabled(ctx: Context): Boolean = try {
        (android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .contains(ctx.packageName + "/", ignoreCase = true)
    } catch (_: Throwable) { false }
    /** service connect hone ka thoda wait */
    fun waitSvc(ctx: Context): MairaAccessibilityService? {
        repeat(15) { MairaAccessibilityService.instance?.let { return it }; if (!accEnabled(ctx)) return null; Thread.sleep(200) }
        return MairaAccessibilityService.instance
    }
    fun why(ctx: Context): String {
        val c = last(ctx)
        return if (accEnabled(ctx)) "Settings me Maira ON dikh raha hai par service connect nahi hui." + (if (c.isNotBlank()) " [crash: $c]" else " Phone ek baar restart karo.")
        else "Settings me Maira ki Accessibility OFF hai."
    }
}
