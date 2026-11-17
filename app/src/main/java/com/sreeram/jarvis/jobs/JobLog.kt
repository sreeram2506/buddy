package com.sreeram.jarvis.jobs

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// One line per job Buddy looked at, so you can always check what it did and why. It never stores resume text or form answers.
object JobLog {
    const val APPLIED = "applied"
    private fun file(c: Context) = File(c.filesDir, "jobs.jsonl")
    private fun day(t: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    @Synchronized fun add(c: Context, job: Job, score: Int, outcome: String, why: String) {
        val line = JSONObject().put("t", System.currentTimeMillis()).put("key", JobLimits.key(job)).put("title", job.title).put("company", job.company)
            .put("score", score).put("outcome", outcome).put("why", why).toString()
        val f = file(c)
        val lines = if (f.exists()) f.readLines().takeLast(999) else emptyList()
        f.writeText((lines + line).joinToString("\n") + "\n")
    }

    private fun entries(c: Context): List<JSONObject> = if (file(c).exists()) file(c).readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() } else emptyList()

    fun appliedKeys(c: Context): Set<String> = entries(c).filter { it.optString("outcome") == APPLIED }.map { it.optString("key") }.toSet()
    fun appliedToday(c: Context): Int = entries(c).count { it.optString("outcome") == APPLIED && day(it.optLong("t")) == day(System.currentTimeMillis()) }

    fun recent(c: Context, n: Int): List<String> = entries(c).takeLast(n).reversed().map {
        "${it.optString("title")} at ${it.optString("company")}: ${it.optString("outcome")}" + it.optString("why").let { w -> if (w.isNotEmpty()) " ($w)" else "" }
    }
}
