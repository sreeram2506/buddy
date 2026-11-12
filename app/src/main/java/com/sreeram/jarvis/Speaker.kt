package com.sreeram.jarvis

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

// Shared text-to-speech so Buddy can speak without its screen open (announcements) and with a good voice.
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    @Volatile var speaking = false
    private val pending = ArrayDeque<String>()

    // Pick the voice for the language a reply is written in; fall back to English if that voice isn't installed.
    fun applyLocale(t: TextToSpeech, text: String) {
        val wanted = Lang.localeFor(text)
        if (t.isLanguageAvailable(wanted) >= TextToSpeech.LANG_AVAILABLE) pickBestVoice(t, wanted)
        else {
            android.util.Log.d("Jarvis", "no ${wanted.language} voice installed, using English")
            pickBestVoice(t, Locale("en", "IN"))
        }
    }

    fun pickBestVoice(t: TextToSpeech, locale: Locale = Locale.getDefault()) {
        t.language = locale
        val best = t.voices?.filter { it.locale.language == locale.language && !it.features.contains("notInstalled") }
            ?.maxByOrNull { it.quality }
        if (best != null) t.voice = best
        t.setSpeechRate(1.05f)
    }

    @Synchronized
    fun stop() {
        pending.clear()
        tts?.stop()
        speaking = false
    }

    @Synchronized
    fun speak(context: Context, text: String) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    if (status == TextToSpeech.SUCCESS) {
                        pickBestVoice(tts!!)
                        tts!!.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(id: String?) { speaking = true }
                            override fun onDone(id: String?) { speaking = false }
                            override fun onError(id: String?) { speaking = false }
                        })
                        ready = true
                        while (pending.isNotEmpty()) { val next = pending.removeFirst(); applyLocale(tts!!, next); tts!!.speak(next, TextToSpeech.QUEUE_ADD, null, "announce") }
                    }
                }
            }
        }
        if (ready) { applyLocale(tts!!, text); tts!!.speak(text, TextToSpeech.QUEUE_ADD, null, "announce") } else pending.addLast(text)
    }
}
