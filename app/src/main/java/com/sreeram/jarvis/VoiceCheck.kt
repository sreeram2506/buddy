package com.sreeram.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

// Records your voice three ways at once while you read a few sentences, so how well Buddy hears you can be measured with your real voice:
//   comm  = exactly what a live conversation sends (echo cancellation + noise suppression on)
//   reco  = the speech-recognition source, lightly processed
//   raw   = the unprocessed microphone
// The files stay on this phone (in Buddy's folder) until you delete them. Nothing is uploaded.
object VoiceCheck {
    val sentences = listOf(
        "Hey buddy, call Amma",
        "What did Dad say about the plumber yesterday?",
        "Open WhatsApp and read the latest message from Srinija",
        "Remind me in one hour to take my medicine",
        "Turn on Away mode for two hours",
        "Call Siva Pachuri",
        "Mere phone ka battery kitna hai?",
        "Nenu repu ubhaya aaru gantalaki lestanu, alarm pettu",
        "అమ్మకు ఫోన్ చేయి",
        "Send a message to Nanna that I will reach by eight"
    )
    private const val SECONDS_EACH = 6
    fun dir(c: Context) = File(c.getExternalFilesDir(null), "voicecheck").apply { mkdirs() }

    fun start(a: Activity, onDone: () -> Unit) {
        val label = TextView(a).apply { textSize = 22f; setPadding(48, 48, 48, 24); text = "Get ready…" }
        val hint = TextView(a).apply { textSize = 13f; setPadding(48, 0, 48, 24); text = "Read each sentence out loud in your normal voice, the way you'd say it to Buddy. Speak naturally." }
        val box = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; addView(label); addView(hint) }
        val dialog = AlertDialog.Builder(a).setTitle("Voice check").setView(box).setCancelable(false).create()
        dialog.show()
        WakeWordService.liveActive = true // frees the microphone
        thread {
            val d = dir(a); d.listFiles()?.forEach { it.delete() }
            val sources = listOf("comm" to MediaRecorder.AudioSource.VOICE_COMMUNICATION, "reco" to MediaRecorder.AudioSource.VOICE_RECOGNITION, "raw" to MediaRecorder.AudioSource.UNPROCESSED)
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recs = ArrayList<Triple<String, AudioRecord, FileOutputStream>>()
            for ((name, src) in sources) try {
                @Suppress("MissingPermission")
                val r = AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16384))
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
                if (name == "comm") {
                    if (AcousticEchoCanceler.isAvailable()) try { AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true } catch (e: Exception) { }
                    if (NoiseSuppressor.isAvailable()) try { NoiseSuppressor.create(r.audioSessionId)?.enabled = true } catch (e: Exception) { }
                }
                recs.add(Triple(name, r, FileOutputStream(File(d, "$name.pcm"))))
            } catch (e: Exception) { }
            recs.forEach { it.second.startRecording() }
            val loops = recs.map { (name, r, out) ->
                thread { val buf = ByteArray(3200); val end = System.currentTimeMillis() + (sentences.size * SECONDS_EACH + 3) * 1000L
                    while (System.currentTimeMillis() < end) { val n = r.read(buf, 0, buf.size); if (n > 0) out.write(buf, 0, n) } }
            }
            File(d, "sentences.txt").writeText(sentences.joinToString("\n"))
            Thread.sleep(2000)
            for ((i, s) in sentences.withIndex()) {
                a.runOnUiThread { label.text = "${i + 1}/${sentences.size}\n\n$s" }
                Thread.sleep(SECONDS_EACH * 1000L)
            }
            loops.forEach { it.join() }
            recs.forEach { (_, r, out) -> try { r.stop() } catch (e: Exception) { }; r.release(); out.close() }
            WakeWordService.liveActive = false
            a.runOnUiThread { dialog.dismiss(); onDone() }
        }
    }
}
