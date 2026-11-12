package com.sreeram.jarvis

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// Records a meeting or lecture, then transcribes it (Whisper) and summarises it with action items.
object Recorder {
    @Volatile var active = false
    private var mr: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private const val CHANNEL = "recording"
    const val ACTION_STOP = "com.sreeram.jarvis.STOP_RECORDING"

    fun start(context: Context): String {
        if (active) return "Already recording"
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "Microphone permission is not granted."
        // The "Hey buddy" service doubles as the foreground service that keeps the microphone usable in the background.
        WakeWordService.start(context)
        active = true // the wake-word loop lets go of the microphone
        Thread.sleep(1000)
        val f = File(context.filesDir, "rec_${System.currentTimeMillis()}.m4a")
        return try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1); r.setAudioSamplingRate(16000); r.setAudioEncodingBitRate(32000)
            r.setMaxDuration(90 * 60 * 1000)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            mr = r; file = f; startedAt = System.currentTimeMillis()
            showNotification(context, "Buddy is recording", "Tap Stop & summarize when the meeting ends.", ongoing = true, withStop = true)
            "Recording started"
        } catch (e: Exception) {
            active = false
            Log.e("Jarvis", "recording failed", e)
            "I couldn't start recording: ${e.message}"
        }
    }

    fun status(): String = if (active) "Recording for ${(System.currentTimeMillis() - startedAt) / 60000} minutes" else "Not recording"

    // Stops, transcribes and summarises. Takes up to a minute or two for a long recording.
    fun stopAndSummarize(context: Context): String {
        if (!active) return "I'm not recording."
        val f = file
        try { mr?.stop() } catch (e: RuntimeException) { Log.w("Jarvis", "recording too short", e) }
        mr?.release(); mr = null
        active = false
        val minutes = (System.currentTimeMillis() - startedAt) / 60000
        showNotification(context, "Summarising your recording", "This can take a minute.", ongoing = true, withStop = false)
        val summary = try {
            if (f == null || !f.exists() || f.length() < 2000) "The recording was too short to summarise."
            else {
                val transcript = Transcriber.transcribe(f)
                if (transcript.isBlank()) "I couldn't hear any speech in the recording."
                else {
                    val summary = Transcriber.summarize(transcript)
                    File(context.filesDir, f.name.replace(".m4a", ".txt")).writeText(transcript)
                    Store(context).addNote("Recording ${SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault()).format(Date())} ($minutes min): $summary")
                    summary
                }
            }
        } catch (e: Exception) {
            Log.e("Jarvis", "summary failed", e)
            "I recorded it but couldn't summarise it (${e.message?.take(80)}). The audio is saved on your phone."
        }
        showNotification(context, "Recording summary", summary, ongoing = false, withStop = false)
        if (!context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("wake", false)) WakeWordService.stop(context)
        return summary
    }

    private fun showNotification(context: Context, title: String, text: String, ongoing: Boolean, withStop: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val b = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title).setContentText(text.take(200)).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(ongoing).setAutoCancel(!ongoing)
        if (withStop) b.addAction(0, "Stop & summarize", PendingIntent.getBroadcast(context, 8, Intent(context, RecorderStopReceiver::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        nm.notify(9, b.build())
    }
}

class RecorderStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The process stays alive because the foreground microphone service is running.
        Thread { Speaker.speak(context, Recorder.stopAndSummarize(context).lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: "Summary ready.") }.start()
    }
}

object Transcriber {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(240, TimeUnit.SECONDS).writeTimeout(240, TimeUnit.SECONDS).build()
    private val chatModels = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b")

    fun transcribe(file: File): String {
        if (BuildConfig.GROQ_KEY.isBlank()) throw Exception("no Groq key")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo").addFormDataPart("response_format", "json")
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType())).build()
        client.newCall(Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${BuildConfig.GROQ_KEY}").post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw Exception("transcription error ${r.code}")
            return JSONObject(text).optString("text").trim()
        }
    }

    private fun chat(system: String, user: String): String {
        var last = "no answer"
        for (model in chatModels) {
            repeat(2) { attempt ->
                val json = JSONObject().put("model", model).put("max_tokens", 700).put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
                client.newCall(Request.Builder().url("https://api.groq.com/openai/v1/chat/completions")
                    .header("Authorization", "Bearer ${BuildConfig.GROQ_KEY}")
                    .post(json.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (r.isSuccessful) return JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
                    last = "error ${r.code}"
                    if (r.code == 429 && attempt == 0) Thread.sleep(20_000) else return@repeat
                }
            }
        }
        throw Exception(last)
    }

    // Long recordings are summarised in pieces (Groq's free tier limits tokens per minute), then merged.
    fun summarize(transcript: String): String {
        val system = "Summarise this transcript as short bullet points: key points, decisions, and action items with owners and deadlines if mentioned. " +
            "Write in the same language as the transcript. No introduction."
        val chunks = transcript.chunked(3500).take(8)
        if (chunks.size == 1) return chat(system, chunks[0])
        val parts = chunks.mapIndexed { i, c -> if (i > 0) Thread.sleep(8_000); chat(system, c) }
        return chat("Merge these partial summaries of one long recording into one short summary with key points, decisions and action items. Same language.", parts.joinToString("\n\n"))
    }
}
