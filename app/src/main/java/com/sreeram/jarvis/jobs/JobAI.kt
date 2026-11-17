package com.sreeram.jarvis.jobs

import android.content.Context
import com.sreeram.jarvis.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// The AI layer on top of the rules: it reads the resume once to understand the person, then reads each job's full description and judges the fit.
// The prompts and the reading of the replies are plain functions (tested); only ask() touches the network.
data class Profile(val titles: List<String>, val searchRoles: List<String>, val seniority: String, val years: Int, val skills: List<String>)
data class Assessment(val score: Int, val fit: String, val reasons: List<String>, val dealbreakers: List<String>)

object JobAI {
        private val models = listOf("gemini-3.5-flash", "gemini-3.6-flash", "gemini-3.8-flash")
    private fun profileFile(c: Context) = File(c.filesDir, "job_profile.json")

    const val PROFILE_PROMPT = "You read a resume and describe the person for a job search. Reply with JSON only: " +
        "{\"titles\":[their current and recent job titles], \"searchRoles\":[6 to 8 job titles they should search for on a job site, " +
        "most likely first, including close alternatives they are qualified for, plain titles without company names], " +
        "\"seniority\":\"junior|mid|senior|lead\", \"years\":total years of professional experience as an integer, " +
        "\"skills\":[their strongest skills, at most 15]}. Use only what the resume says. Do not invent skills or years."

    const val ASSESS_PROMPT = "You judge whether a job suits a candidate. You get the candidate's profile and the job. Be strict and honest: " +
        "compare required years of experience, seniority, the main technologies, and what the work actually is. A title match alone is not a fit. " +
        "Reply with JSON only: {\"score\":0-100, \"fit\":\"strong|maybe|no\", \"reasons\":[up to 3 short reasons], \"dealbreakers\":[anything that rules the job out, such as a required skill they lack, far more experience than they have, or a different kind of work]}."

    fun profileRequest(resume: String) = "Resume:\n" + resume.take(12_000)

    fun assessRequest(p: Profile, job: Job, description: String): String =
        "Candidate: ${p.seniority}, ${p.years} years. Skills: ${p.skills.joinToString(", ")}. Recent titles: ${p.titles.joinToString(", ")}.\n\n" +
            "Job: ${job.title} at ${job.company}, ${job.location}, ${job.ctc}, ${job.posted}.\n" +
            "Required skills listed: ${job.mustSkills.joinToString(", ").ifEmpty { "not listed" }}.\n" +
            "Description:\n" + description.take(6_000)

    private fun strip(raw: String): String = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    private fun list(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.optString(it).trim() }.filter { it.isNotEmpty() }

    fun parseProfile(raw: String): Profile? = runCatching {
        val j = JSONObject(strip(raw))
        val roles = list(j.optJSONArray("searchRoles"))
        if (roles.isEmpty()) null else Profile(list(j.optJSONArray("titles")), roles.distinct().take(8), j.optString("seniority", "mid"), j.optInt("years", 0), list(j.optJSONArray("skills")))
    }.getOrNull()

    fun parseAssessment(raw: String): Assessment? = runCatching {
        val j = JSONObject(strip(raw))
        val score = j.getInt("score").coerceIn(0, 100)
        val breakers = list(j.optJSONArray("dealbreakers"))
        // A dealbreaker overrules a flattering score.
        val fit = if (breakers.isNotEmpty()) "no" else j.optString("fit", "maybe").lowercase().takeIf { it in setOf("strong", "maybe", "no") } ?: "maybe"
        Assessment(score, fit, list(j.optJSONArray("reasons")), breakers)
    }.getOrNull()

    // Applies only when the AI agrees the job fits and no dealbreaker was named.
    fun shouldApply(a: Assessment, minScore: Int) = a.fit != "no" && a.dealbreakers.isEmpty() && a.score >= minScore

    fun saveProfile(c: Context, p: Profile) {
        profileFile(c).writeText(JSONObject().put("titles", JSONArray(p.titles)).put("searchRoles", JSONArray(p.searchRoles)).put("seniority", p.seniority).put("years", p.years).put("skills", JSONArray(p.skills)).toString())
    }
    fun profile(c: Context): Profile? = if (profileFile(c).exists()) parseProfile(profileFile(c).readText()) else null
    fun clearProfile(c: Context) { profileFile(c).delete() }

    // Reads the saved resume with Gemini and stores the profile. Returns null on success, or a plain reason.
    fun buildProfile(c: Context): String? {
        val resume = ResumeStore.text(c)
        if (resume.isBlank()) return "No resume yet."
        val reply = ask(PROFILE_PROMPT, profileRequest(resume)) ?: return "Could not reach Gemini: ${lastError.ifEmpty { "no answer" }}"
        val p = parseProfile(reply) ?: run {
            android.util.Log.w("Jarvis", "job AI profile reply unreadable, ${reply.length} chars, starts: ${reply.take(60).replace('\n', ' ')}")
            return "Gemini's answer could not be read (${reply.length} characters, starting \"${reply.take(50).replace('\n', ' ')}\")."
        }
        saveProfile(c, p); return null
    }

    fun assess(c: Context, job: Job, description: String): Assessment? {
        val p = profile(c) ?: return null
        return ask(ASSESS_PROMPT, assessRequest(p, job, description))?.let { parseAssessment(it) }
    }

    // Why the last call failed, in plain words, so the settings screen can say it instead of a vague "could not reach".
    @Volatile var lastError = ""

    private fun ask(system: String, user: String): String? {
        lastError = ""
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 900).put("temperature", 0.2).put("responseMimeType", "application/json"))
        for (m in models) {
            try {
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000; conn.readTimeout = 75_000; conn.requestMethod = "POST"
                conn.setRequestProperty("content-type", "application/json"); conn.setRequestProperty("x-goog-api-key", BuildConfig.API_KEY)
                conn.doOutput = true; conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
                if (code in 200..299) {
                    android.util.Log.d("Jarvis", "job AI $m answered")
                    val ps = JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                    return (0 until ps.length()).joinToString("") { ps.getJSONObject(it).optString("text") }
                }
                lastError = "$m answered $code: " + text.replace(Regex("AIza[A-Za-z0-9_-]+"), "<key>").take(160)
                android.util.Log.w("Jarvis", "job AI $lastError")
                if (code !in setOf(404, 429, 500, 503)) return null
            } catch (e: java.io.IOException) { lastError = "no connection (${e.javaClass.simpleName}: ${e.message})"; android.util.Log.w("Jarvis", "job AI $m $lastError") } // try the next model: one slow model should not end the job
        }
        return null
    }
}
