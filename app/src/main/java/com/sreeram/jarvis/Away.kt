package com.sreeram.jarvis

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.PowerManager
import android.provider.CallLog
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// Away mode: declines calls and answers texts with a short, honest assistant message, lets chosen people ring through,
// keeps a log, and summarises everything when the user is back. It never touches WhatsApp or other apps.
object Away {
    private const val CHANNEL = "away"

    private fun f(c: Context, name: String) = File(c.filesDir, name)
    @Synchronized private fun state(c: Context): JSONObject = try { JSONObject(f(c, "away.json").readText()) } catch (e: Exception) { JSONObject() }
    @Synchronized private fun save(c: Context, o: JSONObject) { f(c, "away.json").writeText(o.toString()) }
    @Synchronized private fun entries(c: Context): JSONArray = try { JSONArray(f(c, "away_log.json").readText()) } catch (e: Exception) { JSONArray() }

    // True while Away mode is on: either you started it, or the phone is on silent (see silentNow).
    // This is also where a silent-mode session is opened and closed, so it works whenever something checks it.
    fun active(c: Context): Boolean {
        val s = state(c)
        val running = s.optBoolean("active") && s.optLong("until", 0) > System.currentTimeMillis()
        if (running) {
            if (s.optBoolean("silent") && !silentNow(c)) { finishSilent(c); return false }
            return true
        }
        if (silentNow(c)) { start(c, Long.MAX_VALUE, emptyList(), silent = true); return true }
        return false
    }

    // "silent" = the ringer is on Silent; "vibrate" also counts Vibrate; "off" disables this.
    private fun silentNow(c: Context): Boolean {
        val setting = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("silent_away", "silent")
        if (setting == "off") return false
        val ringer = c.getSystemService(AudioManager::class.java).ringerMode
        return ringer == AudioManager.RINGER_MODE_SILENT || (setting == "vibrate" && ringer == AudioManager.RINGER_MODE_VIBRATE)
    }

