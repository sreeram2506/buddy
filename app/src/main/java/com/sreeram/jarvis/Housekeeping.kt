package com.sreeram.jarvis

import android.content.Context
import org.json.JSONArray
import java.io.File

// Keeps Buddy's storage small. Runs when Buddy opens and after a restart; everything it removes is old or rebuildable.
object Housekeeping {
    private const val DAY = 86_400_000L

    private fun size(f: File): Long = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // Trims a JSON list file to its newest entries.
    private fun cap(file: File, keep: Int) {
        val a = try { JSONArray(file.readText()) } catch (e: Exception) { return }
        if (a.length() <= keep) return
        file.writeText(JSONArray((a.length() - keep until a.length()).map { a.get(it) }).toString())
    }

    fun run(c: Context): String {
        val now = System.currentTimeMillis()
        var freed = 0L
        fun remove(f: File) { freed += size(f); f.deleteRecursively() }

        c.filesDir.listFiles()?.forEach { f ->
            // Meeting audio is large (about 14 MB an hour). The transcript and summary are kept; the audio goes after 14 days.
            if (f.name.startsWith("rec_") && f.name.endsWith(".m4a") && now - f.lastModified() > 14 * DAY) remove(f)
            if (f.name.startsWith("rec_") && f.name.endsWith(".txt") && now - f.lastModified() > 90 * DAY) remove(f)
        }
        // Temporary files that are rebuilt when needed.
        c.cacheDir.listFiles()?.forEach { if (now - it.lastModified() > DAY) remove(it) }
        c.getExternalFilesDir(null)?.listFiles()?.forEach { if (now - it.lastModified() > 7 * DAY) remove(it) }
        // Lists that could otherwise grow without limit.
        MemoryStore.prune(c)
        cap(File(c.filesDir, "notes.json"), 1000)
        cap(File(c.filesDir, "away_log.json"), 500)
        cap(File(c.filesDir, "expenses.json"), 5000)
        return "Freed %.1f MB".format(freed / 1048576.0)
    }

    // A plain-language account of what Buddy is storing.
    fun report(c: Context): String {
        fun mb(b: Long) = "%.1f MB".format(b / 1048576.0)
        val recordings = c.filesDir.listFiles()?.filter { it.name.startsWith("rec_") }.orEmpty()
        val model = size(File(c.filesDir, "vosk-model"))
        val total = size(c.filesDir) + size(c.cacheDir) + (c.getExternalFilesDir(null)?.let { size(it) } ?: 0)
        return "Buddy is using ${mb(total)} of data: ${mb(model)} is the Hey buddy speech model it needs, " +
            "${mb(recordings.sumOf { it.length() })} is ${recordings.count { it.name.endsWith(".m4a") }} meeting recordings, " +
            "and ${mb(total - model - recordings.sumOf { it.length() })} is everything else (chat, notes, settings, temporary files)."
    }
}
