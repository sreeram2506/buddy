package com.sreeram.jarvis

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Set by the end_conversation tool; the screen closes once Buddy has said goodbye.
object Session {
    @Volatile var endRequested = false
    @Volatile var liveRunning = false
    @Volatile var liveSpeaking = false // Buddy's voice is playing in a live conversation (drives the orb)
    @Volatile var frameSink: ((ByteArray) -> Unit)? = null // while a live conversation runs, sends a picture to the model
    @Volatile var stopLiveAfterTurn = false // end the live conversation once Buddy finishes speaking
    @Volatile var startInterpreter: ((String, String) -> Unit)? = null
    @Volatile var stopInterpreter: (() -> Unit)? = null
    @Volatile var moveToBack: (() -> Unit)? = null // set by the main screen so tools can step Buddy aside
}

object Turns {
    @Volatile var count = 0
    @Volatile var lastText = ""

    // \\b doesn't work after Hindi/Telugu vowel signs, so word ends are matched with a lookahead instead.
    private const val END = "(?=$|[\\s,.!?।])"
    private val yes = Regex("^(yes|yeah|yep|yup|ok|okay|sure|confirm|confirmed|go ahead|do it|please do|affirmative|correct|haan|haa|han|hanji|ha ji|avunu|sare|ठीक है|हाँ|हां|कर दो|करो|भेज दो|అవును|సరే|ఔను)$END", RegexOption.IGNORE_CASE)
    private val yesExact = Regex("^(call|call her|call him|call them|go|send|send it|send now)$", RegexOption.IGNORE_CASE)
    private val no = Regex("(^|[\\s,.!?])(no|nope|don't|dont|do not|not|cancel|stop|wait|nahi|nahin|vaddu|नहीं|मत|వద్దు|కాదు)$END", RegexOption.IGNORE_CASE)

    // Confirmations are enforced here, not just in the AI's instructions: the user's own latest message must say yes.
    fun affirmative(): Boolean {
        val t = lastText.trim().trimEnd('.', '!', '?')
        return (yes.containsMatchIn(t) || yesExact.matches(t)) && !no.containsMatchIn(t)
    }
}

private const val CHAT_RULES = "In chat screens, a line marked [YOU SENT THIS] is something the user wrote; [THEY SENT THIS] is something the other person wrote. Never mix them up when reading or summarising a conversation, and say who said what. " +
    "Only tell the user a message was sent if the tool result confirms it (a delivery status or the message visible in the chat); if the result says pending, not visible or unconfirmed, say exactly that."

class Brain(private val apiKey: String, private val groqKey: String, private val actions: PhoneActions) {
    private class Unavailable(message: String) : Exception(message)
    private var screenMode = false
    private val failed = mutableSetOf<String>()

    private val history = JSONArray()
    private val system = "You are Buddy, a voice assistant that can control the user's Android phone. " +
        "Be proactive and resourceful. Understand what the user means from loose, casual or mixed English, Hindi and Telugu speech, even when speech recognition garbled it (they may call you Jarvis or Buddy). " +
        "Never wait for exact commands: work out the goal and do it. Do not say you can't do something before you have tried. " +
        "If no dedicated tool fits, operate the phone the way a person would (open the app or Settings, read the screen, tap through, scroll, read the result) or look it up with web_answer. " +
        "Chain as many steps as needed without asking permission. Ask a question only when something essential is truly missing, " +
        "or before anything that spends money, sends a message, deletes data or places a call. " +
        "After finishing, say what you found or did in one short sentence. " +
        "Use the provided tools to do things on the phone. For anything without a dedicated tool, " +
        "drive the screen: open the app, call read_screen, then tap, type_text, scroll or press_key, " +
        "and call read_screen again after each step to check the result. The Buddy app itself is on screen when " +
        "a conversation starts, so open the target app or press home before reading the screen. " +
        "For incoming calls use who_is_calling, answer_call, end_call or reply_to_caller. " +
        "To read messages use read_messages. " +
        "To call or text a person by name, first use find_contact, then call_number or compose_sms; " +
        "if several contacts match, ask which one. " +
        "Before anything that spends or sends money, buys something, deletes data, posts publicly, or sends " +
        "a message or email the user did not dictate, stop and ask the user to confirm in one short question. " +
        "Never type or read out passwords, PINs or OTPs unless the user dictated them. " +
        "If you cannot complete a task, say what blocked you. " +
        "For questions about what is on the user's screen, use look_at_screen or screen_context, not read_screen. " +
        "For current facts, news or scores use web_answer; for weather use get_weather. " +
        "To reply to a message notification use draft_notification_reply and wait for confirmation before confirm_send_reply. " +
        "For an emergency use prepare_sos, ask the user to confirm, then confirm_sos. " +
        "To call someone: find_contact, then call_number (this only prepares the call), read the name back and ask the user to confirm, and only after they say yes call confirm_call. " +
        "If a tool answers NEEDS APPROVAL, nothing has happened yet: tell the user exactly what will be sent or paid and ask them to confirm; call approve_action only after they say yes in a new message. Never try to get around it (for example by tapping Send another way). " +
        "To message someone on WhatsApp use draft_whatsapp, read the draft back and ask the user to confirm; only after they say yes call confirm_send_whatsapp. " +
        "To type something into the app the user is in, use dictate_text (and send=true to press enter). " +
        "When the user describes a routine, save it with save_routine, passing the steps as a JSON string like [{\"tool\":\"set_volume\",\"args\":{\"percent\":20}}]; run it with run_routine. " +
        "Use save_note when asked to take a note, and remember_fact when asked to remember something about the user. " +
        "Spoken replies: one to three short sentences, no markdown."

