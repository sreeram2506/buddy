package com.sreeram.jarvis.msg

enum class Side { YOU, THEM }
enum class Delivery { PENDING, SENT, DELIVERED, READ }

data class ChatMessage(val side: Side, val text: String, val time: String?, val delivery: Delivery?)
data class ChatScreen(val messages: List<ChatMessage>, val typed: String, val sendVisible: Boolean)

// Turns WhatsApp's accessibility tree into a list of messages, who sent each one, and its delivery tick.
// Facts it relies on, checked against a real conversation screen: each message's text is an element with id "message_text";
// its time is "date" and (only for the user's own messages) its tick is "status" with a description of Pending / Sent / Delivered / Read,
// all in the same row; the typing box is "entry"; WhatsApp does not say who sent a message, so the side is read from where the bubble sits.
object ChatParser {
    private val tick = mapOf("pending" to Delivery.PENDING, "sent" to Delivery.SENT, "delivered" to Delivery.DELIVERED, "read" to Delivery.READ)

    // WhatsApp does not label senders, but the user's bubbles sit against the right edge and the other person's against the left.
    // Null when the element is centred (dates, system notes) and so belongs to neither.
    fun side(left: Int, right: Int, width: Int, density: Float): Side? {
        val margin = 24 * density; val rightGap = width - right
        return when { rightGap + margin < left -> Side.YOU; left + margin < rightGap -> Side.THEM; else -> null }
    }

    fun parse(nodes: List<ScreenNode>, width: Int, height: Int, density: Float): ChatScreen {
        val rows = nodes.filter { it.id == "message_text" && !it.text.isNullOrBlank() && it.top > height * 0.10f && it.bottom < height * 0.95f }.sortedBy { it.top }
        val dates = nodes.filter { it.id == "date" }
        val ticks = nodes.filter { it.id == "status" || (it.desc?.lowercase()?.removePrefix("message ") in tick.keys && it.id.isEmpty()) }
        val msgs = rows.mapIndexed { i, m ->
            val side = side(m.left, m.right, width, density) ?: Side.THEM
            val rowEnd = rows.getOrNull(i + 1)?.top ?: Int.MAX_VALUE
            // The time and tick belong to this message if they sit below its text and above the next message.
            val time = dates.filter { it.top >= m.top && it.top < rowEnd }.minByOrNull { it.top }?.text
            val d = if (side == Side.YOU) ticks.filter { it.top >= m.top && it.top < rowEnd }.minByOrNull { it.top }
                ?.desc?.lowercase()?.removePrefix("message ")?.let { tick[it] } else null
            ChatMessage(side, m.text!!, time, d)
        }
        val box = nodes.firstOrNull { it.editable }
        return ChatScreen(msgs, box?.text.orEmpty(), nodes.any { it.id == "send" })
    }
}
