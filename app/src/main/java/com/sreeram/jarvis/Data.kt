package com.sreeram.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Routines, lists and expenses, kept in private app storage on the phone.
class DataStore(context: Context) {
    private val routinesFile = File(context.filesDir, "routines.json")
    private val listsFile = File(context.filesDir, "lists.json")
    private val expensesFile = File(context.filesDir, "expenses.json")

    private fun obj(f: File) = try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
    private fun arr(f: File) = try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }

    // ---- routines: name -> list of {tool, args} ----
    @Synchronized fun saveRoutine(name: String, steps: JSONArray) { routinesFile.writeText(obj(routinesFile).put(name.lowercase(), steps).toString()) }
    @Synchronized fun routineNames(): List<String> = obj(routinesFile).keys().asSequence().toList()
    @Synchronized fun routine(name: String): Pair<String, JSONArray>? {
        val o = obj(routinesFile)
        val key = o.keys().asSequence().firstOrNull { it == name.lowercase() } ?: o.keys().asSequence().firstOrNull { it.contains(name.lowercase()) || name.lowercase().contains(it) } ?: return null
        return key to o.getJSONArray(key)
    }
    @Synchronized fun deleteRoutine(name: String): Boolean {
        val o = obj(routinesFile)
        val key = o.keys().asSequence().firstOrNull { it.contains(name.lowercase()) } ?: return false
        o.remove(key); routinesFile.writeText(o.toString()); return true
    }

    // ---- lists: name -> items ----
    @Synchronized fun listItems(list: String): List<String> = obj(listsFile).optJSONArray(list.lowercase())?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    @Synchronized fun listNames(): List<String> = obj(listsFile).keys().asSequence().toList()
    @Synchronized fun listAdd(list: String, item: String): Int {
        val o = obj(listsFile)
        val a = o.optJSONArray(list.lowercase()) ?: JSONArray()
        a.put(item); o.put(list.lowercase(), a); listsFile.writeText(o.toString()); return a.length()
    }
    @Synchronized fun listRemove(list: String, item: String): Boolean {
        val o = obj(listsFile)
        val items = listItems(list).toMutableList()
        val hit = items.indexOfFirst { it.contains(item, ignoreCase = true) }
        if (hit < 0) return false
        items.removeAt(hit); o.put(list.lowercase(), JSONArray(items)); listsFile.writeText(o.toString()); return true
    }
    @Synchronized fun listClear(list: String) { listsFile.writeText(obj(listsFile).apply { remove(list.lowercase()) }.toString()) }

    // ---- expenses ----
    data class Expense(val time: Long, val amount: Double, val note: String, val category: String)

    @Synchronized fun addExpense(amount: Double, note: String, category: String) {
        arr(expensesFile).put(JSONObject().put("t", System.currentTimeMillis()).put("a", amount).put("n", note).put("c", category)).let { expensesFile.writeText(it.toString()) }
    }
    @Synchronized fun expenses(since: Long): List<Expense> = arr(expensesFile).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getLong("t") >= since }
            .map { Expense(it.getLong("t"), it.getDouble("a"), it.optString("n"), it.optString("c", "other")) }
    }
}
