package com.sreeram.jarvis

import android.service.voice.VoiceInteractionService

class JarvisInteractionService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); instance = this }
    override fun onShutdown() { instance = null; super.onShutdown() }

    companion object {
        @Volatile var instance: JarvisInteractionService? = null
    }
}
