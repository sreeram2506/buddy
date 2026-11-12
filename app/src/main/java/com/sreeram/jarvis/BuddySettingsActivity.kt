package com.sreeram.jarvis

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

// One place to configure every Buddy feature. Everything saves the moment you change it.
class BuddySettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }
    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private lateinit var page: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Buddy settings"
        window.statusBarColor = Color.parseColor("#0B0F14")
        val scroll = ScrollView(this)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(page)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left + dp(16), b.top + dp(12), b.right + dp(16), b.bottom + dp(24)); insets
        }
    }

    override fun onResume() { super.onResume(); build() }

    private fun build() {
        page.removeAllViews()
        val head = TextView(this).apply { text = "Settings"; textSize = 26f; setTextColor(Color.parseColor("#EAF1F7")); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(4), 0, dp(8)) }
        page.addView(head)

        section("Health") {
            val checks = Health.checks(this)
            val bad = checks.count { !it.ok }
            note(if (bad == 0) "Everything Buddy needs is on." else "$bad thing${if (bad == 1) "" else "s"} need attention.")
            checks.filter { !it.ok }.forEach { c -> problem(c) }
            if (bad > 0) button("Fix what I can", "primary") {
                Health.healAccessibility(this)
                val ask = checks.filter { !it.ok && it.permission != null }.mapNotNull { it.permission }.distinct().toTypedArray()
                if (ask.isNotEmpty()) androidx.core.app.ActivityCompat.requestPermissions(this, ask, 7)
                build()
            }
        }
        section("You") {
            text("Your name", "my_name", "Sreeram", "Buddy greets you with this name")
            choice("Language", "lang", Lang.options.map { it.first to it.second }, "auto")
        }
        section("Listening") {
            toggle("Say \"Hey Buddy\" to wake", "wake", false, "Listens only while the phone is unlocked. Audio is checked on the phone and never saved.") { on ->
                if (on) WakeWordService.start(this) else WakeWordService.stop(this)
            }
            button("Train my voice (Hey Buddy)", "primary") { startActivity(Intent(this, MainActivity::class.java).putExtra("train", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            button("Voice check (read 10 sentences)") {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 10)
                else VoiceCheck.start(this) { toast("Voice check saved on this phone"); build() }
            }
            toggle("Live voice (natural talk, you can interrupt)", "live", true)
            toggle("Warn me about low battery", "battery_alerts", true, "Spoken at 20%, 10% and 5%")
        }
        section("Driving mode") {
            toggle("Driving mode", "driving", false, "Reads new messages aloud, announces callers, and texts callers you miss. You can also say \"Hey Buddy, I'm driving\".") { on ->
                if (on) Driving.start(this) else Driving.stop(this)
            }
            text("Text sent to callers while driving", "driving_text", "", "Leave empty for Buddy's default message")
        }
        section("Car") {
            val granted = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                note("Pick your car's Bluetooth and Driving mode turns on by itself when you connect.")
                button("Choose my car") { androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 8) }
            } else {
                val bonded = try { getSystemService(android.bluetooth.BluetoothManager::class.java).adapter?.bondedDevices?.toList().orEmpty() } catch (e: SecurityException) { emptyList() }
                val opts = listOf("" to "Not set") + bonded.map { it.address to (it.name ?: it.address) }
                choice("Start Driving mode when connected to", "car_bt_address", opts, "")
            }
        }
        section("Activity") {
            note("What Buddy has done for you. No message text or codes are kept here.")
            val items = ActivityLog.recent(this, 8)
            if (items.isEmpty()) note("Nothing yet.") else items.forEach { line(it) }
            if (items.isNotEmpty()) button("Clear activity") { ActivityLog.clear(this); build() }
        }
        section("Memory") {
            toggle("Remember my messages", "memory_on", true, "Lets you ask \"what did Dad say about the plumber?\". Kept only on this phone for 90 days. One-time codes and bank messages are never kept.")
            note("${MemoryStore.count(this)} messages remembered.")
            button("Import my text messages (last 90 days)") {
                if (checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_SMS), 9)
                else Thread { val n = MemoryStore.importSms(applicationContext); runOnUiThread { toast("Imported $n messages"); build() } }.start()
            }
            button("Forget all remembered messages", "danger") { MemoryStore.clear(this); toast("Memory cleared"); build() }
        }
        section("Away mode") {
            val on = Away.active(this)
            note(if (on) "Away mode is ON." else "Away mode is off. Say \"Hey Buddy, turn on Away mode\".")
            if (on) button("Turn off Away mode", "danger") { Away.end(this); build() }
            text("Reply to missed callers", "away_text", "", "Leave empty for Buddy's default message")
            number("Text the caller after (seconds of ringing)", "away_text_delay", 10)
            toggle("Decline calls instead of letting them ring", "away_decline", false)
            toggle("Show the summary on screen when I'm back", "away_popup", false)
            choice("Phone on silent counts as Away", "silent_away", listOf("silent" to "Silent only", "vibrate" to "Silent or vibrate", "off" to "Never"), "silent")
        }
        section("Permissions Buddy needs") {
            status("Controls the screen for you", JarvisAccessibilityService.instance != null, Settings.ACTION_ACCESSIBILITY_SETTINGS)
            status("Reads notifications and replies", NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName), Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            status("Is your default assistant", Settings.Secure.getString(contentResolver, "assistant")?.contains(packageName) == true, Settings.ACTION_VOICE_INPUT_SETTINGS)
            button("Allow Buddy to run in the background (battery)") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        section("Privacy and shutdown") {
            note("Buddy saves no voice recordings. Live talk is streamed to Google's Gemini only while a conversation is open.")
            button("Clear chat history") { ChatStore(this).clear(); toast("Chat cleared") }
            button("Shut down Buddy (mic off, Away and Driving off)", "danger") {
                prefs.edit().putBoolean("wake", false).apply()
                WakeWordService.stop(this); if (Away.active(this)) Away.end(this); Driving.stop(this)
                toast("Buddy is off. Open Settings to turn things back on."); build()
            }
        }
    }

    // ---- building blocks ----

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.parseColor("#151B23")); setStroke(dp(1), Color.parseColor("#2A3441")) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    private var cur: LinearLayout? = null
    private fun section(name: String, body: () -> Unit) {
        page.addView(TextView(this).apply { text = name.uppercase(); textSize = 12f; letterSpacing = 0.1f; setTextColor(Color.parseColor("#2EE6C5")); setPadding(dp(6), dp(10), 0, dp(8)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val c = card(); cur = c; body(); page.addView(c)
    }
    private fun add(v: View) { cur!!.addView(v) }
    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) }
    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    private fun EditText.styleField() {
        setBackgroundResource(R.drawable.bg_input); setTextColor(Color.parseColor("#EAF1F7")); setHintTextColor(Color.parseColor("#6B7785")); textSize = 16f
        setPadding(dp(16), dp(11), dp(16), dp(11))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
    }

    private fun line(t: String) = add(TextView(this).apply { text = t; textSize = 13f; setTextColor(Color.parseColor("#C5D0DB")); setPadding(0, dp(3), 0, dp(3)) })

    private fun problem(c: Health.Check) {
        add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4))
            addView(TextView(this@BuddySettingsActivity).apply { text = "✗"; textSize = 18f; setTextColor(Color.parseColor("#FF6B6B")); setPadding(0, 0, dp(10), 0) })
            addView(LinearLayout(this@BuddySettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@BuddySettingsActivity).apply { text = c.label; textSize = 15f })
                addView(TextView(this@BuddySettingsActivity).apply { text = c.why; textSize = 12f; setTextColor(Color.parseColor("#7F8C9A")) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            c.fix?.let { fix -> addView(Button(this@BuddySettingsActivity).apply {
                text = "Fix"; isAllCaps = false; textSize = 13f; stateListAnimator = null; minHeight = dp(36); minimumHeight = dp(36); minWidth = dp(64); minimumWidth = dp(64)
                setBackgroundResource(R.drawable.btn_secondary); setTextColor(Color.parseColor("#2EE6C5")); setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { startActivity(fix) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))) }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
            Thread { val n = MemoryStore.importSms(applicationContext); runOnUiThread { toast("Imported $n messages"); build() } }.start()
        else build()
    }

    private fun note(t: String) = add(TextView(this).apply { text = t; textSize = 13f; setTextColor(Color.parseColor("#7F8C9A")); setPadding(0, dp(2), 0, dp(8)) })

    private fun toggle(label: String, key: String, default: Boolean, hint: String? = null, onChange: ((Boolean) -> Unit)? = null) {
        add(Switch(this).apply {
            text = label; isChecked = prefs.getBoolean(key, default); textSize = 15f; setPadding(0, dp(6), 0, dp(2))
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply(); onChange?.invoke(on) }
        })
        if (hint != null) note(hint)
    }

    private fun text(label: String, key: String, default: String, hint: String? = null) {
        add(TextView(this).apply { text = label; textSize = 14f; setTextColor(Color.parseColor("#C5D0DB")); setPadding(0, dp(10), 0, 0) })
        add(EditText(this).apply {
            styleField(); setText(prefs.getString(key, default)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { prefs.edit().putString(key, s.toString().trim()).apply() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        if (hint != null) note(hint)
    }

    private fun number(label: String, key: String, default: Int) {
        add(TextView(this).apply { text = label; textSize = 14f; setTextColor(Color.parseColor("#C5D0DB")); setPadding(0, dp(10), 0, 0) })
        add(EditText(this).apply {
            styleField(); setText(prefs.getInt(key, default).toString()); inputType = InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { s.toString().toIntOrNull()?.let { prefs.edit().putInt(key, it.coerceIn(0, 120)).apply() } }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
    }

    private fun choice(label: String, key: String, options: List<Pair<String, String>>, default: String) {
        add(TextView(this).apply { text = label; textSize = 14f; setTextColor(Color.parseColor("#C5D0DB")); setPadding(0, dp(10), 0, 0) })
        add(Spinner(this).apply {
            setBackgroundResource(R.drawable.bg_input); setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) }
            adapter = ArrayAdapter(this@BuddySettingsActivity, android.R.layout.simple_spinner_dropdown_item, options.map { it.second })
            setSelection(options.indexOfFirst { it.first == (prefs.getString(key, default) ?: default) }.coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { prefs.edit().putString(key, options[pos].first).apply() }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
    }

    private fun button(label: String, kind: String = "secondary", click: () -> Unit) {
        add(Button(this).apply {
            text = label; isAllCaps = false; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            minHeight = dp(48); minimumHeight = dp(48); stateListAnimator = null
            when (kind) {
                "primary" -> { setBackgroundResource(R.drawable.btn_primary); setTextColor(Color.parseColor("#06231E")) }
                "danger" -> { setBackgroundResource(R.drawable.btn_danger); setTextColor(Color.parseColor("#FF8A8A")) }
                else -> { setBackgroundResource(R.drawable.btn_secondary); setTextColor(Color.parseColor("#2EE6C5")) }
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(4) }
            setOnClickListener { click() }
        })
    }

    private fun status(label: String, ok: Boolean, action: String) {
        add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4))
            addView(TextView(this@BuddySettingsActivity).apply { text = if (ok) "✓" else "✗"; textSize = 18f; setTextColor(if (ok) Color.parseColor("#2E7D6B") else Color.parseColor("#C62828")); setPadding(0, 0, dp(10), 0) })
            addView(TextView(this@BuddySettingsActivity).apply { text = label; textSize = 15f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(Button(this@BuddySettingsActivity).apply {
                text = if (ok) "Review" else "Allow"; isAllCaps = false; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD); stateListAnimator = null
                minHeight = dp(36); minimumHeight = dp(36); minWidth = dp(72); minimumWidth = dp(72); setPadding(dp(14), 0, dp(14), 0)
                setBackgroundResource(if (ok) R.drawable.btn_secondary else R.drawable.btn_primary)
                setTextColor(Color.parseColor(if (ok) "#2EE6C5" else "#06231E"))
                setOnClickListener { startActivity(Intent(action)) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        })
    }
}
