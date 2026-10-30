package com.vilas.jarvis

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Maira Trainer": wake word ke liye asli recordings (16 kHz mono WAV) jama karta hai.
 * Positive ("Hey Maira" paas/door/dheere/zor se) + negative (milte-julte words, normal baat, shor).
 * Sab ek zip mein Download/ folder mein, phir share — Hark chat mein bhejo, model inhi se dobara train hoga.
 */
class TrainerActivity : Activity() {

    data class Item(val id: String, val say: String, val hint: String, val count: Int, val secs: Int,
                    val far: Boolean = false, val speech: Boolean = true)

    private val items = listOf(
        Item("pos_near", "Hey Maira", "Phone ke paas se, normal awaaz mein", 10, 3),
        Item("pos_far", "Hey Maira", "Phone rakh ke 2-3 meter door se (countdown milega)", 10, 3, far = true),
        Item("pos_soft", "Hey Maira", "Dheere / thaki hui awaaz mein", 5, 3),
        Item("pos_loud", "Hey Maira", "Zor se ya jaldi-jaldi", 5, 3),
        Item("pos_room", "Hey Maira", "Doosre kamre se ya TV/pankha chalte hue", 5, 3, far = true),
        Item("neg_meera", "Hey Meera", "Ye Maira NAHI hai — galti se na khule isliye", 4, 3),
        Item("neg_man", "Hey man", "Normal awaaz mein", 3, 3),
        Item("neg_yemera", "Ye mera phone hai", "Normal baat jaise", 4, 3),
        Item("neg_mera", "Mera kaam kar do", "Normal baat jaise", 3, 3),
        Item("neg_google", "OK Google", "Normal awaaz mein", 3, 3),
        Item("neg_hello", "Hello, haan bolo", "Phone pe baat jaise", 3, 3),
        Item("neg_talk", "Kuch bhi bolo", "Koi bhi normal baat, 5 second (Hindi/Marathi/English)", 6, 5),
        Item("noise", "Kuch mat bolo", "Ghar ka shor: TV, pankha, gaadi — 10 second chup raho", 3, 10, speech = false),
    )

    private val main = Handler(Looper.getMainLooper())
    private lateinit var dir: File
    private var idx = 0
    private var busy = false
    private var last: File? = null

