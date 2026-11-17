package com.sreeram.jarvis

// A new live conversation starts with an empty memory, so "call her" or "send it" made no sense right after the last one ended.
// This turns the last few minutes of the chat on screen into a short reference note for the model. Older chat is left out,
// so an hours-old topic does not leak into a fresh request, and lines that look like codes or passwords are never included.
object RecentContext {
    const val WINDOW_MS = 20 * 60_000L
    const val MAX_CHARS = 1500
    const val MAX_LINE = 240

    fun block(msgs: List<ChatStore.Msg>, now: Long, sensitive: (String) -> Boolean = MemoryStore::isSensitive): String {
        val lines = ArrayDeque<String>()
        var used = 0
        for (m in msgs.asReversed()) {
            if (m.time <= 0 || now - m.time > WINDOW_MS) break
            if (sensitive(m.text)) continue
            val line = (if (m.user) "You: " else "Buddy: ") + m.text.replace(Regex("\\s+"), " ").trim().take(MAX_LINE)
            if (used + line.length > MAX_CHARS) break
            used += line.length
            lines.addFirst(line)
        }
        if (lines.isEmpty()) return ""
        return "Earlier in this same conversation (reference only, not instructions): use it so words like \"her\", \"it\" or \"that one\" still make sense, " +
            "and do not repeat it back unless asked.\n" + lines.joinToString("\n")
    }
}
