package com.sreeram.jarvis.jobs

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import com.sreeram.jarvis.BuildConfig
import com.sreeram.jarvis.JarvisAccessibilityService
import com.sreeram.jarvis.msg.Waiter
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// The job hunter as an AI agent: Gemini looks at what the tools return and decides the next step (which role to search, which job to open,
// whether to read the whole description, whether the job fits). The tools only read the screen and press a few fixed buttons, and ApplyGate
// holds the hard rules, so the AI decides what is worth doing but can never do what the user's limits forbid.
object JobAgent {
    private const val PKG = "naukriApp.appModules.login"
    private const val APPLY_ID = "$PKG:id/tvApply"
    private const val MAX_STEPS = 160
    private const val MAX_JOBS = 25
    private val models = listOf("gemini-3.5-flash", "gemini-3.6-flash", "gemini-3.8-flash")
    // Groq is the backup when Gemini is rate limited. Its free tier allows few tokens a minute, so it only gets the recent turns.
    private val groqModels = listOf("openai/gpt-oss-120b", "qwen/qwen3.8-27b", "openai/gpt-oss-20b")

    @Volatile var running = false; private set
    @Volatile var status = "Not run yet"; private set
    @Volatile private var stopRequested = false
    fun stop() { stopRequested = true }

    fun systemPrompt(profile: Profile, prefs: JobPrefs, resumeExcerpt: String, apply: Boolean) = """
You are a job-hunting agent working on the user's phone, in the Naukri app. You act through tools. Think like a careful recruiter's assistant: find roles that truly suit this person, skip the rest, and never waste their time or reputation.

The person: ${profile.seniority}, ${profile.years} years of experience. Recent titles: ${profile.titles.joinToString(", ")}. Strongest skills: ${profile.skills.joinToString(", ")}.
Resume excerpt (for judging fit, never to be repeated back):
$resumeExcerpt

What they want: roles like ${prefs.roles.joinToString(", ")}; in ${prefs.locations.joinToString(", ")}; posted within ${prefs.maxAgeDays} days; at least ${prefs.minSalaryLakhs.toLong()} lakhs a year if pay is shown; never jobs containing: ${prefs.exclude.joinToString(", ")}.

How to work:
1. Begin by calling open_search. Search several different but fitting role titles (use the role titles above and close ones they are qualified for). open_search, then list_results, then scroll_results for more.
2. For each promising result call open_job, then read_description whenever the skills list is missing or the fit is not obvious. Judge honestly: required years versus theirs, seniority, the main technologies, and what the work really is. A matching title alone is not a fit.
3. Always call decide(action, fit, reasons, dealbreakers). A dealbreaker is a required skill they lack, far more experience required than they have, or a different kind of work. fit is 0 to 100.
4. ${if (apply) "If the job fits (fit at least ${prefs.minScore}) call apply_now. If the tool refuses, accept the reason and move on; do not try to get around it." else "This is a preview: decide, but nothing will be applied."}
5. Call back after each job to return to the list. Review at most $MAX_JOBS jobs, then call finish with a short plain summary of what you found and why.
If a screen is unexpected or the app asks questions, call give_up with the reason. Never guess answers on a form.
""".trim()

