package com.sreeram.jarvis.jobs

// The last check before anything is applied. The AI proposes; this decides. It is plain code, so the AI can never talk its way past it.
object ApplyGate {
    class Decision(val action: String, val fit: Int, val reasons: List<String>, val dealbreakers: List<String>)

    // Returns null when applying is allowed, otherwise the plain reason it is not.
    fun refusal(
        job: Job?, decision: Decision?, prefs: JobPrefs, autoApply: Boolean, previewOnly: Boolean,
        appliedToday: Int, alreadyApplied: Set<String>, appliedThisRun: Int, maxThisRun: Int, kind: NaukriScreens.ApplyKind,
    ): String? {
        if (job == null) return "No job is open."
        if (decision == null || decision.action != "apply") return "Record your decision for this job as apply first."
        if (previewOnly || !autoApply) return "Preview only: auto-apply is off, so nothing is applied."
        JobMatcher.reject(job, prefs)?.let { return "This job breaks your filters ($it)." }
        if (decision.dealbreakers.isNotEmpty()) return "You named a dealbreaker (${decision.dealbreakers.first()}), so this job cannot be applied to."
        if (decision.fit < prefs.minScore) return "Fit ${decision.fit} is under the minimum ${prefs.minScore}."
        if (JobLimits.alreadyApplied(job, alreadyApplied)) return "Already applied to this job."
        if (!JobLimits.canApply(appliedToday, prefs.dailyCap)) return "The daily limit of ${prefs.dailyCap} applications is reached."
        if (appliedThisRun >= maxThisRun) return "This run's limit of $maxThisRun applications is reached."
        return when (kind) {
            NaukriScreens.ApplyKind.IN_APP -> null
            NaukriScreens.ApplyKind.EXTERNAL -> "This job applies on the company's site, which Buddy does not do."
            NaukriScreens.ApplyKind.ALREADY -> "Naukri says it is already applied."
            NaukriScreens.ApplyKind.NONE -> "No Apply button is on the screen."
        }
    }
}
