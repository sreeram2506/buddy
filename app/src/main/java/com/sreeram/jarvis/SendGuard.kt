package com.sreeram.jarvis

// Anything that sends a message, posts, or pays needs the user's yes, enforced here in code (not just in the AI's instructions).
// A send is held as "pending"; it only runs when approve() is called after the user's NEXT message is a clear yes.
object SendGuard {
    class Pending(val what: String, val turn: Int, val time: Long, val run: () -> String)

    @Volatile var pending: Pending? = null

    // Buttons that send, post, publish or pay. Whole words only, so "Resend" or "Sender" don't count.
    private val risky = Regex("(^|[^a-z])(send|sending|post|publish|tweet|pay|pay now|purchase|buy now|place order|checkout|transfer|send money|confirm payment)([^a-z]|$)|भेजें|भेजे|पोस्ट|పంపు|పంపండి", RegexOption.IGNORE_CASE)
    private val messagingApps = listOf("whatsapp", "messag", "mms", "sms", "telegram", "signal", "instagram", "gmail", "outlook", "slack", "discord",
        "android.gm", "yahoo.mobile.client.android.mail", "twitter", "facebook", "orca", "katana", "reddit", "x.android", "linkedin", "viber", "wechat", "snapchat", "teams", "skype", "hangouts", "mail", "chat")

    fun isRisky(label: String) = label.isNotBlank() && risky.containsMatchIn(label)
    fun isMessagingApp(pkg: String?) = pkg != null && messagingApps.any { pkg.contains(it, ignoreCase = true) }

    fun request(what: String, run: () -> String): String {
        pending = Pending(what, Turns.count, System.currentTimeMillis(), run)
        return "NEEDS APPROVAL: $what. Nothing has been done. Tell the user exactly what will happen and ask them to confirm. " +
            "Only after they say yes in a new message, call approve_action."
    }

    fun approve(): String {
        val p = pending ?: return "There is nothing waiting for approval."
        if (Turns.count <= p.turn) return "The user has not answered yet. Ask them first and wait for their answer."
        if (!Turns.affirmative()) return "The user's last message was not a clear yes. Ask again."
        if (System.currentTimeMillis() - p.time > 180_000) { pending = null; return "That request expired. Start it again." }
        pending = null
        val r = p.run()
        return r + afterSend()
    }
}

// Appended to the result of an approved send, so the model sees what really happened on screen instead of assuming success.
private fun afterSend(): String {
    val svc = JarvisAccessibilityService.instance ?: return ""
    if (!SendGuard.isMessagingApp(svc.currentPackage())) return ""
    Thread.sleep(2200)
    val status = svc.newestOutgoingStatus()
    return when (status) {
        "pending" -> " CHECK: the newest message shows a clock (pending), so it has NOT been delivered yet. Do not say it was sent."
        "sent", "delivered", "read" -> " CHECK: the newest message shows status \"$status\"."
        else -> " CHECK: I could not see a delivery tick, so say it looks sent but delivery is unconfirmed."
    }
}