    fun declarations(): JSONArray {
        fun fn(name: String, desc: String, props: JSONObject? = null, required: List<String> = emptyList()) = JSONObject().put("name", name).put("description", desc).apply {
            if (props != null) put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray(required)))
        }
        fun p(type: String, d: String) = JSONObject().put("type", type).put("description", d)
        return JSONArray()
            .put(fn("open_search", "Open Naukri's results for a role in a place, limited to the user's recency window.", JSONObject().put("role", p("STRING", "Job title to search")).put("location", p("STRING", "City")), listOf("role", "location")))
            .put(fn("list_results", "List the jobs now visible on the results screen, numbered, with company, place, pay and age. Jobs that break the user's filters are marked."))
            .put(fn("scroll_results", "Scroll the results list down to load more jobs."))
            .put(fn("open_job", "Open a job from the last list by its number. Returns its title, company, pay, place, age, listed skills and the kind of Apply button.", JSONObject().put("number", p("INTEGER", "Number from list_results")), listOf("number")))
            .put(fn("read_description", "Read the full description of the open job (scrolls the page)."))
            .put(fn("decide", "Record your verdict on the open job.", JSONObject().put("action", p("STRING", "apply, skip or save"))
                .put("fit", p("INTEGER", "0 to 100")).put("reasons", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING")).put("description", "up to 3 short reasons"))
                .put("dealbreakers", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING")).put("description", "anything that rules the job out")), listOf("action", "fit", "reasons")))
            .put(fn("apply_now", "Press Apply on the open job. Only works after decide(apply) and when the user's limits allow it."))
            .put(fn("back", "Go back from a job to the results list."))
            .put(fn("read_screen", "Read the whole current screen as text, for an unexpected screen."))
            .put(fn("give_up", "Stop because something unexpected happened.", JSONObject().put("reason", p("STRING", "What went wrong")), listOf("reason")))
            .put(fn("finish", "End the run with a short summary.", JSONObject().put("summary", p("STRING", "What you found and why")), listOf("summary")))
    }

    fun start(c: Context, apply: Boolean, maxApplies: Int = Int.MAX_VALUE): String {
        if (running) return "Job hunt is already running: $status"
        val svc = JarvisAccessibilityService.instance ?: return "Accessibility is off, so I cannot drive Naukri. Turn it on in Settings."
        val profile = JobAI.profile(c) ?: return "Let the AI read your resume first: Settings, Job hunt, Understand my resume with AI."
        val pm = c.getSystemService(PowerManager::class.java); val km = c.getSystemService(KeyguardManager::class.java)
        if (!pm.isInteractive || km.isKeyguardLocked) return "The phone is locked. Unlock it and start again."
        if (c.packageManager.getLaunchIntentForPackage(PKG) == null) return "Naukri is not installed."
        running = true; stopRequested = false; status = "Starting"
        Thread { try { loop(c.applicationContext, svc, profile, apply, maxApplies) } catch (e: Exception) { Log.w("Jarvis", "job agent failed", e); status = "Stopped: ${e.message}" } finally { runCatching { leaveJobPage(svc) }; running = false } }.start()
        return if (apply && !JobConfig.autoApply(c)) "Started as a preview, because \"Let Buddy apply on its own\" is off. Nothing will be applied." else "Started."
    }

    private class Run(val c: Context, val svc: JarvisAccessibilityService, val prefs: JobPrefs, val apply: Boolean, val maxApplies: Int) {
        var cards: List<NaukriScreens.Card> = emptyList()
        var job: Job? = null
        var decision: ApplyGate.Decision? = null
        var kind = NaukriScreens.ApplyKind.NONE
        val seen = HashSet<String>()
        var reviewed = 0; var applied = 0; var skipped = 0; var matched = 0
        var done: String? = null
        val h = c.resources.displayMetrics.heightPixels
        fun nodes() = svc.screenNodes()
        fun screen() = NaukriScreens.identify(nodes())
        fun footer() = "[reviewed $reviewed, matched $matched, applied $applied]"
    }

    private fun loop(c: Context, svc: JarvisAccessibilityService, profile: Profile, apply: Boolean, maxApplies: Int) {
        traceCtx = c; dailyOut.clear(); trace("--- run started, apply=$apply")
        val prefs = JobConfig.prefs(c)
        val run = Run(c, svc, prefs, apply, maxApplies)
        val system = systemPrompt(profile, prefs, ResumeStore.text(c).take(3500), apply && JobConfig.autoApply(c))
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Start the job hunt."))))
        var steps = 0
        while (steps++ < MAX_STEPS && run.done == null && !stopRequested) {
            if (svc.currentPackage() != PKG && steps > 2) { status = "Stopped: you left Naukri."; return }
            val reply = chat(system, contents) ?: run { status = "Stopped: the AI could not be reached."; return }
            contents.put(reply)
            val parts = reply.optJSONArray("parts") ?: JSONArray()
            val results = JSONArray()
            for (i in 0 until parts.length()) {
                val call = parts.getJSONObject(i).optJSONObject("functionCall") ?: continue
                val name = call.getString("name"); val args = call.optJSONObject("args") ?: JSONObject()
                status = describe(name, args, run)
                val out = try { tool(run, name, args) } catch (e: Exception) { "That step failed: ${e.message}" }
                trace("$name -> ${out.replace('\n', ' ').take(140)}")
                results.put(JSONObject().put("functionResponse", JSONObject().put("name", name).put("response", JSONObject().put("result", out.take(if (name == "read_description") 4500 else 1800)))))
            }
            if (results.length() == 0) { contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Continue with a tool call, or call finish.")))) }
            else contents.put(JSONObject().put("role", "user").put("parts", results))
            while (contents.length() > 40) contents.remove(1) // keep the opening instruction and the recent steps
        }
        val end = run.done ?: if (stopRequested) "Stopped." else "Stopped: step limit reached."
        status = "$end ${run.footer()}" + if (apply && JobConfig.autoApply(c)) "" else " (preview, nothing applied)"
        trace("END: $status")
    }

    private fun describe(name: String, a: JSONObject, r: Run) = when (name) {
        "open_search" -> "Searching ${a.optString("role")} in ${a.optString("location")}"
        "open_job" -> "Opening a job"
        "read_description" -> "Reading ${r.job?.title ?: "the job"}"
        "apply_now" -> "Applying to ${r.job?.title ?: "a job"}"
        else -> "Working ($name)"
    }

    private fun tool(r: Run, name: String, a: JSONObject): String {
        val c = r.c; val svc = r.svc
        return when (name) {
            "open_search" -> {
                val role = a.optString("role"); val loc = a.optString("location")
                if (role.isBlank() || loc.isBlank()) return "Give a role and a location."
                svc.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(JobHunt.url(role, loc, r.prefs.maxAgeDays))).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (!Waiter.until(15_000) { r.screen() == NaukriScreens.Screen.RESULTS }) return "Naukri did not show results. Try again or give up."
                r.job = null; r.decision = null
                "Results: " + (r.nodes().firstOrNull { it.id == "textViewHelperText" }?.text ?: "shown")
            }
            "list_results" -> {
                if (r.screen() != NaukriScreens.Screen.RESULTS) return "Not on a results screen (${r.screen()}). Use back or open_search."
                r.cards = NaukriScreens.parseResults(r.nodes(), r.h)
                if (r.cards.isEmpty()) return "No jobs visible. Scroll or search again."
                r.cards.mapIndexed { i, card ->
                    val j = card.job; val bad = JobMatcher.reject(j, r.prefs); val done = JobLimits.key(j) in r.seen
                    "${i + 1}. ${j.title} | ${j.company} | ${j.location} | ${j.ctc.ifEmpty { "pay not shown" }} | ${j.posted}" + (if (done) " [already reviewed]" else if (bad != null) " [breaks your filters: $bad]" else "")
                }.joinToString("\n")
            }
            "scroll_results" -> { svc.scroll("down"); Thread.sleep(1200); "Scrolled. Call list_results again." }
            "open_job" -> {
                val card = r.cards.getOrNull(a.optInt("number") - 1) ?: return "No job with that number. Call list_results first."
                JobMatcher.reject(card.job, r.prefs)?.let { why -> r.seen += JobLimits.key(card.job); r.skipped++; JobLog.add(c, card.job, 0, "skipped", why); return "Not opened: it breaks the user's filters ($why)." }
                if (JobLimits.key(card.job) in r.seen) return "Already reviewed in this run."
                if (r.reviewed >= MAX_JOBS) { r.done = "Done: reached the limit of $MAX_JOBS jobs."; return "Job limit reached. Call finish." }
                svc.tapXY(card.tapX.toFloat(), card.tapY.toFloat())
                if (!Waiter.until(8_000) { r.screen() == NaukriScreens.Screen.JOB && NaukriScreens.parseJob(r.nodes()) != null }) return "The job did not open. Call list_results again."
                val j = NaukriScreens.parseJob(r.nodes())!!.let { it.copy(location = it.location.ifEmpty { card.job.location }, ctc = it.ctc.ifEmpty { card.job.ctc }, posted = it.posted.ifEmpty { card.job.posted }) }
                r.job = j; r.decision = null; r.seen += JobLimits.key(card.job); r.seen += JobLimits.key(j); r.reviewed++
                r.kind = NaukriScreens.applyKind(r.nodes())
                "${j.title} | ${j.company} | ${j.location} | ${j.ctc} | ${j.posted}\nListed required skills: ${j.mustSkills.joinToString(", ").ifEmpty { "none shown" }}\nApply button: ${r.kind}"
            }
            "read_description" -> {
                if (r.job == null) return "No job is open."
                val text = LinkedHashSet<String>()
                repeat(3) { text.addAll(NaukriScreens.descriptionText(r.nodes())); svc.scroll("down"); Thread.sleep(800) }
                text.joinToString("\n").ifBlank { "No description text could be read." }
            }
            "decide" -> {
                val j = r.job ?: return "No job is open."
                val action = a.optString("action").lowercase().takeIf { it in setOf("apply", "skip", "save") } ?: return "action must be apply, skip or save."
                fun list(k: String) = (0 until (a.optJSONArray(k)?.length() ?: 0)).map { a.optJSONArray(k)!!.optString(it) }.filter { it.isNotBlank() }
                val d = ApplyGate.Decision(action, a.optInt("fit").coerceIn(0, 100), list("reasons"), list("dealbreakers"))
                r.decision = d
                val why = "AI ${d.fit}: " + (d.dealbreakers + d.reasons).joinToString("; ")
                if (action == "skip" || d.dealbreakers.isNotEmpty()) { r.skipped++; JobLog.add(c, j, d.fit, "skipped", why); "Recorded as skipped. ${r.footer()}" }
                else { r.matched++; JobLog.add(c, j, d.fit, "match", why); (if (action == "apply") "Recorded. Call apply_now if you want to apply. " else "Saved as a match. ") + r.footer() }
            }
            "apply_now" -> {
                val j = r.job
                val why = ApplyGate.refusal(j, r.decision, r.prefs, JobConfig.autoApply(c), !r.apply, JobLog.appliedToday(c), JobLog.appliedKeys(c), r.applied, r.maxApplies, r.kind)
                if (why != null) return "Not applied: $why"
                if (!svc.pressById(APPLY_ID)) { JobLog.add(c, j!!, r.decision!!.fit, "failed", "could not press Apply"); return "Could not press Apply." }
                var outcome = NaukriScreens.AfterApply.OTHER
                Waiter.until(7_000) { outcome = NaukriScreens.afterApply(svc.screenNodes()); outcome != NaukriScreens.AfterApply.OTHER }
                when (outcome) {
                    NaukriScreens.AfterApply.APPLIED -> { r.applied++; JobLog.add(c, j!!, r.decision!!.fit, JobLog.APPLIED, "AI ${r.decision!!.fit}: " + r.decision!!.reasons.joinToString("; ")); "Applied. ${r.footer()}" }
                    NaukriScreens.AfterApply.QUESTIONS -> { JobLog.add(c, j!!, r.decision!!.fit, "needs answers", "Naukri asked questions that are not guessed"); "Naukri asked questions. Not answered. Call back and move on." }
                    else -> { JobLog.add(c, j!!, r.decision!!.fit, "unconfirmed", "pressed Apply but could not see the result; stopped to be safe"); r.done = "Stopped: Apply was pressed but the result could not be confirmed."; "Could not confirm the result. The run stops." }
                }
            }
            "back" -> {
                svc.pressKey("back")
                if (!Waiter.until(6_000) { r.screen() == NaukriScreens.Screen.RESULTS }) { svc.pressKey("back"); if (!Waiter.until(5_000) { r.screen() == NaukriScreens.Screen.RESULTS }) return "Not back on the results list. Use read_screen, or give_up." }
                r.job = null; r.decision = null; "Back on the results list. ${r.footer()}"
            }
            "read_screen" -> svc.snapshot().take(1800)
            "give_up" -> { r.done = "Stopped: ${a.optString("reason")}."; "Stopping." }
            "finish" -> { r.done = "Done: ${a.optString("summary")}"; "Finished." }
            else -> "Unknown tool $name."
        }
    }

    // "retryDelay": "23s" in Google's rate-limit reply -> 23. Null when it names none.
    fun retryDelaySeconds(body: String): Double? = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(body)?.groupValues?.get(1)?.toDoubleOrNull()

    @Volatile private var lastCall = 0L
    private val dailyOut = HashSet<String>()

    // A plain trace of the run in Buddy's own storage (phone logs roll over too fast to rely on). No resume text or answers are written here.
    @Volatile private var traceCtx: Context? = null
    private fun trace(line: String) {
        Log.d("Jarvis", "job agent $line")
        val c = traceCtx ?: return
        runCatching {
            val f = java.io.File(c.filesDir, "job_agent.log")
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val lines = if (f.exists()) f.readLines().takeLast(299) else emptyList()
            f.writeText((lines + "$stamp $line").joinToString("\n") + "\n")
        }
    }

    // A rate limit that names a per-day quota will not clear by waiting a few seconds.
    // "You exceeded your current quota" with no retry delay means the allowance is used up, not that calls came too fast.
    fun isDailyLimit(body: String) = Regex("PerDay|per day|daily", RegexOption.IGNORE_CASE).containsMatchIn(body) ||
        (body.contains("exceeded your current quota", ignoreCase = true) && retryDelaySeconds(body) == null)

    // One Gemini turn with the tools. A rate limit (429) is waited out and retried, not treated as a failure; a missing model (404) or a busy one (500/503) moves on
    // to the next model. Returns null only when every model has failed on every try.
    private fun chat(system: String, contents: JSONArray): JSONObject? {
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", OpenAiBridge.withSignatures(contents)).put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations())))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 900).put("temperature", 0.3))
        for (attempt in 0 until 6) {
            var rateLimited: Double? = null
            for (m in models) {
                if (stopRequested) return null
                if (m in dailyOut) continue
                val wait = 2_500 - (System.currentTimeMillis() - lastCall) // keep a steady pace so the limit is rarely hit
                if (wait > 0) Thread.sleep(wait)
                lastCall = System.currentTimeMillis()
                try {
                    val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent").openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000; conn.readTimeout = 40_000; conn.requestMethod = "POST"
                    conn.setRequestProperty("content-type", "application/json"); conn.setRequestProperty("x-goog-api-key", BuildConfig.API_KEY)
                    conn.doOutput = true; conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = conn.responseCode
                    val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
                    if (code in 200..299) return JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                    trace("$m answered $code: " + text.replace(Regex("AIza[A-Za-z0-9_-]+"), "<key>").replace(Regex("\\s+"), " ").take(220))
                    if (code == 429 && isDailyLimit(text)) { dailyOut += m; continue }
                    if (code == 429) { rateLimited = retryDelaySeconds(text) ?: (6.0 * (attempt + 1)); break }
                    if (code !in setOf(404, 500, 503)) return null
                } catch (e: java.io.IOException) { Log.w("Jarvis", "job agent $m ${e.javaClass.simpleName}: ${e.message}") }
            }
            // Gemini gave no answer this round (quota, rate limit, overload or timeout): do not wait while another provider can answer.
            status = "Asking Groq (Gemini did not answer)"
            groq(system, contents)?.let { return it }
            val secs = rateLimited ?: if (attempt >= 1) return null else 3.0
            status = "Waiting ${secs.toInt()}s for the AI (rate limit)"; trace(status)
            var left = (secs.coerceAtMost(60.0) * 1000).toLong()
            while (left > 0 && !stopRequested) { Thread.sleep(500); left -= 500 }
        }
        return null
    }

    // One turn through Groq. Null when its models are limited or fail, so the caller can wait and try again.
    private fun groq(system: String, contents: JSONArray): JSONObject? {
        val key = BuildConfig.GROQ_KEY
        if (key.isBlank()) { trace("no Groq key in this build"); return null }
        for (m in groqModels) {
            if (stopRequested) return null
            try {
                val body = JSONObject().put("model", m).put("messages", OpenAiBridge.messages(system, contents)).put("tools", OpenAiBridge.tools(declarations()))
                    .put("tool_choice", "auto").put("temperature", 0.3).put("max_tokens", 900)
                val conn = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000; conn.readTimeout = 60_000; conn.requestMethod = "POST"
                conn.setRequestProperty("content-type", "application/json"); conn.setRequestProperty("Authorization", "Bearer $key")
                conn.doOutput = true; conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
                if (code in 200..299) {
                    val msg = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    trace("answered by Groq $m")
                    return OpenAiBridge.toGemini(msg)
                }
                trace("Groq $m answered $code: " + text.replace(Regex("\\s+"), " ").take(160))
            } catch (e: Exception) { trace("Groq $m ${e.javaClass.simpleName}: ${e.message}") }
        }
        return null
    }

    // Leaves Naukri on its results list, not stuck on a job page, whatever way the run ended.
    private fun leaveJobPage(svc: JarvisAccessibilityService) {
        repeat(3) {
            if (NaukriScreens.identify(svc.screenNodes()) != NaukriScreens.Screen.JOB) return
            svc.pressKey("back"); Thread.sleep(1200)
        }
    }
}

// One place for the rest of the app to start, stop and read the job hunt: the AI agent when the AI is on and has read the resume, else the plain rules.
object JobRunner {
    private fun agent(c: Context) = JobConfig.useAI(c) && JobAI.profile(c) != null
    val running get() = JobAgent.running || JobHunt.running
    val status get() = if (JobAgent.running || JobAgent.status != "Not run yet") JobAgent.status else JobHunt.status
    fun start(c: Context, apply: Boolean) = if (agent(c)) JobAgent.start(c, apply) else JobHunt.start(c, apply)
    fun stop() { JobAgent.stop(); JobHunt.stop() }
}
