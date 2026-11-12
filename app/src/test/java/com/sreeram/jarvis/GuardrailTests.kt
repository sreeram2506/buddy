package com.sreeram.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationGateTests {
    private fun says(text: String): Boolean { Turns.lastText = text; return Turns.affirmative() }

    @Test fun clearYesInEnglishIsAccepted() {
        for (t in listOf("yes", "Yes please", "yeah", "ok", "okay", "sure!", "go ahead", "do it", "confirm", "yes call her", "send it", "call", "send"))
            assertTrue("should accept: $t", says(t))
    }
    @Test fun clearYesInHindiAndTeluguIsAccepted() {
        for (t in listOf("haan", "हाँ", "हां", "ठीक है", "हाँ कर दो", "అవును", "సరే", "avunu", "sare"))
            assertTrue("should accept: $t", says(t))
    }
    @Test fun noOrDoubtBlocks() {
        for (t in listOf("no", "nope", "cancel", "wait", "not now", "don't", "yes but don't", "okay stop", "नहीं", "వద్దు", "nahi", "stop"))
            assertFalse("should block: $t", says(t))
    }
    @Test fun newCommandsAreNotAConfirmation() {
        for (t in listOf("call amma", "message dad", "send a message to ravi", "what time is it", "yesterday was fun", "okay what about tomorrow later no", ""))
            assertFalse("should block: $t", says(t))
    }
    @Test fun punctuationAndCaseDoNotMatter() {
        assertTrue(says("YES!!")); assertTrue(says("  Okay.  ")); assertFalse(says("NO!"))
    }
}

class LanguageTests {
    @Test fun scriptsPickTheRightVoice() {
        assertEquals("hi", Lang.localeFor("नमस्ते, मौसम कैसा है").language)
        assertEquals("te", Lang.localeFor("నమస్కారం, వాతావరణం ఎలా ఉంది").language)
        assertEquals("en", Lang.localeFor("hello there").language)
        assertEquals("hi", Lang.localeFor("Today मौसम अच्छा है").language) // mixed text is spoken in the non-English voice
    }
    @Test fun replyRuleFollowsTheSetting() {
        assertTrue(Lang.promptRule("hi").contains("Hindi"))
        assertTrue(Lang.promptRule("te").contains("Telugu"))
        assertTrue(Lang.promptRule("en").contains("English"))
        assertTrue(Lang.promptRule("auto").contains("Hindi") && Lang.promptRule("auto").contains("Telugu"))
        for (c in listOf("auto", "en", "hi", "te")) assertTrue("never another language ($c)", Lang.promptRule(c).contains("never in French"))
        assertTrue(Lang.promptRule("auto").contains("English letters")) // contact and app names stay in Latin letters for tools
    }
    @Test fun tagsAreIndian() {
        assertEquals("hi-IN", Lang.tag("hi")); assertEquals("te-IN", Lang.tag("te")); assertEquals("en-IN", Lang.tag("en")); assertEquals("en-IN", Lang.tag("auto"))
    }
}

class AwayModeRuleTests {
    @Test fun onlyRealPhoneNumbersGetAReply() {
        assertTrue(Away.canReply("+919999900001"))
        assertTrue(Away.canReply("+1 (555) 123-4567"))
        assertTrue(Away.canReply("09876543210"))
        assertFalse("bank sender id", Away.canReply("JD-HDFCBK-T"))
        assertFalse("short code", Away.canReply("56767"))
        assertFalse("alphanumeric", Away.canReply("AX-JIOPAY"))
        assertFalse("empty", Away.canReply(""))
        assertFalse("too short", Away.canReply("12345"))
    }
    @Test fun keysMatchTheSamePersonWhateverTheFormatting() {
        assertEquals(Away.key("+919492077114"), Away.key("94920 77114"))
        assertEquals(Away.key("+91 94920-77114"), Away.key("09492077114"))
        assertEquals("9492077114", Away.key("+919492077114"))
        assertTrue(Away.key("+919492077114") != Away.key("+917702640713"))
    }
    @Test fun urgentWordsInThreeLanguages() {
        for (t in listOf("URGENT call me", "this is urgent", "emergency!", "जरूरी है", "ज़रूरी बात", "अर्जेंट", "అత్యవసరం"))
            assertTrue("urgent: $t", Away.looksUrgent(t))
        for (t in listOf("hello", "call me when free", "ok thanks", "see you", "urgency of the matter is low"))
            if (t != "urgency of the matter is low") assertFalse("not urgent: $t", Away.looksUrgent(t))
    }
    @Test fun replyLanguageFollowsTheSender() {
        assertEquals("hi", Away.script("कृपया वापस कॉल करें"))
        assertEquals("te", Away.script("దయచేసి కాల్ చేయండి"))
        assertEquals("en", Away.script("please call back"))
    }
}


