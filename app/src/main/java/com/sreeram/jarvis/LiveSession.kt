package com.sreeram.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// A real-time voice conversation with Gemini Live: your microphone streams up, the model's voice streams back,
// you can interrupt it by speaking, and it can use Buddy's phone tools mid-conversation.
class LiveSession(
    private val context: Context,
    private val actions: PhoneActions,
    private val systemText: String,
    private val listener: Listener,
    private val declarations: JSONArray = actions.declarations,
    private val greeting: String? = null,
    private val toolHandler: ((String, JSONObject) -> String)? = null
) {
    interface Listener {
        fun onReady()
        fun onUserText(text: String)
        fun onBuddyText(text: String)
        fun onGoodbye()
        fun onClosed(reason: String, failed: Boolean, wasReady: Boolean)
    }

    private val client = shared
    private var startedAt = 0L
    private var firstAudioLogged = false
    private var socket: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var ready = false
    @Volatile private var modelSpeaking = false
        set(v) { field = v; Session.liveSpeaking = v }
    @Volatile private var speakingUntil = 0L
    @Volatile private var lastActivity = System.currentTimeMillis()
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echoCancelled = false
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private val userBuf = StringBuilder()
    private val buddyBuf = StringBuilder()
    private var goodbyeAfterTurn = false
    private var stepsThisTurn = 0 // tool calls since the user last spoke; capped so a stuck task cannot run away
    private var audioManager: AudioManager? = null
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedCallVolume = -1
    private val bye = Regex("^(bye|by|goodbye|good bye)( bye)?(,? (buddy|body))?$", RegexOption.IGNORE_CASE)

    companion object {
        private const val MAX_STEPS = 40
        // One client for every conversation, HTTP/1.1 only (what a websocket needs), so a connection opened ahead of time can be reused.
        private val shared = OkHttpClient.Builder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS).connectionPool(okhttp3.ConnectionPool(2, 10, TimeUnit.MINUTES)).build()
        @Volatile private var warmAt = 0L

        // Opens the network connection to Gemini before it is needed, so "Hey Buddy" does not wait for DNS and TLS setup.
        fun prewarm() {
            if (System.currentTimeMillis() - warmAt < 4 * 60_000) return
            warmAt = System.currentTimeMillis()
            thread {
                try { shared.newCall(Request.Builder().url("https://generativelanguage.googleapis.com/").head().build()).execute().close(); Log.d("Jarvis", "live connection warmed") }
                catch (e: Exception) { warmAt = 0 }
            }
        }
    }

    fun start() {
        startedAt = System.currentTimeMillis()
        running = true
        Session.liveRunning = true
        Session.frameSink = { sendFrame(it) }
        Session.endRequested = false
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${BuildConfig.API_KEY}"
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setupJson()) }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("Jarvis", "live failure ${response?.code}: ${t.message}")
                end("connection failed", failed = true)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("Jarvis", "live closed $code $reason")
                end(reason.ifBlank { "closed" }, failed = code != 1000)
            }
        })
    }

    fun stop() = end("ended", failed = false)

    private fun setupJson(): String = JSONObject().put("setup", JSONObject()
        .put("model", "models/gemini-3.8-live")
        .put("generationConfig", JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Puck")))))
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText))))
        .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
        // Speech detection: a natural pause (about a second) no longer ends your turn, so a sentence is not cut in half and
        // answered early; the start of speech is noticed quickly, so quiet or short words are not missed.
        .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()
            .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH").put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
            .put("prefixPaddingMs", 300).put("silenceDurationMs", 1200)))
        .put("inputAudioTranscription", JSONObject())
        .put("outputAudioTranscription", JSONObject())
        .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))).toString()

    private fun send(json: JSONObject) { socket?.send(json.toString()) }

    private fun sendFrame(jpeg: ByteArray) {
        if (!running || !ready) return
        send(JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject()
            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)).put("mimeType", "image/jpeg"))))
    }

    private fun handle(raw: String) {
        if (!running) return
        val m = try { JSONObject(raw) } catch (e: Exception) { return }
        if (m.has("setupComplete")) {
            startAudio(); ready = true; listener.onReady()
            Log.d("Jarvis", "live ready in ${System.currentTimeMillis() - startedAt} ms")
            // Answer back by name as soon as the conversation opens, so you know it heard you.
            greeting?.let { send(JSONObject().put("clientContent", JSONObject().put("turns", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", it))))).put("turnComplete", true))) }
            return
        }
        if (m.has("goAway")) { end("session time limit", failed = false); return }
        m.optJSONObject("toolCall")?.let { runTools(it); return }
        val sc = m.optJSONObject("serverContent") ?: return
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            userBuf.append(it); lastActivity = System.currentTimeMillis()
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            finishUser(); buddyBuf.append(it); lastActivity = System.currentTimeMillis()
        }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                finishUser()
                if (!firstAudioLogged) { firstAudioLogged = true; Log.d("Jarvis", "live first voice after ${System.currentTimeMillis() - startedAt} ms") }
                modelSpeaking = true
                lastActivity = System.currentTimeMillis()
                playQueue.offer(Base64.decode(data, Base64.DEFAULT))
            }
        }
        if (sc.optBoolean("interrupted")) {
            // The user spoke over Buddy: drop everything still queued so it falls silent immediately.
            playQueue.clear()
            track?.apply { pause(); flush(); play() }
            modelSpeaking = false
            finishBuddy()
        }
        if (sc.optBoolean("turnComplete")) {
            finishBuddy()
            speakingUntil = System.currentTimeMillis() + 600
            lastActivity = System.currentTimeMillis()
            if (Session.stopLiveAfterTurn) { Session.stopLiveAfterTurn = false; end("handing the microphone over", failed = false); return }
            if (goodbyeAfterTurn || Session.endRequested) { Session.endRequested = false; listener.onGoodbye(); end("goodbye", failed = false) }
        }
    }

    // The user's words arrive in pieces; once the model starts answering they are complete.
    private fun finishUser() {
        val text = userBuf.toString().trim()
        if (text.isEmpty()) return
        userBuf.setLength(0)
        Turns.count++
        Turns.lastText = text
        stepsThisTurn = 0
        if (bye.matches(text.trimEnd('.', '!'))) goodbyeAfterTurn = true
        listener.onUserText(text)
    }

    private fun finishBuddy() {
        val text = buddyBuf.toString().trim()
        buddyBuf.setLength(0)
        if (text.isNotEmpty()) listener.onBuddyText(text)
    }

    private fun runTools(toolCall: JSONObject) {
        finishUser()
        val calls = toolCall.optJSONArray("functionCalls") ?: return
        thread {
            val responses = JSONArray()
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                stepsThisTurn++
                val out = if (stepsThisTurn > MAX_STEPS) "STOP: that is too many steps for one request. Stop now, tell the user briefly what you tried and where you got stuck, and ask what they want to do."
                          else (toolHandler ?: actions::run)(c.getString("name"), c.optJSONObject("args") ?: JSONObject())
                val quiet = c.getString("name") == "type_text"
                Log.d("Jarvis", "live tool ${c.getString("name")} ${if (quiet) "(text hidden)" else c.optJSONObject("args")} -> ${if (quiet) "" else out.take(200)}")
                responses.put(JSONObject().put("id", c.optString("id")).put("name", c.getString("name"))
                    .put("response", JSONObject().put("result", out)))
            }
            lastActivity = System.currentTimeMillis()
            if (running) send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)))
        }
    }

    // ---- audio ----

    private fun startAudio() {
        val am = context.getSystemService(AudioManager::class.java).also { audioManager = it }
        savedMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headset = outputs.any { it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET) }
        if (!headset) {
            if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            else @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = true }
        }
        savedCallVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        if (savedCallVolume < max * 0.7) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (max * 0.8).toInt(), 0)

        val minRec = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minRec, 8192))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); end("microphone unavailable", failed = true); return }
        echoCancelled = AcousticEchoCanceler.isAvailable() && try { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } != null } catch (e: Exception) { false }
        if (NoiseSuppressor.isAvailable()) try { NoiseSuppressor.create(rec.audioSessionId)?.enabled = true } catch (e: Exception) { }
        Log.d("Jarvis", "live audio: echo cancellation=$echoCancelled headset=$headset")
        record = rec

        val minTrack = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minTrack, 48000)).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }

        thread { playbackLoop() }
        thread { micLoop(rec, headset) }
    }

    private fun playbackLoop() {
        while (running) {
            val chunk = playQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            track?.write(chunk, 0, chunk.size)
        }
    }

    private fun micLoop(rec: AudioRecord, headset: Boolean) {
        val buf = ShortArray(640) // 40 ms
        var levelSum = 0.0; var levelPeak = 0; var levelCount = 0; var levelAt = System.currentTimeMillis()
        rec.startRecording()
        // Without echo cancellation the model would hear itself and interrupt itself, so mute the mic while it talks.
        val halfDuplex = !echoCancelled && !headset
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            for (i in 0 until n) { val v = Math.abs(buf[i].toInt()); levelSum += v.toDouble() * v; if (v > levelPeak) levelPeak = v }
            levelCount += n
            if (System.currentTimeMillis() - levelAt > 5000) {
                Log.d("Jarvis", "live mic level (5s): rms=${Math.sqrt(levelSum / levelCount).toInt()} peak=$levelPeak of 32767")
                levelSum = 0.0; levelPeak = 0; levelCount = 0; levelAt = System.currentTimeMillis()
            }
            if (System.currentTimeMillis() - lastActivity > 45_000) { end("silence", failed = false); return }
            val talking = modelSpeaking && (playQueue.isNotEmpty() || System.currentTimeMillis() < speakingUntil)
            if (!modelSpeaking || playQueue.isEmpty()) { if (System.currentTimeMillis() >= speakingUntil) modelSpeaking = false }
            if (halfDuplex && talking) continue
            val bytes = ByteArray(n * 2)
            for (i in 0 until n) { bytes[i * 2] = (buf[i].toInt() and 0xFF).toByte(); bytes[i * 2 + 1] = (buf[i].toInt() shr 8).toByte() }
            send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000"))))
        }
    }

    @Synchronized
    private fun end(reason: String, failed: Boolean) {
        if (!running) return
        running = false
        Session.liveRunning = false
        Session.liveSpeaking = false
        Session.frameSink = null
        Vision.stop(context)
        val wasReady = ready
        try { record?.stop() } catch (e: Exception) { }
        record?.release(); record = null
        track?.release(); track = null
        playQueue.clear()
        audioManager?.let { am ->
            am.mode = savedMode
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
            if (savedCallVolume >= 0) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedCallVolume, 0)
        }
        try { socket?.close(1000, "bye") } catch (e: Exception) { }
        listener.onClosed(reason, failed, wasReady)
    }
}
