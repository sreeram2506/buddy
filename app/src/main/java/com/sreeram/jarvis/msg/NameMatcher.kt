package com.sreeram.jarvis.msg

// Finds the contact a spoken name means even when speech recognition spelled it differently ("Seva" / "Shiva" for Siva, "Amar" for Amma).
object NameMatcher {
    // A sound-alike key for South-Asian names: spellings that sound the same give the same key.
    fun key(name: String): String {
        var s = name.lowercase().filter { it.isLetter() }
        if (s.isEmpty()) return ""
        for ((a, b) in listOf("sh" to "s", "ch" to "c", "th" to "t", "dh" to "d", "kh" to "k", "gh" to "g", "ph" to "f", "bh" to "b", "ee" to "i", "oo" to "u", "aa" to "a", "w" to "v", "z" to "s", "y" to "i", "e" to "i", "o" to "u", "x" to "ks", "q" to "k")) s = s.replace(a, b)
        s = s.replace(Regex("(.)\\1+"), "$1")                       // double letters: Amma -> Ama
        return s.first() + s.drop(1).replace(Regex("[aiu]"), "")   // keep the first letter, drop later vowels
    }

    fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    // The best contact(s) for a spoken name: exact word match first, then sound-alike. Several results means "ask which one".
    fun best(spoken: String, contacts: List<String>): List<String> {
        val q = spoken.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        contacts.filter { c -> c.lowercase().contains(q) }.let { if (it.isNotEmpty()) return it }
        fun words(c: String) = c.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length >= 2 }
        val spoken = words(q).ifEmpty { return emptyList() }
        // Every spoken word must sound like some word of the contact's name ("Shiva Pachuri" -> "Siva Pachuri ksrm").
        val scored = contacts.mapNotNull { c ->
            val theirs = words(c).map { key(it) }.filter { it.isNotEmpty() }
            var total = 0
            for (w in spoken) {
                val k = key(w)
                val d = theirs.minOfOrNull { distance(k, it) } ?: return@mapNotNull null
                if (d > (if (k.length <= 3) 0 else 1)) return@mapNotNull null
                total += d
            }
            c to total
        }
        val min = scored.minOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == min }.map { it.first }
    }
}
