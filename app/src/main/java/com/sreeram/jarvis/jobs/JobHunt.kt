package com.sreeram.jarvis.jobs

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import com.sreeram.jarvis.JarvisAccessibilityService
import com.sreeram.jarvis.msg.Waiter

// Searches Naukri with the user's Job hunt settings, scores every job against the resume, and (only when asked to) applies.
// It reads the screen after every step and stops the moment something is not what it expects: it never reports an application it did not see.
object JobHunt {
    private const val PKG = "naukriApp.appModules.login"
    private const val APPLY_ID = "$PKG:id/tvApply"
    private const val MAX_JOBS = 40

    @Volatile var running = false; private set
    @Volatile var status = "Not run yet"; private set
    @Volatile private var stopRequested = false
    fun stop() { stopRequested = true }

    private class Tally { var seen = 0; var skipped = 0; var matched = 0; var applied = 0; var needAnswers = 0; var external = 0 }

    // apply = false only previews: nothing is pressed except opening jobs. maxApplies limits this run on top of the daily cap.
    fun start(c: Context, apply: Boolean, maxApplies: Int = Int.MAX_VALUE): String {
        if (running) return "Job hunt is already running: $status"
        val svc = JarvisAccessibilityService.instance ?: return "Accessibility is off, so I cannot drive Naukri. Turn it on in Settings."
        if (!ResumeStore.hasResume(c)) return "Add your resume first: Settings, Job hunt, Choose resume."
        if (ResumeStore.skills(c).isEmpty()) return "I found no skills in your resume, so I cannot match jobs. Check the file is a text PDF."
        val pm = c.getSystemService(PowerManager::class.java); val km = c.getSystemService(KeyguardManager::class.java)
        if (!pm.isInteractive || km.isKeyguardLocked) return "The phone is locked. Unlock it and start again."
        if (c.packageManager.getLaunchIntentForPackage(PKG) == null) return "Naukri is not installed."
        running = true; stopRequested = false; status = "Starting"
        Thread { try { run(c.applicationContext, svc, apply && JobConfig.autoApply(c), maxApplies) } catch (e: Exception) { Log.w("Jarvis", "job hunt failed", e); status = "Stopped: ${e.message}" } finally { running = false } }.start()
        return if (apply && !JobConfig.autoApply(c)) "Started as a preview, because \"Let Buddy apply on its own\" is off. Nothing will be applied." else "Started."
    }

    fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    fun url(role: String, location: String, maxAgeDays: Int): String {
        val age = when { maxAgeDays <= 1 -> 1; maxAgeDays <= 3 -> 3; maxAgeDays <= 7 -> 7; maxAgeDays <= 15 -> 15; else -> 30 }
        return "https://www.naukri.com/${slug(role)}-jobs-in-${slug(location)}?jobAge=$age"
    }

    private fun run(c: Context, svc: JarvisAccessibilityService, apply: Boolean, maxApplies: Int) {
        val prefs = JobConfig.prefs(c); val resume = ResumeStore.skills(c)
        val tally = Tally(); val seen = HashSet<String>()
        val h = c.resources.displayMetrics.heightPixels
        fun nodes() = svc.screenNodes()
        fun screen() = NaukriScreens.identify(nodes())
        fun onNaukri() = svc.currentPackage() == PKG
        fun back() = svc.pressKey("back")
        fun live() = !stopRequested && onNaukri()

        outer@ for (role in prefs.roles.take(6)) for (loc in prefs.locations.take(2)) {
            if (stopRequested) break@outer
            status = "Searching $role in $loc"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url(role, loc, prefs.maxAgeDays))).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            svc.startActivity(intent)
            if (!Waiter.until(15_000) { screen() == NaukriScreens.Screen.RESULTS }) { status = "Naukri did not show results for $role in $loc"; continue }