    fun silentSetting(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("silent_away", "silent") ?: "silent"

    private fun finishSilent(c: Context) {
        val summary = end(c)
        notifySummary(c, "Silent mode ended", summary, popup = true)
    }

    private fun defaultVips(c: Context): List<String> = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getStringSet("away_vips", emptySet())?.toList() ?: emptyList()
    fun setDefaultVips(c: Context, keys: Set<String>) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putStringSet("away_vips", keys).apply()
    fun key(number: String): String = number.filter { it.isDigit() }.takeLast(10)
    private fun myName(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("my_name", "Sreeram") ?: "Sreeram"
    private fun time(t: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))

    // ---- the words ----

    internal fun script(text: String) = when {
        text.any { it in 'ऀ'..'ॿ' } -> "hi"
        text.any { it in 'ఀ'..'౿' } -> "te"
        else -> "en"
    }

    fun callReply(c: Context): String {
        c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("away_text", null)?.let { return it }
        val n = myName(c)
        return "Hi, this is $n's assistant. $n can't take calls now. Text your message here and $n will reply when free. If it's urgent, reply URGENT."
    }

    private fun smsFirst(c: Context, lang: String): String {
        c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("away_text", null)?.let { return it }
        val n = myName(c)
        return when (lang) {
            "hi" -> "नमस्ते, मैं $n का असिस्टेंट हूँ। वे अभी व्यस्त हैं और लौटकर आपका संदेश पढ़ेंगे। ज़रूरी हो तो URGENT लिखें।"
            "te" -> "నమస్కారం, నేను $n అసిస్టెంట్‌ని. $n ప్రస్తుతం బిజీగా ఉన్నారు, తిరిగి వచ్చాక మీ సందేశం చదువుతారు. అత్యవసరమైతే URGENT అని రాయండి."
            else -> "Hi, this is $n's assistant. $n is away right now and will read your message when back. If it's urgent, reply URGENT."
        }
    }

    private fun ack(lang: String) = when (lang) {
        "hi" -> "ठीक है, धन्यवाद। आपका संदेश पहुँचा दिया है।"
        "te" -> "సరే, ధన్యవాదాలు. మీ సందేశాన్ని చేరవేశాను."
        else -> "Got it, thanks. Your message has been passed on."
    }

    private fun urgentAck(c: Context, lang: String): String {
        val n = myName(c)
        return when (lang) {
            "hi" -> "आपका संदेश ज़रूरी चिह्नित कर दिया है। $n को सूचित कर दिया है।"
            "te" -> "మీ సందేశాన్ని అత్యవసరంగా గుర్తించాను. $n కి తెలియజేశాను."
            else -> "Marked urgent. $n has been alerted."
        }
    }

    internal fun looksUrgent(body: String) = Regex("urgent|emergency|जरूरी|ज़रूरी|अर्जेंट|అత్యవసర", RegexOption.IGNORE_CASE).containsMatchIn(body)

    // ---- control ----

    fun start(c: Context, untilMs: Long, vipKeys: List<String>, silent: Boolean = false) {
        val o = JSONObject().put("active", true).put("silent", silent).put("start", System.currentTimeMillis()).put("until", untilMs)
            .put("vips", JSONArray((vipKeys + defaultVips(c)).distinct())).put("people", JSONObject())
        save(c, o)
        f(c, "away_log.json").delete()
        if (!silent) scheduleEnd(c, untilMs)
        notifyOngoing(c, untilMs, silent)
    }

    fun end(c: Context): String {
        val s = summary(c)
        val wasSilent = state(c).optBoolean("silent")
        save(c, state(c).put("active", false))
        if (wasSilent && silentNow(c)) try { c.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (e: Exception) { }
        c.getSystemService(AlarmManager::class.java).cancel(endIntent(c))
        c.getSystemService(NotificationManager::class.java).cancel(31)
        Store(c).addNote("Away summary: ${s.replace('\n', ' ')}")
        return s
    }

    fun rescheduleAfterBoot(c: Context) {
        val s = state(c)
        if (s.optBoolean("active")) scheduleEnd(c, maxOf(s.optLong("until"), System.currentTimeMillis() + 5000))
    }

    private fun endIntent(c: Context) = PendingIntent.getBroadcast(c, 7101, Intent(c, AwayEndReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun scheduleEnd(c: Context, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, endIntent(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, endIntent(c))
    }

    private fun channel(c: Context): NotificationManager {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Away mode", NotificationManager.IMPORTANCE_HIGH))
        return nm
    }

    private fun notifyOngoing(c: Context, until: Long, silent: Boolean = false) {
        val back = PendingIntent.getBroadcast(c, 7102, Intent(c, AwayStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        channel(c).notify(31, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(if (silent) "Silent mode: Away mode is on" else "Away mode is on")
            .setContentText(if (silent) "Handling calls and texts while your phone is silent. Tap I'm back to turn the ringer on."
                            else "Handling calls and texts until ${time(until)}. Tap I'm back to stop.")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).addAction(0, "I'm back", back).build())
    }

    fun notifySummary(c: Context, title: String, text: String, popup: Boolean = false) {
        val headline = headline(c)
        val quiet = headline == "nothing came in"
        c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("pending_summary", text).apply() // shown in Buddy's chat next time it opens
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("away_quiet", "Away summary (nothing new)", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(c, 7103, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val heading = "$title: $headline"
        val publicVersion = NotificationCompat.Builder(c, if (quiet) "away_quiet" else CHANNEL).setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(heading).setContentText("Unlock to see the details").build()
        channel(c)
        nm.notify(32, NotificationCompat.Builder(c, if (quiet) "away_quiet" else CHANNEL).setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(heading).setContentText(text.lineSequence().filter { it.isNotBlank() }.let { l -> l.drop(1).firstOrNull() ?: l.firstOrNull() }.orEmpty().take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            .setContentIntent(open).setAutoCancel(true)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH).build())
        if (popup && !quiet) openBuddyWithSummary(c)
    }

    // Opens Buddy's screen with the summary, as the default assistant is allowed to. Only when the screen is on and unlocked,
    // so it never lights up a sleeping phone or covers a lock screen.
    private fun openBuddyWithSummary(c: Context) {
        val prefs = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("away_popup", true)) return
        val awake = c.getSystemService(PowerManager::class.java).isInteractive && !c.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        if (!awake) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            JarvisInteractionService.instance?.showSession(android.os.Bundle().apply { putBoolean("summary_only", true) }, 0)
        }
    }

    // ---- events ----

    internal fun logCaller(c: Context, number: String, text: String, urgent: Boolean) =
        log(c, if (urgent) "urgent" else "text", number, Contacts.name(c, number), "(on the phone) $text")

    private fun log(c: Context, type: String, number: String?, name: String?, text: String) {
        val a = entries(c)
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("type", type).put("n", number ?: "").put("name", name ?: "").put("text", text))
        f(c, "away_log.json").writeText(a.toString())
    }

    private fun person(c: Context, number: String): JSONObject {
        val s = state(c)
        val people = s.optJSONObject("people") ?: JSONObject().also { s.put("people", it) }
        val p = people.optJSONObject(key(number)) ?: JSONObject().also { people.put(key(number), it) }
        return p.also { save(c, s) }
    }

    private fun putField(c: Context, number: String, field: String, value: Any) {
        val s = state(c)
        val people = s.optJSONObject("people") ?: JSONObject().also { s.put("people", it) }
        people.put(key(number), (people.optJSONObject(key(number)) ?: JSONObject()).put(field, value))
        s.put("people", people); save(c, s)
    }

    private fun markSent(c: Context, number: String, field: String) {
        val s = state(c)
        val people = s.optJSONObject("people") ?: JSONObject().also { s.put("people", it) }
        people.put(key(number), (people.optJSONObject(key(number)) ?: JSONObject()).put(field, true))
        s.put("people", people); save(c, s)
    }

    fun isVip(c: Context, number: String): Boolean = state(c).optJSONArray("vips")?.let { a -> (0 until a.length()).any { a.getString(it) == key(number) } } ?: false

    // Real people only: skip bank/OTP/shop senders, which show up as names or short codes.
    internal fun canReply(number: String) = number.filter { it.isDigit() }.length >= 10 && number.none { it.isLetter() }

    // Sends the text and asks the phone to report back whether it left, and whether it was delivered.
    private fun send(c: Context, number: String, text: String): Boolean {
        if (c.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) { onSendFailed(c, number, "no permission to send texts"); return false }
        android.util.Log.d("Jarvis", "away: sending auto-reply to ${key(number)}")
        val sms = c.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(text)
        val base = (System.nanoTime() and 0xFFFFFF).toInt() * 8
        fun report(action: String, i: Int) = PendingIntent.getBroadcast(c, base + i + (if (action == "sent") 0 else 4),
            Intent(c, SmsResultReceiver::class.java).setAction(action).putExtra("n", number), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        val sent = ArrayList<PendingIntent>(); val delivered = ArrayList<PendingIntent>()
        for (i in 0 until minOf(parts.size, 4)) { sent.add(report("sent", i)); delivered.add(report("delivered", i)) }
        return try {
            sms.sendMultipartTextMessage(number, null, parts, if (sent.size == parts.size) sent else null, if (delivered.size == parts.size) delivered else null)
            true
        } catch (e: Exception) { onSendFailed(c, number, e.message ?: "error"); false }
    }

    fun onSendFailed(c: Context, number: String, why: String) {
        val who = Contacts.name(c, number) ?: number
        android.util.Log.w("Jarvis", "auto-reply to $who failed: $why")
        log(c, "status", number, who, "reply failed: $why")
        channel(c).notify(("f" + number).hashCode(), NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Couldn't send the auto-reply to $who").setContentText(why).setAutoCancel(true).build())
    }

    // Called on an incoming call. Returns true if the call was declined.
    fun onCall(c: Context, number: String): Boolean {
        val name = Contacts.name(c, number)
        if (isVip(c, number)) { log(c, "call", number, name, "rang through"); return false }
        // Someone calling twice within 3 minutes is probably urgent, so the second call is allowed to ring (like Android's "repeat callers").
        val lastCall = person(c, number).optLong("lastCall", 0)
        putField(c, number, "lastCall", System.currentTimeMillis())
        if (lastCall > 0 && System.currentTimeMillis() - lastCall < 180_000) { log(c, "call", number, name, "repeat call, rang through"); return false }
        log(c, "call", number, name, "declined")
        if (c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED)
            c.getSystemService(TelecomManager::class.java).endCall()
        maybeReplyToCall(c, number)
        return true
    }

    // Texts a caller at most once every 3 minutes (so repeated rings do not spam them), and not at all if they have already texted back.
    fun textCaller(c: Context, number: String, text: String) = maybeReplyToCall(c, number, text)

    private fun maybeReplyToCall(c: Context, number: String, text: String? = null) {
        val p = person(c, number)
        if (canReply(number) && !p.optBoolean("acked") && System.currentTimeMillis() - p.optLong("repliedAt", 0) > 3 * 60_000) {
            if (send(c, number, text ?: callReply(c))) { markSent(c, number, "replied"); putField(c, number, "repliedAt", System.currentTimeMillis()) }
        }
    }

    // ---- ring first, text only if the call goes unanswered ----

    private fun declineNow(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("away_decline", false)

    private fun textDelay(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getInt("away_text_delay", 10)

    // A call started ringing in Away mode. It keeps ringing. The caller is texted after a few seconds if it is still unanswered,
    // because the phone often isn't told the caller hung up until the network times out (about 30 seconds). If the call ends
    // sooner, the text goes out then instead.
    fun onRinging(c: Context, number: String) {
        android.util.Log.d("Jarvis", "away: ringing from ${key(number)}")
        if (isVip(c, number)) { log(c, "call", number, Contacts.name(c, number), "rang through"); return }
        if (declineNow(c)) { onCall(c, number); return }
        save(c, state(c).put("ring", JSONObject().put("n", number).put("answered", false).put("sent", false)))
        val delay = textDelay(c)
        if (delay > 0) Thread {
            Thread.sleep(delay * 1000L)
            onRingTimeout(c, number)
        }.start()
    }

    private fun onRingTimeout(c: Context, number: String) {
        val s = state(c)
        val ring = s.optJSONObject("ring") ?: return
        if (ring.optString("n") != number || ring.optBoolean("answered") || ring.optBoolean("sent") || !s.optBoolean("active")) return
        @Suppress("DEPRECATION")
        if (c.getSystemService(android.telephony.TelephonyManager::class.java).callState != android.telephony.TelephonyManager.CALL_STATE_RINGING) return // it just ended; onIdle handles it
        android.util.Log.d("Jarvis", "away: still ringing after ${textDelay(c)}s, texting ${key(number)}")
        log(c, "call", number, Contacts.name(c, number), "missed")
        maybeReplyToCall(c, number)
        save(c, state(c).put("ring", ring.put("sent", true)))
    }

    fun onOffHook(c: Context) {
        val s = state(c)
        s.optJSONObject("ring")?.let { save(c, s.put("ring", it.put("answered", true))) }
    }

    // The phone went idle: if a tracked call was never answered (missed, hung up, or declined by you), text the caller now.
    fun onIdle(c: Context) {
        val s = state(c)
        val ring = s.optJSONObject("ring") ?: return
        save(c, s.apply { remove("ring") })
        android.util.Log.d("Jarvis", "away: call ended (answered=${ring.optBoolean("answered")}, alreadyTexted=${ring.optBoolean("sent")})")
        if (ring.optBoolean("answered") || ring.optBoolean("sent") || !s.optBoolean("active")) return
        val number = ring.getString("n")
        log(c, "call", number, Contacts.name(c, number), "missed")
        maybeReplyToCall(c, number)
    }

    fun onSms(c: Context, from: String, body: String) {
        if (!canReply(from)) return // service and OTP messages are not worth logging
        val name = Contacts.name(c, from)
        val urgent = looksUrgent(body)
        log(c, if (urgent) "urgent" else "sms", from, name, body)
        if (urgent) {
            val who = name ?: from
            notifyUrgent(c, who, body)
            if (c.getSystemService(PowerManager::class.java).isInteractive) Speaker.speak(c, "Urgent message from $who: $body")
        }
        if (isVip(c, from)) return
        val p = person(c, from)
        val lang = script(body)
        when {
            urgent && !p.optBoolean("urgentAcked") -> if (send(c, from, urgentAck(c, lang))) markSent(c, from, "urgentAcked")
            !urgent && !p.optBoolean("acked") ->
                if (send(c, from, if (p.optBoolean("replied")) ack(lang) else smsFirst(c, lang))) markSent(c, from, "acked")
        }
    }

    private fun notifyUrgent(c: Context, who: String, body: String) {
        channel(c).notify(("u" + who).hashCode(), NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("URGENT from $who").setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX).setAutoCancel(true).build())
    }

    // ---- the summary ----

    // One-line tally for notification titles, e.g. "2 calls declined, 3 texts, 1 urgent".
    fun headline(c: Context): String {
        val log = entries(c)
        var calls = 0; var texts = 0; var urgent = 0
        for (i in 0 until log.length()) when (log.getJSONObject(i).getString("type")) { "call" -> calls++; "urgent" -> urgent++; "status" -> {}; else -> texts++ }
        val parts = ArrayList<String>()
        if (urgent > 0) parts.add("$urgent urgent")
        if (calls > 0) parts.add("$calls call${if (calls > 1) "s" else ""}")
        if (texts > 0) parts.add("$texts text${if (texts > 1) "s" else ""}")
        return if (parts.isEmpty()) "nothing came in" else parts.joinToString(", ")
    }

    fun summary(c: Context): String {
        val s = state(c)
        val start = s.optLong("start", 0)
        if (start == 0L) return "Away mode hasn't been used yet."
        val log = entries(c)
        val calls = LinkedHashMap<String, MutableList<Long>>()
        val texts = ArrayList<String>()
        val urgent = ArrayList<String>()
        val problems = ArrayList<String>()
        for (i in 0 until log.length()) {
            val e = log.getJSONObject(i)
            val who = e.optString("name").ifBlank { e.optString("n").ifBlank { "Private number" } }
            when (e.getString("type")) {
                "call" -> calls.getOrPut(who) { ArrayList() }.add(e.getLong("t"))
                "status" -> problems.add("$who (${e.getString("text")})")
                "urgent" -> urgent.add("$who: ${e.getString("text").take(160)} (${time(e.getLong("t"))})")
                else -> texts.add("$who: ${e.getString("text").take(160)} (${time(e.getLong("t"))})")
            }
        }
        val end = minOf(System.currentTimeMillis(), s.optLong("until", System.currentTimeMillis()))
        val out = ArrayList<String>()
        out.add("Away from ${time(start)} to ${time(end)}.")
        if (urgent.isNotEmpty()) out.add("URGENT: " + urgent.joinToString("; "))
        if (calls.isNotEmpty()) out.add("Missed calls: " + calls.entries.joinToString(", ") { "${it.key}${if (it.value.size > 1) " x${it.value.size}" else ""} (last ${time(it.value.last())})" })
        if (texts.isNotEmpty()) out.add("Texts: " + texts.joinToString("; "))
        if (problems.isNotEmpty()) out.add("Auto-replies that did not go out: " + problems.joinToString("; "))
        missedVipCalls(c, start)?.let { out.add("Calls that rang through and were missed: $it") }
        val others = NotificationStore.query(null, null, 100).filter { it.time >= start && !it.pkg.contains("messaging") && !it.pkg.contains("mms") && it.pkg != c.packageName }
        if (others.isNotEmpty()) {
            val pm = c.packageManager
            out.add("Other messages and updates: " + others.groupBy { it.pkg }.entries.take(5).joinToString("; ") { (pkg, list) ->
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
                "$label: " + list.take(4).joinToString(", ") { "${it.sender} - ${it.text.take(60)}" } + if (list.size > 4) " and ${list.size - 4} more" else ""
            })
        }
        if (calls.isEmpty() && texts.isEmpty() && urgent.isEmpty() && others.isEmpty() && problems.isEmpty()) out.add("Nothing came in while you were away.")
        return out.joinToString("\n")
    }

    private fun missedVipCalls(c: Context, since: Long): String? {
        if (c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        val found = ArrayList<String>()
        c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} = ?", arrayOf(since.toString(), CallLog.Calls.MISSED_TYPE.toString()), "${CallLog.Calls.DATE} DESC")?.use {
            while (it.moveToNext() && found.size < 6) {
                val number = it.getString(0) ?: continue
                if (isVip(c, number)) found.add(it.getString(1)?.takeIf { n -> n.isNotBlank() } ?: number)
            }
        }
        return found.takeIf { it.isNotEmpty() }?.distinct()?.joinToString(", ")
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Away.active(context)) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val from = msgs.firstOrNull()?.originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val pending = goAsync()
        thread { try { Away.onSms(context, from, body) } finally { pending.finish() } }
    }
}

class AwayEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val summary = Away.end(context)
        Away.notifySummary(context, "Away mode ended", summary)
    }
}

class AwayStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val summary = Away.end(context)
        Away.notifySummary(context, "Welcome back", summary, popup = true)
    }
}


// Gets the phone's report on each auto-reply: whether it left, and whether it was delivered.
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra("n") ?: return
        when (intent.action) {
            "sent" -> if (resultCode == android.app.Activity.RESULT_OK) android.util.Log.d("Jarvis", "auto-reply to ${Away.key(number)} left the phone")
                      else Away.onSendFailed(context, number, when (resultCode) {
                          SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile service"
                          SmsManager.RESULT_ERROR_RADIO_OFF -> "airplane mode or radio off"
                          SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "the network refused it (check your SMS balance or SIM)"
                          else -> "error code $resultCode" })
            "delivered" -> android.util.Log.d("Jarvis", "auto-reply to ${Away.key(number)} was delivered")
        }
    }
}
