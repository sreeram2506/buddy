package com.sreeram.jarvis

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Buddy's private memory of your messages: one line per message, kept only on this phone, for 90 days.
// It lets you ask "what did Dad say about the plumber?". One-time codes and bank messages are never kept.
object MemoryStore {
    private const val KEEP_MS = 90L * 86_400_000
    private const val MAX = 6000
    private val sensitive = Regex("otp|one.?time|verification code|security code|passcode|password|\\bcvv\\b|a/c|acct|account (no|number)|debited|credited|upi|\\b\\d{6}\\b is your", RegexOption.IGNORE_CASE)
    private val chatApps = listOf("whatsapp", "messaging", "mms", "telegram", "signal", "sms", "com.google.android.gm", "instagram", "orca", "slack", "teams")

    internal fun isSensitive(t: String) = sensitive.containsMatchIn(t)

    private fun file(c: Context) = File(c.filesDir, "memory.jsonl")
    fun enabled(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("memory_on", true)

    @Synchronized
    fun add(c: Context, pkg: String, sender: String, text: String, time: Long) {
        if (!enabled(c) || chatApps.none { pkg.contains(it) }) return
        if (isSensitive(text) || isSensitive(sender)) return
        if (text.isBlank() || sender.isBlank()) return
        val f = file(c)
        f.appendText(JSONObject().put("t", time).put("p", pkg).put("s", sender.take(80)).put("m", text.take(600)).toString() + "\n")
        if (f.length() > 3_000_000) prune(c)
    }

    @Synchronized
    fun prune(c: Context) {
        val f = file(c); if (!f.exists()) return
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val keep = f.readLines().filter { l -> try { JSONObject(l).getLong("t") >= cutoff } catch (e: Exception) { false } }.takeLast(MAX)
        f.writeText(keep.joinToString("\n") + if (keep.isEmpty()) "" else "\n")
    }

    @Synchronized fun clear(c: Context) { file(c).delete() }
    @Synchronized fun count(c: Context) = try { file(c).readLines().size } catch (e: Exception) { 0 }

    private val stop = setOf("the", "a", "an", "of", "to", "and", "is", "was", "what", "did", "say", "said", "about", "who", "me", "my", "i", "tell", "send", "sent", "that", "for", "in", "on", "it", "with", "when", "where", "how", "much")

    // Messages matching every meaningful word (or, failing that, any), newest first, optionally only from one person / the last N days.
    @Synchronized
    fun search(c: Context, query: String, person: String?, days: Int?, limit: Int = 8): String {
        val f = file(c)
        if (!f.exists()) return "I haven't kept any messages yet. I start remembering from the moment Memory is on."
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in stop }
        val since = if (days != null && days > 0) System.currentTimeMillis() - days * 86_400_000L else 0L
        val all = f.readLines().mapNotNull { try { JSONObject(it) } catch (e: Exception) { null } }
            .filter { it.getLong("t") >= since && (person.isNullOrBlank() || it.getString("s").contains(person, ignoreCase = true)) }
        // A word also matches its stem, so "plumber" finds "plumbing" and "deliveries" finds "delivery".
        fun score(o: JSONObject): Int { val h = (o.getString("m") + " " + o.getString("s")).lowercase(); return words.count { h.contains(it) || (it.length >= 6 && h.contains(it.take(5))) } }
        val strict = if (words.isEmpty()) all else all.filter { score(it) == words.size }
        val hits = (if (strict.isNotEmpty()) strict else all.filter { score(it) > 0 }).sortedByDescending { it.getLong("t") }.take(limit)
        if (hits.isEmpty()) return "I couldn't find anything about that in the messages I've kept (${count(c)} messages, last 90 days)."
        val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        return hits.joinToString("\n") { "${fmt.format(Date(it.getLong("t")))} — ${it.getString("s")}: ${it.getString("m")}" }
    }

    // One-time (and repeatable) import of the last 90 days of texts, so Memory is useful from day one. Needs the Texts permission.
    @Synchronized
    fun importSms(c: Context): Int {
        if (c.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return -1
        val prefs = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val since = maxOf(System.currentTimeMillis() - KEEP_MS, prefs.getLong("memory_sms_until", 0))
        var newest = since; var added = 0
        val rows = StringBuilder()
        c.contentResolver.query(android.provider.Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "body", "date"), "date > ?", arrayOf(since.toString()), "date ASC")?.use { cur ->
            while (cur.moveToNext() && added < 4000) {
                val addr = cur.getString(0) ?: continue; val body = cur.getString(1) ?: continue; val date = cur.getLong(2)
                newest = maxOf(newest, date)
                // Service senders (BX-HDFCBK etc.) are not people; skip them and anything sensitive.
                if (addr.any { it.isLetter() } || isSensitive(body) || body.isBlank()) continue
                val name = Contacts.name(c, addr) ?: addr
                rows.append(JSONObject().put("t", date).put("p", "sms").put("s", name.take(80)).put("m", body.take(600)).toString()).append('\n')
                added++
            }
        }
        if (added > 0) file(c).appendText(rows.toString())
        prefs.edit().putLong("memory_sms_until", newest).apply()
        prune(c)
        return added
    }
}
