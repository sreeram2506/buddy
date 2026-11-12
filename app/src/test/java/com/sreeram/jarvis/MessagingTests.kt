package com.sreeram.jarvis

import com.sreeram.jarvis.msg.*
import org.junit.Assert.*
import org.junit.Test

class MessagingTests {
    private val W = 1224; private val H = 2720; private val D = 3f

    private fun n(id: String, text: String? = null, desc: String? = null, l: Int, t: Int, r: Int, b: Int, editable: Boolean = false) =
        ScreenNode(id, text, desc, l, t, r, b, editable)

    // Positions taken from a real WhatsApp conversation screen: the user's bubbles end near the right edge (right gap 227 px)
    // and carry a tick; the other person's start near the left edge (left gap 57 px) and carry none.
    private fun screen(typed: String = "", youTick: String? = "Delivered", extraYou: String? = null): List<ScreenNode> {
        val l = arrayListOf(
            n("whatsapp_toolbar_home", desc = "Back", l = 0, t = 80, r = 150, b = 200),
            n("message_text", "Where are you", l = 57, t = 1100, r = 640, b = 1160),
            n("date", "9:40", l = 600, t = 1170, r = 700, b = 1210),
            n("message_text", "Reaching in ten minutes", l = 487, t = 1500, r = 997, b = 1560),
            n("date", "9:41", l = 997, t = 1600, r = 1100, b = 1640))
        if (youTick != null) l.add(n("status", desc = youTick, l = 1086, t = 1606, r = 1146, b = 1646))
        if (extraYou != null) {
            l.add(n("message_text", extraYou, l = 761, t = 1800, r = 997, b = 1860)); l.add(n("date", "9:42", l = 997, t = 1900, r = 1100, b = 1940))
            if (youTick != null) l.add(n("status", desc = youTick, l = 1086, t = 1906, r = 1146, b = 1946))
        }
        l.add(n("entry", typed, l = 100, t = 2500, r = 1000, b = 2620, editable = true))
        if (typed.isNotBlank()) l.add(n("send", desc = "Send", l = 1050, t = 2500, r = 1200, b = 2620))
        return l
    }
    private fun parse(nodes: List<ScreenNode>) = ChatParser.parse(nodes, W, H, D)

    @Test fun readsWhoSentWhatFromWhereTheBubbleSits() {
        val c = parse(screen())
        assertEquals(listOf(Side.THEM, Side.YOU), c.messages.map { it.side })
        assertEquals("Where are you", c.messages[0].text)
        assertEquals("9:41", c.messages[1].time)
    }
    @Test fun onlyTheUsersMessagesCarryATick() {
        val c = parse(screen(youTick = "Read"))
        assertNull(c.messages[0].delivery)
        assertEquals(Delivery.READ, c.messages[1].delivery)
    }
    @Test fun aBubbleStretchingAcrossTheScreenIsStillAttributedToTheRightSide() {
        // a long incoming message: starts at the left edge, ends 200 px from the right
        val nodes = listOf(n("message_text", "A very long message ".repeat(8), l = 57, t = 1000, r = 1024, b = 1300))
        assertEquals(Side.THEM, parse(nodes).messages.single().side)
        val mine = listOf(n("message_text", "A very long reply ".repeat(8), l = 200, t = 1000, r = 1166, b = 1300))
        assertEquals(Side.YOU, parse(mine).messages.single().side)
    }
    @Test fun centredElementsBelongToNeitherSide() {
        assertNull(ChatParser.side(450, 774, W, D))
    }
    @Test fun toolbarAndTypingBoxAreNotMessages() {
        val nodes = listOf(n("message_text", "Contact name", l = 57, t = 90, r = 500, b = 160), n("message_text", "typing", l = 57, t = 2600, r = 400, b = 2700))
        assertTrue(parse(nodes).messages.isEmpty())
    }

