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
