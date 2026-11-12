package com.sreeram.jarvis

import android.content.Context
import android.content.Intent
import java.util.UUID

// Only Buddy's own buttons (widget, tile, wake word) may tell Buddy to start listening. Without this, any other app
// could open Buddy with the "auto listen" extra and switch the microphone on.
object Launch {
    const val EXTRA_TOKEN = "t"

    fun token(c: Context): String {
        val prefs = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        return prefs.getString("launch_token", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("launch_token", it).apply() }
    }

    fun listenIntent(c: Context): Intent = Intent(c, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_AUTO_LISTEN, true).putExtra(EXTRA_TOKEN, token(c))

    fun isTrusted(c: Context, i: Intent) = i.getBooleanExtra(MainActivity.EXTRA_AUTO_LISTEN, false) && i.getStringExtra(EXTRA_TOKEN) == token(c)
}
