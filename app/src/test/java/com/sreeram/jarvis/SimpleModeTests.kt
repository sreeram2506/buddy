package com.sreeram.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimpleModeTests {
    @Test fun contactsComeFromACommaOrLineList() = assertEquals(listOf("Amma", "Ravi", "Dad"), SimpleMode.contacts("Amma, Ravi\nDad, Amma"))

    @Test fun anEmptyListFallsBackToMyself() {
        assertEquals(listOf("Myself"), SimpleMode.contacts(""))
        assertEquals(listOf("Myself"), SimpleMode.contacts(null))
        assertEquals(listOf("Myself"), SimpleMode.contacts(" , ,"))
    }

    @Test fun anExactNameBeatsAPartialMatch() {
        assertEquals("Myself" to "8309078104", SimpleMode.pickContact("Myself", "Myself Office: 9999999999; Myself: 8309078104"))
    }

    @Test fun aPartialMatchIsUsedWhenThereIsNoExactOne() {
        assertEquals("Ravi Kumar" to "+919876543210", SimpleMode.pickContact("Ravi", "Ravi Kumar: +919876543210"))
    }

    @Test fun noContactOrNoNumberGivesNull() {
        assertNull(SimpleMode.pickContact("Zed", "No contact matching Zed"))
        assertNull(SimpleMode.pickContact("Zed", "Contacts permission not granted. Ask the user to allow it in Buddy settings."))
        assertNull(SimpleMode.pickContact("Zed", ""))
    }
}

class PlainResultTests {
    @Test fun aDeliveredSendIsShortAndPlain() = assertEquals("Sent to Myself.", SimpleMode.plain("Sent to Myself on WhatsApp. Status: read."))

    @Test fun instructionsAddressedToTheAiBecomeInstructionsToThePerson() {
        val r = SimpleMode.plain("I could not find the Send button, so nothing was sent. Tell the user to tap Send themselves, or offer to try again.")
        assertEquals("I could not send it. Please open WhatsApp and tap the green Send button yourself.", r)
        org.junit.Assert.assertFalse(SimpleMode.plain("The message to Ravi is in the chat but still pending (a clock icon), so it has NOT been delivered yet, probably no internet. Do not say it was sent.").contains("Do not say"))
    }

    @Test fun commonProblemsAreExplainedInPlainWords() {
        assertEquals("I could not find that person in your contacts.", SimpleMode.plain("No contact matching Zed"))
        assertEquals("That took too long, so I stopped. Please try again.", SimpleMode.plain("That request expired. Ask Buddy again."))
        assertEquals("That was already answered.", SimpleMode.plain(Approvals.NOT_WAITING))
    }

    @Test fun anythingElseIsShownAsIs() = assertEquals("Battery is at 80%", SimpleMode.plain("Battery is at 80%"))

    @Test fun theSpokenQuestionNamesTheActionAndBothButtons() {
        val s = SimpleMode.askAloud("Send WhatsApp to Myself: \"I'm OK\"")
        assertEquals(true, s.contains("Send WhatsApp to Myself") && s.contains("green") && s.contains("red"))
    }
}
