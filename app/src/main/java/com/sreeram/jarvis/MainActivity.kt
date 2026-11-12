package com.sreeram.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var langSpinner: Spinner
    private lateinit var talk: TextView
    private lateinit var tts: TextToSpeech
    private lateinit var recognizer: SpeechRecognizer
    private val store by lazy { ChatStore(this) }
    private var conversation = false   // keep listening after each reply until it goes quiet
    private var listening = false
        set(v) { field = v; WakeWordService.suspended = v }
    private var visible = false
    private var syncingLang = false
    private var live: LiveSession? = null
    private lateinit var orb: OrbView
    private lateinit var chipsScroll: View
    private lateinit var liveSwitch: Switch
    // Keeps the screen on while a request runs, even when Buddy is driving another app. The timeout is a safety net.
    @Suppress("DEPRECATION")
    private val taskLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "buddy:task")
    }
    private val actions by lazy { PhoneActions(this) }
    private val brain by lazy { Brain(BuildConfig.API_KEY, BuildConfig.GROQ_KEY, actions) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), bars.bottom + dp(16))
            insets
        }
        thread { Housekeeping.run(applicationContext) }
        Health.healAccessibility(this)
        chat = findViewById(R.id.chat)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        langSpinner = findViewById(R.id.lang)
        talk = findViewById(R.id.talk)
        orb = findViewById(R.id.orb)
        orb.stateProvider = {
            val t = talk.text.toString()
            when {
                t.startsWith("Connecting") || t.startsWith("Thinking") || t.startsWith("Summarising") -> OrbView.State.THINKING
                Session.liveSpeaking || Speaker.speaking || t.startsWith("Speaking") -> OrbView.State.SPEAKING
                t.startsWith("Listening") || t.startsWith("Live") || t.startsWith("Stop recording") -> OrbView.State.LISTENING
                else -> OrbView.State.IDLE
            }
        }
        orb.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); talk.performClick() }
        chipsScroll = findViewById(R.id.chips_scroll)
        val chips = findViewById<LinearLayout>(R.id.chips)
        for (q in listOf("What's the weather?", "Read my messages", "Remind me in 1 hour", "Turn on Away mode", "What did I miss?")) {
            chips.addView(TextView(this).apply {
                text = q; textSize = 14f; setTextColor(0xFFC5D0DB.toInt()); setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(14), dp(9), dp(14), dp(9)); isClickable = true
                setOnClickListener { input.setText(q); sendTyped() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        Session.moveToBack = { runOnUiThread { moveTaskToBack(true) } }
        Session.startInterpreter = { a, b -> restartLive(brain.interpreterPrompt(a, b), declsFor(setOf("stop_interpreter"))) }
        Session.stopInterpreter = { restartLive(null, null) }
        WakeWordService.onStop = {
            conversation = false
            listening = false
            tts.stop()
            talk.text = "Talk to Buddy"
        }

        store.all().takeLast(60).forEach { addBubble(it.text, it.user, save = false) } // a long history would slow startup and scrolling
        findViewById<TextView>(R.id.settings).setOnClickListener { startActivity(Intent(this, BuddySettingsActivity::class.java)) }
        findViewById<TextView>(R.id.clear).setOnClickListener { store.clear(); chat.removeAllViews() }

        langSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Lang.options.map { it.second })
        syncLangSpinner()
        langSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!syncingLang) Lang.set(this@MainActivity, Lang.options[pos].first)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val wake = findViewById<Switch>(R.id.wake)
        wake.isChecked = prefs.getBoolean("wake", false)
        wake.setOnClickListener {
            val on = wake.isChecked
            prefs.edit().putBoolean("wake", on).apply()
            if (on) enableWake() else WakeWordService.stop(this)
        }
        if (wake.isChecked) enableWake()

        liveSwitch = findViewById(R.id.live)
        liveSwitch.isChecked = prefs.getBoolean("live", true)
        liveSwitch.setOnClickListener { prefs.edit().putBoolean("live", liveSwitch.isChecked).apply() }
        findViewById<Button>(R.id.train).setOnClickListener { startTraining() }

        tts = TextToSpeech(this, this)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(listener)
        talk.setOnClickListener {
            when {
                Recorder.active -> { talk.text = "Summarising..."; thread { Recorder.stopAndSummarize(this).let { sum -> runOnUiThread { addBubble(sum, user = false); talk.text = "Talk to Buddy" } } } }
                live != null -> endLive()
                listening -> stopConversation()
                else -> beginConversation()
            }
        }
        findViewById<Button>(R.id.send).setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
        if (Launch.isTrusted(this, intent)) talk.post { beginConversation() }
        if (intent.getBooleanExtra("train", false)) talk.post { startTraining() }
        if (BuildConfig.API_KEY.isBlank()) note("Set GEMINI_API_KEY in local.properties and rebuild.")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("train", false)) startTraining()
        if (Launch.isTrusted(this, intent)) beginConversation()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        Speaker.pickBestVoice(tts)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { WakeWordService.activitySpeaking = true }
            override fun onError(id: String?) { WakeWordService.activitySpeaking = false }
            override fun onStop(id: String?, interrupted: Boolean) { WakeWordService.activitySpeaking = false }
            override fun onDone(id: String?) {
                WakeWordService.activitySpeaking = false
                if (conversation && visible) talk.postDelayed({ if (conversation && visible && !listening) startListening() }, 300)
            }
        })
    }

    private fun speak(text: String, id: String) {
        Speaker.applyLocale(tts, text)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun syncLangSpinner() {
        syncingLang = true
        langSpinner.setSelection(Lang.options.indexOfFirst { it.first == Lang.current(this) }.coerceAtLeast(0))
        syncingLang = false
    }

    // ---- chat display ----

    private fun addBubble(text: String, user: Boolean, save: Boolean = true) {
        chipsScroll.visibility = View.GONE
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bubble_text))
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setLineSpacing(0f, 1.1f)
            setBackgroundResource(if (user) R.drawable.bubble_user else R.drawable.bubble_buddy)
            maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
            setTextIsSelectable(true)
        }
        chat.addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (user) Gravity.END else Gravity.START
            topMargin = dp(10)
        })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        if (save) store.add(user, text)
    }

    private fun note(text: String) {
        chat.addView(TextView(this).apply { this.text = text; textSize = 13f; alpha = 0.6f; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- conversation flow ----

    private fun declsFor(names: Set<String>): JSONArray {
        val out = JSONArray()
        for (i in 0 until actions.declarations.length()) actions.declarations.getJSONObject(i).takeIf { it.getString("name") in names }?.let { out.put(it) }
        return out
    }

    private fun restartLive(system: String?, decls: JSONArray?) = runOnUiThread {
        live?.stop()
        talk.postDelayed({ startLive(system, decls, greet = false) }, 700)
    }

    private fun beginConversation() {
        if (live != null) return
        if (Recorder.active) {
            // The microphone is busy recording; one tap on the button ends it and starts the summary.
            note("A recording is in progress. Tap the button to stop it and get the summary.")
            talk.text = "Stop recording & summarize"
            return
        }
        if (liveSwitch.isChecked && BuildConfig.API_KEY.isNotBlank()) { startLive(); return }
        conversation = true
        startListening()
    }

    // ---- live voice (Gemini Live) ----

    private fun startLive(system: String? = null, decls: JSONArray? = null, greet: Boolean = true) {
        if (missingPermissions().isNotEmpty()) { ActivityCompat.requestPermissions(this, missingPermissions().toTypedArray(), 1); return }
        tts.stop()
        talk.text = "Connecting..."
        taskLock.acquire(15 * 60 * 1000L)
        WakeWordService.liveActive = true
        live = LiveSession(this, actions, system ?: brain.liveSystemPrompt(), object : LiveSession.Listener {
            override fun onReady() = runOnUiThread { talk.text = "Live - tap to end" }
            override fun onUserText(text: String) = runOnUiThread { addBubble(text, user = true) }
            override fun onBuddyText(text: String) = runOnUiThread { addBubble(text, user = false); syncLangSpinner() }
            override fun onGoodbye() = runOnUiThread { talk.postDelayed({ finishAndRemoveTask() }, 700) }
            override fun onClosed(reason: String, failed: Boolean, wasReady: Boolean) = runOnUiThread {
                live = null
                WakeWordService.liveActive = false
                if (taskLock.isHeld) taskLock.release()
                talk.text = "Talk to Buddy"
                android.util.Log.d("Jarvis", "live ended: $reason failed=$failed ready=$wasReady")
                if (failed && !wasReady) {
                    note("Live voice isn't available right now, using standard voice.")
                    conversation = true
                    startListening()
                } else if (failed) note("Live conversation ended unexpectedly. Tap to start again.")
            }
        }, decls ?: actions.declarations, if (greet) greetingFor() else null).also { it.start() }
    }

    private fun endLive() { live?.stop() }

    // Buddy answers back by name as soon as a conversation opens, so you know it heard you.
    private fun greetingFor(): String {
        val name = getSharedPreferences("jarvis", MODE_PRIVATE).getString("my_name", "Sreeram") ?: "Sreeram"
        return "[The conversation has just started.] Greet me by my name, $name, in two or three words, for example \"Yes, $name?\", " +
            "and then wait for my request. Do not say anything else."
    }

    // ---- "Train my voice": learn how you say Hey buddy ----

    private fun startTraining() {
        if (live != null) endLive()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 3); return
        }
        val dialog = AlertDialog.Builder(this).setTitle("Train my voice").setMessage("Getting ready...")
            .setCancelable(false).setNegativeButton("Stop") { _, _ -> VoiceTrainer.cancel() }.show()
        thread {
            VoiceTrainer.run(applicationContext, object : VoiceTrainer.Ui {
                override fun say(text: String) { runOnUiThread { dialog.setMessage(text) } }
                override fun done(summary: String) {
                    runOnUiThread { dialog.setMessage(summary); dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Close"; dialog.setCancelable(true) }
                }
            })
        }
    }

    // "Bye buddy": say goodbye, end the conversation and close the screen.
    private fun goodbye() {
        conversation = false
        listening = false
        talk.text = "Bye!"
        tts.speak("Bye!", TextToSpeech.QUEUE_FLUSH, null, "bye")
        talk.postDelayed({ finishAndRemoveTask() }, 900)
    }

    private fun stopConversation() {
        conversation = false
        listening = false
        recognizer.cancel()
        talk.text = "Talk to Buddy"
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        endLive()
        if (listening) recognizer.cancel()
        listening = false
        conversation = false
        tts.stop()
        handle(text, spoken = false)
    }

    // Runs a request from either speech or typing. Typed requests get a written answer only.
    private fun handle(said: String, spoken: Boolean) {
        if (Regex("^(bye|by|goodbye|good bye)( bye)?(,? (buddy|body))?$", RegexOption.IGNORE_CASE).matches(said.trim().trimEnd('.', '!'))) {
            goodbye(); return
        }
        addBubble(said, user = true)
        talk.text = "Thinking..."
        thread {
            taskLock.acquire(3 * 60 * 1000L)
            val reply = try { brain.ask(said) } catch (e: Exception) { "Sorry, something went wrong." }
            finally { if (taskLock.isHeld) taskLock.release() }
            runOnUiThread {
                addBubble(reply, user = false)
                syncLangSpinner()
                if (Session.endRequested) {
                    Session.endRequested = false
                    conversation = false
                    speak(reply, "buddy")
                    talk.postDelayed({ finishAndRemoveTask() }, 2500)
                } else if (spoken) {
                    talk.text = if (conversation) "Speaking... (tap to talk)" else "Talk to Buddy"
                    speak(reply, "buddy")
                } else talk.text = "Talk to Buddy"
            }
        }
    }

    private fun missingPermissions(): List<String> = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.SEND_SMS, Manifest.permission.READ_CALENDAR, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA, Manifest.permission.RECEIVE_SMS)
        .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun startListening() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
            return
        }
        tts.stop()
        listening = true
        talk.text = "Listening... (tap to stop)"
        val code = Lang.current(this)
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Lang.tag(code))
            if (code == "auto" && android.os.Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("en-IN", "hi-IN", "te-IN"))
            }
        })
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            listening = false
            val said = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (said.isNullOrBlank()) { talk.text = "Talk to Buddy"; return }
            handle(said, spoken = true)
        }
        override fun onError(error: Int) {
            android.util.Log.d("Jarvis", "speech error $error")
            listening = false
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            conversation = false
            talk.text = "Talk to Buddy"
            if (!quiet && error != SpeechRecognizer.ERROR_CLIENT) note("(speech error $error)")
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 3) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginConversation()
    }

    private fun enableWake() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            WakeWordService.start(this)
    }

    override fun onResume() {
        super.onResume(); visible = true; syncLangSpinner()
        WakeWordService.onWakeInApp = { if (live == null && !listening) beginConversation() }
        // Settings may have changed these while this screen was in the background.
        getSharedPreferences("jarvis", MODE_PRIVATE).let { p ->
            liveSwitch.isChecked = p.getBoolean("live", true)
            if (p.getBoolean("wake", false)) enableWake()
            val parts = ArrayList<String>()
            if (p.getBoolean("wake", false)) parts.add("Hey Buddy on")
            if (Away.active(this)) parts.add("Away on")
            if (Driving.active(this)) parts.add("Driving on")
            findViewById<TextView>(R.id.status).text = if (parts.isEmpty()) "Tap Talk or open Settings" else parts.joinToString(" · ")
        }
        // An Away summary that arrived while Buddy was closed appears here as a chat message.
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        prefs.getString("pending_summary", null)?.let {
            prefs.edit().remove("pending_summary").apply()
            addBubble("While you were away:\n$it", user = false)
            getSystemService(android.app.NotificationManager::class.java).cancel(32)
        }
    }

    override fun onPause() {
        visible = false
        WakeWordService.onWakeInApp = null
        WakeWordService.suspended = false
        if (listening) { recognizer.cancel(); listening = false; talk.text = "Talk to Buddy" }
        super.onPause()
    }

    override fun onDestroy() {
        live?.stop()
        Session.moveToBack = null
        Session.startInterpreter = null
        Session.stopInterpreter = null
        WakeWordService.onStop = null; WakeWordService.activitySpeaking = false
        recognizer.destroy(); tts.shutdown(); super.onDestroy()
    }

    companion object {
        const val EXTRA_AUTO_LISTEN = "auto_listen"
    }
}
