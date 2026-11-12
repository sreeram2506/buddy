package com.sreeram.jarvis

import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log

class JarvisSession(context: Context) : VoiceInteractionSession(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var gotAssist = false
    private var gotShot = false
    private var launched = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        gotAssist = false; gotShot = false; launched = false
        Log.d("Jarvis", "session shown, flags=$showFlags")
        if (args?.getBoolean("summary_only") == true) {
            // Just show the Away summary: no screen capture to wait for, and the microphone stays off.
            launched = true
            startAssistantActivity(Intent(context, MainActivity::class.java))
            handler.postDelayed({ hide() }, 1500)
            return
        }
        // Wait for Android to hand over the screen contents before covering the app with Buddy's own screen.
        handler.postDelayed({ launch() }, 1500)
        handler.postDelayed({ hide() }, 4000)
    }

    private fun launch() {
        if (launched) return
        launched = true
        startAssistantActivity(Launch.listenIntent(context))
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        val sb = StringBuilder()
        val s = state.assistStructure
        s?.let { for (i in 0 until it.windowNodeCount) walk(it.getWindowNodeAt(i).rootViewNode, sb) }
        if (s != null) {
            ScreenContext.text = sb.toString().take(4000)
            ScreenContext.time = System.currentTimeMillis()
        }
        Log.d("Jarvis", "assist: structure=${s != null} windows=${s?.windowNodeCount} activity=${s?.activityComponent?.shortClassName} chars=${sb.length}")
        gotAssist = true
        if (gotShot) launch()
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot != null) {
            ScreenContext.screenshot = screenshot
            ScreenContext.time = System.currentTimeMillis()
        }
        Log.d("Jarvis", "assist screenshot: ${screenshot?.width}x${screenshot?.height}")
        gotShot = true
        if (gotAssist) launch()
    }

    private fun walk(node: AssistStructure.ViewNode?, sb: StringBuilder) {
        if (node == null || sb.length > 4000) return
        val t = node.text?.toString()
        if (!t.isNullOrBlank() && node.inputType == 0) sb.append(t).append('\n')
        else node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), sb)
    }
}
