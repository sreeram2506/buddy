package com.sreeram.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

// "Train my voice": you say "Hey buddy" a few times. For each try, Buddy checks whether the wake detector would have woken,
// and what the speech engine hears when it is free to hear anything. If your way of saying it comes out as a close variant
// (say "hay buddi"), that variant is added to the phrases the wake detector accepts. Only words are kept, never the audio.
object VoiceTrainer {
    interface Ui { fun say(text: String); fun done(summary: String) }

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    @SuppressLint("MissingPermission")
    private fun record(ms: Int): ByteArray {
        val rate = 16000
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 16000 * 2)
        val out = ByteArray(rate * 2 * ms / 1000)
        rec.startRecording()
        var got = 0
        while (got < out.size) { val n = rec.read(out, got, out.size - got); if (n <= 0) break; got += n }
        rec.stop(); rec.release()
        return out
    }

    private fun level(pcm: ByteArray): Int {
        var sum = 0.0; var n = 0
        var i = 0
        while (i + 1 < pcm.size) { val v = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8); sum += v.toShort().toDouble().let { it * it }; n++; i += 2 }
        return if (n == 0) 0 else Math.sqrt(sum / n).toInt()
    }

    // The words (with confidence) the engine hears in this recording, optionally restricted to a grammar.
    private fun decode(model: Model, pcm: ByteArray, grammar: String?): List<Pair<String, Double>> {
        val rec = if (grammar == null) Recognizer(model, 16000f) else Recognizer(model, 16000f, grammar)
        rec.setWords(true)
        val all = ByteArray(16000) + pcm + ByteArray(32000)
        val words = ArrayList<Pair<String, Double>>()
        fun take(json: String) { JSONObject(json).optJSONArray("result")?.let { a -> for (i in 0 until a.length()) words.add(a.getJSONObject(i).getString("word") to a.getJSONObject(i).optDouble("conf", 0.0)) } }
        var i = 0
        while (i < all.size) { val n = minOf(4096, all.size - i); if (rec.acceptWaveForm(all.copyOfRange(i, i + n), n)) take(rec.result); i += n }
        take(rec.finalResult); rec.close()
        return words
    }

    fun run(context: Context, ui: Ui, takes: Int = 5) {
        cancelled = false
        val dir = File(context.filesDir, "vosk-model")
        if (!File(dir, ".ready").exists()) { ui.done("Turn on \"Say Hey Buddy to wake\" once so the speech model is set up, then try again."); return }
        val model = Model(dir.absolutePath)
        val lines = ArrayList<String>(); val learned = LinkedHashSet<String>(); var woke = 0; var quiet = 0; var done = 0
        try {
            for (t in 1..takes) {
                if (cancelled) break
                ui.say("Take $t of $takes\n\nSay \"Hey buddy\" right after the beep, the way you normally say it.")
                Thread.sleep(1200)
                ToneGenerator(AudioManager.STREAM_MUSIC, 60).startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                Thread.sleep(250)
                val pcm = record(3000)
                done++
                if (level(pcm) < 60) { quiet++; lines.add("$t: I could barely hear you. Speak a little louder or closer."); continue }
                val variants = WakePhrases.variants(context) + learned
                val gram = decode(model, pcm, WakePhrases.grammar(variants))
                val accepted = WakePhrases.accepts(gram, variants)
                val freeText = decode(model, pcm, null).joinToString(" ") { it.first }
                if (accepted) { woke++; lines.add("$t: woke up (heard \"$freeText\")") }
                else {
                    val cand = WakePhrases.candidateFrom(freeText)
                    if (cand != null) { learned.add(cand); lines.add("$t: heard \"$freeText\" - learned \"$cand\"") }
                    else lines.add("$t: did not wake (heard \"${freeText.ifBlank { "nothing clear" }}\")")
                }
            }
        } finally { model.close() }
        learned.forEach { WakePhrases.addVariant(context, it) }
        if (learned.isNotEmpty()) WakeWordService.reloadGrammar = true
        val head = when {
            done == 0 -> "Stopped before any try."
            woke == done -> "It woke up every time ($woke of $done). Your voice is already well recognised."
            else -> "It woke up $woke of $done times." + if (learned.isNotEmpty()) " I learned ${learned.size} new way${if (learned.size > 1) "s" else ""} you say it, so it should do better now." else ""
        }
        ui.done(head + "\n\n" + lines.joinToString("\n") + if (quiet > 0) "\n\nTip: hold the phone about a forearm away." else "")
    }
}
