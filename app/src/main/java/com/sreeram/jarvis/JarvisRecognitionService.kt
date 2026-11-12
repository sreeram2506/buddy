package com.sreeram.jarvis

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

// Required by Android for a voice-interaction service to be selectable as the assistant.
// Buddy does its own listening in MainActivity, so this only reports "not supported".
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onStopListening(listener: Callback) {}
    override fun onCancel(listener: Callback) {}
}