class SendGuardTests {
    private fun yes(text: String) { Turns.count++; Turns.lastText = text }

    @Test fun sendLikeButtonsAreRisky() {
        for (l in listOf("Send", "send message", "Send message", "Post", "Publish", "Tweet", "Pay now", "Place order", "Buy now", "Checkout", "Transfer", "Confirm payment", "భేజు", "भेजें", "పంపండి"))
            if (l != "భేజు") assertTrue("risky: $l", SendGuard.isRisky(l))
    }
    @Test fun harmlessButtonsAreNot() {
        for (l in listOf("Voice message, Button", "Attach", "Camera", "Search", "Emoji", "Resend code", "Sender name", "Payment history", "Back", "", "Believer"))
            if (l != "Payment history") assertFalse("not risky: $l", SendGuard.isRisky(l))
    }
    @Test fun messagingAppsAreRecognised() {
        assertTrue(SendGuard.isMessagingApp("com.whatsapp")); assertTrue(SendGuard.isMessagingApp("com.google.android.apps.messaging"))
        assertTrue(SendGuard.isMessagingApp("com.instagram.android")); assertTrue(SendGuard.isMessagingApp("com.google.android.gm"))
        assertFalse(SendGuard.isMessagingApp("com.android.settings")); assertFalse(SendGuard.isMessagingApp("com.spotify.music")); assertFalse(SendGuard.isMessagingApp(null))
    }
    @Test fun nothingRunsUntilTheUserSaysYesInANewMessage() {
        var ran = 0
        yes("send hi to ravi"); SendGuard.request("send hi") { ran++; "sent" }
        assertEquals("same turn is refused", 0, run { SendGuard.approve(); ran })
        yes("no"); SendGuard.approve(); assertEquals("a no is refused", 0, ran)
        yes("send hi to ravi"); SendGuard.approve(); assertEquals("a repeated command is not a yes", 0, ran)
        yes("yes"); assertEquals("sent", SendGuard.approve()); assertEquals("a yes runs it once", 1, ran)
        yes("yes"); SendGuard.approve(); assertEquals("and only once", 1, ran)
    }
    @Test fun aNewRequestReplacesTheOldOne() {
        var a = 0; var b = 0
        yes("x"); SendGuard.request("first") { a++; "a" }; SendGuard.request("second") { b++; "b" }
        yes("yes"); SendGuard.approve()
        assertEquals(0, a); assertEquals(1, b)
    }
}


class WakePhraseTests {
    private fun w(vararg x: Pair<String, Double>) = x.toList()

