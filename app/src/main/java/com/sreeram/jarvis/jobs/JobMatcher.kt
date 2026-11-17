package com.sreeram.jarvis.jobs

import java.util.Locale

// What the user wants, from the Job hunt settings.
data class JobPrefs(
    val roles: List<String>,
    val locations: List<String>,
    val minSalaryLakhs: Double,
    val maxAgeDays: Int,
    val minScore: Int,
    val dailyCap: Int,
    val exclude: List<String>,
)

// One job as read from the screen (see JobPages for where each field comes from).
data class Job(
    val title: String,
    val company: String,
    val location: String,
    val ctc: String,
    val posted: String,
    val mustSkills: List<String>,
    val goodSkills: List<String>,
)

data class Verdict(val apply: Boolean, val score: Int, val why: String)

object JobMatcher {
    // Skill names as people write them in resumes and job posts, mapped to one spelling.
    private val aliases = mapOf(
        "js" to "javascript", "node" to "node.js", "nodejs" to "node.js", "reactjs" to "react", "react.js" to "react",
        "angularjs" to "angular", "ts" to "typescript", "golang" to "go", "k8s" to "kubernetes", "postgres" to "postgresql",
        "restful api" to "rest api", "restfull api" to "rest api", "restful apis" to "rest api", "rest apis" to "rest api",
        "spring" to "spring boot", "aws cloud" to "aws", "amazon web services" to "aws", "ml" to "machine learning",
    )

    fun normalize(skill: String): String {
        val s = skill.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9+#.\\s/-]"), " ").replace(Regex("\\.+(?=\\s|$)"), "").replace(Regex("\\s+"), " ").trim()
        return aliases[s] ?: s
    }

    // The skills a job names, as one comma list on the screen: "Java, Restfull API, System Design, ".
    fun splitSkills(raw: String): List<String> = raw.split(',', ';', '|').map { normalize(it) }.filter { it.length >= 2 }.distinct()

    // Skills found in the resume text. Matches whole words so "go" does not match "google".
    fun resumeSkills(text: String, vocabulary: Collection<String>): Set<String> {
        val body = " " + text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9+#.\\s/-]"), " ").replace(Regex("\\.+(?=\\s|$)"), "").replace(Regex("\\s+"), " ") + " "
        val found = HashSet<String>()
        for (v in vocabulary.map { normalize(it) }.distinct()) {
            if (v.isNotEmpty() && body.contains(" $v ")) found += v
        }
        for ((alias, canon) in aliases) if (body.contains(" $alias ")) found += canon
        return found
    }

    // "2d ago", "Posted 14d ago", "Just now", "30+ days ago", "1 month ago" -> days. Unknown -> null.
    fun ageDays(raw: String): Int? {
        val t = raw.lowercase(Locale.ROOT)
        if (Regex("just now|today|few hours|\\bhour|\\bminute|\\d+h ago|\\d+m ago").containsMatchIn(t)) return 0
        Regex("(\\d+)\\+?\\s*(?:d|days?)\\b").find(t)?.let { return it.groupValues[1].toInt() }
        Regex("(\\d+)\\s*(?:w|weeks?)\\b").find(t)?.let { return it.groupValues[1].toInt() * 7 }
        Regex("(\\d+)\\s*(?:mo|months?)\\b").find(t)?.let { return it.groupValues[1].toInt() * 30 }
        return null
    }

    // "6-20 Lacs PA" -> the top of the range, in lakhs. "Not disclosed" -> null.
    fun topLakhs(raw: String): Double? {
        val nums = Regex("(\\d+(?:\\.\\d+)?)").findAll(raw.replace(",", "")).map { it.groupValues[1].toDouble() }.toList()
        if (nums.isEmpty()) return null
        return if (raw.lowercase(Locale.ROOT).contains("cr")) nums.last() * 100 else nums.last()
    }

    // The checks that need no skills list, so a result card can be rejected without opening it. Null means "worth opening".
    fun reject(job: Job, prefs: JobPrefs): String? {
        val hay = "${job.title} ${job.mustSkills.joinToString(" ")}".lowercase(Locale.ROOT)
        prefs.exclude.map { it.lowercase(Locale.ROOT).trim() }.filter { it.isNotEmpty() }.firstOrNull { hay.contains(it) }
            ?.let { return "contains \"$it\", which you excluded" }

        val title = titleKey(job.title)
        if (prefs.roles.isNotEmpty() && prefs.roles.none { r -> titleKey(r).split(' ').filter { it.length > 2 }.all { w -> title.contains(w) } })
            return "the title does not match any of your roles"

        if (prefs.locations.isNotEmpty() && prefs.locations.none { job.location.contains(it.trim(), ignoreCase = true) })
            return "not in your locations"

        val age = ageDays(job.posted)
        if (age != null && age > prefs.maxAgeDays) return "posted $age days ago"

        val top = topLakhs(job.ctc)
        if (top != null && top < prefs.minSalaryLakhs) return "pays up to ${trim(top)} lakhs, under your ${trim(prefs.minSalaryLakhs)}"
        return null
    }

    // Job titles written differently mean the same: "Fullstack Engineer" and "Full Stack Developer".
    fun titleKey(s: String): String = s.lowercase(Locale.ROOT).replace(Regex("full[\\s-]*stack"), "full stack").replace(Regex("\\b(engineer|programmer|sde)\\b"), "developer")
        .replace(Regex("[^a-z0-9+#.\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    // requireSkills = false lets the AI judge a job whose page lists no skills, from its description.
    fun judge(job: Job, resume: Set<String>, prefs: JobPrefs, requireSkills: Boolean = true): Verdict {
        reject(job, prefs)?.let { return Verdict(false, 0, it) }

        val must = job.mustSkills.map { normalize(it) }.filter { it.isNotEmpty() }
        if (must.isEmpty()) return if (requireSkills) Verdict(false, 0, "the job lists no required skills, so a match cannot be checked") else Verdict(true, 0, "no skills listed on the page")
        val have = must.count { it in resume }
        val good = job.goodSkills.map { normalize(it) }.filter { it.isNotEmpty() }
        val goodHave = good.count { it in resume }
        // Required skills carry the score. Nice-to-have skills add up to 10 points.
        val score = (have * 90 / must.size + if (good.isEmpty()) 10 else goodHave * 10 / good.size).coerceIn(0, 100)
        val missing = must.filter { it !in resume }
        val why = "$have of ${must.size} required skills" + if (missing.isNotEmpty()) " (missing ${missing.take(3).joinToString(", ")})" else ""
        return if (score >= prefs.minScore) Verdict(true, score, why) else Verdict(false, score, "score $score is under your ${prefs.minScore}: $why")
    }

    private fun trim(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}

// Applications are limited per day and never repeated. Pure so it can be tested without a phone.
object JobLimits {
    fun key(job: Job) = normalizeKey("${job.title}|${job.company}|${job.location}")
    private fun normalizeKey(s: String) = s.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    fun canApply(appliedToday: Int, cap: Int) = appliedToday < cap
    fun alreadyApplied(job: Job, appliedKeys: Set<String>) = key(job) in appliedKeys
}
