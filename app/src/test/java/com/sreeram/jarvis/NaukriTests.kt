package com.sreeram.jarvis

import com.sreeram.jarvis.jobs.JobMatcher
import com.sreeram.jarvis.jobs.JobPrefs
import com.sreeram.jarvis.jobs.NaukriScreens
import com.sreeram.jarvis.jobs.NaukriScreens.AfterApply
import com.sreeram.jarvis.jobs.NaukriScreens.ApplyKind
import com.sreeram.jarvis.jobs.NaukriScreens.Screen
import com.sreeram.jarvis.msg.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Fixtures are the real Naukri screens read from the phone on 16 Nov 2026 (ids and bounds as the app reported them).
class NaukriTests {
    private fun n(id: String, text: String?, l: Int = 0, t: Int = 0, r: Int = 100, b: Int = 50, editable: Boolean = false, clickable: Boolean = false) =
        ScreenNode(id, text, null, l, t, r, b, editable, clickable)

    private val results = listOf(
        n("editTextSearch", "Results for full stack dev..., hyderabad", 270, 234, 1128, 306),
        n("textViewHelperText", "511 results", 48, 378, 242, 458),
        n("textViewDesignation", "Java Full Stack Developer", 84, 524, 966, 598),
        n("textViewCompanyName", "Tech Mahindra", 84, 598, 658, 663),
        n("textViewConsultantName", "Posted by Talent21", 84, 663, 735, 728),
        n("textViewLocation", "Hyderabad, Bengaluru, Chennai", 84, 728, 737, 820),
        n("textViewCTC", "6-20 Lacs PA", 96, 894, 1140, 968),
        n("textViewDate", "2d ago", 357, 1090, 465, 1137),
        n("textViewDesignation", "Java Full Stack Developer", 84, 1279, 966, 1353),
        n("textViewCompanyName", "Cloudxtreme", 84, 1353, 621, 1418),
        n("textViewStar", "3.3 (8 Reviews)", 84, 1418, 375, 1478),
        n("textViewLocation", "Hyderabad, Bengaluru, Chennai", 84, 1478, 737, 1570),
        n("textViewCTC", "15-30 Lacs PA", 96, 1644, 1140, 1718),
        n("textViewDate", "3d ago", 84, 1834, 193, 1881),
        n("textViewDesignation", "Full Stack Developer", 84, 2400, 966, 2470), // half hidden behind the filter bar
        n("textViewCompanyName", "Hidden Co", 84, 2470, 600, 2530),
        n("chip_filter", "Distance", 180, 2504, 413, 2600),
    )

    private val jobPage = listOf(
        n("tv_title", "Java Full Stack Developer", 48, 534, 1176, 619),
        n("tv_companyName", "Tech Mahindra", 48, 637, 364, 702),
        n("tv_consultantName", "Posted by Talent21", 48, 702, 454, 785),
        n("", "3042 applicants", 48, 857, 346, 904),
        n("", "Posted 2d ago", 908, 857, 1176, 904),
        n("tv_job_highlights", "Job highlights", 108, 1216, 1116, 1281),
        n("tv_location", "Hyderabad, Bengaluru, Chennai", 96, 1865, 1128, 1921),
        n("tv_salary", "₹ 6-20 Lacs P.A.", 96, 1945, 1128, 2001),
        n("tv_preferred_skill", "Must have skills", 96, 2037, 1128, 2093),
        n("tv_preferred_skill", "Java, Restfull API, System Design, Spring Boot, ", 96, 2093, 1128, 2149),
        n("tv_other_skills_hi", "Good to have skills", 96, 2185, 1128, 2241),
        n("tvApply", "Apply now", 72, 2499, 1152, 2624, clickable = true),
    )

    private val prefs = JobPrefs(listOf("Full Stack Developer"), listOf("Hyderabad"), 0.0, 3, 70, 10, listOf("intern"))

    @Test fun screensAreRecognisedById() {
        assertEquals(Screen.RESULTS, NaukriScreens.identify(results))
        assertEquals(Screen.JOB, NaukriScreens.identify(jobPage))
        assertEquals(Screen.HOME, NaukriScreens.identify(listOf(n("textViewRecoJobsText", "Handpicked jobs for you (67)"))))
        assertEquals(Screen.OTHER, NaukriScreens.identify(listOf(n("chat_message_tv", "Are you still working as a Software Engineer"))))
    }

    @Test fun resultCardsAreGroupedAndOffScreenOnesDropped() {
        val cards = NaukriScreens.parseResults(results, 2720)
        assertEquals(2, cards.size)
        assertEquals("Tech Mahindra", cards[0].job.company); assertEquals("6-20 Lacs PA", cards[0].job.ctc); assertEquals("2d ago", cards[0].job.posted)
        assertEquals("Cloudxtreme", cards[1].job.company); assertEquals("3d ago", cards[1].job.posted) // the rating line does not break the card
        assertEquals(525 to 561, cards[0].tapX to cards[0].tapY)
    }

    @Test fun cardsCanBeRejectedBeforeOpeningThem() {
        val cards = NaukriScreens.parseResults(results, 2720)
        assertNull(JobMatcher.reject(cards[0].job, prefs))
        assertTrue(JobMatcher.reject(cards[1].job.copy(posted = "6d ago"), prefs)!!.contains("6 days"))
    }

    @Test fun jobPageIsReadIncludingSkills() {
        val job = NaukriScreens.parseJob(jobPage)!!
        assertEquals("Java Full Stack Developer", job.title); assertEquals("Tech Mahindra", job.company)
        assertEquals("Posted 2d ago", job.posted); assertEquals(20.0, JobMatcher.topLakhs(job.ctc)!!, 0.0)
        assertEquals(listOf("java", "rest api", "system design", "spring boot"), job.mustSkills)
        assertTrue(job.goodSkills.isEmpty()) // the heading is on screen but its list is below the fold
        assertEquals(2, JobMatcher.ageDays(job.posted))
    }

    @Test fun halfLoadedPageIsNotReadAsAJob() {
        assertNull(NaukriScreens.parseJob(listOf(n("iv_back_btn", "back"))))
    }

    @Test fun applyButtonKindsAreToldApart() {
        assertEquals(ApplyKind.IN_APP, NaukriScreens.applyKind(jobPage))
        assertEquals(ApplyKind.EXTERNAL, NaukriScreens.applyKind(listOf(n("tvApply", "Apply on company site"))))
        assertEquals(ApplyKind.ALREADY, NaukriScreens.applyKind(listOf(n("tvApply", "Applied"))))
        assertEquals(ApplyKind.NONE, NaukriScreens.applyKind(listOf(n("tv_title", "x"))))
    }

    @Test fun onlyAVisibleAppliedCountsAsSuccess() {
        assertEquals(AfterApply.APPLIED, NaukriScreens.afterApply(listOf(n("tvApply", "Applied"))))
        assertEquals(AfterApply.APPLIED, NaukriScreens.afterApply(listOf(n("", "You have successfully applied to this job"))))
        assertEquals(AfterApply.QUESTIONS, NaukriScreens.afterApply(listOf(n("", "Answer a few questions"), n("answer", "", editable = true))))
        assertEquals(AfterApply.OTHER, NaukriScreens.afterApply(jobPage)) // still showing "Apply now": nothing was sent as far as the screen shows
        assertFalse(NaukriScreens.afterApply(jobPage) == AfterApply.APPLIED)
        assertNotNull(NaukriScreens.parseJob(jobPage))
    }
}
