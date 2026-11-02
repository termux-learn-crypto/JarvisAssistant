package com.vilas.jarvis

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Maira ka apna brain (command + chat), phone pe hi chalta hai. Koi Gemini/Qwen/API nahi. */
object MairaPy {
    @Volatile private var mod: PyObject? = null

    @Synchronized private fun module(ctx: Context): PyObject {
        mod?.let { return it }
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
        return Python.getInstance().getModule("maira_app").also { mod = it }
    }

    /** App khulte hi background mein brain load kar do taki pehla jawab tez aaye. */
    fun warmup(ctx: Context) {
        Thread {
            try { module(ctx).callAttr("warm") } catch (_: Throwable) {}
        }.start()
    }

    fun plan(ctx: Context, text: String): Plan {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())
        return Plan.parse(module(ctx).callAttr("ask", text, now).toString())
    }
}
