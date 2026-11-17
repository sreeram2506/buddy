package com.sreeram.jarvis.jobs

import android.content.Context

// The "Job hunt" settings, read from the same preferences the settings screen writes.
object JobConfig {
    fun prefs(c: Context): JobPrefs {
        val p = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        fun list(key: String, default: String) = (p.getString(key, default) ?: default).split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        // With AI on, the roles the AI worked out from the resume are searched as well, after the ones the user typed.
        val aiRoles = if (useAI(c)) JobAI.profile(c)?.searchRoles.orEmpty() else emptyList()
        return JobPrefs(
            roles = (list("job_roles", "Full Stack Developer") + aiRoles).distinctBy { it.lowercase() },
            locations = list("job_locations", "Hyderabad"),
            minSalaryLakhs = p.getInt("job_min_lakhs", 0).toDouble(),
            maxAgeDays = p.getInt("job_max_age", 3),
            minScore = p.getInt("job_min_score", 70),
            dailyCap = p.getInt("job_daily_cap", 10),
            exclude = list("job_exclude", "intern, trainee"),
        )
    }

    fun useAI(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("job_ai", true)
    fun autoApply(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("job_auto_apply", false)
}
