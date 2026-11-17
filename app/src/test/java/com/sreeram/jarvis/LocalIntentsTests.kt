package com.sreeram.jarvis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIntentsTests {
    private fun tool(s: String) = LocalIntents.match(s)?.tool
    private fun arg(s: String, k: String) = LocalIntents.match(s)?.args?.get(k)

    @Test fun torchInHindiTeluguAndRomanLetters() {
        assertEquals(true, arg("torch chalu karo", "on")); assertEquals(false, arg("torch band karo", "on"))
        assertEquals(true, arg("टॉर्च चालू करो", "on")); assertEquals(false, arg("टॉर्च बंद करो", "on"))
        assertEquals(true, arg("టార్చ్ ఆన్ చేయి", "on")); assertEquals(false, arg("టార్చ్ ఆఫ్ చేయి", "on"))
    }

    @Test fun volumeUpAndDown() {
        assertEquals("up", arg("volume badhao", "direction")); assertEquals("down", arg("awaz kam karo", "direction"))
        assertEquals("up", arg("आवाज़ बढ़ाओ", "direction")); assertEquals("down", arg("वॉल्यूम कम करो", "direction"))
        assertEquals("up", arg("వాల్యూమ్ పెంచు", "direction")); assertEquals("down", arg("వాల్యూమ్ తగ్గించు", "direction"))
    }

    @Test fun batteryQuestions() {
        assertEquals("battery_status", tool("battery kitni hai")); assertEquals("battery_status", tool("बैटरी कितनी है")); assertEquals("battery_status", tool("బ్యాటరీ ఎంత ఉంది"))
    }

    @Test fun unclearOrLongRequestsGoToTheAi() {
        assertNull(tool("torch")); assertNull(tool("torch on band")); assertNull(tool("volume"))
        assertNull(tool("call Ravi and tell him the volume is too low on the phone"))
        assertNull(tool("")); assertNull(tool("play some music"))
    }
}

class LiveResumeTests {
    @Test fun onlyAResumableHandleWithAValueIsKept() {
        assertEquals("abc", LiveResume.handleFrom(JSONObject("""{"sessionResumptionUpdate":{"newHandle":"abc","resumable":true}}""")))
        assertNull(LiveResume.handleFrom(JSONObject("""{"sessionResumptionUpdate":{"newHandle":"abc","resumable":false}}""")))
        assertNull(LiveResume.handleFrom(JSONObject("""{"sessionResumptionUpdate":{"newHandle":"","resumable":true}}""")))
        assertNull(LiveResume.handleFrom(JSONObject("""{"serverContent":{}}""")))
    }

    @Test fun resumingNeedsAHandleAndIsLimited() {
        assertTrue(LiveResume.canResume("abc", 0)); assertTrue(LiveResume.canResume("abc", LiveResume.MAX_RESUMES - 1))
        assertFalse(LiveResume.canResume("abc", LiveResume.MAX_RESUMES)); assertFalse(LiveResume.canResume(null, 0)); assertFalse(LiveResume.canResume("", 0))
    }
}

class LanguageStyleTests {
    @Test fun everyLanguageSettingAsksForSimpleSpokenWords() {
        for (c in listOf("auto", "en", "hi", "te")) assertTrue(Lang.promptRule(c).contains("simple everyday words") && Lang.promptRule(c).contains("English letters"))
    }
}
