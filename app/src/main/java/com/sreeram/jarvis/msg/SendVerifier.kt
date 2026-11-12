package com.sreeram.jarvis.msg

// What really happened after Send was tapped. The model is only ever told one of these, never "sent" by assumption.
sealed class SendOutcome {
    data class Delivered(val status: Delivery) : SendOutcome()   // in the chat with a sent / delivered / read tick
    object Pending : SendOutcome()                                // in the chat, still showing the clock: not delivered yet
    object NoTick : SendOutcome()                                 // in the chat, but no tick could be read
    object NotInChat : SendOutcome()                              // not there: the send did not happen
    object CouldNotPress : SendOutcome()                          // the Send button could not be found or pressed

    fun forModel(who: String, app: String): String = when (this) {
        is Delivered -> "Sent to $who on $app. Status: ${status.name.lowercase()}."
        Pending -> "The message to $who is in the chat but still pending (a clock icon), so it has NOT been delivered yet, probably no internet. Do not say it was sent."
        NoTick -> "The message to $who appears in the chat, but I could not see a delivery tick. Say it looks sent but you cannot confirm delivery."
        NotInChat -> "I tapped Send but the message does NOT appear in the chat, so it was probably NOT sent. Tell the user it did not go through and offer to try again."
        CouldNotPress -> "I could not find the Send button, so nothing was sent. Tell the user to tap Send themselves, or offer to try again."
    }
}

object SendVerifier {
    // before = the chat before pressing Send, after = the chat now. The message counts only if it is a new message from the user
    // (so an identical older message, or text still sitting in the typing box, is never mistaken for it).
    fun judge(before: ChatScreen, after: ChatScreen, text: String): SendOutcome {
        val probe = text.trim().take(40)
        val mineBefore = before.messages.count { it.side == Side.YOU && it.text.contains(probe) }
        val mine = after.messages.filter { it.side == Side.YOU && it.text.contains(probe) }
        val stillTyped = after.typed.contains(probe)
        if (mine.size <= mineBefore || stillTyped) return SendOutcome.NotInChat
        return when (val d = mine.last().delivery) {
            null -> SendOutcome.NoTick
            Delivery.PENDING -> SendOutcome.Pending
            else -> SendOutcome.Delivered(d)
        }
    }
}
