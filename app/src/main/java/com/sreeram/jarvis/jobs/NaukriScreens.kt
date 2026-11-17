package com.sreeram.jarvis.jobs

import com.sreeram.jarvis.msg.ScreenNode

// Reads Naukri's screens from plain ScreenNode data, so it can be tested without a phone.
// Naukri names its fields (textViewDesignation, tv_title, tvApply...), so nothing here depends on the position or language of a label.
object NaukriScreens {
    enum class Screen { JOB, RESULTS, HOME, OTHER }
    enum class ApplyKind { IN_APP, EXTERNAL, ALREADY, NONE }
    enum class AfterApply { APPLIED, QUESTIONS, OTHER }

    // A job on the results list. tapX/tapY is where to press to open it.
    data class Card(val job: Job, val tapX: Int, val tapY: Int)

    fun identify(nodes: List<ScreenNode>): Screen = when {
        nodes.any { it.id == "tv_title" } -> Screen.JOB
        nodes.any { it.id == "textViewDesignation" } || nodes.any { it.id == "textViewHelperText" } -> Screen.RESULTS
        nodes.any { it.id == "textViewRecoJobsText" } -> Screen.HOME
        else -> Screen.OTHER
    }

    // Every job whose title is fully on screen, away from the toolbar and the filter bar.
    fun parseResults(nodes: List<ScreenNode>, screenHeight: Int): List<Card> {
        data class Draft(var title: ScreenNode, var company: String = "", var location: String = "", var ctc: String = "", var date: String = "")
        val drafts = ArrayList<Draft>()
        for (n in nodes) {
            val text = n.text?.trim().orEmpty()
            when (n.id) {
                "textViewDesignation" -> drafts += Draft(n)
                "textViewCompanyName" -> drafts.lastOrNull()?.company = text
                "textViewLocation" -> drafts.lastOrNull()?.location = text
                "textViewCTC" -> drafts.lastOrNull()?.ctc = text
                "textViewDate" -> drafts.lastOrNull()?.date = text
            }
        }
        return drafts.filter { it.title.top > screenHeight * 0.12 && it.title.bottom < screenHeight * 0.88 && !it.title.text.isNullOrBlank() }
            .map { Card(Job(it.title.text!!.trim(), it.company, it.location, it.ctc, it.date, emptyList(), emptyList()), (it.title.left + it.title.right) / 2, (it.title.top + it.title.bottom) / 2) }
    }

    // The full job page. Null when the page is not fully loaded yet.
    fun parseJob(nodes: List<ScreenNode>): Job? {
        fun byId(id: String) = nodes.firstOrNull { it.id == id }?.text?.trim().orEmpty()
        val title = byId("tv_title")
        if (title.isEmpty()) return null
        val posted = nodes.firstOrNull { it.text?.trim()?.startsWith("Posted ") == true && it.id != "tv_consultantName" }?.text?.trim().orEmpty()
        return Job(title, byId("tv_companyName"), byId("tv_location"), byId("tv_salary"), posted,
            JobMatcher.splitSkills(valueAfter(nodes, "Must have skills")), JobMatcher.splitSkills(valueAfter(nodes, "Good to have skills")))
    }

    // The text of the node that follows a heading like "Must have skills"; empty when it is not on screen.
    private fun valueAfter(nodes: List<ScreenNode>, heading: String): String {
        val i = nodes.indexOfFirst { it.text?.trim().equals(heading, ignoreCase = true) }
        if (i < 0) return ""
        // The list is a sibling field with the same id as its heading. If it is below the screen, the next text on screen is something else entirely.
        val headingId = nodes[i].id
        val next = nodes.drop(i + 1).firstOrNull { !it.text.isNullOrBlank() } ?: return ""
        if (headingId.isNotEmpty() && next.id != headingId) return ""
        val t = next.text!!.trim()
        return if (t.equals("Good to have skills", true) || t.equals("Must have skills", true) || t.equals("Job description", true)) "" else t
    }

    fun applyKind(nodes: List<ScreenNode>): ApplyKind {
        val t = nodes.firstOrNull { it.id == "tvApply" }?.text?.trim()?.lowercase().orEmpty()
        return when {
            t.isEmpty() -> ApplyKind.NONE
            t.contains("applied") -> ApplyKind.ALREADY
            t.contains("company site") || t.contains("website") -> ApplyKind.EXTERNAL
            t.startsWith("apply") -> ApplyKind.IN_APP
            else -> ApplyKind.NONE
        }
    }

    // What the screen shows after Apply was pressed. Only a visible "applied" counts as success: anything else is never reported as one.
    fun afterApply(nodes: List<ScreenNode>): AfterApply {
        val all = nodes.mapNotNull { it.text?.lowercase() }
        if (applyKind(nodes) == ApplyKind.ALREADY || all.any { it.contains("applied successfully") || it.contains("successfully applied") || it.contains("application sent") }) return AfterApply.APPLIED
        if (nodes.any { it.editable } || all.any { it.contains("question") }) return AfterApply.QUESTIONS
        return AfterApply.OTHER
    }
}

// Text of the job description on screen: everything below the "Job description" heading except the Apply button, in reading order.
fun NaukriScreens.descriptionText(nodes: List<ScreenNode>): List<String> {
    val i = nodes.indexOfFirst { it.text?.trim().equals("Job description", ignoreCase = true) }
    if (i < 0) return emptyList()
    return nodes.drop(i + 1).filter { it.id != "tvApply" && !it.text.isNullOrBlank() }.map { it.text!!.trim() }
}
