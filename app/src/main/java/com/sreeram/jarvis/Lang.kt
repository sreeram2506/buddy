package com.sreeram.jarvis

import android.content.Context
import java.util.Locale

// Which language Buddy listens for and answers in: auto (English, Hindi or Telugu), or one fixed language.
object Lang {
    val options = listOf("auto" to "Auto", "en" to "English", "hi" to "हिन्दी", "te" to "తెలుగు")

    fun current(c: Context): String = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"
    fun set(c: Context, code: String) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("lang", code).apply()

    fun tag(code: String) = when (code) { "hi" -> "hi-IN"; "te" -> "te-IN"; else -> "en-IN" }

    // Speak each reply in the language it is written in, whatever the setting is.
    fun localeFor(text: String): Locale = when {
        text.any { it in 'ऀ'..'ॿ' } -> Locale("hi", "IN")
        text.any { it in 'ఀ'..'౿' } -> Locale("te", "IN")
        else -> Locale("en", "IN")
    }

    // Speech recognition can mishear a word as another language (Turkish, Italian, French...). Whatever it seems to be,
    // the user only ever speaks English, Hindi or Telugu, so Buddy must only ever answer in those.
    fun promptRule(code: String): String = "The user only ever speaks English, Hindi or Telugu. If a word or sentence sounds like any other language, " +
        "assume it was misheard and answer in English, Hindi or Telugu only, never in French, Spanish, Turkish, Italian or any other language. " +
        "Keep tool arguments such as contact names, app names and search queries in English letters. " + when (code) {
        "hi" -> "Always reply in Hindi, written in Devanagari script, whatever language the user speaks."
        "te" -> "Always reply in Telugu, written in Telugu script, whatever language the user speaks."
        "en" -> "Always reply in English."
        else -> "The user speaks English, Hindi or Telugu, sometimes mixed. Reply in the language they last used, in that language's own script (Devanagari for Hindi, Telugu script for Telugu)."
    }
}