    fun interpreterPrompt(a: String, b: String): String =
        "You are a live interpreter between $a and $b. Whenever you hear $a, speak the translation in $b. Whenever you hear $b, speak the translation in $a. " +
        "Translate faithfully and completely in the first person, as if you were the speaker. Add no comments, greetings, explanations or answers of your own, " +
        "and never refuse to translate ordinary speech. Speak at a natural pace. Only if someone says 'stop interpreting' or 'exit interpreter mode', call stop_interpreter."

    fun liveSystemPrompt(): String = systemPrompt() + " " + actions.speechHints() + " You are in a live voice conversation. Talk naturally and warmly, like a helpful friend: " +
        "short sentences, no lists, no markdown, no reading out symbols or URLs. If the user interrupts, stop and listen. " +
        "To see what the user is pointing the camera at, use start_camera_view; to see their screen, use start_screen_share. Then answer from what you see, and call stop_sharing when you are done. " +
        "Do not narrate your tool use; just do it and give a brief natural confirmation. " +
        "For any job that needs operating apps or settings (checking battery usage, changing an app's setting, finding something inside an app), say you are on it and do it yourself with the screen tools: " +
        "open_app, then read_screen, then tap, type_text, scroll or press_key, and read_screen again after each step until you reach the answer. Do not hand the job back to the user. " +
        CHAT_RULES + " " +
        "To find something someone said or sent in the past (a number, address, plan, what Dad said about something), use search_memory. " +
        (if (actions.drivingOn()) "Driving mode is ON: the user's hands are busy. Keep every answer to one short sentence, read messages aloud, offer to reply by voice, and still ask them to confirm before anything is sent. " else "") +
        "When the user says goodbye, bye, see you, that's all, or asks you to close or quit, say a short farewell and call end_conversation."

    private fun systemPrompt(): String {
        val facts = actions.facts()
        val base = system + " " + actions.languageRule() + " " + CHAT_RULES
        return if (facts.isEmpty()) base else base + " Known facts about the user: " + facts.joinToString("; ") + "."
    }

