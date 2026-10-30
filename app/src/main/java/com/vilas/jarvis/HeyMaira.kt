package com.vilas.jarvis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.FloatBuffer

/**
 * "Hey Maira" ka apna dedicated wake model (Siri jaisa, sirf ek phrase ke liye bana).
 * openWakeWord style: 16 kHz audio -> melspectrogram.onnx -> Google speech embedding (96) ->
 * hey_maira.onnx (Maira ke liye train kiya chhota network) -> score 0..1.
 * Har 80 ms (1280 samples) ek naya score.
 */
class HeyMairaDetector(ctx: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1); setInterOpNumThreads(1)
    }
    private val mel = env.createSession(ctx.assets.open("wake/melspectrogram.onnx").use { it.readBytes() }, opts)
    private val emb = env.createSession(ctx.assets.open("wake/embedding_model.onnx").use { it.readBytes() }, opts)
    private val head = env.createSession(ctx.assets.open("wake/hey_maira.onnx").use { it.readBytes() }, opts)
    private val headIn = head.inputNames.first()

    private val hist = FloatArray(CHUNK + 480)   // pichhle 480 + naye 1280 samples
    private var pend = 0
    private val melRows = ArrayList<FloatArray>()
    private val feats = ArrayList<FloatArray>()

    init { reset() }

    // Auto volume (AGC): door se / dheere bolne pe awaaz ko upar laata hai (max 8x), paas se pe kuch nahi karta.
    private var lvl = 0f
    var gain = 1f; private set

    fun reset() {
        java.util.Arrays.fill(hist, 0f); pend = 0
        lvl = 0f; gain = 1f
        melRows.clear(); repeat(76) { melRows.add(FloatArray(32) { 1f }) }
        feats.clear()
    }

    /** Naye samples do. Is call mein bane scores mein se sabse bada lautata hai (koi nahi bana to -1). */
    fun feed(buf: ShortArray, n: Int): Float {
        var best = -1f
        var pk = 0
        for (i in 0 until n) { val a = Math.abs(buf[i].toInt()); if (a > pk) pk = a }
        lvl = maxOf(pk.toFloat(), lvl * 0.97f)          // tez attack, ~2 sec mein dheere release
        gain = if (lvl < 1f) 1f else (TARGET / lvl).coerceIn(1f, MAX_GAIN)
        for (i in 0 until n) {
            hist[480 + pend] = (buf[i] * gain).coerceIn(-32768f, 32767f)
            pend++
            if (pend == CHUNK) {
                val s = step()
                if (s > best) best = s
                System.arraycopy(hist, CHUNK, hist, 0, 480)
                pend = 0
            }
        }
        return best
    }

    private fun step(): Float {
        // 1) mel
        OnnxTensor.createTensor(env, FloatBuffer.wrap(hist), longArrayOf(1, hist.size.toLong())).use { t ->
            mel.run(mapOf("input" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val out = r[0].value as Array<Array<Array<FloatArray>>>
                for (row in out[0][0]) melRows.add(FloatArray(32) { row[it] / 10f + 2f })
            }
        }
        while (melRows.size > 120) melRows.removeAt(0)
        // 2) embedding (aakhri 76 mel rows)
        val win = FloatArray(76 * 32)
        val start = melRows.size - 76
        for (k in 0 until 76) System.arraycopy(melRows[start + k], 0, win, k * 32, 32)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(win), longArrayOf(1, 76, 32, 1)).use { t ->
            emb.run(mapOf("input_1" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val out = r[0].value as Array<Array<Array<FloatArray>>>
                feats.add(out[0][0][0].copyOf())
            }
        }
        while (feats.size > 16) feats.removeAt(0)
        if (feats.size < 16) return 0f
        // 3) Maira head
        val x = FloatArray(16 * 96)
        for (k in 0 until 16) System.arraycopy(feats[k], 0, x, k * 96, 96)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 1536)).use { t ->
            head.run(mapOf(headIn to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                return (r[0].value as Array<FloatArray>)[0][0]
            }
        }
    }

    override fun close() {
        try { mel.close() } catch (_: Exception) {}
        try { emb.close() } catch (_: Exception) {}
        try { head.close() } catch (_: Exception) {}
    }

    companion object {
        const val CHUNK = 1280
        const val DEFAULT_TH = 0.5f
        const val TARGET = 9000f
        const val MAX_GAIN = 8f

        /** Settings / calibration ke hisaab se threshold. */
        fun threshold(ctx: Context): Float {
            val p = Brain.prefs(ctx)
            var th = p.getFloat("wake_th", DEFAULT_TH)
            val easy = p.getBoolean("wake_easy", false)
            if (easy) th *= 0.6f
            return th.coerceIn(if (easy) 0.15f else 0.3f, 0.95f)
        }
    }
}

/** "Apni awaaz sikhao": user ki recording ka sabse bada score nikalna. */
object WakeTrainer {
    /** ms tak mic se record karke detector se score. Background thread pe chalao. */
    fun recordScore(ctx: Context, det: HeyMairaDetector, ms: Int = 2600): Float {
        val total = 16000 * ms / 1000
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2)
        val audio = ShortArray(total)
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mic")
            rec.startRecording()
            var got = 0
            while (got < total) {
                val n = rec.read(audio, got, minOf(2048, total - got))
                if (n <= 0) break
                got += n
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        // Mic se awaaz hi nahi aayi (mic kisi aur ke paas tha) -> -2 (gina nahi jayega)
        var sum = 0.0
        for (v in audio) sum += v.toDouble() * v
        val rms = Math.sqrt(sum / audio.size)
        if (rms < 30.0) return -2f
        return scoreOf(det, audio)
    }

    fun scoreOf(det: HeyMairaDetector, audio: ShortArray): Float {
        det.reset()
        val sil = ShortArray(16000 * 3 / 2)  // warm-up
        det.feed(sil, sil.size)
        var best = 0f
        var i = 0
        while (i < audio.size) {
            val n = minOf(1280, audio.size - i)
            val part = audio.copyOfRange(i, i + n)
            best = maxOf(best, det.feed(part, n))
            i += n
        }
        val tail = ShortArray(8000)
        best = maxOf(best, det.feed(tail, tail.size))
        return best
    }

    /** Scores se threshold: ek-do miss maaf, phir thoda neeche rakho; had se zyada neeche nahi. */
    /** 0.05 se kam = woh take fail hua (der se bola / mic busy), use chhod do. Kam se kam 3 achhe chahiye. */
    fun pickThreshold(scores: List<Float>): Float? {
        val good = scores.filter { it >= 0.05f }.sorted()
        if (good.size < 3) return null
        val ref = if (good.size >= 5) good[1] else good.first()
        return (ref * 0.6f).coerceIn(0.3f, 0.6f)
    }
}
