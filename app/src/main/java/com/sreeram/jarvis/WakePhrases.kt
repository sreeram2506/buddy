package com.sreeram.jarvis

import android.content.Context

// What counts as "Hey buddy". Measured on real microphone audio, "buddy" is heard clearly almost every time, while the word
// before it is shaky ("hey" often comes out as "a", "hay" or "he"). So a short two-word phrase ending in "buddy" is accepted,
// whichever of those comes first. Look-alikes ("okay buddy", "hey party", "hey Bobby") are listed so the speech engine files
// them there instead of forcing them into "buddy", and a sentence that merely contains "buddy" never wakes it.
object WakePhrases {
    private val firstWords = setOf("hey", "hay", "a", "he", "hi")
    private val decoys = listOf("hey party", "hey bobby", "hey brother", "hey baby", "hey bird", "hey bunny", "hey birdie",
        "okay buddy", "hello buddy", "my buddy", "thanks buddy", "alright buddy", "sorry buddy", "yes buddy", "no buddy", "good buddy",
        "dear buddy", "come buddy", "a party", "party")

    fun variants(c: Context): Set<String> = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getStringSet("wake_variants", emptySet()) ?: emptySet()
    fun addVariant(c: Context, v: String) { c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putStringSet("wake_variants", variants(c) + v).apply() }
    fun clearVariants(c: Context) { c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().remove("wake_variants").apply() }

    fun grammar(variants: Set<String>): String {
        val accept = firstWords.map { "$it buddy" } + "buddy" + variants
        val all = accept.distinct() + "bye buddy" + decoys.filter { it !in accept } + "[unk]"
        return all.joinToString(",", "[", "]") { "\"$it\"" }
    }

    // words = what the engine heard in one finished utterance, each with its confidence. The phrase has to END in a clearly
    // heard "buddy", with at most two short lead-in words that are "hey"-like or not recognised at all, because on a real
    // microphone the "hey" is often lost ("a buddy", "[unknown] buddy"). A sentence that merely contains "buddy" never wakes it.
    fun accepts(words: List<Pair<String, Double>>, variants: Set<String>): Boolean {
        val last = words.lastOrNull() ?: return false
        // "buddy" on its own is what a soft or accented "hey" often comes out as; trained voices may be a little less sure, others must be very sure.
        if (words.size == 1) return last.first == "buddy" && last.second >= (if ("buddy" in variants) 0.8 else 0.9)
        // "Hey buddy" at the start of what was said, even when the request follows in the same breath ("Hey buddy, call Amma").
        val at = words.indexOfFirst { it.first == "buddy" }
        if (at in 1..2 && words[at].second >= 0.5 && words.size - at - 1 <= 4 &&
            words.take(at).all { it.first in firstWords || it.first == "[unk]" }) return true
        if (words.size == 2 && last.second >= 0.5 && "${words[0].first} ${last.first}" in variants) return true
        return false
    }

    fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) { 0 } }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    // From what the engine heard when it was free to hear anything (e.g. "hay buddi"), the variant of "hey buddy" this could be,
    // or null if it isn't close to it (so a stray word is never learned as a wake phrase).
    fun candidateFrom(heard: String): String? {
        val w = heard.trim().lowercase().split(Regex("\\s+"))
        if (w.size == 1 && w[0] == "buddy") return "buddy" // the "hey" was lost; accept "buddy" on its own for this user
        if (w.size != 2 || w[0] !in firstWords + setOf("hello")) return null
        val second = w[1]
        if (second == "buddy" || "hey $second" in decoys || editDistance(second, "buddy") > 2 || second.length < 3) return null
        return "hey $second"
    }
}
