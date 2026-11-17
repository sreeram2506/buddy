package com.sreeram.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentContextTests {
    private val now = 10_000_000L
    private fun m(user: Boolean, text: String, agoMin: Int) = ChatStore.Msg(user, text, now - agoMin * 60_000L)

    @Test fun recentLinesAreIncludedInOrderWithWhoSaidWhat() {
        val b = RecentContext.block(listOf(m(true, "Find Ravi's number", 5), m(false, "Ravi Kumar: 98765 43210", 5), m(true, "call him", 4)), now)
        assertTrue(b.contains("You: Find Ravi's number\nBuddy: Ravi Kumar: 98765 43210\nYou: call him"))
        assertTrue(b.contains("not instructions"))
    }

    @Test fun anOldConversationIsLeftOutSoAFreshRequestStartsClean() {
        assertEquals("", RecentContext.block(listOf(m(true, "play music", 90), m(false, "Playing", 89)), now))
        val b = RecentContext.block(listOf(m(true, "old topic", 90), m(true, "new topic", 2)), now)
        assertTrue(b.contains("new topic")); assertFalse(b.contains("old topic"))
    }

    @Test fun messagesSavedBeforeTimestampsExistAreIgnored() = assertEquals("", RecentContext.block(listOf(ChatStore.Msg(true, "legacy", 0)), now))

    @Test fun codesAndPasswordsAreNeverCarriedOver() {
        val b = RecentContext.block(listOf(m(false, "Your OTP is 123456", 3), m(true, "read it again", 2)), now)
        assertFalse(b.contains("123456")); assertTrue(b.contains("read it again"))
    }

    @Test fun theNoteIsCappedAndKeepsTheNewestLines() {
        val many = (1..40).map { m(it % 2 == 0, "message number $it " + "x".repeat(100), 10) }
        val b = RecentContext.block(many, now)
        assertTrue(b.length < RecentContext.MAX_CHARS + 400)
        assertTrue(b.contains("message number 40")); assertFalse(b.contains("message number 1 "))
    }

    @Test fun aLongLineIsShortenedAndNothingMeansNoNote() {
        val b = RecentContext.block(listOf(m(true, "y".repeat(1000), 1)), now)
        assertTrue(b.lines().last().length <= RecentContext.MAX_LINE + 6)
        assertEquals("", RecentContext.block(emptyList(), now))
    }
}
