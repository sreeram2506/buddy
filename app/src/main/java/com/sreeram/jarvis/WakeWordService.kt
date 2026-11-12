package com.sreeram.jarvis

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.service.voice.VoiceInteractionSession
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

// Listens for "Hey buddy" while the phone is awake and unlocked, then opens the assistant.
class WakeWordService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNow()
        if (worker == null) {
            running = true
            worker = Thread { listenLoop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (receiversRegistered) { unregisterReceiver(batteryReceiver); unregisterReceiver(ringerReceiver); receiversRegistered = false }
        running = false
        worker = null
        super.onDestroy()
    }

    private var lastBattery = 100
    private var receiversRegistered = false

    // Spoken low-battery warnings at 20, 10 and 5 percent while unplugged and the screen is on.
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / scale
            val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val enabled = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("battery_alerts", true)
            if (enabled && !charging && pct in 0 until lastBattery && isAwake() && listOf(20, 10, 5).any { lastBattery > it && pct <= it }) {
                Log.d("Jarvis", "battery alert at $pct")
                Speaker.speak(c, "Heads up, your battery is at $pct percent.")
            }
            lastBattery = pct
        }
    }

    // Opens or closes a silent-mode Away session as soon as the ringer changes, so the summary has the right start time.
    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Log.d("Jarvis", "ringer changed to ${c.getSystemService(AudioManager::class.java).ringerMode}")
            Thread { Away.active(c) }.start()
        }
    }

    private fun startForegroundNow() {
        if (!receiversRegistered) {
            ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this, ringerReceiver, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiversRegistered = true
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Buddy", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Buddy is listening for \"Hey buddy\"")
            .setContentText("Only while the phone is unlocked. Tap Stop to turn off.")
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    private fun modelDir(): File {
        val dir = File(filesDir, "vosk-model")
        if (!File(dir, ".ready").exists()) {
            dir.deleteRecursively()
            copyAsset("model-en-us", dir)
            File(dir, ".ready").writeText("ok")
        }
        return dir
    }

    private fun copyAsset(path: String, target: File) {
        val children = assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(path).use { i -> target.outputStream().use { o -> i.copyTo(o) } }
        } else {
            target.mkdirs()
            children.forEach { copyAsset("$path/$it", File(target, it)) }
        }
    }

    private fun isAwake(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        return pm.isInteractive && !km.isKeyguardLocked
    }

    private fun listenLoop() {
        var model: Model? = null
        var rec: Recognizer? = null
        var stopRec: Recognizer? = null
        var audio: AudioRecord? = null
        try {
            model = Model(modelDir().absolutePath)
            var variants = WakePhrases.variants(this)
            var r = Recognizer(model, 16000f, WakePhrases.grammar(variants))
            r.setWords(true)
            rec = r
            stopRec = Recognizer(model, 16000f, "[\"stop\", \"buddy stop\", \"be quiet\", \"shut up\", \"[unk]\"]")
            stopRec.setWords(true)
            var lastMode = ""
            var lastStop = 0L
            val stopWords = setOf("stop", "buddy stop", "be quiet", "shut up")
            val buf = ShortArray(2048)
            var lastTrigger = 0L
            var offLoggedAt = 0L
            // Sound detector: the speech model only runs while there is sound above the room's background level.
            var noiseFloor = 60.0
            var gateUntil = 0L
            var gateOpen = false
            val preRoll = ArrayDeque<ShortArray>()
            var frames = 0
            var decoded = 0
            var statsAt = System.currentTimeMillis()
            while (running) {
                if (reloadGrammar) {
                    // "Train my voice" learned a new way of saying it.
                    reloadGrammar = false
                    variants = WakePhrases.variants(this)
                    r.close(); r = Recognizer(model, 16000f, WakePhrases.grammar(variants)); r.setWords(true); rec = r
                    Log.d("Jarvis", "wake phrases now: hey buddy + ${variants.joinToString()}")
                }
                val awake = isAwake()
                val stopMode = awake && (Speaker.speaking || activitySpeaking)
                val wakeMode = awake && !suspended && !Speaker.speaking && System.currentTimeMillis() >= holdUntil
                val mode = if (liveActive || Recorder.active) "off" else if (stopMode) "stop" else if (wakeMode) "wake" else "off"
                if (mode == "off") {
                    audio?.release(); audio = null
                    r.reset(); stopRec.reset(); lastMode = "off"; gateOpen = false; preRoll.clear()
                    if (System.currentTimeMillis() - offLoggedAt > 10_000) {
                        offLoggedAt = System.currentTimeMillis()
                        Log.d("Jarvis", "wake listener idle: awake=$awake live=$liveActive recorder=${Recorder.active} suspended=$suspended speaking=${Speaker.speaking} hold=${holdUntil > System.currentTimeMillis()}")
                    }
                    Thread.sleep(400)
                    continue
                }
                if (mode == "wake") LiveSession.prewarm()
                if (mode != lastMode) { Log.d("Jarvis", "listen mode: $lastMode -> $mode"); r.reset(); stopRec.reset(); lastMode = mode; gateOpen = false; preRoll.clear() }
                if (audio == null) {
                    val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
                    if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); audio = null; Thread.sleep(1000); continue }
                    audio.startRecording()
                }
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (stopMode) {
                    amplify(buf, n)
                    // While Buddy is talking, the only thing worth hearing is a request to be quiet.
                    // Partial results arrive immediately; waiting for a final one would mean waiting for Buddy to stop talking.
                    val said = if (stopRec.acceptWaveForm(buf, n)) JSONObject(stopRec.result).optString("text").trim()
                               else JSONObject(stopRec.partialResult).optString("partial").trim()
                    if (said in stopWords && System.currentTimeMillis() - lastStop > 2000) {
                        lastStop = System.currentTimeMillis()
                        Log.d("Jarvis", "stop word heard: $said")
                        stopRec.reset()
                        Speaker.stop()
                        Handler(Looper.getMainLooper()).post { onStop?.invoke() }
                    }
                    continue
                }
                val now = System.currentTimeMillis()
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = Math.sqrt(sum / n)
                val threshold = minOf(maxOf(MIN_SOUND_LEVEL, noiseFloor * 1.8), MAX_SOUND_LEVEL)
                if (rms > threshold) gateUntil = now + GATE_HANGOVER_MS
                else if (now > gateUntil) noiseFloor = 0.97 * noiseFloor + 0.03 * rms // learn how loud "quiet" is here
                amplify(buf, n) // measured above, so the sound detector is unaffected
                frames++
                if (now > gateUntil) {
                    if (gateOpen) { r.reset(); gateOpen = false }
                    preRoll.addLast(buf.copyOf(n)); if (preRoll.size > 3) preRoll.removeFirst()
                    if (now - statsAt > 60_000) { Log.d("Jarvis", "sound detector: model ran on $decoded of $frames chunks, quiet level ${noiseFloor.toInt()}"); frames = 0; decoded = 0; statsAt = now }
                    continue
                }
                if (!gateOpen) {
                    // Sound just started: feed the last few chunks first so the beginning of "hey" isn't cut off.
                    gateOpen = true
                    preRoll.forEach { r.acceptWaveForm(it, it.size) }
                    preRoll.clear()
                }
                decoded++
                if (now - statsAt > 60_000) { Log.d("Jarvis", "sound detector: model ran on $decoded of $frames chunks, quiet level ${noiseFloor.toInt()}"); frames = 0; decoded = 0; statsAt = now }
                // Only act on finished phrases; partial guesses fire on noise.
                var text = ""
                if (r.acceptWaveForm(buf, n)) {
                    val result = JSONObject(r.result)
                    val words = result.optJSONArray("result")
                    if (words != null) {
                        val heard = (0 until words.length()).joinToString(" ") { val w = words.getJSONObject(it); "${w.optString("word")}(%.2f)".format(w.optDouble("conf", 0.0)) }
                        // What the listener heard (words only, never audio), so a missed "Hey buddy" can be diagnosed.
                        if (heard.replace("[unk]", "").any { it.isLetter() }) Log.d("Jarvis", "wake listener heard: $heard")
                        val list = (0 until words.length()).map { val w = words.getJSONObject(it); w.optString("word") to w.optDouble("conf", 0.0) }
                        if (WakePhrases.accepts(list, variants)) {
                            if (File(filesDir, "wake_dry_run").exists()) Log.d("Jarvis", "wake DRY-RUN: would have woken on: $heard")
                            else text = "hey buddy"
                        }
                    }
                }
                if (text == "hey buddy" && System.currentTimeMillis() - lastTrigger > 8000) {
                    lastTrigger = System.currentTimeMillis()
                    r.reset()
                    Log.d("Jarvis", "wake word heard: $text")
                    acknowledge()
                    holdUntil = System.currentTimeMillis() + 15_000 // the conversation is starting; liveActive takes over from here
                    val inApp = onWakeInApp
                    if (inApp == null) try { JarvisAccessibilityService.instance?.captureContext() } catch (e: Exception) { Log.w("Jarvis", "screen capture failed", e) }
                    audio.release(); audio = null
                    // If Buddy's own screen is already open, start the conversation right there instead of opening a panel over it.
                    Handler(Looper.getMainLooper()).post { if (inApp != null) inApp() else openAssistant() }
                }
            }
        } catch (e: Exception) {
            Log.e("Jarvis", "wake word loop failed", e)
        } finally {
            audio?.release(); rec?.close(); stopRec?.close(); model?.close()
        }
    }

    // The microphone source used for listening is quiet on this phone: recorded real speech came out at about a quarter of the level of a
    // normal recording, and "Hey buddy" was misheard until the audio was made louder. The offline model hears much better with this boost.
    private fun amplify(buf: ShortArray, n: Int) {
        for (i in 0 until n) buf[i] = (buf[i] * VOICE_GAIN).toInt().coerceIn(-32768, 32767).toShort()
    }

    // An instant chime so you know it woke up, before the live conversation has finished connecting and says your name.
    private fun acknowledge() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 200)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 600)
        } catch (e: Exception) { Log.w("Jarvis", "chime failed", e) }
    }

    private fun openAssistant() {
        val svc = JarvisInteractionService.instance
        if (svc != null) svc.showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        else startActivity(Launch.listenIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val ACTION_STOP = "com.sreeram.jarvis.STOP_WAKE"
        private const val CHANNEL = "wake"
        private const val MIN_SOUND_LEVEL = 80.0    // below this the room counts as quiet
        private const val MAX_SOUND_LEVEL = 450.0   // however noisy the room, speech louder than this is always listened to
        private const val GATE_HANGOVER_MS = 1500L   // keep listening this long after the last sound so phrase endings are heard
        // True while the Buddy screen owns the microphone.
        @Volatile var suspended = false // Buddy's own screen is using the microphone
        private const val VOICE_GAIN = 3
        @Volatile var holdUntil = 0L
        @Volatile var onWakeInApp: (() -> Unit)? = null // set while Buddy's screen is in front
        // True while Buddy's own screen is speaking; the service then listens only for "stop".
        @Volatile var activitySpeaking = false
        // True while a Gemini Live conversation is using the microphone.
        @Volatile var liveActive = false
        // Set after "Train my voice" so the listener picks up the new phrases.
        @Volatile var reloadGrammar = false
        @Volatile var onStop: (() -> Unit)? = null

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, WakeWordService::class.java))
    }
}
