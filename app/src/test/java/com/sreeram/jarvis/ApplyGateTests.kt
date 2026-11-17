package com.sreeram.jarvis

import com.sreeram.jarvis.jobs.ApplyGate
import com.sreeram.jarvis.jobs.Job
import com.sreeram.jarvis.jobs.JobLimits
import com.sreeram.jarvis.jobs.JobMatcher
import com.sreeram.jarvis.jobs.JobPrefs
import com.sreeram.jarvis.jobs.NaukriScreens.ApplyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplyGateTests {
    private val prefs = JobPrefs(listOf("Full Stack Developer"), listOf("Hyderabad"), 10.0, 3, 60, 10, listOf("intern"))
    private val job = Job("Java Full Stack Developer", "Tech Mahindra", "Hyderabad, Bengaluru, Chennai", "6-20 Lacs PA", "2d ago",
        JobMatcher.splitSkills("Java, Restfull API, System Design, Spring Boot, "), emptyList())
    private val apply = ApplyGate.Decision("apply", 80, listOf("matches my stack"), emptyList())

    private fun refusal(
        job: Job? = this.job, decision: ApplyGate.Decision? = apply, prefs: JobPrefs = this.prefs,
        autoApply: Boolean = true, previewOnly: Boolean = false, appliedToday: Int = 0, alreadyApplied: Set<String> = emptySet(),
        appliedThisRun: Int = 0, maxThisRun: Int = 5, kind: ApplyKind = ApplyKind.IN_APP,
    ) = ApplyGate.refusal(job, decision, prefs, autoApply, previewOnly, appliedToday, alreadyApplied, appliedThisRun, maxThisRun, kind)

    @Test fun allowsAGoodJobWhenEverythingIsOn() = assertNull(refusal())

    @Test fun refusesWithNoOpenJob() = assertEquals("No job is open.", refusal(job = null))

    @Test fun needsAnApplyDecisionFirst() {
        assertNotNull(refusal(decision = null))
        assertNotNull(refusal(decision = ApplyGate.Decision("skip", 90, emptyList(), emptyList())))
    }

    @Test fun autoApplyOffMeansNothingIsApplied() {
        assertTrue(refusal(autoApply = false)!!.startsWith("Preview only"))
        assertTrue(refusal(previewOnly = true)!!.startsWith("Preview only"))
    }

    @Test fun excludedWordsBlockTheJob() {
        val intern = job.copy(title = "Java Full Stack Developer Intern")
        assertTrue(refusal(job = intern)!!.contains("breaks your filters"))
    }

    @Test fun aNamedDealbreakerBlocksEvenAHighFit() {
        val d = ApplyGate.Decision("apply", 95, emptyList(), listOf("night shifts"))
        assertTrue(refusal(decision = d)!!.contains("night shifts"))
    }

    @Test fun fitUnderTheMinimumIsRefusedAndTheMinimumItselfPasses() {
        assertTrue(refusal(decision = ApplyGate.Decision("apply", 59, emptyList(), emptyList()))!!.contains("under the minimum 60"))
        assertNull(refusal(decision = ApplyGate.Decision("apply", 60, emptyList(), emptyList())))
    }

    @Test fun neverAppliesTwiceToTheSameJob() {
        assertEquals("Already applied to this job.", refusal(alreadyApplied = setOf(JobLimits.key(job))))
    }

    @Test fun dailyCapStopsAtTheLimit() {
        assertNull(refusal(appliedToday = 9))
        assertTrue(refusal(appliedToday = 10)!!.contains("daily limit of 10"))
    }

    @Test fun perRunLimitStopsAtTheLimit() {
        assertNull(refusal(appliedThisRun = 4, maxThisRun = 5))
        assertTrue(refusal(appliedThisRun = 5, maxThisRun = 5)!!.contains("run's limit of 5"))
    }

    @Test fun onlyInAppApplyIsAllowed() {
        assertNull(refusal(kind = ApplyKind.IN_APP))
        assertTrue(refusal(kind = ApplyKind.EXTERNAL)!!.contains("company's site"))
        assertTrue(refusal(kind = ApplyKind.ALREADY)!!.contains("already applied"))
        assertTrue(refusal(kind = ApplyKind.NONE)!!.contains("No Apply button"))
    }

    @Test fun theAiDecisionAloneCannotOverrideAFailedCheck() {
        // A confident apply at fit 100 still loses to every hard rule.
        val sure = ApplyGate.Decision("apply", 100, listOf("perfect"), emptyList())
        assertNotNull(refusal(decision = sure, autoApply = false))
        assertNotNull(refusal(decision = sure, appliedToday = 10))
        assertNotNull(refusal(decision = sure, kind = ApplyKind.EXTERNAL))
    }
}
