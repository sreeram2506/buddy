package com.sreeram.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// The conversation shown on Buddy's screen, kept on the phone so it is still there next time.
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chat.json")
    data class Msg(val user: Boolean, val text: String)

    private fun load() = try { JSONArray(file.readText()) } catch (e: Exception) { JSONArray() }

    fun all(): List<Msg> = load().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Msg(it.getBoolean("u"), it.getString("t")) } }

    @Synchronized fun add(user: Boolean, text: String) {
        val kept = all().takeLast(59) + Msg(user, text)
        file.writeText(JSONArray(kept.map { JSONObject().put("u", it.user).put("t", it.text) }).toString())
    }

    fun clear() { file.delete() }
}
