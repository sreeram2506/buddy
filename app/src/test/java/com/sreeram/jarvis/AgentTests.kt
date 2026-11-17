package com.sreeram.jarvis

import com.sreeram.jarvis.jobs.ApplyGate
import com.sreeram.jarvis.jobs.Job
import com.sreeram.jarvis.jobs.JobAgent
import com.sreeram.jarvis.jobs.JobLimits
import com.sreeram.jarvis.jobs.JobPrefs
import com.sreeram.jarvis.jobs.OpenAiBridge
import com.sreeram.jarvis.jobs.NaukriScreens.ApplyKind
import com.sreeram.jarvis.jobs.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTests {
    private val prefs = JobPrefs(listOf("Full Stack Developer"), listOf("Hyderabad"), 10.0, 3, 70, 10, listOf("intern"))
    private val job = Job("Java Full Stack Developer", "Tech Mahindra", "Hyderabad, Bengaluru", "6-20 Lacs PA", "Posted 2d ago", listOf("java"), emptyList())
    private fun yes(fit: Int = 85, dealbreakers: List<String> = emptyList()) = ApplyGate.Decision("apply", fit, listOf("stack matches"), dealbreakers)
    private fun gate(
        j: Job? = job, d: ApplyGate.Decision? = yes(), auto: Boolean = true, preview: Boolean = false, today: Int = 0, applied: Set<String> = emptySet(),
        run: Int = 0, max: Int = 5, kind: ApplyKind = ApplyKind.IN_APP, p: JobPrefs = prefs,
    ) = ApplyGate.refusal(j, d, p, auto, preview, today, applied, run, max, kind)

    @Test fun aGoodFitWithEverythingInOrderIsAllowed() = assertNull(gate())

    @Test fun theAICannotApplyWithoutDecidingApplyFirst() {
        assertTrue(gate(d = null)!!.contains("decision"))
        assertTrue(gate(d = ApplyGate.Decision("skip", 90, emptyList(), emptyList()))!!.contains("decision"))
    }

    @Test fun autoApplyOffOrPreviewNeverApplies() {
        assertTrue(gate(auto = false)!!.contains("Preview"))
        assertTrue(gate(preview = true)!!.contains("Preview"))
    }

    @Test fun aDealbreakerOrLowFitStopsItEvenIfTheAIAsksToApply() {
        assertTrue(gate(d = yes(95, listOf("needs 10 years")))!!.contains("dealbreaker"))
        assertTrue(gate(d = yes(60))!!.contains("under the minimum"))
    }

    @Test fun theUsersHardFiltersCannotBeOverruled() {
        assertTrue(gate(j = job.copy(title = "Full Stack Developer Intern"))!!.contains("filters"))
        assertTrue(gate(j = job.copy(ctc = "3-6 Lacs PA"))!!.contains("filters"))
        assertTrue(gate(j = job.copy(posted = "Posted 9d ago"))!!.contains("filters"))
        assertTrue(gate(j = job.copy(location = "Pune"))!!.contains("filters"))
    }

    @Test fun limitsAndRepeatsAreEnforced() {
        assertTrue(gate(today = 10)!!.contains("daily limit"))
        assertTrue(gate(run = 5, max = 5)!!.contains("run's limit"))
        assertTrue(gate(applied = setOf(JobLimits.key(job)))!!.contains("Already applied"))
    }

    @Test fun onlyAnInAppApplyButtonIsPressed() {
        assertTrue(gate(kind = ApplyKind.EXTERNAL)!!.contains("company's site"))
        assertTrue(gate(kind = ApplyKind.ALREADY)!!.contains("already"))
        assertTrue(gate(kind = ApplyKind.NONE)!!.contains("No Apply button"))
        assertTrue(gate(j = null)!!.contains("No job"))
    }

    @Test fun theToolsAreExactlyTheOnesTheAgentMayUse() {
        val names = (0 until JobAgent.declarations().length()).map { JobAgent.declarations().getJSONObject(it).getString("name") }.toSet()
        assertEquals(setOf("open_search", "list_results", "scroll_results", "open_job", "read_description", "decide", "apply_now", "back", "read_screen", "give_up", "finish"), names)
    }

    @Test fun thePromptCarriesThePersonTheirLimitsAndTheRules() {
        val p = Profile(listOf("Software Engineer"), listOf("Java Developer"), "mid", 4, listOf("Java", "Angular"))
        val text = JobAgent.systemPrompt(p, prefs, "RESUME TEXT", apply = true)
        for (must in listOf("mid, 4 years", "Java, Angular", "Hyderabad", "intern", "at least 10 lakhs", "RESUME TEXT", "dealbreaker", "Never guess answers", "fit at least 70"))
            assertTrue(must, text.contains(must))
        assertTrue(JobAgent.systemPrompt(p, prefs, "r", apply = false).contains("nothing will be applied"))
    }
}

class RateLimitTests {
    @Test fun theWaitGoogleAsksForIsRead() {
        assertEquals(23.0, JobAgent.retryDelaySeconds("{\"error\":{\"code\":429,\"details\":[{\"retryDelay\": \"23s\"}]}}")!!, 0.0)
        assertEquals(7.5, JobAgent.retryDelaySeconds("\"retryDelay\":\"7.5s\"")!!, 0.0)
        assertNull(JobAgent.retryDelaySeconds("{\"error\":{\"code\":429}}"))
    }
}

