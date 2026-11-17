package com.sreeram.jarvis

import org.json.JSONObject

// Short Hindi and Telugu commands (in their own script or in English letters) for the same one-step jobs, matched by keyword so that
// small differences in spelling or endings ("karo", "करो", "చేయి") do not matter. Only short sentences count, so a long request that happens to
// contain "volume" still goes to the AI.
object LocalIntents {
    class Hit(val tool: String, val args: Map<String, Any>)

    private val torch = listOf("torch", "tarch", "flashlight", "flash", "टॉर्च", "टार्च", "फ्लैश", "టార్చ్", "టార్చి", "ఫ్లాష్")
    private val on = listOf("on", "chalu", "shuru", "jalao", "चालू", "शुरू", "जलाओ", "ఆన్", "వెలిగించ")
    private val off = listOf("off", "band", "bandh", "bujhao", "बंद", "बुझाओ", "ఆఫ్", "ఆర్పు", "ఆపు")
    private val volume = listOf("volume", "awaz", "aawaz", "avaz", "sound", "वॉल्यूम", "आवाज", "आवाज़", "వాల్యూమ్", "సౌండ్")
    private val up = listOf("up", "badhao", "badha", "zyada", "jyada", "increase", "बढ़ा", "बढ़ा", "ज़्यादा", "ज्यादा", "పెంచ")
    private val down = listOf("down", "kam", "ghatao", "decrease", "कम", "घटा", "తగ్గించ", "తగ్గ")
    private val battery = listOf("battery", "बैटरी", "బ్యాటరీ")

    private fun words(t: String) = t.lowercase().split(Regex("[\\s,.!?।]+")).filter { it.isNotEmpty() }
    private fun has(w: List<String>, keys: List<String>) = w.any { tok -> keys.any { tok == it || (it.length >= 3 && tok.startsWith(it)) } }

    fun match(raw: String): Hit? {
        val w = words(raw)
        if (w.isEmpty() || w.size > 6) return null
        if (has(w, torch)) {
            val wantOn = has(w, on); val wantOff = has(w, off)
            if (wantOn != wantOff) return Hit("set_flashlight", mapOf("on" to wantOn))
            return null
        }
        if (has(w, volume)) {
            val wantUp = has(w, up); val wantDown = has(w, down)
            if (wantUp != wantDown) return Hit("adjust_volume", mapOf("direction" to if (wantUp) "up" else "down"))
            return null
        }
        if (has(w, battery)) return Hit("battery_status", emptyMap())
        return null
    }
}

// Instant, offline handling for the most common one-step commands. Returns null when it doesn't recognise the request.
object FastPath {
    fun tryHandle(raw: String, actions: PhoneActions): String? {
        val t = raw.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("^(hey )?buddy,? "), "").replace(Regex("^please "), "")
        fun run(tool: String, vararg args: Pair<String, Any>) = actions.run(tool, JSONObject(args.toMap()))

        LocalIntents.match(raw)?.let { return run(it.tool, *it.args.toList().toTypedArray()) }
        Regex("^(?:speak|talk|reply|answer|respond|switch to|change language to|use)(?: in| to)? (english|hindi|telugu|auto|automatic)$").find(t)?.let {
            return run("set_language", "language" to it.groupValues[1])
        }
        Regex("^(?:set )?(?:the )?volume (?:to )?(\\d{1,3})(?: percent)?$").find(t)?.let { return run("set_volume", "percent" to it.groupValues[1].toInt()) }
        if (Regex("^(?:turn )?(?:the )?volume up$|^louder$|^turn it up$").matches(t)) return run("adjust_volume", "direction" to "up")
        if (Regex("^(?:turn )?(?:the )?volume down$|^quieter$|^turn it down$|^lower the volume$").matches(t)) return run("adjust_volume", "direction" to "down")
        Regex("^(?:turn |switch )?(on|off) (?:the )?(?:flashlight|torch)$|^(?:flashlight|torch) (on|off)$").find(t)?.let {
            return run("set_flashlight", "on" to ((it.groupValues[1] + it.groupValues[2]) == "on"))
        }
        if (Regex("^(?:what(?:'s| is) )?(?:my |the )?battery(?: level| percentage)?$|^how much battery.*").matches(t)) return run("battery_status")
        if (Regex("^(?:pause|stop)(?: the)?(?: music| song| video)?$").matches(t)) return run("media_control", "action" to "pause")
        if (Regex("^(?:play|resume)(?: the)?(?: music| song| video)?$").matches(t)) return run("media_control", "action" to "play")
        if (Regex("^(?:next|skip)(?: the)?(?: song| track)?$").matches(t)) return run("media_control", "action" to "next")
        if (Regex("^(?:previous|go back)(?: the)?(?: song| track)$|^previous$").matches(t)) return run("media_control", "action" to "previous")
        Regex("^(?:set )?(?:a )?timer (?:for )?(\\d+) (second|minute|hour)s?$").find(t)?.let {
            val n = it.groupValues[1].toInt()
            val secs = when (it.groupValues[2]) { "hour" -> n * 3600; "minute" -> n * 60; else -> n }
            return run("set_timer", "seconds" to secs)
        }
        Regex("^(?:set )?(?:the )?brightness (?:to )?(\\d{1,3})(?: percent)?$").find(t)?.let { return run("set_brightness", "percent" to it.groupValues[1].toInt()) }
        Regex("^(?:turn )?(on|off) do not disturb$|^do not disturb (on|off)$").find(t)?.let {
            return run("set_do_not_disturb", "on" to ((it.groupValues[1] + it.groupValues[2]) == "on"))
        }
        Regex("^open ([a-z0-9 ]{2,30})$").find(t)?.let {
            val name = it.groupValues[1]
            if (!name.contains(" and ") && name.split(" ").size <= 3) return run("open_app", "name" to name).takeUnless { r -> r.startsWith("No app") }
        }
        return null
    }
}