    // Sending every tool on every request wastes tokens and trips rate limits, so each request gets only the
    // always-on screen tools plus the groups its wording (and the last few things the user said) points to.
    private val recentUser = mutableListOf<String>()
    private var activeTools = JSONArray()
    private val alwaysOn = setOf("read_screen", "tap", "tap_xy", "type_text", "scroll", "press_key", "open_app", "web_search", "open_url", "navigate", "set_flashlight", "set_language", "end_conversation", "approve_action")
    private val toolGroups = listOf(
        Regex("call|text|message|msg|sms|whatsapp|contact|answer|decline|reject|caller|reply|announce|dial|tell|send|read|yes|confirm") to
            setOf("find_contact", "call_number", "confirm_call", "compose_sms", "who_is_calling", "answer_call", "end_call", "reply_to_caller", "read_messages", "announce_messages_from", "draft_whatsapp", "confirm_send_whatsapp"),
        Regex("volume|music|song|play|pause|next|previous|louder|quiet|sound|spotify|youtube|track|mute|skip") to
            setOf("media_control", "set_volume", "adjust_volume", "play_music"),
        Regex("note|remember|forget|write|memory") to setOf("save_note", "read_notes", "remember_fact", "forget_fact"),
        Regex("remind|alarm|timer|wake me|schedule|minutes|hour") to setOf("set_reminder", "list_reminders", "cancel_reminder", "set_timer", "set_alarm"),
        Regex("weather|news|score|latest|who |what |when |where |how |price|briefing|morning|calendar|event|battery|today|tomorrow|time|why") to
            setOf("web_answer", "get_weather", "morning_briefing", "get_calendar", "battery_status", "screen_context", "look_at_screen"),
        Regex("screen|translate|summar|this page|this|look at") to setOf("screen_context", "look_at_screen"),
        Regex("brightness|disturb|dnd|setting|wifi|bluetooth|hotspot|airplane|nfc|display") to setOf("set_brightness", "set_do_not_disturb", "open_settings"),
        Regex("show you|look at this|what do you see|see this|see my|share my screen|screen share|point the camera|camera view") to setOf("start_camera_view", "start_screen_share", "stop_sharing"),
        Regex("camera|photo|picture|what is this|what's this|identify|scan|snap") to setOf("look_with_camera"),
        Regex("miss|notification|unread|digest|summar|reply|respond") to setOf("notification_digest", "draft_notification_reply", "confirm_send_reply"),
        Regex("location|where am i|where i am|sos|emergency|help|trouble|danger|share|yes|confirm") to
            setOf("where_am_i", "share_location", "set_emergency_contact", "prepare_sos", "confirm_sos", "confirm_send_reply"),
        Regex("routine|when i say|good night|bedtime|leaving home") to setOf("save_routine", "run_routine", "list_routines", "delete_routine"),
        Regex("list|shopping|todo|to-do|to do|grocer|expense|spent|spend|paid|cost") to setOf("list_add", "list_show", "list_remove", "list_clear", "add_expense", "expense_summary"),
        Regex("type|dictate|write|compose|enter|say this") to setOf("dictate_text"),
        Regex("briefing|battery alert|every morning|every day|daily") to setOf("set_daily_briefing", "set_battery_alerts", "morning_briefing"),
        Regex("away|silent|vibrate|ring through|pop up|popup|open by itself|decline|ring then|seconds|delay|busy|handle my|i'm back|im back|back now|what did i miss|missed|decline|not available") to
            setOf("start_away", "end_away", "away_summary", "away_status", "set_away_message", "set_my_name", "set_silent_away", "set_ring_through_always", "set_away_popup", "set_away_decline", "set_away_text_delay"),
        Regex("what did you do|what have you done|activity|history of|did you (open|send|call)") to setOf("what_did_you_do"),
        Regex("what did|who said|who sent|told me|said about|find (the|a) message|search my messages|last week|yesterday|earlier|remember when|that address|that number") to setOf("search_memory", "read_messages"),
        Regex("driving|drive|car|ride|on the road") to setOf("driving_mode", "read_messages", "reply_to_caller", "draft_notification_reply", "confirm_send_reply"),
        Regex("interpret|translate for|translator|between .* and") to setOf("start_interpreter", "stop_interpreter"),
        Regex("record|meeting|lecture|transcrib|minutes of") to setOf("start_recording", "stop_recording", "recording_status"),
        Regex("clean|buddy.*(size|storage)|storage|space|memory|ram|usage|used most|apps i|lock|health|uptime|phone status") to setOf("phone_health", "app_usage_today", "lock_phone", "battery_status", "storage_report", "clean_up_buddy"))

    private fun selectTools(): JSONArray {
        val text = recentUser.joinToString(" ").lowercase()
        val names = toolGroups.filter { it.first.containsMatchIn(text) }.flatMap { it.second }.toMutableSet()
        val all = actions.declarations
        if (names.isEmpty()) return all // nothing recognised: let the model see every tool
        names.addAll(alwaysOn)
        val out = JSONArray()
        for (i in 0 until all.length()) if (all.getJSONObject(i).getString("name") in names) out.put(all.getJSONObject(i))
        Log.d("Jarvis", "tools: ${out.length()} of ${all.length()}")
        return out
    }

    private val screenTools = setOf("read_screen", "tap", "tap_xy", "type_text", "scroll", "press_key")
    private val models = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash")
    private val retryable = setOf(404, 429, 500, 503)

