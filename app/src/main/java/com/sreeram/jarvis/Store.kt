package com.sreeram.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Notes and remembered facts, kept in private app storage on the phone.
class Store(context: Context) {
    private val notesFile = File(context.filesDir, "notes.json")
    private val factsFile = File(context.filesDir, "facts.json")

    private fun load(f: File) = try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }

    @Synchronized fun addNote(text: String) {
        val a = load(notesFile)
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("text", text))
        notesFile.writeText(a.toString())
    }

    @Synchronized fun notes(sinceMs: Long): List<Pair<Long, String>> {
        val a = load(notesFile)
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.getLong("t") >= sinceMs }.map { it.getLong("t") to it.getString("text") }
    }

    @Synchronized fun addFact(fact: String) {
        val a = load(factsFile)
        a.put(fact)
        factsFile.writeText(a.toString())
    }

    @Synchronized fun facts(): List<String> = load(factsFile).let { a -> (0 until a.length()).map { a.getString(it) } }

    @Synchronized fun removeFacts(keyword: String): Int {
        val keep = facts().filter { !it.contains(keyword, ignoreCase = true) }
        val removed = facts().size - keep.size
        factsFile.writeText(JSONArray(keep).toString())
        return removed
    }
}

// Reminders live in their own file so they survive restarts and can be rescheduled after boot.
class ReminderStore(context: Context) {
    private val file = File(context.filesDir, "reminders.json")
    data class Reminder(val id: Int, val time: Long, val text: String)

    private fun load() = try { JSONArray(file.readText()) } catch (e: Exception) { JSONArray() }
    private fun save(list: List<Reminder>) {
        file.writeText(JSONArray(list.map { JSONObject().put("id", it.id).put("t", it.time).put("text", it.text) }).toString())
    }

    @Synchronized fun all(): List<Reminder> = load().let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { Reminder(it.getInt("id"), it.getLong("t"), it.getString("text")) }
    }.sortedBy { it.time }

    @Synchronized fun add(time: Long, text: String): Reminder {
        val r = Reminder((all().maxOfOrNull { it.id } ?: 0) + 1, time, text)
        save(all() + r)
        return r
    }

    @Synchronized fun remove(id: Int) = save(all().filter { it.id != id })
}
