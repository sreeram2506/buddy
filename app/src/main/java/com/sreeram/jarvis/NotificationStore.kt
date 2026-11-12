package com.sreeram.jarvis

object NotificationStore {
    class ReplyTarget(val sender: String, val intent: android.app.PendingIntent, val input: android.app.RemoteInput)

    private val replies = LinkedHashMap<String, ReplyTarget>()

    @Synchronized fun rememberReply(r: ReplyTarget) { replies.remove(r.sender.lowercase()); replies[r.sender.lowercase()] = r }

    @Synchronized fun replyFor(sender: String): ReplyTarget? =
        replies.values.toList().asReversed().firstOrNull { it.sender.contains(sender, ignoreCase = true) }

    data class Msg(val pkg: String, val sender: String, val text: String, val time: Long)

    private val msgs = ArrayDeque<Msg>()

    @Synchronized
    fun add(m: Msg): Boolean {
        if (msgs.lastOrNull()?.let { it.pkg == m.pkg && it.sender == m.sender && it.text == m.text } == true) return false
        msgs.addLast(m)
        while (msgs.size > 100) msgs.removeFirst()
        return true
    }

    @Synchronized
    fun query(sender: String?, app: String?, limit: Int = 3): List<Msg> = msgs.reversed()
        .filter { sender.isNullOrBlank() || it.sender.contains(sender, ignoreCase = true) }
        .filter { app.isNullOrBlank() || it.pkg.contains(app, ignoreCase = true) }
        .take(limit)
}