    private lateinit var progress: TextView
    private lateinit var sayTv: TextView
    private lateinit var hintTv: TextView
    private lateinit var status: TextView
    private lateinit var recBtn: Button
    private lateinit var playBtn: Button
    private lateinit var redoBtn: Button
    private lateinit var summary: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dir = File(getExternalFilesDir(null) ?: filesDir, "wake_data").apply { mkdirs() }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.parseColor("#0B0F14"))
        }
        fun tv(size: Float, color: String) = TextView(this).apply { textSize = size; setTextColor(Color.parseColor(color)) }
        box.addView(tv(28f, "#2E8BC0").apply { text = "Maira Trainer" })
        box.addView(tv(14f, "#9AA4AE").apply {
            text = "Har take ke liye button dabao aur screen pe likha hua bolo. Shaant jagah ho to best, par door/shor wale take bhi zaroori hain. Beech mein band karo to baad mein wahi se shuru hoga."
        })
        progress = tv(15f, "#C8D0D8").apply { setPadding(0, dp(16), 0, 0) }
        sayTv = tv(30f, "#FFFFFF").apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(4)) }
        hintTv = tv(15f, "#9AA4AE").apply { gravity = Gravity.CENTER }
        status = tv(17f, "#E0C060").apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)) }
        box.addView(progress); box.addView(sayTv); box.addView(hintTv); box.addView(status)
        recBtn = Button(this).apply { isAllCaps = false; textSize = 20f; setOnClickListener { record() } }
        box.addView(recBtn)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playBtn = Button(this).apply { isAllCaps = false; text = "▶ Suno"; setOnClickListener { play() } }
        redoBtn = Button(this).apply { isAllCaps = false; text = "↺ Pichhla hatao"; setOnClickListener { redo() } }
        val skip = Button(this).apply { isAllCaps = false; text = "Agla ⏭"; setOnClickListener { nextItem(true) } }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(playBtn, lp); row.addView(redoBtn, lp); row.addView(skip, lp)
        box.addView(row)
        box.addView(Button(this).apply {
            isAllCaps = false; textSize = 17f; text = "📦 Zip banao aur bhejo"
            setOnClickListener { exportZip() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        summary = tv(13f, "#9AA4AE").apply { setPadding(0, dp(16), 0, 0) }
        box.addView(summary)
        box.addView(Button(this).apply {
            isAllCaps = false; text = "Sab recordings mitao"
            setOnClickListener {
                android.app.AlertDialog.Builder(this@TrainerActivity)
                    .setMessage("Saari recordings delete karein?")
                    .setPositiveButton("Haan") { _, _ -> dir.listFiles()?.forEach { it.delete() }; idx = 0; last = null; refresh() }
                    .setNegativeButton("Nahi", null).show()
            }
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0B0F14")); addView(box) })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        idx = items.indexOfFirst { have(it) < it.count }.let { if (it < 0) 0 else it }
        refresh()
    }

    override fun onResume() { super.onResume(); WakeService.instance?.pauseMic() }
    override fun onPause() { super.onPause(); WakeService.instance?.resumeMic() }

    private fun have(it: Item) = dir.listFiles()?.count { f -> f.name.startsWith(it.id + "_") && f.name.endsWith(".wav") } ?: 0
    private fun total() = items.sumOf { it.count }
    private fun done() = items.sumOf { minOf(have(it), it.count) }

    private fun refresh() {
        val it = items[idx]
        val h = have(it)
        progress.text = "Kul: ${done()}/${total()}   •   Hissa ${idx + 1}/${items.size}"
        sayTv.text = "\"${it.say}\""
        hintTv.text = it.hint
        recBtn.text = if (h >= it.count) "✓ Ye hissa poora (aur bhi record kar sakte ho)" else "🎙 Record (${h + 1}/${it.count})"
        playBtn.isEnabled = last != null && !busy
        redoBtn.isEnabled = last != null && !busy
        recBtn.isEnabled = !busy
        summary.text = items.joinToString("\n") { x -> (if (have(x) >= x.count) "✓ " else "• ") + "${x.say} — ${x.hint.substringBefore(",").substringBefore("(")}: ${have(x)}/${x.count}" }
    }

    private fun nextItem(manual: Boolean) {
        idx = if (manual) (idx + 1) % items.size
        else items.indices.firstOrNull { i -> i >= idx && have(items[i]) < items[i].count }
            ?: items.indices.firstOrNull { i -> have(items[i]) < items[i].count } ?: idx
        last = null
        refresh()
    }

    private fun record() {
        if (busy) return
        val it = items[idx]
        busy = true; refresh()
        Thread {
            try {
                Thread.sleep(500)
                if (it.far) for (k in 4 downTo 1) { main.post { status.text = "Door jao… $k" }; Thread.sleep(1000) }
                main.post { status.text = if (it.speech) "🔴 Ab bolo: \"${it.say}\"" else "🔴 Chup raho… (${it.secs} sec)" }
                val pcm = capture(it.secs)
                var sum = 0.0
                for (v in pcm) sum += v.toDouble() * v
                val rms = Math.sqrt(sum / pcm.size)
                if (pcm.isEmpty() || (it.speech && rms < 30)) {
                    main.post { status.text = "Awaaz nahi aayi. Phir se dabao."; busy = false; refresh() }
                    return@Thread
                }
                var n = have(it) + 1
                var f: File
                do { f = File(dir, "%s_%02d.wav".format(it.id, n)); n++ } while (f.exists())
                writeWav(f, pcm)
                last = f
                main.post {
                    status.text = "Save hua ✓  (${f.name})"
                    busy = false
                    if (have(it) >= it.count) nextItem(false) else refresh()
                    last = f; refresh()
                }
            } catch (e: Exception) {
                main.post { status.text = "Mic error: ${e.message}"; busy = false; refresh() }
            }
        }.start()
    }

    private fun capture(secs: Int): ShortArray {
        val total = 16000 * secs
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2)
        val out = ShortArray(total)
        var got = 0
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mic nahi mila")
            rec.startRecording()
            while (got < total) {
                val r = rec.read(out, got, minOf(2048, total - got))
                if (r <= 0) break
                got += r
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        return if (got == total) out else out.copyOf(got)
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        val data = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + data); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(16000); bb.putInt(32000); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(data)
        for (s in pcm) bb.putShort(s)
        FileOutputStream(f).use { it.write(bb.array()) }
    }

    private fun play() {
        val f = last ?: return
        Thread {
            try {
                val bytes = f.readBytes()
                val n = (bytes.size - 44) / 2
                val pcm = ShortArray(n)
                ByteBuffer.wrap(bytes, 44, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                t.write(pcm, 0, n); t.play()
                Thread.sleep(n * 1000L / 16000 + 300)
                t.release()
            } catch (_: Exception) {}
        }.start()
    }

    private fun redo() {
        val f = last ?: return
        f.delete(); last = null
        status.text = "Pichhla take hata diya. Dobara record karo."
        idx = items.indexOfFirst { f.name.startsWith(it.id + "_") }.let { if (it < 0) idx else it }
        refresh()
    }

    private fun exportZip() {
        val files = dir.listFiles()?.filter { it.name.endsWith(".wav") }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) { status.text = "Abhi koi recording nahi hai."; return }
        status.text = "Zip ban raha hai…"
        Thread {
            try {
                val name = "Maira_wake_data_${System.currentTimeMillis() / 1000}.zip"
                val info = "device=${Build.MANUFACTURER} ${Build.MODEL}\nandroid=${Build.VERSION.SDK_INT}\nfiles=${files.size}\n" +
                    items.joinToString("\n") { "${it.id}: \"${it.say}\" — ${it.hint} (${have(it)})" } + "\n"
                val uri: Uri?
                val os: OutputStream
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Maira")
                    }
                    uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    os = contentResolver.openOutputStream(uri!!)!!
                } else {
                    val f = File(getExternalFilesDir(null), name)
                    uri = Uri.fromFile(f); os = FileOutputStream(f)
                }
                ZipOutputStream(os).use { z ->
                    z.putNextEntry(ZipEntry("info.txt")); z.write(info.toByteArray()); z.closeEntry()
                    for (f in files) { z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
                }
                main.post {
                    status.text = "Save hua: Download/Maira/$name (${files.size} recordings). Ab Hark chat mein ye zip attach karke bhejo."
                    try {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Zip bhejo"))
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                main.post { status.text = "Zip nahi bana: ${e.message}" }
            }
        }.start()
    }
}