            var idle = 0
            while (idle < 2 && tally.seen < MAX_JOBS && live()) {
                val cards = NaukriScreens.parseResults(nodes(), h).filter { JobLimits.key(it.job) !in seen }
                if (cards.isEmpty()) { idle++; svc.scroll("down"); Thread.sleep(1200); continue }
                idle = 0
                for (card in cards) {
                    if (!live() || tally.seen >= MAX_JOBS) break
                    seen += JobLimits.key(card.job); tally.seen++
                    status = "Looking at ${card.job.title} at ${card.job.company}"
                    val early = JobMatcher.reject(card.job, prefs)
                    if (early != null) { tally.skipped++; JobLog.add(c, card.job, 0, "skipped", early); continue }
                    if (JobLimits.alreadyApplied(card.job, JobLog.appliedKeys(c))) { tally.skipped++; JobLog.add(c, card.job, 0, "skipped", "already applied"); continue }

                    svc.tapXY(card.tapX.toFloat(), card.tapY.toFloat())
                    if (!Waiter.until(8_000) { screen() == NaukriScreens.Screen.JOB && NaukriScreens.parseJob(nodes()) != null }) { status = "Could not open ${card.job.title}"; back(); Waiter.until(5_000) { screen() == NaukriScreens.Screen.RESULTS }; continue }
                    var job = NaukriScreens.parseJob(nodes())!!
                    var tries = 0
                    while (job.mustSkills.isEmpty() && tries++ < 2) { svc.scroll("down"); Thread.sleep(900); NaukriScreens.parseJob(nodes())?.let { job = it } ; if (job.mustSkills.isNotEmpty()) break }
                    if (job.mustSkills.isEmpty()) Log.d("Jarvis", "job page without skills, nodes: " + nodes().take(70).joinToString(" | ") { "${it.id}:${(it.text ?: it.desc).orEmpty().take(30)}" })
                    val full = job.copy(location = job.location.ifEmpty { card.job.location }, ctc = job.ctc.ifEmpty { card.job.ctc }, posted = job.posted.ifEmpty { card.job.posted })
                    val kind = NaukriScreens.applyKind(nodes()) // read now: the page is about to be scrolled
                    val ai = JobConfig.useAI(c) && JobAI.profile(c) != null
                    // With AI on, the rules only enforce your hard filters (role, place, age, pay, excluded words); the AI judges the fit.
                    val rules = JobMatcher.judge(full, resume, if (ai) prefs.copy(minScore = 0) else prefs, requireSkills = !ai)
                    var verdict = rules
                    if (ai && rules.apply) {
                        status = "Reading ${job.title} at ${job.company}"
                        val text = LinkedHashSet<String>()
                        repeat(3) { text.addAll(NaukriScreens.descriptionText(nodes())); svc.scroll("down"); Thread.sleep(800) }
                        val a = JobAI.assess(c, full, text.joinToString("\n"))
                        verdict = when {
                            a == null -> if (rules.score >= prefs.minScore) rules else Verdict(false, rules.score, "AI was unavailable and the skill match was ${rules.score}, under your ${prefs.minScore}")
                            JobAI.shouldApply(a, prefs.minScore) -> Verdict(true, a.score, "AI ${a.score}, ${a.fit}: " + a.reasons.joinToString("; "))
                            else -> Verdict(false, a.score, "AI ${a.score}, ${a.fit}: " + (a.dealbreakers + a.reasons).joinToString("; "))
                        }
                    }
                    if (!verdict.apply) { tally.skipped++; JobLog.add(c, job, verdict.score, "skipped", verdict.why) }
                    else {
                        tally.matched++
                        when {
                            kind == NaukriScreens.ApplyKind.ALREADY -> JobLog.add(c, job, verdict.score, "skipped", "Naukri says you already applied")
                            kind == NaukriScreens.ApplyKind.EXTERNAL -> { tally.external++; JobLog.add(c, job, verdict.score, "external", "applies on the company site: ${verdict.why}") }
                            kind != NaukriScreens.ApplyKind.IN_APP -> JobLog.add(c, job, verdict.score, "skipped", "no Apply button found")
                            !apply -> JobLog.add(c, job, verdict.score, "match", "would apply: ${verdict.why}")
                            !JobLimits.canApply(JobLog.appliedToday(c), prefs.dailyCap) || tally.applied >= maxApplies -> { JobLog.add(c, job, verdict.score, "match", "limit reached, not applied: ${verdict.why}"); stopRequested = true }
                            else -> if (!applyOne(c, svc, job, verdict, tally)) stopRequested = true
                        }
                    }
                    back()
                    if (!Waiter.until(6_000) { screen() == NaukriScreens.Screen.RESULTS }) { back(); if (!Waiter.until(5_000) { screen() == NaukriScreens.Screen.RESULTS }) { status = "Lost my place in Naukri"; break@outer } }
                }
                if (live()) { svc.scroll("down"); Thread.sleep(1200) }
            }
        }
        status = (if (stopRequested && !onNaukri()) "Stopped: you left Naukri. " else if (stopRequested) "Stopped. " else "Done. ") +
            "Looked at ${tally.seen}, skipped ${tally.skipped}, ${tally.matched} matched" +
            (if (apply) ", applied to ${tally.applied}" else " (preview, nothing applied)") +
            (if (tally.external > 0) ", ${tally.external} apply on the company site" else "") + (if (tally.needAnswers > 0) ", ${tally.needAnswers} need your answers" else "") + "."
        Log.d("Jarvis", "job hunt: $status")
    }

    // Presses Apply and reads the screen. Returns false when the outcome could not be confirmed, which stops the run.
    private fun applyOne(c: Context, svc: JarvisAccessibilityService, job: Job, v: Verdict, tally: Tally): Boolean {
        status = "Applying to ${job.title} at ${job.company}"
        if (!svc.pressById(APPLY_ID)) { JobLog.add(c, job, v.score, "failed", "could not press Apply"); return true }
        var outcome = NaukriScreens.AfterApply.OTHER
        Waiter.until(7_000) { outcome = NaukriScreens.afterApply(svc.screenNodes()); outcome != NaukriScreens.AfterApply.OTHER }
        when (outcome) {
            NaukriScreens.AfterApply.APPLIED -> { tally.applied++; JobLog.add(c, job, v.score, JobLog.APPLIED, v.why); return true }
            NaukriScreens.AfterApply.QUESTIONS -> { tally.needAnswers++; JobLog.add(c, job, v.score, "needs answers", "Naukri asked questions I will not guess"); return true }
            else -> { JobLog.add(c, job, v.score, "unconfirmed", "pressed Apply but could not see the result; stopped to be safe"); return false }
        }
    }
}