class QuotaTests {
    @Test fun aPerDayLimitIsToldApartFromAPerMinuteOne() {
        assertTrue(JobAgent.isDailyLimit("{\"quotaId\":\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}"))
        org.junit.Assert.assertFalse(JobAgent.isDailyLimit("{\"quotaId\":\"GenerateRequestsPerMinutePerProjectPerModel-FreeTier\",\"retryDelay\":\"23s\"}"))
    }
}

class OpenAiBridgeTests {
    private val calls = org.json.JSONArray(
        """[{"role":"user","parts":[{"text":"Start the job hunt."}]},
            {"role":"model","parts":[{"functionCall":{"name":"open_search","args":{"role":"Full Stack Developer","location":"Hyderabad"}}}]},
            {"role":"user","parts":[{"functionResponse":{"name":"open_search","response":{"result":"Results: 583 results"}}}]},
            {"role":"model","parts":[{"functionCall":{"name":"list_results","args":{}}}]},
            {"role":"user","parts":[{"functionResponse":{"name":"list_results","response":{"result":"1. Full Stack Developer | X"}}}]}]""")

    @Test fun toolsKeepTheirShapeWithLowerCaseTypes() {
        val t = OpenAiBridge.tools(JobAgent.declarations())
        assertEquals(11, t.length())
        val open = (0 until t.length()).map { t.getJSONObject(it).getJSONObject("function") }.first { it.getString("name") == "open_search" }
        assertEquals("object", open.getJSONObject("parameters").getString("type"))
        assertEquals("string", open.getJSONObject("parameters").getJSONObject("properties").getJSONObject("role").getString("type"))
        val none = (0 until t.length()).map { t.getJSONObject(it).getJSONObject("function") }.first { it.getString("name") == "list_results" }
        assertEquals("object", none.getJSONObject("parameters").getString("type")) // a tool with no parameters still gets an empty schema
    }

    @Test fun everyToolResultFollowsTheCallThatAskedForIt() {
        val m = OpenAiBridge.messages("SYSTEM", calls)
        val roles = (0 until m.length()).map { m.getJSONObject(it).getString("role") }
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant", "tool"), roles)
        assertEquals(m.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id"), m.getJSONObject(3).getString("tool_call_id"))
        assertEquals(m.getJSONObject(4).getJSONArray("tool_calls").getJSONObject(0).getString("id"), m.getJSONObject(5).getString("tool_call_id"))
        assertTrue(m.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments").contains("Hyderabad"))
    }

    @Test fun onlyTheRecentTurnsAreSentAndTheyStartOnAModelTurn() {
        val m = OpenAiBridge.messages("SYSTEM", calls, keepLast = 3)
        val roles = (0 until m.length()).map { m.getJSONObject(it).getString("role") }
        assertEquals(listOf("system", "user", "assistant", "tool"), roles) // the first kept turn would have been a bare tool result, so it starts one turn later
        assertTrue(m.getJSONObject(1).getString("content").startsWith("Continue"))
    }

    @Test fun aGroqReplyBecomesAGeminiStyleTurn() {
        val msg = org.json.JSONObject("""{"role":"assistant","content":null,"tool_calls":[{"id":"x","type":"function","function":{"name":"decide","arguments":"{\"action\":\"skip\",\"fit\":20,\"reasons\":[\"wrong stack\"]}"}}]}""")
        val g = OpenAiBridge.toGemini(msg)
        val call = g.getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall")
        assertEquals("decide", call.getString("name")); assertEquals(20, call.getJSONObject("args").getInt("fit"))
        assertEquals("model", g.getString("role"))
    }
}

class QuotaWordingTests {
    @Test fun anExhaustedQuotaIsNotMistakenForARateLimit() {
        val used = "{\"error\":{\"code\":429,\"message\":\"You exceeded your current quota, please check your plan and billing details.\"}}"
        assertTrue(JobAgent.isDailyLimit(used))
        org.junit.Assert.assertFalse(JobAgent.isDailyLimit(used.replace("}}", ",\"details\":[{\"retryDelay\":\"23s\"}]}}")))
    }
}

class SignatureTests {
    @Test fun callsFromAnotherModelGetTheBypassSignatureWithoutChangingTheOriginal() {
        val c = org.json.JSONArray("""[{"role":"model","parts":[{"functionCall":{"name":"back","args":{}}},{"functionCall":{"name":"x","args":{}},"thoughtSignature":"real"}]}]""")
        val out = OpenAiBridge.withSignatures(c)
        val parts = out.getJSONObject(0).getJSONArray("parts")
        assertEquals("skip_thought_signature_validator", parts.getJSONObject(0).getString("thoughtSignature"))
        assertEquals("real", parts.getJSONObject(1).getString("thoughtSignature")) // a real signature is kept
        assertTrue(!c.getJSONObject(0).getJSONArray("parts").getJSONObject(0).has("thoughtSignature"))
    }
}