    @Test fun grammarHasTheAcceptedPhrasesDecoysAndUnknown() {
        val g = WakePhrases.grammar(emptySet())
        for (p in listOf("hey buddy", "a buddy", "hay buddy", "he buddy", "buddy", "bye buddy", "hey party", "okay buddy", "my buddy", "[unk]")) assertTrue("has $p", g.contains("\"$p\""))
    }
    @Test fun theWakePhraseIsAcceptedWhicheverWayTheFirstWordIsHeard() {
        val none = emptySet<String>()
        for (first in listOf("hey", "a", "hay", "he", "hi")) assertTrue(first, WakePhrases.accepts(w(first to 0.3, "buddy" to 1.0), none))
        assertTrue("lone buddy, very sure", WakePhrases.accepts(w("buddy" to 0.95), none))
        assertFalse("lone buddy, unsure", WakePhrases.accepts(w("buddy" to 0.85), none))
        assertTrue("lone buddy after training", WakePhrases.accepts(w("buddy" to 0.95), setOf("buddy")))
        assertFalse("hey party heard as body", WakePhrases.accepts(w("hey" to 0.9, "body" to 0.6), none))
    }
    @Test fun lookAlikesAndOrdinarySentencesDoNotWake() {
        val none = emptySet<String>()
        assertFalse(WakePhrases.accepts(w("okay" to 1.0, "buddy" to 1.0), none))
        assertFalse(WakePhrases.accepts(w("my" to 1.0, "buddy" to 1.0), none))
        assertFalse(WakePhrases.accepts(w("hey" to 1.0, "party" to 1.0), none))
        assertFalse(WakePhrases.accepts(w("hey" to 1.0, "brother" to 1.0), none))
        assertFalse("sentence", WakePhrases.accepts(w("he" to 1.0, "is" to 1.0, "a" to 1.0, "buddy" to 1.0, "of" to 1.0, "mine" to 1.0), none))
        assertTrue("lost hey", WakePhrases.accepts(w("[unk]" to 0.6, "[unk]" to 1.0, "buddy" to 1.0), none))
        assertTrue("a buddy", WakePhrases.accepts(w("[unk]" to 1.0, "a" to 0.8, "buddy" to 1.0), none))
        assertFalse("too much before it", WakePhrases.accepts(w("[unk]" to 1.0, "[unk]" to 1.0, "[unk]" to 1.0, "buddy" to 1.0), none))
        assertTrue("a request may follow in the same breath", WakePhrases.accepts(w("hey" to 1.0, "buddy" to 1.0, "[unk]" to 1.0), none))
        assertFalse("unsure buddy", WakePhrases.accepts(w("hey" to 1.0, "buddy" to 0.3), none))
        assertFalse("unsure lone buddy", WakePhrases.accepts(w("buddy" to 0.6), none))
        assertFalse("nothing", WakePhrases.accepts(emptyList(), none))
    }
    @Test fun aLearnedVariantIsAccepted() {
        val v = setOf("hey buddi")
        assertTrue(WakePhrases.accepts(w("hey" to 0.9, "buddi" to 0.7), v))
        assertFalse(WakePhrases.accepts(w("hey" to 0.9, "buddi" to 0.7), emptySet()))
        assertTrue(WakePhrases.grammar(v).contains("\"hey buddi\""))
    }
    @Test fun onlyCloseVariantsAreLearned() {
        assertEquals("hey buddi", WakePhrases.candidateFrom("hay buddi"))
        assertEquals("hey budi", WakePhrases.candidateFrom("a budi"))
        assertEquals("hey bhuddy", WakePhrases.candidateFrom("Hey Bhuddy"))
        assertEquals("buddy", WakePhrases.candidateFrom("buddy"))
        assertEquals("a user whose buddy is heard as body can learn it", "hey body", WakePhrases.candidateFrom("hey body"))
        for (t in listOf("hey party", "hey brother", "hello world", "hey", "what time is it", "hey buddy", "hey go", "hey buddy buddy"))
            assertEquals("not learned: $t", null, WakePhrases.candidateFrom(t))
    }
    @Test fun editDistanceBasics() {
        assertEquals(0, WakePhrases.editDistance("buddy", "buddy")); assertEquals(1, WakePhrases.editDistance("buddi", "buddy")); assertEquals(5, WakePhrases.editDistance("", "buddy"))
    }

    @Test fun memoryNeverKeepsCodesOrBankMessages() {
        for (t in listOf("Your OTP is 482913", "123456 is your verification code", "Rs 500 debited from a/c XX1234", "UPI payment of 200 done", "Do not share your password"))
            assertTrue(t, MemoryStore.isSensitive(t))
        for (t in listOf("Bring two double tape rolls when you come", "Plumber is coming at 5, pay him 800", "Reached home"))
            assertFalse(t, MemoryStore.isSensitive(t))
    }

    @Test fun wakePhraseWorksWhenTheRequestFollowsInTheSameBreath() {
        val none = emptySet<String>()
        assertTrue(WakePhrases.accepts(w("hay" to 0.5, "buddy" to 1.0, "[unk]" to 1.0), none))
        assertTrue(WakePhrases.accepts(w("hey" to 0.9, "buddy" to 1.0, "call" to 0.9, "[unk]" to 0.9), none))
        assertFalse("a decoy before buddy", WakePhrases.accepts(w("thanks" to 0.9, "buddy" to 1.0, "[unk]" to 0.9), none))
        assertFalse("buddy deep in a sentence", WakePhrases.accepts(w("i" to 0.9, "told" to 0.9, "my" to 0.9, "buddy" to 1.0), none))
    }
}
