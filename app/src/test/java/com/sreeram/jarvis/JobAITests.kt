package com.sreeram.jarvis

import com.sreeram.jarvis.jobs.Assessment
import com.sreeram.jarvis.jobs.Job
import com.sreeram.jarvis.jobs.JobAI
import com.sreeram.jarvis.jobs.NaukriScreens
import com.sreeram.jarvis.jobs.Profile
import com.sreeram.jarvis.jobs.descriptionText
import com.sreeram.jarvis.msg.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobAITests {
    @Test fun profileIsReadEvenWhenWrappedInAMarkdownFence() {
        val raw = "```json\n{\"titles\":[\"Software Engineer\"],\"searchRoles\":[\"Full Stack Developer\",\"Java Developer\",\"Full Stack Developer\"],\"seniority\":\"mid\",\"years\":4,\"skills\":[\"Java\",\"Angular\"]}\n```"
        val p = JobAI.parseProfile(raw)!!
        assertEquals(listOf("Full Stack Developer", "Java Developer"), p.searchRoles) // duplicates dropped
        assertEquals(4, p.years); assertEquals("mid", p.seniority)
    }

    @Test fun anUnusableProfileReplyIsRejectedNotGuessed() {
        assertNull(JobAI.parseProfile("Sorry, I cannot read that"))
        assertNull(JobAI.parseProfile("{\"titles\":[\"x\"],\"searchRoles\":[]}"))
    }

    @Test fun aDealbreakerOverrulesAHighScore() {
        val a = JobAI.parseAssessment("{\"score\":90,\"fit\":\"strong\",\"reasons\":[\"stack matches\"],\"dealbreakers\":[\"needs 10 years, candidate has 4\"]}")!!
        assertEquals("no", a.fit); assertFalse(JobAI.shouldApply(a, 70))
    }

    @Test fun strongFitAboveTheThresholdApplies() {
        val a = JobAI.parseAssessment("{\"score\":82,\"fit\":\"strong\",\"reasons\":[\"Java and Angular match\"],\"dealbreakers\":[]}")!!
        assertTrue(JobAI.shouldApply(a, 70)); assertFalse(JobAI.shouldApply(a, 90))
        assertFalse(JobAI.shouldApply(Assessment(95, "no", emptyList(), emptyList()), 70))
    }

    @Test fun scoreIsKeptInRangeAndBadRepliesAreNull() {
        assertEquals(100, JobAI.parseAssessment("{\"score\":250,\"fit\":\"strong\"}")!!.score)
        assertNull(JobAI.parseAssessment("not json")); assertNull(JobAI.parseAssessment("{\"fit\":\"strong\"}"))
    }

    @Test fun requestsCarryTheFactsTheModelNeeds() {
        val p = Profile(listOf("Software Engineer"), listOf("Java Developer"), "mid", 4, listOf("Java", "Angular"))
        val job = Job("Java Full Stack Developer", "Tech Mahindra", "Hyderabad", "6-20 Lacs PA", "Posted 2d ago", listOf("java", "spring boot"), emptyList())
        val text = JobAI.assessRequest(p, job, "x".repeat(20_000))
        assertTrue(text.contains("mid, 4 years")); assertTrue(text.contains("Tech Mahindra")); assertTrue(text.contains("java, spring boot"))
        assertTrue(text.length < 7_000) // a long description is cut, not sent whole
    }

    @Test fun descriptionIsEverythingBelowItsHeadingExceptTheApplyButton() {
        fun n(id: String, t: String) = ScreenNode(id, t, null, 0, 0, 1, 1)
        val nodes = listOf(n("tv_title", "Java Full Stack Developer"), n("textViewHeader", "Job description"), n("txtRolesRespHeader", "What you’ll do"), n("", "Build REST APIs in Spring Boot"), n("tvApply", "Apply now"))
        assertEquals(listOf("What you’ll do", "Build REST APIs in Spring Boot"), NaukriScreens.descriptionText(nodes))
        assertTrue(NaukriScreens.descriptionText(listOf(n("tv_title", "x"))).isEmpty())
        assertNotNull(nodes)
    }
}
