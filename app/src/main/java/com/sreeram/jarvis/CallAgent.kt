package com.sreeram.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// Answers a call for you in Away mode: picks up, says Buddy is the assistant, finds out who it is and what they need,
// and notes the message for you. The caller hears Buddy through the phone's speaker-and-mic, so it needs no special call access.
object CallAgent {
    private var live: LiveSession? = null
    private var number = ""
    private var message: String? = null
    private var urgent = false
    private var name = ""

    private fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    // ---- Auto mode: its own switch, separate from Away mode ----
    fun active(c: Context): Boolean {
        val until = prefs(c).getLong("auto_until", 0)
        return prefs(c).getBoolean("auto_mode", false) && (until == 0L || System.currentTimeMillis() < until)
    }

    // Calls you miss are forwarded to Buddy's phone number (the call server) using the standard carrier code for "forward when unanswered".
    // The number is set once with set_call_server_number; turning Auto mode off cancels it.
    fun serverNumber(c: Context) = prefs(c).getString("call_server_number", "") ?: ""
    fun setServerNumber(c: Context, n: String): String {
        val clean = n.filter { it.isDigit() || it == '+' }
        if (clean.length < 8) return "That doesn't look like a phone number."
        prefs(c).edit().putString("call_server_number", clean).apply()
        return "Buddy's call number is saved: $clean. Turn on Auto mode to start forwarding missed calls to it."
    }

    private fun ussd(c: Context, code: String, what: String) {
        try {
            c.getSystemService(android.telephony.TelephonyManager::class.java).sendUssdRequest(code, object : android.telephony.TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(tm: android.telephony.TelephonyManager, request: String, response: CharSequence) { Log.d("Jarvis", "auto mode: $what -> $response") }
                override fun onReceiveUssdResponseFailed(tm: android.telephony.TelephonyManager, request: String, failureCode: Int) { Log.d("Jarvis", "auto mode: $what failed ($failureCode)") }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) { Log.d("Jarvis", "auto mode: $what needs the Phone permission") }
    }

    fun start(c: Context, minutes: Int): String {
        val num = serverNumber(c)
        if (num.isNotEmpty()) ussd(c, "**61*$num**20#", "forward missed calls")
        val until = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        prefs(c).edit().putBoolean("auto_mode", true).putLong("auto_until", until).apply()
        notifyOn(c)
        val tail = if (minutes > 0) ", for $minutes minutes." else ", until you turn it off."
        return if (num.isEmpty()) "Auto mode is on, but Buddy's call number isn't set yet, so calls still ring normally. Tell me the number once and I'll save it."
               else "Auto mode is on. Calls you don't answer go to Buddy, who talks to the caller and texts you the message$tail"
    }

    fun stopMode(c: Context): String {
        prefs(c).edit().putBoolean("auto_mode", false).apply()
        if (serverNumber(c).isNotEmpty()) ussd(c, "##61#", "stop forwarding")
        c.getSystemService(android.app.NotificationManager::class.java).cancel(NOTE)
        return "Auto mode is off. Calls ring normally."
    }

    fun status(c: Context) = if (active(c)) "Auto mode is on." else "Auto mode is off."

    private const val NOTE = 7301
    private fun notifyOn(c: Context) {
        val nm = c.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("auto_mode", "Auto mode", android.app.NotificationManager.IMPORTANCE_LOW))
        val off = android.app.PendingIntent.getBroadcast(c, 7302, Intent(c, AutoOffReceiver::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTE, androidx.core.app.NotificationCompat.Builder(c, "auto_mode").setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Auto mode is on").setContentText("Buddy answers your calls and takes messages. Tap Turn off to stop.")
            .setOngoing(true).addAction(0, "Turn off", off).build())
    }

    // A call is ringing in Auto mode: after a few rings, if it is still ringing, Buddy picks up. Chosen contacts always ring through.
    fun onRinging(c: Context, num: String) {
        if (Away.isVip(c, num)) return
        Thread {
            Thread.sleep(prefs(c).getInt("auto_ring_seconds", 12) * 1000L)
            val tm = c.getSystemService(android.telephony.TelephonyManager::class.java)
            @Suppress("DEPRECATION")
            if (active(c) && CallReceiver.number == num && tm.callState == android.telephony.TelephonyManager.CALL_STATE_RINGING) answer(c, num)
        }.start()
    }
    fun busy() = live != null

    private val tools = JSONArray().put(JSONObject().put("name", "take_message").put("description",
        "Save what the caller wants Sreeram to know. Call it once you have their name and reason.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("caller_name", JSONObject().put("type", "string")).put("message", JSONObject().put("type", "string"))
            .put("urgent", JSONObject().put("type", "boolean"))).put("required", JSONArray().put("message"))))
        .put(JSONObject().put("name", "end_call").put("description", "Hang up, after you have said goodbye."))

