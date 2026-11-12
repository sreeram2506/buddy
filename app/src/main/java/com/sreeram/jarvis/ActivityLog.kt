package com.sreeram.jarvis

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A plain-language record of what Buddy did on your behalf ("opened WhatsApp", "drafted a message to Amma"),
// so you can always check. It never stores message text, typed text or codes, only what kind of thing was done and to whom.
object ActivityLog {
    private const val MAX = 300
    private fun file(c: Context) = File(c.filesDir, "activity.jsonl")

    // Tools that only look at things or are part of every step; logging them would bury the useful lines.
    private val quiet = setOf("read_screen", "screen_context", "look_at_screen", "scroll", "approve_action", "stop_sharing", "list_reminders",
        "list_routines", "list_show", "read_notes", "recording_status", "away_status", "battery_status", "end_conversation")

    private fun say(name: String, a: JSONObject, result: String): String? = when (name) {
        "open_app" -> "Opened ${a.optString("name")}"
        "call_number" -> "Prepared a call to ${a.optString("name", a.optString("number"))}; waiting for your OK"
        "confirm_call" -> if (result.startsWith("Calling")) "Placed the call" else "Tried to place a call, but it did not start"
        "compose_sms", "draft_whatsapp" -> "Drafted a message to ${a.optString("contact", a.optString("number"))}"
        "confirm_send_whatsapp", "confirm_send_reply", "approve_action" -> "Sent after your OK"
        "tap", "tap_xy" -> if (result.startsWith("NEEDS APPROVAL")) "Held a Send button until you approve" else "Tapped on screen"
        "type_text", "dictate_text" -> "Typed some text"
        "press_key" -> "Pressed ${a.optString("key")}"
        "play_music" -> "Played ${a.optString("query", a.optString("song"))}"
        "set_alarm" -> "Set an alarm"; "set_timer" -> "Started a timer"
        "set_reminder" -> "Set a reminder"
        "start_away" -> "Turned on Away mode"; "end_away" -> "Turned off Away mode"
        "driving_mode" -> if (a.optBoolean("on")) "Turned on Driving mode" else "Turned off Driving mode"
        "search_memory" -> "Searched your remembered messages"
        "web_search", "web_answer" -> "Looked something up"
        "navigate" -> "Started navigation"
        "answer_call" -> "Answered a call"; "end_call" -> "Ended a call"
        "reply_to_caller" -> "Declined a call and texted the caller"
        "prepare_sos", "confirm_sos" -> "Emergency help: ${if (name == "confirm_sos") "sent" else "prepared"}"
        "share_location" -> "Shared your location"
        "start_camera_view", "start_screen_share" -> "Started sharing ${if (name == "start_camera_view") "the camera" else "your screen"} with the AI"
        "start_recording" -> "Started recording a meeting"; "stop_recording" -> "Stopped recording"
        else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun tool(c: Context, name: String, args: JSONObject, result: String) {
        if (name in quiet) return
        val text = say(name, args, result) ?: return
        add(c, text, failed = result.startsWith("Error") || result.contains("not granted", true))
    }

    @Synchronized
    fun add(c: Context, text: String, failed: Boolean = false) {
        try {
            val f = file(c)
            f.appendText(JSONObject().put("t", System.currentTimeMillis()).put("m", text).put("f", failed).toString() + "\n")
            if (f.length() > 120_000) { val keep = f.readLines().takeLast(MAX); f.writeText(keep.joinToString("\n") + "\n") }
        } catch (e: Exception) { }
    }

    @Synchronized
    fun recent(c: Context, n: Int): List<String> {
        val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
        return try {
            file(c).readLines().takeLast(n).reversed().map { val o = JSONObject(it); "${fmt.format(Date(o.getLong("t")))}  ${if (o.optBoolean("f")) "⚠ " else ""}${o.getString("m")}" }
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized fun clear(c: Context) { file(c).delete() }
}