    private fun userTurn(text: String) =
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))

    fun ask(userText: String): String {
        val start = history.length()
        screenMode = false
        failed.clear()
        Turns.count++
        Turns.lastText = userText
        recentUser.add(userText)
        if (recentUser.size > 3) recentUser.removeAt(0)
        activeTools = selectTools()
        Log.d("Jarvis", "user: $userText")
        FastPath.tryHandle(userText, actions)?.let { Log.d("Jarvis", "fast path"); return finish(start, userText, it) }
        history.put(userTurn(userText))
        try {
            repeat(25) {
                val content = generate()
                history.put(content)
                val parts = content.optJSONArray("parts") ?: JSONArray()
                val results = JSONArray()
                for (i in 0 until parts.length()) {
                    val call = parts.getJSONObject(i).optJSONObject("functionCall") ?: continue
                    if (call.getString("name") in screenTools) screenMode = true
                    val out = actions.run(call.getString("name"), call.optJSONObject("args") ?: JSONObject())
                    Log.d("Jarvis", "tool ${call.getString("name")} ${call.optJSONObject("args")} -> $out")
                    val fr = JSONObject().put("name", call.getString("name"))
                        .put("response", JSONObject().put("result", out))
                    call.optString("id").takeIf { it.isNotEmpty() }?.let { fr.put("id", it) }
                    results.put(JSONObject().put("functionResponse", fr))
                }
                if (results.length() == 0) {
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                    return finish(start, userText, sb.toString().ifBlank { "Done." })
                }
                history.put(JSONObject().put("role", "user").put("parts", results))
                dropOldScreens()
            }
            return finish(start, userText, "That took too many steps, so I stopped.")
        } catch (e: Exception) {
            while (history.length() > start) history.remove(history.length() - 1)
            Log.e("Jarvis", "ask failed", e)
            return friendly(e)
        }
    }

    // Screen dumps are large; only the newest one is useful, so blank out older ones to save tokens.
    private fun dropOldScreens() {
        var seenLatest = false
        for (i in history.length() - 1 downTo 0) {
            val parts = history.getJSONObject(i).optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val fr = parts.getJSONObject(j).optJSONObject("functionResponse") ?: continue
                if (fr.optString("name") != "read_screen") continue
                if (seenLatest) fr.put("response", JSONObject().put("result", "(older screen omitted)"))
                seenLatest = true
            }
        }
    }

    // Gemini 3 requires a thought signature on every function call in the current turn. Calls made by
    // another model (Groq) have none, so mark them with the documented bypass value.
    private fun withSignatures(): JSONArray {
        val copy = JSONArray(history.toString())
        for (i in 0 until copy.length()) {
            val parts = copy.getJSONObject(i).optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                if (part.has("functionCall") && !part.has("thoughtSignature"))
                    part.put("thoughtSignature", "skip_thought_signature_validator")
            }
        }
        return copy
    }

    // Raw API errors are long JSON. Log them, but only ever show or speak a short human sentence.
    private fun friendly(e: Exception): String {
        val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return when {
            Regex("429|rate limit|quota|RESOURCE_EXHAUSTED", RegexOption.IGNORE_CASE).containsMatchIn(m) ->
                "I've hit my AI usage limit for now. Please try again in a minute."
            Regex("No AI provider", RegexOption.IGNORE_CASE).containsMatchIn(m) -> "I've hit my AI usage limit for now. Please try again in a minute."
            Regex("503|high demand|UNAVAILABLE|overloaded", RegexOption.IGNORE_CASE).containsMatchIn(m) ->
                "The AI service is busy right now. Please try again in a moment."
            e is java.io.IOException || Regex("Unable to resolve|timeout|timed out|connect", RegexOption.IGNORE_CASE).containsMatchIn(m) ->
                "I can't reach the internet right now."
            else -> "Sorry, something went wrong. Please try again."
        }
    }

    private fun finish(start: Int, userText: String, reply: String): String {
        while (history.length() > start) history.remove(history.length() - 1)
        history.put(userTurn(userText))
        history.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", reply))))
        return reply
    }

    private fun generate(): JSONObject {
        // Screen control sends big payloads that exceed Groq's free per-minute token limit, so Gemini leads there.
        val order = if (screenMode) listOf("gemini", "groq") else listOf("groq", "gemini")
        var last: Exception? = null
        for (provider in order) {
            if (provider in failed || (provider == "groq" && groqKey.isBlank())) continue
            try {
                return if (provider == "groq") generateGroq() else generateGemini()
            } catch (e: Exception) {
                Log.w("Jarvis", "$provider failed: ${e.message?.take(150)}")
                failed.add(provider)
                last = e
            }
        }
        throw last ?: Exception("No AI provider is configured.")
    }

    private fun generateGemini(): JSONObject {
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", withSignatures())
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", activeTools)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 400))
        var text = ""
        var code = 0
        for (model in models) {
            try {
                val r = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                    body, "x-goog-api-key" to apiKey)
                code = r.first
                text = r.second
            } catch (e: java.io.IOException) {
                throw Unavailable(e.message ?: "network error")
            }
            if (code in 200..299 || code !in retryable) break
        }
        if (code in retryable) throw Unavailable("Error $code")
        if (code !in 200..299) throw Exception("Error $code: $text")
        return JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
    }

    private fun post(url: String, body: JSONObject, vararg headers: Pair<String, String>): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 40000
        conn.setRequestProperty("content-type", "application/json")
        headers.forEach { conn.setRequestProperty(it.first, it.second) }
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return code to stream.bufferedReader().readText()
    }

    private val groqModels = listOf("openai/gpt-oss-120b", "qwen/qwen3.8-27b", "openai/gpt-oss-20b")

    private fun generateGroq(): JSONObject {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        val pendingIds = ArrayDeque<String>()
        for (i in 0 until history.length()) {
            val c = history.getJSONObject(i)
            val parts = c.optJSONArray("parts") ?: continue
            if (c.getString("role") == "model") {
                val text = StringBuilder()
                val calls = JSONArray()
                for (j in 0 until parts.length()) {
                    val part = parts.getJSONObject(j)
                    part.optString("text").takeIf { it.isNotEmpty() }?.let { text.append(it) }
                    part.optJSONObject("functionCall")?.let { fc ->
                        val id = fc.optString("id").ifEmpty { "call_${i}_$j" }
                        pendingIds.addLast(id)
                        calls.put(JSONObject().put("id", id).put("type", "function").put("function", JSONObject()
                            .put("name", fc.getString("name"))
                            .put("arguments", (fc.optJSONObject("args") ?: JSONObject()).toString())))
                    }
                }
                val m = JSONObject().put("role", "assistant").put("content", if (text.isEmpty()) JSONObject.NULL else text.toString())
                if (calls.length() > 0) m.put("tool_calls", calls)
                msgs.put(m)
            } else {
                val text = StringBuilder()
                for (j in 0 until parts.length()) {
                    val part = parts.getJSONObject(j)
                    val fr = part.optJSONObject("functionResponse")
                    if (fr != null) {
                        msgs.put(JSONObject().put("role", "tool")
                            .put("tool_call_id", fr.optString("id").ifEmpty { pendingIds.removeFirstOrNull() ?: "call_x" })
                            .put("content", fr.getJSONObject("response").optString("result")))
                    } else text.append(part.optString("text"))
                }
                if (text.isNotEmpty()) msgs.put(JSONObject().put("role", "user").put("content", text.toString()))
            }
        }
        val tools = JSONArray()
        for (i in 0 until activeTools.length()) {
            val d = activeTools.getJSONObject(i)
            val params = d.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject())
            tools.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", d.getString("name")).put("description", d.getString("description")).put("parameters", params)))
        }
        var code = 0
        var text = ""
        for (round in 0..1) {
          if (round == 1) Thread.sleep(15_000) // every Groq model was rate limited; the per-minute allowance refills quickly
          for (model in groqModels) {
            val body = JSONObject().put("model", model).put("messages", msgs).put("tools", tools)
                .put("tool_choice", "auto").put("max_tokens", 400)
            val r = post("https://api.groq.com/openai/v1/chat/completions", body, "Authorization" to "Bearer $groqKey")
            code = r.first
            text = r.second
            if (code != 429 && code != 404) break
          }
          if (code != 429) break
        }
        if (code !in 200..299) throw Exception("Groq error $code: $text")
        val message = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val outParts = JSONArray()
        message.optString("content").takeIf { it.isNotEmpty() && it != "null" }?.let { outParts.put(JSONObject().put("text", it)) }
        message.optJSONArray("tool_calls")?.let { tc ->
            for (k in 0 until tc.length()) {
                val f = tc.getJSONObject(k)
                val fn = f.getJSONObject("function")
                outParts.put(JSONObject().put("functionCall", JSONObject()
                    .put("name", fn.getString("name"))
                    .put("args", JSONObject(fn.optString("arguments").ifBlank { "{}" }))
                    .put("id", f.getString("id"))))
            }
        }
        return JSONObject().put("role", "model").put("parts", outParts)
    }
}
