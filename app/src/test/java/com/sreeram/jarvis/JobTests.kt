package com.sreeram.jarvis

import com.sreeram.jarvis.jobs.Job
import com.sreeram.jarvis.jobs.JobLimits
import com.sreeram.jarvis.jobs.JobMatcher
import com.sreeram.jarvis.jobs.JobPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobTests {
    private val prefs = JobPrefs(listOf("Full Stack Developer"), listOf("Hyderabad"), 10.0, 3, 60, 10, listOf("intern"))
    // The Tech Mahindra posting read from Naukri on the phone.
    private val job = Job("Java Full Stack Developer", "Tech Mahindra", "Hyderabad, Bengaluru, Chennai", "6-20 Lacs PA", "2d ago",
        JobMatcher.splitSkills("Java, Restfull API, System Design, Spring Boot, "), emptyList())
    private val resume = setOf("java", "rest api", "spring boot", "system design", "angular")

    @Test fun ageReadsNaukriAndLinkedInWording() {
        assertEquals(2, JobMatcher.ageDays("2d ago")); assertEquals(14, JobMatcher.ageDays("Posted 14d ago"))
        assertEquals(7, JobMatcher.ageDays("1 week ago")); assertEquals(30, JobMatcher.ageDays("1 month ago"))
        assertEquals(0, JobMatcher.ageDays("Just now")); assertNull(JobMatcher.ageDays("Be an early applicant"))
    }

    @Test fun salaryTakesTheTopOfTheRange() {
        assertEquals(20.0, JobMatcher.topLakhs("6-20 Lacs PA")!!, 0.0)
        assertEquals(12.5, JobMatcher.topLakhs("₹ 12.5 Lacs P.A.")!!, 0.0)
        assertEquals(100.0, JobMatcher.topLakhs("1 Cr")!!, 0.0)
        assertNull(JobMatcher.topLakhs("Not disclosed"))
    }

    @Test fun misspeltAndAliasedSkillsStillMatch() {
        assertEquals(listOf("java", "rest api", "system design", "spring boot"), JobMatcher.splitSkills("Java, Restfull API, System Design, Spring Boot, "))
        val found = JobMatcher.resumeSkills("Built Node and ReactJS apps with Postgres. Worked at Google.", listOf("node.js", "react", "postgresql", "go"))
        assertEquals(setOf("node.js", "react", "postgresql"), found) // "go" must not match "Google"
    }

    @Test fun strongMatchApplies() {
        val v = JobMatcher.judge(job, resume, prefs)
        assertTrue(v.why, v.apply); assertEquals(100, v.score)
    }

    @Test fun missingRequiredSkillsLowersTheScoreAndSaysWhich() {
        val v = JobMatcher.judge(job, setOf("java"), prefs)
        assertFalse(v.apply); assertTrue(v.why, v.why.contains("missing")); assertTrue(v.score < 60)
    }

    @Test fun filtersRejectWithAReason() {
        assertTrue(JobMatcher.judge(job.copy(posted = "9d ago"), resume, prefs).why.contains("9 days"))
        assertTrue(JobMatcher.judge(job.copy(ctc = "3-6 Lacs PA"), resume, prefs).why.contains("under your 10"))
        assertTrue(JobMatcher.judge(job.copy(location = "Pune"), resume, prefs).why.contains("locations"))
        assertTrue(JobMatcher.judge(job.copy(title = "Full Stack Developer Intern"), resume, prefs).why.contains("excluded"))
        assertTrue(JobMatcher.judge(job.copy(title = "Data Analyst"), resume, prefs).why.contains("roles"))
    }

    @Test fun noRequiredSkillsMeansNoBlindApply() {
        assertFalse(JobMatcher.judge(job.copy(mustSkills = emptyList()), resume, prefs).apply)
    }

    @Test fun neverTwiceAndNeverOverTheCap() {
        assertFalse(JobLimits.canApply(10, 10)); assertTrue(JobLimits.canApply(9, 10))
        val seen = setOf(JobLimits.key(job))
        assertTrue(JobLimits.alreadyApplied(job.copy(company = "Tech  Mahindra"), seen))
        assertFalse(JobLimits.alreadyApplied(job.copy(company = "Apple"), seen))
    }
}

class TitleTests {
    private val prefs = JobPrefs(listOf("Full Stack Developer"), listOf("Hyderabad"), 0.0, 3, 60, 10, emptyList())
    private fun job(t: String) = Job(t, "X", "Hyderabad", "", "1d ago", listOf("java"), emptyList())

    @Test fun differentSpellingsOfTheSameRoleAreAccepted() {
        for (t in listOf("Fullstack Engineer", "Full-Stack Developer", "Java Full Stack Developer", "Sr. Full stack developer- React js", "Full Stack Software Engineer"))
            assertEquals(t, null, JobMatcher.reject(job(t), prefs))
        assertTrue(JobMatcher.reject(job("Senior Developer"), prefs)!!.contains("roles"))
        assertTrue(JobMatcher.reject(job("Data Analyst"), prefs)!!.contains("roles"))
    }

    @Test fun aJobWithNoSkillsListIsLeftToTheAIOnlyWhenAsked() {
        val bare = job("Full Stack Developer").copy(mustSkills = emptyList())
        assertFalse(JobMatcher.judge(bare, setOf("java"), prefs).apply)
        assertTrue(JobMatcher.judge(bare, setOf("java"), prefs, requireSkills = false).apply)
    }
}