    @Test fun deliveredWhenTheMessageIsNewAndTicked() {
        val before = parse(screen(typed = "On my way"))
        val after = parse(screen(youTick = "Delivered", extraYou = "On my way"))
        assertEquals(SendOutcome.Delivered(Delivery.DELIVERED), SendVerifier.judge(before, after, "On my way"))
    }
    @Test fun pendingClockIsNotReportedAsSent() {
        val before = parse(screen(typed = "On my way"))
        val after = parse(screen(youTick = "Pending", extraYou = "On my way"))
        assertEquals(SendOutcome.Pending, SendVerifier.judge(before, after, "On my way"))
    }
    @Test fun messageStillInTheTypingBoxMeansNotSent() {
        val before = parse(screen(typed = "On my way"))
        val after = parse(screen(typed = "On my way"))
        assertEquals(SendOutcome.NotInChat, SendVerifier.judge(before, after, "On my way"))
    }
    @Test fun anOlderIdenticalMessageIsNotMistakenForTheNewOne() {
        // "Reaching in ten minutes" was already in the chat; sending the same text again but not appearing must not count
        val before = parse(screen())
        val after = parse(screen())
        assertEquals(SendOutcome.NotInChat, SendVerifier.judge(before, after, "Reaching in ten minutes"))
    }
    @Test fun aNewMessageWithoutATickIsReportedAsUnconfirmed() {
        val before = parse(screen(typed = "On my way"))
        val after = parse(screen(youTick = null, extraYou = "On my way"))
        assertEquals(SendOutcome.NoTick, SendVerifier.judge(before, after, "On my way"))
    }
    @Test fun theModelIsNeverToldSentUnlessItWasConfirmed() {
        assertFalse(SendOutcome.Pending.forModel("Amma", "WhatsApp").startsWith("Sent"))
        assertFalse(SendOutcome.NotInChat.forModel("Amma", "WhatsApp").startsWith("Sent"))
        assertFalse(SendOutcome.CouldNotPress.forModel("Amma", "WhatsApp").startsWith("Sent"))
        assertTrue(SendOutcome.Delivered(Delivery.SENT).forModel("Amma", "WhatsApp").startsWith("Sent"))
    }

    @Test fun soundAlikeSpellingsOfANameShareAKey() {
        assertEquals(NameMatcher.key("Siva"), NameMatcher.key("Seva"))
        assertEquals(NameMatcher.key("Siva"), NameMatcher.key("Shiva"))
        assertEquals(NameMatcher.key("Srinija"), NameMatcher.key("Srinija"))
        assertNotEquals(NameMatcher.key("Ravi"), NameMatcher.key("Rani"))
    }
    @Test fun aMisheardNameFindsTheRightContact() {
        val contacts = listOf("Siva Pachuri ksrm", "Ravi Kumar", "Amma", "Nanna", "Srinija", "Sita")
        assertEquals(listOf("Siva Pachuri ksrm"), NameMatcher.best("Seva", contacts))
        assertEquals(listOf("Siva Pachuri ksrm"), NameMatcher.best("Shiva Pachuri", contacts))
        assertEquals(listOf("Amma"), NameMatcher.best("Amma", contacts))
        assertEquals(listOf("Nanna"), NameMatcher.best("Nana", contacts))
    }
    @Test fun anUnknownNameMatchesNobodyInsteadOfGuessing() {
        assertTrue(NameMatcher.best("Zebra", listOf("Siva", "Ravi", "Amma")).isEmpty())
    }
    @Test fun waiterReturnsAsSoonAsTheConditionIsTrueAndGivesUpAtTheTimeout() {
        var t = 0L; var calls = 0
        assertTrue(Waiter.until(1000, 100, { t }, { t += it }) { ++calls >= 3 })
        assertEquals(3, calls)
        t = 0
        assertFalse(Waiter.until(1000, 100, { t }, { t += it }) { false })
    }

    @Test fun numbersAreStandardisedWhateverTheWayTheyAreWritten() {
        for (raw in listOf("98765 43210", "09876543210", "+91 98765 43210", "+91-9876543210", "919876543210"))
            assertEquals(raw, "919876543210", PhoneNumbers.digitsWithCountryCode(raw, "IN"))
        assertEquals("14155550123", PhoneNumbers.digitsWithCountryCode("+1 (415) 555-0123", "IN"))
        assertTrue(PhoneNumbers.sameNumber("98765 43210", "+91 9876543210"))
        assertFalse(PhoneNumbers.sameNumber("98765 43210", "98765 43211"))
    }
}
