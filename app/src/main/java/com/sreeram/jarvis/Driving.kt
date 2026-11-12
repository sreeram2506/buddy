package com.sreeram.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat

// Driving mode: new messages are read aloud, calls are announced, and a caller you cannot answer gets a "driving, will call back" text.
// Buddy still asks before sending anything, so nothing goes out by mistake.
object Driving {
    private const val NOTE = 7401
    private fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    fun active(c: Context) = prefs(c).getBoolean("driving", false)
    fun replyText(c: Context) = prefs(c).getString("driving_text", null)?.takeIf { it.isNotBlank() }
        ?: "I'm driving right now, so I can't pick up. I'll call you back as soon as I stop. – Buddy, ${prefs(c).getString("my_name", "Sreeram")}'s assistant"

    fun start(c: Context): String {
        prefs(c).edit().putBoolean("driving", true).apply()
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("driving", "Driving mode", NotificationManager.IMPORTANCE_LOW))
        val off = PendingIntent.getBroadcast(c, 7402, Intent(c, DrivingOffReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTE, NotificationCompat.Builder(c, "driving").setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Driving mode is on").setContentText("Buddy reads your messages aloud and texts callers you can't answer.")
            .setOngoing(true).addAction(0, "Turn off", off).build())
        return "Driving mode is on. I'll read new messages aloud, tell you who's calling, and text callers you miss. Say \"Hey Buddy\" to reply by voice, and I'll check with you before sending."
    }

    fun stop(c: Context): String {
        prefs(c).edit().putBoolean("driving", false).apply()
        c.getSystemService(NotificationManager::class.java).cancel(NOTE)
        return "Driving mode is off."
    }

    // New chat message while driving: read it out (short), without anyone having to touch the phone.
    fun onMessage(c: Context, sender: String, text: String) {
        if (!active(c)) return
        Speaker.speak(c, "Message from $sender: ${text.take(220)}")
    }

    // A call is ringing while driving: if it is still ringing after a while, the caller is texted that you are driving.
    fun onRinging(c: Context, number: String) {
        if (!active(c) || Away.isVip(c, number)) return
        Thread {
            Thread.sleep(15_000)
            @Suppress("DEPRECATION")
            val ringing = c.getSystemService(TelephonyManager::class.java).callState == TelephonyManager.CALL_STATE_RINGING
            if (active(c) && ringing && CallReceiver.number == number) Away.textCaller(c, number, replyText(c))
        }.start()
    }
}

class DrivingOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Driving.stop(context) }
}
