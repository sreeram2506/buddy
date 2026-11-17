package com.sreeram.jarvis

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

// The pure parts of Simple mode, kept out of the screen so they can be tested without a phone.
object SimpleMode {
    const val CONTACTS_KEY = "simple_contacts"
    const val DEFAULT_CONTACTS = "Myself"
    val presets = listOf("I'm OK", "Please call me")

    fun contacts(raw: String?): List<String> = (raw ?: "").split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf(DEFAULT_CONTACTS) }

    // find_contact answers "Name: number; Name2: number2" or a sentence such as "No contact matching X". An exact name wins over a partial match.
    // The tools answer in words meant for the AI ("Tell the user to ..."). On the big screen the person reads them, so they get plain sentences.
    fun plain(result: String): String {
        val r = result.trim()
        Regex("^Sent to (.+?) on [A-Za-z ]+\\. Status: (sent|delivered|read)\\.$").find(r)?.let { return "Sent to ${it.groupValues[1]}." }
        return when {
            r.contains("could not find the Send button", true) -> "I could not send it. Please open WhatsApp and tap the green Send button yourself."
            r.contains("does NOT appear in the chat", true) || r.contains("probably NOT sent", true) -> "It may not have gone through. Please check the chat."
            r.contains("still pending", true) -> "The message is waiting to be sent. It will go when your phone has internet."
            r.contains("could not see a delivery tick", true) -> "It looks sent, but I cannot be sure it arrived."
            r.contains("expired", true) -> "That took too long, so I stopped. Please try again."
            r.startsWith("No contact matching", true) -> "I could not find that person in your contacts."
            r.contains("permission", true) && r.contains("contacts", true) -> "Buddy needs permission to see your contacts. Please ask someone to allow it in Settings."
            r.contains("no longer waiting", true) -> "That was already answered."
            r.startsWith("No emergency contacts are set", true) -> "No helper has been chosen yet. Please ask someone to add one in Buddy settings."
            r.contains("SMS permission is not granted", true) -> "Buddy is not allowed to send texts. Please ask someone to allow it in Settings."
            r.startsWith("I can't get your location", true) -> "I cannot find where you are. Please check that location is turned on."
            else -> r
        }
    }

    // What Buddy says out loud when it asks for approval, so a person who cannot read the box still knows what they are saying yes to.
    fun askAloud(title: String) = "Buddy needs your OK. $title. Tap the green button for yes, or the red button for no."

    fun pickContact(wanted: String, found: String): Pair<String, String>? {
        val options = found.split(';').mapNotNull { part ->
            val name = part.substringBefore(':').trim(); val number = part.substringAfter(':', "").trim()
            if (name.isNotEmpty() && number.count { it.isDigit() } >= 5) name to number else null
        }
        return options.firstOrNull { it.first.equals(wanted, true) } ?: options.firstOrNull()
    }
}

// One big, plain screen for people who find phones hard: a few large buttons, and the yes/no for anything Buddy wants to do shown as two huge buttons.
// It uses the same tools and the same approval step as voice, so nothing is sent, called or paid without a tap on Yes.
class SimpleModeActivity : AppCompatActivity() {
    private val actions by lazy { PhoneActions(this) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var askBox: LinearLayout
    private lateinit var askText: TextView
    private var shownId = -1L
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val poll = object : Runnable {
        override fun run() {
            val o = Approvals.current()
            if (o == null) { askBox.visibility = View.GONE; shownId = -1 }
            else if (o.id != shownId) { shownId = o.id; askText.text = o.title; askBox.visibility = View.VISIBLE; Speaker.speak(applicationContext, SimpleMode.askAloud(o.title)) }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Buddy"
        window.statusBarColor = Color.parseColor("#0B0F14")
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0B0F14")); addView(page) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + dp(16), b.top + dp(16), b.right + dp(16), b.bottom + dp(24)); insets
        }

        page.addView(text("Buddy", 36f, "#EAF1F7", bold = true))

        askText = text("", 26f, "#FFFFFF", bold = true)
        askBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(Color.parseColor("#5A4A00")); cornerRadius = dp(20).toFloat() }
            addView(text("Buddy needs your OK", 20f, "#FFE680"))
            addView(askText)
            addView(big("Yes, go ahead", "#1E8E3E") { answer(true) })
            addView(big("No, cancel", "#B3261E") { answer(false) })
        }
        page.addView(askBox, spaced())

        status = text("Tap a button.", 24f, "#C5D0DB")
        page.addView(status, spaced())

        page.addView(big("I need help", "#B3261E") { help() }, spaced())
        page.addView(big("Where am I?", "#1F4E79") { run("Finding where you are") { actions.run("where_am_i", JSONObject()) } }, spaced())
        page.addView(big("Read my messages", "#1F4E79") { run("Reading your messages") { actions.run("read_messages", JSONObject()) } }, spaced())

        for (name in SimpleMode.contacts(getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString(SimpleMode.CONTACTS_KEY, SimpleMode.DEFAULT_CONTACTS))) {
            page.addView(text(name, 28f, "#2EE6C5", bold = true), spaced(24))
            page.addView(big("Call $name", "#1F4E79") { call(name) }, spaced())
            SimpleMode.presets.forEach { msg -> page.addView(big("Tell $name: $msg", "#1F4E79") { tell(name, msg) }, spaced()) }
        }
    }

    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(poll) }

    private fun answer(yes: Boolean) {
        val o = Approvals.current() ?: return
        askBox.visibility = View.GONE
        if (!yes) { Approvals.cancel(o.id); ActivityLog.add(applicationContext, "Cancelled on the big screen: ${o.title}"); say("Cancelled. Nothing was done."); return }
        run("Doing it") { Approvals.approve(o.id).also { ActivityLog.add(applicationContext, "Approved on the big screen: $it") } }
    }

    // Texts the emergency contacts with the location, after the same Yes/No as everything else.
    private fun help() = run("Getting help ready") {
        val r = actions.run("prepare_sos", JSONObject())
        if (Approvals.current() != null) ASK else r
    }

    private fun call(name: String) = run("Finding $name") {
        val found = actions.run("find_contact", JSONObject().put("name", name))
        val c = SimpleMode.pickContact(name, found) ?: return@run found
        actions.run("call_number", JSONObject().put("number", c.second).put("name", c.first))
        ASK
    }

    private fun tell(name: String, message: String) = run("Getting it ready") {
        val r = actions.run("draft_whatsapp", JSONObject().put("contact", name).put("message", message))
        if (Approvals.current() != null) ASK else r
    }

    // Runs a tool off the main thread. If it set up something that needs approval, the yellow box shows it; otherwise its answer is shown and spoken.
    private fun run(doing: String, block: () -> String) {
        status.text = "$doing…"
        Thread {
            val r = try { block() } catch (e: Exception) { "Something went wrong: ${e.message}" }.let { if (it == ASK) it else SimpleMode.plain(it) }
            runOnUiThread { if (r == ASK) say("Please tap Yes or No in the yellow box.") else say(r) }
            if (r != ASK) Speaker.speak(applicationContext, r.take(600))
        }.start()
    }

    private fun say(t: String) { status.text = t }

    private fun text(t: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color)); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun big(label: String, color: String, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 24f; isAllCaps = false; setTextColor(Color.WHITE); minimumHeight = dp(84)
        background = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = dp(20).toFloat() }
        gravity = Gravity.CENTER; setOnClickListener { click() }
    }

    private fun spaced(top: Int = 12) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private companion object { const val ASK = "\u0000ask" }
}
