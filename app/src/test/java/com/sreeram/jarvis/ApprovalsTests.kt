package com.sreeram.jarvis

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApprovalsTests {
    private var now = 1_000_000L

    @Before fun setUp() { now = 1_000_000L; Approvals.clock = { now }; Approvals.clear(); Approvals.offer("reset", { "" }, {}); Approvals.clear() }
    @After fun tearDown() { Approvals.clear(); Approvals.clock = { System.currentTimeMillis() } }

    @Test fun approvingRunsTheActionOnceAndReturnsItsResult() {
        var runs = 0
        val o = Approvals.offer("Send hi to Ravi", { runs++; "Sent" }, {})
        assertEquals("Sent", Approvals.approve(o.id))
        assertEquals(1, runs)
        assertEquals("That request is no longer waiting.", Approvals.approve(o.id)) // a second tap does nothing
        assertEquals(1, runs)
    }

    @Test fun cancelRunsTheCancelHandlerAndNeverTheAction() {
        var ran = false; var cancelled = false
        val o = Approvals.offer("Call Amma", { ran = true; "Called" }, { cancelled = true })
        assertTrue(Approvals.cancel(o.id))
        assertTrue(cancelled); assertFalse(ran)
        assertNull(Approvals.current())
        assertFalse(Approvals.cancel(o.id))
    }

    @Test fun aNewerOfferReplacesTheOlderOneAndTheOldTapIsRejected() {
        var oldRan = false
        val old = Approvals.offer("old", { oldRan = true; "old" }, {})
        val new = Approvals.offer("new", { "new" }, {})
        assertNotEquals(old.id, new.id)
        assertEquals("That request is no longer waiting.", Approvals.approve(old.id))
        assertFalse(oldRan)
        assertEquals("new", Approvals.approve(new.id))
    }

    @Test fun anExpiredOfferIsNotRun() {
        var ran = false
        val o = Approvals.offer("Send money", { ran = true; "Paid" }, {})
        now += Approvals.TTL_MS + 1
        assertNull(Approvals.current())
        assertEquals("That request expired. Ask Buddy again.", Approvals.approve(o.id))
        assertFalse(ran)
    }

    @Test fun anOfferJustInsideTheLimitStillWorks() {
        val o = Approvals.offer("Send", { "ok" }, {})
        now += Approvals.TTL_MS
        assertEquals("ok", Approvals.approve(o.id))
    }

    @Test fun aWrongIdNeverRunsAnything() {
        var ran = false
        Approvals.offer("Send", { ran = true; "x" }, {})
        assertEquals("That request is no longer waiting.", Approvals.approve(-1))
        assertFalse(ran)
        assertFalse(Approvals.cancel(999))
    }

    @Test fun afterATapASpokenYesReportsWhatHappenedInsteadOfNothingWaiting() {
        val o = Approvals.offer("Send hi", { "Sent to Ravi" }, {})
        Approvals.approve(o.id)
        assertEquals("The user already approved this by tapping the notification. Result: Sent to Ravi", Approvals.tappedResult())
        now += Approvals.TTL_MS + 1
        assertNull(Approvals.tappedResult())
    }

    @Test fun aNewOfferForgetsThePreviousTappedResult() {
        val o = Approvals.offer("Send hi", { "Sent" }, {})
        Approvals.approve(o.id)
        Approvals.offer("Send another", { "x" }, {})
        assertNull(Approvals.tappedResult())
    }

    @Test fun clearingAfterAVoiceApprovalRemovesTheOffer() {
        val o = Approvals.offer("Send", { "x" }, {})
        Approvals.clear()
        assertNull(Approvals.current())
        assertEquals("That request is no longer waiting.", Approvals.approve(o.id))
    }

    @Test fun sendGuardRunsOnATapWithoutAnySpokenYes() {
        // No user turn has happened and the transcript is empty, so a voice approval would be refused; a tap is not.
        Turns.count = 5; Turns.lastText = ""
        var sent = false
        SendGuard.request("press Enter in com.whatsapp, which sends the message") { sent = true; "Done" }
        assertTrue(SendGuard.approve().startsWith("The user has not answered yet"))
        assertFalse(sent)
        val r = SendGuard.approve(viaTap = true)
        assertTrue(r.startsWith("Done"))
        assertTrue(sent)
        assertEquals("There is nothing waiting for approval.", SendGuard.approve())
    }

    @Test fun sendGuardStillRefusesAnExpiredRequestOnATap() {
        var sent = false
        SendGuard.request("tap Send") { sent = true; "Done" }
        SendGuard.pending = SendGuard.Pending("tap Send", 0, System.currentTimeMillis() - 200_000) { sent = true; "Done" }
        assertEquals("That request expired. Start it again.", SendGuard.approve(viaTap = true))
        assertFalse(sent)
    }
}