    private fun prompt(c: Context): String {
        val me = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("my_name", "Sreeram") ?: "Sreeram"
        return """You are Buddy, $me's AI phone assistant. $me could not pick up, so you answered his call.
Start by saying so, plainly: "Hello, this is Buddy, $me's assistant. He can't come to the phone right now. May I take a message?" Never pretend to be $me or a person.
Speak the caller's language (English, Hindi or Telugu); if unsure, start in English. Keep every reply to one or two short sentences.
Find out who is calling and why. Once you have it, call take_message, tell them $me will get it, say goodbye, then call end_call.
Mark urgent=true only for a real emergency or if they say it is urgent.
Never share anything about $me (location, schedule, numbers, what he is doing), never agree to a payment, appointment or request, and never ask for or accept OTPs, passwords or bank details; say $me will call back himself.
If the caller is silent or it is a sales or robocall, say you will pass it on and end the call. Stop after about three minutes."""
    }

    // Picks up the ringing call. Returns false if it could not.
    fun answer(c: Context, num: String): Boolean {
        // Experimental: Android silences every app's microphone during a phone call, so Buddy cannot hear the caller on this phone.
        if (live != null || !prefs(c).getBoolean("auto_answer_experimental", false)) return false
        val tm = c.getSystemService(TelecomManager::class.java)
        try { tm.acceptRingingCall() } catch (e: SecurityException) { Log.d("Jarvis", "call agent: cannot answer: ${e.message}"); return false }
        number = num; message = null; urgent = false
        name = Contacts.name(c, num) ?: ""
        Log.d("Jarvis", "call agent: answered ${Away.key(num)}")
        Handler(Looper.getMainLooper()).postDelayed({ begin(c) }, 800)
        return true
    }

    private fun begin(c: Context) {
        val app = c.applicationContext
        WakeWordService.liveActive = true
        val session = LiveSession(app, PhoneActions(app), prompt(app), object : LiveSession.Listener {
            override fun onReady() { Log.d("Jarvis", "call agent: talking") }
            override fun onUserText(text: String) { Log.d("Jarvis", "call agent: caller said: $text") }
            override fun onBuddyText(text: String) { Log.d("Jarvis", "call agent: buddy said: $text") }
            override fun onGoodbye() {}
            override fun onClosed(reason: String, failed: Boolean, wasReady: Boolean) = finish(app, reason)
        }, tools, "[The caller has just picked up and the call is connected.] Say your opening line now.") { tool, args ->
            when (tool) {
                "take_message" -> {
                    message = args.optString("message"); urgent = args.optBoolean("urgent")
                    args.optString("caller_name").takeIf { it.isNotBlank() && name.isBlank() }?.let { name = it }
                    "Saved. Now say goodbye and call end_call."
                }
                "end_call" -> { Session.stopLiveAfterTurn = true; "Ending after you finish speaking." }
                else -> "That is not available on a call."
            }
        }
        live = session
        session.start()
        // Safety net: never stay on a call longer than 3 minutes.
        Handler(Looper.getMainLooper()).postDelayed({ if (live === session) session.stop() }, 180_000)
    }

    // The call is over (the caller hung up, or Buddy finished): hang up if needed, then tell you what was said.
    fun finish(c: Context, reason: String) {
        if (number.isEmpty()) return
        live = null
        WakeWordService.liveActive = false
        try { c.getSystemService(TelecomManager::class.java).endCall() } catch (e: Exception) { }
        val who = name.ifBlank { Away.key(number) }
        Log.d("Jarvis", "call agent: finished ($reason), message=$message")
        val m = message
        Away.logCaller(c, number, m ?: "picked up but left no message", urgent)
        if (urgent) Away.notifySummary(c, "Urgent call from $who", "$who: $m", popup = false)
        number = ""
    }

    fun onCallEnded(c: Context) { if (number.isNotEmpty()) live?.stop() }
}

class AutoOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { CallAgent.stopMode(context) }
}
