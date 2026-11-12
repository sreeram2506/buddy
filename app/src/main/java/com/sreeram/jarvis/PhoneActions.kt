package com.sreeram.jarvis

import android.Manifest
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import com.sreeram.jarvis.msg.SendOutcome
import com.sreeram.jarvis.msg.SendVerifier
import com.sreeram.jarvis.msg.Waiter
import com.sreeram.jarvis.msg.NameMatcher
import com.sreeram.jarvis.msg.PhoneNumbers
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.app.RemoteInput
import android.telephony.SmsManager as Sms
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.app.SearchManager
import android.provider.MediaStore
import android.app.NotificationManager
import android.os.BatteryManager
import android.provider.CalendarContract
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Context
import android.media.AudioManager
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject

class PhoneActions(private val context: Context) {
    fun drivingOn() = Driving.active(context)
    private var torchOn = false
    private val store = Store(context)
    private val reminders = ReminderStore(context)
    private val data = DataStore(context)
    private class Draft(val number: String, val name: String, val message: String, val turn: Int, val time: Long)
    private var draft: Draft? = null
    private class PendingAction(val kind: String, val target: String, val message: String, val turn: Int, val time: Long)
    private var pending: PendingAction? = null

    fun facts(): List<String> = store.facts()

    // Names of starred contacts and the people called most, so the speech recognition expects them (Amma, Dad, ...).
    private fun frequentNames(): List<String> {
        val names = LinkedHashSet<String>()
        try {
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
                context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                    "${ContactsContract.Contacts.STARRED}=1", null, null)?.use { while (it.moveToNext() && names.size < 15) it.getString(0)?.let { n -> names.add(n) } }
            if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED)
                context.contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, arrayOf(android.provider.CallLog.Calls.CACHED_NAME), null, null, "date DESC LIMIT 150")?.use {
                    val count = HashMap<String, Int>()
                    while (it.moveToNext()) it.getString(0)?.takeIf { n -> n.isNotBlank() }?.let { n -> count[n] = (count[n] ?: 0) + 1 }
                    count.entries.sortedByDescending { e -> e.value }.take(10).forEach { e -> names.add(e.key) }
                }
        } catch (e: Exception) { }
        return names.take(25)
    }

    // Context for the AI that helps it understand an Indian accent and the user's own words and names.
    fun speechHints(): String {
        val name = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("my_name", "Sreeram") ?: "Sreeram"
        val names = frequentNames()
        return "The user's name is $name. They speak Indian English, Hindi and Telugu, with an Indian accent, so listen charitably and expect Indian names and words " +
            "(Amma, Nanna, Anna, Bhai, Akka). When a word is unclear, choose the reading that makes sense for a person in India. " +
            "Words they often use with you: Buddy, Away mode, Driving mode, Hey Buddy, WhatsApp, reminder, remember, memory, silent mode, summary. " +
            "If what you heard sounds like gibberish, think what they most likely meant from that list before asking them to repeat. " +
            if (names.isNotEmpty()) " People they often mention: ${names.joinToString(", ")}." else ""
    }
    fun languageRule(): String = Lang.promptRule(Lang.current(context))

    val declarations: JSONArray = JSONArray()
        .put(decl("open_app", "Open an installed app by its name.", "name" to "string"))
        .put(decl("set_alarm", "Set an alarm at a 24-hour time.", "hour" to "integer", "minute" to "integer", "label" to "string?"))
        .put(decl("set_timer", "Start a countdown timer.", "seconds" to "integer", "label" to "string?"))
        .put(decl("find_contact", "Look up phone numbers in the user's contacts by name. Use this before calling or texting a person.", "name" to "string"))
        .put(decl("call_number", "Prepare a phone call to a number. This does NOT dial. Read the name and number back and ask the user to confirm.", "number" to "string", "name" to "string?"))
        .put(decl("compose_sms", "Open a text message to a phone number with the body filled in. The user taps send.", "number" to "string", "body" to "string"))
        .put(decl("web_search", "Search the web in the browser.", "query" to "string"))
        .put(decl("open_url", "Open a web address in the browser.", "url" to "string"))
        .put(decl("navigate", "Start navigation to a place in Google Maps.", "destination" to "string"))
        .put(decl("media_control", "Control music or video playback. action is one of: play, pause, toggle, next, previous, stop.", "action" to "string"))
        .put(decl("set_volume", "Set a volume level from 0 to 100 percent. stream is media (default), ring, alarm, notification or call.", "percent" to "integer", "stream" to "string?"))
        .put(decl("adjust_volume", "Turn volume up or down one step. direction is up or down. stream is media (default), ring, alarm, notification or call.", "direction" to "string", "stream" to "string?"))
        .put(decl("read_messages", "Read the latest message notifications. Optionally filter by sender name and app (for example whatsapp).", "sender" to "string?", "app" to "string?"))
        .put(decl("who_is_calling", "Tell who is calling on an incoming call right now."))
        .put(decl("answer_call", "Answer the incoming phone call."))
        .put(decl("end_call", "End or reject the current phone call."))
        .put(decl("reply_to_caller", "Decline the incoming call and send the caller a text message.", "message" to "string"))
        .put(decl("read_screen", "List what is currently on the phone screen as numbered elements. Call this before tap or type_text and after every action to see the result."))
        .put(decl("tap", "Tap a numbered element from the latest read_screen.", "index" to "integer"))
        .put(decl("tap_xy", "Tap raw screen coordinates when no element matches.", "x" to "integer", "y" to "integer"))
        .put(decl("type_text", "Type text into a text field. Pass index of an editable element, or omit it for the focused field.", "text" to "string", "index" to "integer?"))
        .put(decl("scroll", "Scroll the screen. direction is down (see content below), up, left or right.", "direction" to "string"))
        .put(decl("press_key", "Press a system key: back, home, recents, notifications, quick_settings, enter, lock, screenshot.", "key" to "string"))
        .put(decl("save_note", "Save a note for the user.", "text" to "string"))
        .put(decl("read_notes", "Read the user's saved notes. period is today, week or all.", "period" to "string?"))
        .put(decl("remember_fact", "Remember a fact about the user for future conversations.", "fact" to "string"))
        .put(decl("forget_fact", "Forget remembered facts that contain a keyword.", "keyword" to "string"))
        .put(decl("battery_status", "Get the battery level and whether the phone is charging."))
        .put(decl("get_calendar", "List calendar events for the next N days (default 1).", "days" to "integer?"))
        .put(decl("set_brightness", "Set screen brightness from 0 to 100 percent.", "percent" to "integer"))
        .put(decl("set_do_not_disturb", "Turn Do Not Disturb on or off.", "on" to "boolean"))
        .put(decl("open_settings", "Open a settings page: wifi, bluetooth, hotspot, display, sound, battery, location, airplane, apps, nfc, or internet_panel (quick Wi-Fi and mobile data switches). Then use the screen tools to flip a switch.", "page" to "string"))
        .put(decl("announce_messages_from", "Start or stop speaking out new messages from a person as they arrive.", "sender" to "string", "on" to "boolean"))
        .put(decl("set_reminder", "Set a reminder. Give either in_minutes (minutes from now) or at (24-hour time like 17:30, today or the next day if it has passed).", "text" to "string", "in_minutes" to "integer?", "at" to "string?"))
        .put(decl("list_reminders", "List the user's pending reminders."))
        .put(decl("cancel_reminder", "Cancel reminders whose text contains a keyword.", "keyword" to "string"))
        .put(decl("web_answer", "Look up current information on the web: news, sports scores, facts, prices. Pass the full question.", "question" to "string"))
        .put(decl("get_weather", "Get today's weather for a city, or for the phone's location if city is omitted.", "city" to "string?"))
        .put(decl("morning_briefing", "Gather weather, today's calendar, reminders, battery and recent messages for a good-morning briefing."))
        .put(decl("play_music", "Play a song, artist or playlist by name. app is spotify, youtube music or youtube; omit to pick automatically.", "query" to "string", "app" to "string?"))
        .put(decl("screen_context", "Get the text of the screen the user was looking at when they summoned Buddy. Use for questions about what is on their screen."))
        .put(decl("look_at_screen", "Look at a screenshot of what the user was viewing when they summoned Buddy and answer a question about it (describe, translate, summarise).", "question" to "string"))
        .put(decl("draft_whatsapp", "Prepare a WhatsApp message to a contact. This does NOT send it. Read the draft back to the user and ask them to confirm.", "contact" to "string", "message" to "string"))
        .put(decl("confirm_send_whatsapp", "Send the prepared WhatsApp draft. Only call this after the user has said yes to the draft in a new message."))
        .put(decl("phone_health", "Report storage space, memory and uptime of the phone."))
        .put(decl("app_usage_today", "List the apps the user spent the most time in today."))
        .put(decl("lock_phone", "Lock the phone screen."))
        .put(decl("look_with_camera", "Take a photo with the phone camera and answer a question about it (what is this, read the text, translate). camera is back (default) or front.", "question" to "string", "camera" to "string?"))
        .put(decl("notification_digest", "Summarise the user's recent notifications and unread messages."))
        .put(decl("draft_notification_reply", "Prepare an inline reply to a message notification (for example WhatsApp). This does NOT send. Read it back and ask the user to confirm.", "sender" to "string", "message" to "string"))
        .put(decl("confirm_send_reply", "Send the prepared notification reply. Only call after the user said yes in a new message."))
        .put(decl("where_am_i", "Say where the user is right now, with a map link."))
        .put(decl("share_location", "Open a text message to a contact containing the user's current location. The user taps Send.", "contact" to "string"))
        .put(decl("set_emergency_contact", "Add a contact who will receive SOS messages.", "name" to "string"))
        .put(decl("prepare_sos", "Prepare an SOS text with the user's location for the emergency contacts. Does NOT send. Ask the user to confirm."))
        .put(decl("confirm_sos", "Send the prepared SOS. Only call after the user confirmed in a new message."))
        .put(decl("set_language", "Change the language Buddy listens and replies in. language is auto, english, hindi or telugu.", "language" to "string"))
        .put(decl("confirm_call", "Place the prepared phone call. Only call this after the user said yes in a new message. Set speaker to true if they asked for the speaker.", "speaker" to "boolean?"))
        .put(decl("end_conversation", "End the conversation and close Buddy. Call this when the user says goodbye, bye, see you, that is all, or asks to close or quit. Say a brief farewell first."))
        .put(decl("save_routine", "Save a named routine: several phone actions run by one phrase. steps is a JSON string: an array of {\"tool\":name,\"args\":{...}}. Allowed tools: set_volume, adjust_volume, set_brightness, set_do_not_disturb, set_flashlight, set_alarm, set_timer, media_control, open_app, play_music, open_settings, get_weather, battery_status, set_reminder, save_note, set_language, web_search, navigate.", "name" to "string", "steps" to "string"))
        .put(decl("run_routine", "Run a saved routine by name.", "name" to "string"))
        .put(decl("list_routines", "List the saved routines."))
        .put(decl("delete_routine", "Delete a saved routine.", "name" to "string"))
        .put(decl("list_add", "Add an item to a list such as shopping or to-do.", "list" to "string", "item" to "string"))
        .put(decl("list_show", "Read out a list. Omit list to see which lists exist.", "list" to "string?"))
        .put(decl("list_remove", "Remove an item from a list.", "list" to "string", "item" to "string"))
        .put(decl("list_clear", "Empty a whole list.", "list" to "string"))
        .put(decl("add_expense", "Record money the user spent.", "amount" to "number", "note" to "string", "category" to "string?"))
        .put(decl("expense_summary", "Total of the user's spending. period is today, week or month.", "period" to "string?"))
        .put(decl("dictate_text", "Type text into the text box the user is working in, in whatever app is open. Set send to true to press enter afterwards.", "text" to "string", "send" to "boolean?"))
        .put(decl("set_daily_briefing", "Speak the morning briefing every day at a time (24-hour HH:mm), or pass off to stop.", "time" to "string"))
        .put(decl("set_battery_alerts", "Turn spoken low-battery warnings (at 20, 10 and 5 percent) on or off.", "on" to "boolean"))
        .put(decl("start_interpreter", "Start live interpreter mode: Buddy translates a conversation between two languages out loud, in both directions. Use when the user wants to talk to someone who speaks another language.", "language_a" to "string", "language_b" to "string"))
        .put(decl("stop_interpreter", "Leave interpreter mode and go back to normal conversation."))
        .put(decl("start_recording", "Record a meeting, lecture or conversation so it can be transcribed and summarised later."))
        .put(decl("stop_recording", "Stop the recording and give a transcript summary with action items."))
        .put(decl("recording_status", "Say whether a recording is in progress and for how long."))
        .put(decl("start_away", "Turn on Away mode: Buddy declines calls with an assistant text and answers texts once, and keeps a log. Give hours (default 2) or an until time (24-hour HH:mm). ring_through is a comma-separated list of contact names who may still call.", "hours" to "number?", "until" to "string?", "ring_through" to "string?"))
        .put(decl("end_away", "Turn off Away mode and report what happened while the user was away: missed calls, texts, urgent items."))
        .put(decl("away_summary", "Summarise what came in during Away mode without ending it. Also use for 'what did I miss'."))
        .put(decl("search_memory", "Search the user's remembered messages (WhatsApp, SMS, chats, email notifications from the last 90 days) for something someone said or sent, e.g. 'what did Dad say about the plumber'. query = the topic words; person and days are optional.", "query" to "string", "person" to "string?", "days" to "integer?"))
        .put(decl("driving_mode", "Turn Driving mode on or off: messages are read aloud and callers you miss get a 'driving' text.", "on" to "boolean"))
        .put(decl("what_did_you_do", "List what Buddy has done on the user's behalf recently (apps opened, messages drafted, calls, etc.).", "count" to "integer?"))
        .put(decl("away_status", "Say whether Away mode is on and until when."))
        .put(decl("set_away_message", "Change the text Buddy sends to callers and texters in Away mode.", "text" to "string"))
        .put(decl("set_my_name", "Set the name Buddy uses when it replies for the user in Away mode.", "name" to "string"))
        .put(decl("set_silent_away", "Choose whether a silent phone counts as Away mode. setting is silent (default: ringer on Silent), vibrate (Silent or Vibrate) or off.", "setting" to "string"))
        .put(decl("set_ring_through_always", "Set the people whose calls are never declined in any Away mode, as a comma-separated list of contact names.", "names" to "string"))
        .put(decl("set_away_popup", "Choose whether Buddy opens its screen by itself to show the Away summary when the user is back (silent mode off or I'm back). on or off.", "on" to "boolean"))
        .put(decl("set_away_decline", "Choose how Away mode handles calls. on = decline the call straight away and text the caller. off (default) = let it ring, and text the caller only if it goes unanswered.", "on" to "boolean"))
        .put(decl("set_away_text_delay", "Set how many seconds an unanswered call rings in Away mode before Buddy texts the caller (0 = only after the call ends). Default 10.", "seconds" to "integer"))
        .put(decl("storage_report", "Say how much storage Buddy itself is using and what it is made of."))
        .put(decl("clean_up_buddy", "Free up Buddy's own storage: removes old meeting audio, temporary files and trims long logs."))
        .put(decl("approve_action", "Carry out the action that is waiting for the user's approval (sending a message, posting, paying, texting a caller). Only call this after the user said yes in a new message."))
        .put(decl("start_camera_view", "Let the user show you something through the camera during this live conversation: you will see a new picture about once a second. camera is back (default) or front.", "camera" to "string?"))
        .put(decl("start_screen_share", "Let the user show you their screen during this live conversation: you will see their screen about every second. It needs the user's approval."))
        .put(decl("stop_sharing", "Stop looking through the camera or at the screen."))
        .put(decl("set_flashlight", "Turn the flashlight on or off.", "on" to "boolean"))

    fun run(name: String, args: JSONObject): String = execute(name, args).also { ActivityLog.tool(context, name, args, it) }

    private fun execute(name: String, args: JSONObject): String = try {
        when (name) {
            "open_app" -> openApp(args.getString("name"))
            "set_alarm" -> start(Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, args.getInt("hour"))
                .putExtra(AlarmClock.EXTRA_MINUTES, args.getInt("minute"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Buddy"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Alarm set")
            "set_timer" -> start(Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, args.getInt("seconds"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Buddy"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Timer started")
            "find_contact" -> findContact(args.getString("name"))
            "call_number" -> prepareCall(args.getString("number"), args.optString("name"))
            "confirm_call" -> { speakerNext = args.optBoolean("speaker"); confirmCall() }
            "compose_sms" -> start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(args.getString("number"))))
                .putExtra("sms_body", args.getString("body")), "Message ready to send")
            "web_search" -> start(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(args.getString("query")))), "Search opened")
            "open_url" -> start(Intent(Intent.ACTION_VIEW, Uri.parse(args.getString("url"))), "Opened")
            "navigate" -> start(Intent(Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=" + Uri.encode(args.getString("destination")))), "Navigation started")
            "media_control" -> mediaControl(args.getString("action"))
            "set_volume" -> setVolume(args.getInt("percent"), args.optString("stream", "media"))
            "adjust_volume" -> adjustVolume(args.getString("direction"), args.optString("stream", "media"))
            "read_messages" -> readMessages(args.optString("sender"), args.optString("app"))
            "who_is_calling" -> whoIsCalling()
            "answer_call" -> telecom { it.acceptRingingCall(); "Call answered" }
            "end_call" -> telecom { if (it.endCall()) "Call ended" else "No call to end" }
            "reply_to_caller" -> args.getString("message").let { m -> SendGuard.request("decline the call and text the caller: \"${m.take(80)}\"") { replyToCaller(m) } }
            "approve_action" -> SendGuard.approve()
            "read_screen" -> { Thread.sleep(400); a11y { it.snapshot() } }
            "tap" -> a11y { svc ->
                val i = args.getInt("index"); val label = svc.labelOf(i)
                if (SendGuard.isRisky(label)) SendGuard.request("tap \"${label.take(40)}\" in ${svc.currentPackage()}, which sends or pays") {
                    a11y { it.clickLabeled(label.take(60)).let { ok -> if (ok) "Tapped ${label.take(40)}" else "I couldn't find that button any more" } } }
                else svc.tap(i)
            }.also { Thread.sleep(800) }
            "tap_xy" -> a11y { svc ->
                val x = args.getInt("x"); val y = args.getInt("y"); val label = svc.labelAt(x, y)
                if (SendGuard.isRisky(label)) SendGuard.request("tap \"${label.take(40)}\" in ${svc.currentPackage()}, which sends or pays") { a11y { it.tapXY(x.toFloat(), y.toFloat()) } }
                else svc.tapXY(x.toFloat(), y.toFloat())
            }.also { Thread.sleep(800) }
            "type_text" -> a11y { it.typeText(args.getString("text"), if (args.has("index")) args.getInt("index") else null) }
            "scroll" -> a11y { it.scroll(args.getString("direction")) }.also { Thread.sleep(600) }
            "press_key" -> a11y { svc ->
                val key = args.getString("key")
                if (key.equals("enter", true) && SendGuard.isMessagingApp(svc.currentPackage()))
                    SendGuard.request("press Enter in ${svc.currentPackage()}, which sends the message") { a11y { it.pressKey("enter") } }
                else svc.pressKey(key)
            }.also { Thread.sleep(800) }
            "save_note" -> { store.addNote(args.getString("text")); "Note saved" }
            "read_notes" -> readNotes(args.optString("period", "today"))
            "remember_fact" -> { store.addFact(args.getString("fact")); "Remembered" }
            "forget_fact" -> "Forgot ${store.removeFacts(args.getString("keyword"))} facts"
            "battery_status" -> battery()
            "get_calendar" -> calendar(if (args.has("days")) args.getInt("days") else 1)
            "set_brightness" -> brightness(args.getInt("percent"))
            "set_do_not_disturb" -> dnd(args.getBoolean("on"))
            "open_settings" -> openSettings(args.getString("page"))
            "announce_messages_from" -> announce(args.getString("sender"), args.getBoolean("on"))
            "set_reminder" -> setReminder(args.getString("text"), if (args.has("in_minutes")) args.getInt("in_minutes") else null, args.optString("at"))
            "list_reminders" -> listReminders()
            "cancel_reminder" -> cancelReminders(args.getString("keyword"))
            "web_answer" -> Web.answer(args.getString("question"))
            "get_weather" -> Web.weather(context, args.optString("city"))
            "morning_briefing" -> briefing()
            "play_music" -> playMusic(args.getString("query"), args.optString("app"))
            "screen_context" -> screenContext()
            "look_at_screen" -> lookAtScreen(args.getString("question"))
            "draft_whatsapp" -> draftWhatsapp(args.getString("contact"), args.getString("message"))
            "confirm_send_whatsapp" -> confirmWhatsapp()
            "phone_health" -> phoneHealth()
            "app_usage_today" -> appUsageToday()
            "lock_phone" -> a11y { it.pressKey("lock") }
            "look_with_camera" -> lookWithCamera(args.getString("question"), args.optString("camera") == "front")
            "notification_digest" -> digest()
            "draft_notification_reply" -> draftReply(args.getString("sender"), args.getString("message"))
            "confirm_send_reply" -> confirmReply()
            "where_am_i" -> whereAmI()
            "share_location" -> shareLocation(args.getString("contact"))
            "set_emergency_contact" -> addEmergency(args.getString("name"))
            "prepare_sos" -> prepareSos()
            "confirm_sos" -> confirmSos()
            "set_language" -> {
                val code = when (args.getString("language").lowercase().take(2)) { "en" -> "en"; "hi" -> "hi"; "te" -> "te"; else -> "auto" }
                Lang.set(context, code)
                "Language set to " + Lang.options.first { it.first == code }.second
            }
            "end_conversation" -> { Session.endRequested = true; "Ending after your farewell. Say a brief goodbye now." }
            "save_routine" -> saveRoutine(args.getString("name"), args.getString("steps"))
            "run_routine" -> runRoutine(args.getString("name"))
            "list_routines" -> data.routineNames().joinToString(", ").ifBlank { "No routines saved" }
            "delete_routine" -> if (data.deleteRoutine(args.getString("name"))) "Routine deleted" else "No routine with that name"
            "list_add" -> "Added. ${args.getString("list")} now has ${data.listAdd(args.getString("list"), args.getString("item"))} items"
            "list_show" -> showList(args.optString("list"))
            "list_remove" -> if (data.listRemove(args.getString("list"), args.getString("item"))) "Removed" else "That item isn't on the list"
            "list_clear" -> { data.listClear(args.getString("list")); "List cleared" }
            "add_expense" -> { data.addExpense(args.getDouble("amount"), args.getString("note"), args.optString("category").ifBlank { "other" }); "Recorded ${args.getDouble("amount")} for ${args.getString("note")}" }
            "expense_summary" -> expenseSummary(args.optString("period", "today"))
            "dictate_text" -> a11y { it.dictate(args.getString("text")) }.let { r ->
                if (args.optBoolean("send") && r == "Typed it") {
                    val pkg = a11y { it.currentPackage().orEmpty() }
                    // Typing is harmless; pressing Enter in a chat or mail app sends it, so that needs a yes.
                    if (SendGuard.isMessagingApp(pkg)) "Typed it, but not sent. " + SendGuard.request("press Enter in $pkg to send what was just typed") { a11y { s -> s.pressKey("enter") } }
                    else { a11y { s -> s.pressKey("enter") }; r }
                } else r
            }
            "set_daily_briefing" -> setBriefing(args.getString("time"))
            "set_battery_alerts" -> {
                context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean("battery_alerts", args.getBoolean("on")).apply()
                if (args.getBoolean("on")) "Battery warnings on" else "Battery warnings off"
            }
            "start_away" -> startAway(if (args.has("hours")) args.getDouble("hours") else null, args.optString("until"), args.optString("ring_through"))
            "end_away" -> Away.end(context)
            "away_summary" -> Away.summary(context)
            "search_memory" -> MemoryStore.search(context, args.getString("query"), args.optString("person").ifBlank { null }, if (args.has("days")) args.optInt("days") else null)
            "driving_mode" -> if (args.getBoolean("on")) Driving.start(context) else Driving.stop(context)
            "what_did_you_do" -> ActivityLog.recent(context, args.optInt("count", 8).coerceIn(1, 30)).joinToString("\n").ifBlank { "I haven't done anything yet." }
            "away_status" -> (if (Away.active(context)) "Away mode is on" else "Away mode is off") + ". A silent phone counts as away: " + Away.silentSetting(context)
            "set_away_message" -> { context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("away_text", args.getString("text")).apply(); "Away message updated" }
            "set_my_name" -> { context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("my_name", args.getString("name")).apply(); "I'll use ${args.getString("name")}" }
            "set_silent_away" -> {
                val v = args.getString("setting").lowercase().let { if (it.startsWith("vib")) "vibrate" else if (it == "off" || it.startsWith("no") || it.startsWith("dis")) "off" else "silent" }
                context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("silent_away", v).apply()
                when (v) { "off" -> "A silent phone will no longer count as Away mode"; "vibrate" -> "Away mode will now turn on when your phone is on Silent or Vibrate"; else -> "Away mode will turn on whenever your phone is on Silent" }
            }
            "set_ring_through_always" -> {
                val keys = HashSet<String>(); val missing = ArrayList<String>()
                args.getString("names").split(",", " and ").map { it.trim() }.filter { it.isNotBlank() }.forEach { n ->
                    val (hit, _) = findUniqueContact(n); if (hit != null) keys.add(Away.key(hit.second)) else missing.add(n)
                }
                Away.setDefaultVips(context, keys)
                "${keys.size} people will always ring through" + if (missing.isNotEmpty()) ". I couldn't find ${missing.joinToString(", ")}" else ""
            }
            "set_away_popup" -> {
                context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean("away_popup", args.getBoolean("on")).apply()
                if (args.getBoolean("on")) "Buddy will open by itself to show what you missed" else "Buddy will only send a notification"
            }
            "set_away_decline" -> {
                context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean("away_decline", args.getBoolean("on")).apply()
                if (args.getBoolean("on")) "Away mode will decline calls straight away and text the caller" else "Away mode will let calls ring and text the caller only if it goes unanswered"
            }
            "set_away_text_delay" -> {
                val sec = args.getInt("seconds").coerceIn(0, 30)
                context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putInt("away_text_delay", sec).apply()
                if (sec == 0) "Buddy will text the caller only after the call ends" else "Buddy will text the caller after $sec seconds of ringing"
            }
            "storage_report" -> Housekeeping.report(context)
            "clean_up_buddy" -> Housekeeping.run(context) + ". " + Housekeeping.report(context)
            "start_camera_view" -> if (!Session.liveRunning) "Camera view needs a live conversation. Say Hey buddy first." else Vision.startCamera(context, args.optString("camera") == "front")
            "start_screen_share" -> if (!Session.liveRunning) "Screen sharing needs a live conversation. Say Hey buddy first."
                else SendGuard.request("share the user's screen with the AI for up to 5 minutes, so it can see everything shown on it") { Vision.startScreen(context) }
            "stop_sharing" -> Vision.stop(context)
            "set_flashlight" -> flashlight(args.getBoolean("on"))
            else -> "Unknown action $name"
        }
    } catch (e: Exception) {
        "Failed: ${e.message}"
    }

    private fun start(intent: Intent, ok: String): String {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1200)
        return ok
    }

    private fun prepareCall(number: String, name: String): String {
        val display = name.ifBlank { contactName(number) ?: number }
        pending = PendingAction("call", number, display, Turns.count, System.currentTimeMillis())
        return "Ready to call $display on $number. Nothing has been dialled. Ask the user to confirm."
    }

    @Volatile private var speakerNext = false

    private fun confirmCall(): String {
        val p = takePending("call") ?: return "There is no call prepared."
        if (Turns.count <= p.turn) return "The user has not confirmed yet. Ask them first and wait for their answer."
        if (!Turns.affirmative()) return "The user's last message was not a clear yes. Ask again."
        if (System.currentTimeMillis() - p.time > 180000) { pending = null; return "That call expired. Prepare it again." }
        pending = null
        return call(p.target, speakerNext)
    }

    // Places the call through Telecom, which works even when Buddy is not on screen (a plain ACTION_CALL from the background is
    // silently ignored by Android), then checks that the phone really started dialling so Buddy never claims a call that did not happen.
    private fun call(number: String, speaker: Boolean = false): String {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return start(Intent(Intent.ACTION_DIAL, uri), "No call permission, opened the dialer instead")
        val tm = context.getSystemService(TelecomManager::class.java)
        try { tm.placeCall(uri, Bundle()) } catch (e: SecurityException) { return "The phone refused to place the call: ${e.message}" }
        val phone = context.getSystemService(android.telephony.TelephonyManager::class.java)
        @Suppress("DEPRECATION")
        val started = (0 until 12).any { Thread.sleep(500); phone.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE }
        if (!started) return "The call did not start. Do not tell the user it is calling; tell them it failed and offer to try again or open the dialer."
        if (speaker) {
            val onSpeaker = (0 until 10).any { Thread.sleep(500); JarvisAccessibilityService.instance?.clickLabeled("Speaker") == true }
            return "Calling $number now" + if (onSpeaker) ", speaker is on." else ". I could not switch the speaker on; the user can tap Speaker."
        }
        return "Calling $number now."
    }

    private fun a11y(block: (JarvisAccessibilityService) -> String): String =
        JarvisAccessibilityService.instance?.let(block) ?: "Accessibility access is not enabled for Buddy."

    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun streamOf(name: String) = when (name.lowercase()) {
        "ring", "ringer" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "call" -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC
    }

    private fun mediaControl(action: String): String {
        val key = when (action.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return "Sent $action"
    }

    private fun setVolume(percent: Int, stream: String): String {
        val s = streamOf(stream)
        val level = (audio.getStreamMaxVolume(s) * percent.coerceIn(0, 100) / 100.0).toInt()
        audio.setStreamVolume(s, level, AudioManager.FLAG_SHOW_UI)
        return "Volume set to $percent percent"
    }

    private fun adjustVolume(direction: String, stream: String): String {
        val dir = if (direction.equals("down", true)) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
        audio.adjustStreamVolume(streamOf(stream), dir, AudioManager.FLAG_SHOW_UI)
        return "Volume turned $direction"
    }

    private fun readMessages(sender: String, app: String): String {
        if (context.packageName !in NotificationManagerCompat.getEnabledListenerPackages(context))
            return "Notification access is not enabled for Buddy."
        val found = NotificationStore.query(sender, app)
        if (found.isEmpty()) return "No recent message notifications" + if (sender.isNotBlank()) " from $sender" else ""
        val now = System.currentTimeMillis()
        return found.joinToString("; ") { "${it.sender} said \"${it.text}\" ${(now - it.time) / 60000} minutes ago" }
    }

    private fun telecom(block: (TelecomManager) -> String): String {
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED)
            return "Phone call permission is not granted."
        return block(context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager)
    }

    private fun whoIsCalling(): String {
        if (!CallReceiver.ringing) return "No incoming call right now"
        val number = CallReceiver.number ?: return "A call is ringing but the number is hidden"
        return "${contactName(number) ?: "Unknown number"} ($number)"
    }

    private fun contactName(number: String): String? = Contacts.name(context, number)

    private fun readNotes(period: String): String {
        val now = System.currentTimeMillis()
        val since = when (period.lowercase()) {
            "all" -> 0L
            "week" -> now - 7 * 86400000L
            else -> java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
        }
        val fmt = SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault())
        val found = store.notes(since)
        return if (found.isEmpty()) "No notes for that period" else found.joinToString("; ") { "${fmt.format(Date(it.first))}: ${it.second}" }
    }

    private fun battery(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Battery $level percent, " + if (bm.isCharging) "charging" else "not charging"
    }

    private fun calendar(days: Int): String {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            return "Calendar permission is not granted."
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now); android.content.ContentUris.appendId(it, now + days * 86400000L)
        }.build()
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        val out = ArrayList<String>()
        context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
            null, null, CalendarContract.Instances.BEGIN + " ASC")?.use {
            while (it.moveToNext() && out.size < 15)
                out.add("${it.getString(0)} ${if (it.getInt(2) == 1) "(all day)" else fmt.format(Date(it.getLong(1)))}")
        }
        return if (out.isEmpty()) "No events" else out.joinToString("; ")
    }

    private fun brightness(percent: Int): String {
        if (!Settings.System.canWrite(context)) return "Brightness permission is not granted."
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (percent.coerceIn(0, 100) * 255 / 100))
        return "Brightness set to $percent percent"
    }

    private fun dnd(on: Boolean): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return "Do Not Disturb access is not granted."
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return if (on) "Do Not Disturb on" else "Do Not Disturb off"
    }

    private fun openSettings(page: String): String {
        val action = when (page.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "internet_panel" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            else -> return "Unknown settings page $page"
        }
        return start(Intent(action), "Opened $page settings")
    }

    private fun setReminder(text: String, inMinutes: Int?, at: String): String {
        val time = when {
            inMinutes != null -> System.currentTimeMillis() + inMinutes * 60000L
            at.isNotBlank() -> {
                val m = Regex("(\\d{1,2}):(\\d{2})").find(at) ?: return "I couldn't read that time."
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); set(java.util.Calendar.MINUTE, m.groupValues[2].toInt())
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }
                if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                cal.timeInMillis
            }
            else -> return "I need a time for the reminder."
        }
        val r = reminders.add(time, text)
        ReminderScheduler.schedule(context, r)
        return "Reminder set for " + SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(time))
    }

    private fun listReminders(): String {
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        val all = reminders.all()
        return if (all.isEmpty()) "No reminders" else all.joinToString("; ") { "${it.text} at ${fmt.format(Date(it.time))}" }
    }

    private fun cancelReminders(keyword: String): String {
        val hit = reminders.all().filter { it.text.contains(keyword, ignoreCase = true) }
        hit.forEach { ReminderScheduler.cancel(context, it); reminders.remove(it.id) }
        return "Cancelled ${hit.size} reminders"
    }

    private fun briefing(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val parts = listOf(
            "Time of day: " + if (hour < 12) "morning" else if (hour < 17) "afternoon" else "evening",
            "Weather: " + try { Web.weather(context, "") } catch (e: Exception) { "unavailable" },
            "Calendar: " + calendar(1),
            "Reminders: " + listReminders(),
            battery(),
            "Recent message notifications: " + (NotificationStore.query(null, null, 5).joinToString("; ") { "${it.sender}: ${it.text}" }.ifBlank { "none" }))
        return parts.joinToString("\n")
    }

    // The title and artist of what is playing in this app right now, or null if it is not playing.
    private fun nowPlaying(pkg: String): String? = try {
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(context, JarvisNotificationListener::class.java))
            .firstOrNull { it.packageName == pkg && it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?.let { c -> c.metadata?.let { m -> listOfNotNull(m.getString(MediaMetadata.METADATA_KEY_TITLE), m.getString(MediaMetadata.METADATA_KEY_ARTIST)).joinToString(" by ") }.orEmpty() }
    } catch (e: SecurityException) { null }

    // Waits for the app to report that audio has started, checking twice a second.
    private fun awaitPlaying(pkg: String, ms: Long): String? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { nowPlaying(pkg)?.let { return it }; Thread.sleep(500) }
        return null
    }

    // Opens the app's search, taps the result, and then checks with Android that audio really started.
    private fun playMusic(query: String, app: String): String {
        val pm = context.packageManager
        val apps = mapOf("spotify" to "com.spotify.music", "youtube music" to "com.google.android.apps.youtube.music",
            "youtube" to "com.google.android.youtube")
        val key = apps.keys.firstOrNull { app.contains(it, ignoreCase = true) && app.isNotBlank() }
            ?: apps.keys.first { pm.getLaunchIntentForPackage(apps[it]!!) != null }
        val pkg = apps[key]!!
        if (pm.getLaunchIntentForPackage(pkg) == null) return "$key is not installed"
        val intent = if (key == "youtube") Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))).setPackage(pkg)
        else Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*").putExtra(SearchManager.QUERY, query)
        start(intent, "")
        nowPlaying(pkg)?.let { return "Playing $it on $key" } // some apps do start by themselves
        val svc = JarvisAccessibilityService.instance
            ?: return "I opened $key with $query, but accessibility is off, so I can't tap the song. Tap it to play."
        var tapped: String? = null
        for (i in 0 until 7) { tapped = svc.tapFirstResult(pkg, query); if (tapped != null) break; Thread.sleep(1000) }
        if (tapped == null) return "I opened $key and searched for $query, but I couldn't find a result to tap."
        awaitPlaying(pkg, 8000)?.let { return "Playing ${it.ifBlank { tapped.take(60) }} on $key" }
        // The result may have opened a page with its own Play button.
        if (svc.clickLabeled("Play") || svc.clickLabeled("Shuffle play") || svc.clickLabeled("Shuffle")) {
            awaitPlaying(pkg, 6000)?.let { return "Playing ${it.ifBlank { tapped.take(60) }} on $key" }
        }
        return "I opened $key and tapped ${tapped.take(60)}, but I can't confirm it started playing. It may need a tap on play."
    }

    private fun normalizeNumber(raw: String): String = PhoneNumbers.digitsWithCountryCode(raw, Locale.getDefault().country.ifBlank { "IN" })

    private fun draftWhatsapp(name: String, message: String): String {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return "Contacts permission is not granted."
        val found = contactMatches(name, 6) { normalizeNumber(it) }
        if (found.isEmpty()) return "No contact matching $name"
        if (found.values.distinct().size > 1) return "Several contacts match: " + found.values.distinct().joinToString(", ") + ". Ask which one."
        val (number, display) = found.entries.first()
        draft = Draft(number, display, message, Turns.count, System.currentTimeMillis())
        return "Draft to $display on WhatsApp: \"$message\". Nothing has been sent. Read it back and ask the user to confirm."
    }

    private fun confirmWhatsapp(): String {
        val d = draft ?: return "There is no draft to send."
        if (Turns.count <= d.turn) return "The user has not confirmed yet. Ask them first and wait for their answer."
        if (!Turns.affirmative()) return "The user's last message was not a clear yes. Ask again."
        if (System.currentTimeMillis() - d.time > 180000) { draft = null; return "That draft expired. Prepare it again." }
        draft = null
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${d.number}?text=" + Uri.encode(d.message))).setPackage("com.whatsapp"), "")
        val svc = JarvisAccessibilityService.instance ?: return "Opened the chat with your message typed in. Accessibility is off, so tap Send yourself."
        return sendWhatsAppAndVerify(svc, d.name, d.message).forModel(d.name, "WhatsApp")
    }

    // Open chat -> wait for the typed message and the Send button -> press Send by its id -> wait for the message to appear -> judge from the screen.
    // Every wait ends as soon as its condition is true (no fixed sleeps), and the result is what the screen shows, never an assumption.
    private fun sendWhatsAppAndVerify(svc: JarvisAccessibilityService, who: String, text: String): SendOutcome {
        val probe = text.trim().take(40)
        val ready = Waiter.until(8000) { svc.currentPackage() == "com.whatsapp" && svc.chatScreen().let { it.sendVisible && it.typed.contains(probe) } }
        if (!ready) return SendOutcome.CouldNotPress
        val before = svc.chatScreen()
        val pressed = svc.pressById("com.whatsapp:id/send") || SEND_LABELS.any { svc.clickLabeled(it) }
        if (!pressed) return SendOutcome.CouldNotPress
        // Wait until the message has left the typing box and shows a tick (or give up and report what is visible).
        Waiter.until(7000) { svc.chatScreen().let { a -> SendVerifier.judge(before, a, text).let { it is SendOutcome.Delivered } } }
        return SendVerifier.judge(before, svc.chatScreen(), text)
    }

    private val SEND_LABELS = listOf("Send", "पाठवा", "भेजें", "పంపండి", "அனுப்பு", "Enviar", "Envoyer")

    private fun phoneHealth(): String {
        val fs = StatFs(Environment.getDataDirectory().path)
        val gb = 1024.0 * 1024 * 1024
        val mem = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val up = SystemClock.elapsedRealtime() / 3600000
        return "Storage %.1f GB free of %.0f GB. Memory %.1f GB free of %.1f GB. Up for %d hours. ".format(
            fs.availableBytes / gb, fs.totalBytes / gb, mem.availMem / gb, mem.totalMem / gb, up) + battery()
    }

    private fun appUsageToday(): String {
        val ops = context.getSystemService(AppOpsManager::class.java)
        if (ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) != AppOpsManager.MODE_ALLOWED)
            return "Usage access is not granted."
        val start = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
        val pm = context.packageManager
        val top = context.getSystemService(UsageStatsManager::class.java)
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, System.currentTimeMillis())
            .filter { it.totalTimeInForeground > 60000 && it.packageName != context.packageName }
            .sortedByDescending { it.totalTimeInForeground }.take(5)
        if (top.isEmpty()) return "No usage recorded yet today"
        return top.joinToString("; ") {
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(it.packageName, 0)).toString() } catch (e: Exception) { it.packageName }
            "$label ${it.totalTimeInForeground / 60000} minutes"
        }
    }

    private fun lookWithCamera(question: String, front: Boolean): String {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return "Camera permission is not granted."
        val photo = CameraCapture.capture(context, front) ?: return "I couldn't take a photo. The camera may be busy."
        return Web.vision(question, photo)
    }

    private fun digest(): String {
        if (context.packageName !in NotificationManagerCompat.getEnabledListenerPackages(context)) return "Notification access is not enabled for Buddy."
        val recent = NotificationStore.query(null, null, 25)
        if (recent.isEmpty()) return "No recent notifications"
        val pm = context.packageManager
        return recent.groupBy { it.pkg }.entries.joinToString("\n") { (pkg, list) ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            "$label: " + list.joinToString("; ") { "${it.sender}: ${it.text.take(100)}" }
        }
    }

    private fun draftReply(sender: String, message: String): String {
        val target = NotificationStore.replyFor(sender) ?: return "I can't reply to $sender, there is no recent message notification from them that allows replies."
        pending = PendingAction("reply", target.sender, message, Turns.count, System.currentTimeMillis())
        return "Draft reply to ${target.sender}: \"$message\". Nothing has been sent. Read it back and ask the user to confirm."
    }

    private fun takePending(kind: String): PendingAction? {
        val p = pending ?: return null
        return if (p.kind == kind) p else null
    }

    private fun confirmReply(): String {
        val p = takePending("reply") ?: return "There is no reply to send."
        if (Turns.count <= p.turn) return "The user has not confirmed yet. Ask them first and wait for their answer."
        if (!Turns.affirmative()) return "The user's last message was not a clear yes. Ask again."
        if (System.currentTimeMillis() - p.time > 180000) { pending = null; return "That draft expired. Prepare it again." }
        val target = NotificationStore.replyFor(p.target) ?: run { pending = null; return "That notification is gone, so I can't reply." }
        pending = null
        val intent = Intent()
        RemoteInput.addResultsToIntent(arrayOf(target.input), intent, Bundle().apply { putCharSequence(target.input.resultKey, p.message) })
        return try { target.intent.send(context, 0, intent); "Replied to ${target.sender}" } catch (e: Exception) { "Failed: ${e.message}" }
    }

    private fun currentLocation(): Location? {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(LocationManager::class.java)
        val provider = if (fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        var fresh: Location? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            lm.getCurrentLocation(provider, null, context.mainExecutor) { fresh = it; latch.countDown() }
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) { /* fall back to the last known fix */ }
        return fresh ?: lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }

    private fun locationText(): String? {
        val loc = currentLocation() ?: return null
        @Suppress("DEPRECATION")
        val address = try { Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.getAddressLine(0) } catch (e: Exception) { null }
        return (address ?: "%.5f, %.5f".format(loc.latitude, loc.longitude)) + " https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
    }

    private fun whereAmI(): String = locationText()?.let { "You are near $it" } ?: "I can't get your location. Check that location permission and location services are on."

    private fun findUniqueContact(name: String): Pair<Pair<String, String>?, String?> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null to "Contacts permission is not granted."
        val found = LinkedHashMap<String, String>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$name%"), null)?.use {
            while (it.moveToNext() && found.size < 6) found.putIfAbsent(it.getString(0), it.getString(1).filter { c -> c.isDigit() || c == '+' })
        }
        if (found.isEmpty()) return null to "No contact matching $name"
        if (found.size > 1) return null to "Several contacts match: ${found.keys.joinToString(", ")}. Ask which one."
        val e = found.entries.first()
        return (e.key to e.value) to null
    }

    private fun shareLocation(contact: String): String {
        val (hit, err) = findUniqueContact(contact)
        hit ?: return err!!
        val where = locationText() ?: return "I can't get your location."
        return start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(hit.second))).putExtra("sms_body", "My location: $where"),
            "Message to ${hit.first} is ready with your location. Tap Send.")
    }

    private fun emergencyContacts(): Set<String> =
        context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getStringSet("emergency", emptySet()) ?: emptySet()

    private fun addEmergency(name: String): String {
        val (hit, err) = findUniqueContact(name)
        hit ?: return err!!
        context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
            .putStringSet("emergency", emergencyContacts() + "${hit.first}|${hit.second}").apply()
        return "${hit.first} will receive SOS messages"
    }

    private fun prepareSos(): String {
        val list = emergencyContacts()
        if (list.isEmpty()) return "No emergency contacts are set. Ask the user who to add."
        pending = PendingAction("sos", "", "", Turns.count, System.currentTimeMillis())
        return "Ready to text SOS with your location to ${list.joinToString(", ") { it.substringBefore('|') }}. Nothing has been sent. Ask the user to confirm."
    }

    private fun confirmSos(): String {
        val p = takePending("sos") ?: return "There is no SOS prepared."
        if (Turns.count <= p.turn) return "The user has not confirmed yet. Ask them first and wait for their answer."
        if (!Turns.affirmative()) return "The user's last message was not a clear yes. Ask again."
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "SMS permission is not granted."
        pending = null
        val body = "SOS! I need help. " + (locationText()?.let { "My location: $it" } ?: "I couldn't get my location.")
        val sms = context.getSystemService(Sms::class.java)
        val sent = emergencyContacts().map { it.substringBefore('|') to it.substringAfter('|') }
        sent.forEach { sms.sendMultipartTextMessage(it.second, null, sms.divideMessage(body), null, null) }
        return "SOS sent to " + sent.joinToString(", ") { it.first }
    }

    private val routineSafe = setOf("set_volume", "adjust_volume", "set_brightness", "set_do_not_disturb", "set_flashlight", "set_alarm", "set_timer",
        "media_control", "open_app", "play_music", "open_settings", "get_weather", "battery_status", "set_reminder", "save_note", "set_language", "web_search", "navigate")

    private fun saveRoutine(name: String, stepsJson: String): String {
        val steps = try { JSONArray(stepsJson) } catch (e: Exception) { return "The steps weren't valid. Send a JSON array of tool and args." }
        for (i in 0 until steps.length()) {
            val tool = steps.getJSONObject(i).optString("tool")
            if (tool !in routineSafe) return "A routine can't include $tool. Calls, messages and sending things must be done one at a time."
        }
        data.saveRoutine(name, steps)
        return "Routine ${name.lowercase()} saved with ${steps.length()} steps"
    }

    private fun runRoutine(name: String): String {
        val (key, steps) = data.routine(name) ?: return "I don't have a routine called $name. Saved: " + data.routineNames().joinToString(", ").ifBlank { "none" }
        val results = ArrayList<String>()
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val tool = step.optString("tool")
            if (tool !in routineSafe) { results.add("skipped $tool"); continue }
            results.add(run(tool, step.optJSONObject("args") ?: JSONObject()))
            Thread.sleep(400)
        }
        return "Ran $key: " + results.joinToString("; ")
    }

    private fun showList(list: String): String {
        if (list.isBlank()) return "Lists: " + data.listNames().joinToString(", ").ifBlank { "none yet" }
        val items = data.listItems(list)
        return if (items.isEmpty()) "$list is empty" else "$list: " + items.joinToString(", ")
    }

    private fun expenseSummary(period: String): String {
        val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }
        when (period.lowercase()) { "week" -> cal.add(java.util.Calendar.DAY_OF_YEAR, -6); "month" -> cal.set(java.util.Calendar.DAY_OF_MONTH, 1) }
        val list = data.expenses(cal.timeInMillis)
        if (list.isEmpty()) return "No expenses recorded for $period"
        val byCat = list.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }
        return "Spent %.0f in ${list.size} items this $period. ".format(list.sumOf { it.amount }) + byCat.entries.joinToString(", ") { "${it.key} %.0f".format(it.value) }
    }

    private fun setBriefing(time: String): String {
        if (time.equals("off", true)) { BriefingScheduler.set(context, null); return "Daily briefing off" }
        val m = Regex("(\\d{1,2}):(\\d{2})").find(time) ?: return "I couldn't read that time."
        BriefingScheduler.set(context, "%d:%02d".format(m.groupValues[1].toInt(), m.groupValues[2].toInt()))
        return "I'll give you a briefing every day at ${m.groupValues[1]}:${m.groupValues[2]}"
    }

    // The briefing as natural sentences, spoken without needing an AI model.
    fun spokenBriefing(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val parts = ArrayList<String>()
        parts.add(if (hour < 12) "Good morning." else if (hour < 17) "Good afternoon." else "Good evening.")
        try { Web.weather(context, "").let { if (!it.contains("permission") && !it.contains("don't have")) parts.add("Weather. $it.") } } catch (e: Exception) { }
        val cal = calendar(1)
        parts.add(if (cal == "No events") "Nothing on your calendar today." else "On your calendar: $cal.")
        val rem = listReminders()
        if (rem != "No reminders") parts.add("Reminders: $rem.")
        parts.add(battery() + ".")
        return parts.joinToString(" ")
    }

    private fun startRecording(): String {
        if (Recorder.active) return "Already recording"
        if (Session.liveRunning) {
            // The live conversation owns the microphone, so let it finish this reply and close first.
            Session.stopLiveAfterTurn = true
            kotlin.concurrent.thread {
                var waited = 0
                while (Session.liveRunning && waited < 100) { Thread.sleep(100); waited++ }
                Recorder.start(context)
            }
            return "Recording will start as soon as I finish speaking. To stop it, tap Stop and summarize in the notification, or open Buddy and tap the button."
        }
        return Recorder.start(context)
    }

    private fun startAway(hours: Double?, until: String, ringThrough: String): String {
        val missing = listOf(Manifest.permission.SEND_SMS to "send texts", Manifest.permission.RECEIVE_SMS to "see incoming texts", Manifest.permission.ANSWER_PHONE_CALLS to "decline calls")
            .filter { context.checkSelfPermission(it.first) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) return "I need permission to " + missing.joinToString(" and ") { it.second } + " first."
        val at = when {
            hours != null -> System.currentTimeMillis() + (hours * 3600000).toLong()
            until.isNotBlank() -> {
                val m = Regex("(\\d{1,2}):(\\d{2})").find(until) ?: return "I couldn't read that time."
                val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); set(java.util.Calendar.MINUTE, m.groupValues[2].toInt()); set(java.util.Calendar.SECOND, 0) }
                if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                cal.timeInMillis
            }
            else -> System.currentTimeMillis() + 2 * 3600000L
        }
        val vipKeys = ArrayList<String>()
        val notFound = ArrayList<String>()
        ringThrough.split(",", " and ").map { it.trim() }.filter { it.isNotBlank() }.forEach { name ->
            val (hit, _) = findUniqueContact(name)
            if (hit != null) vipKeys.add(Away.key(hit.second)) else notFound.add(name)
        }
        Away.start(context, at, vipKeys)
        return "Away mode is on until " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(at)) +
            (if (vipKeys.isNotEmpty()) ". ${vipKeys.size} people can still ring through" else "") +
            (if (notFound.isNotEmpty()) ". I couldn't find ${notFound.joinToString(", ")} in your contacts" else "")
    }

    private fun screenContext(): String {
        val t = ScreenContext.text?.takeIf { System.currentTimeMillis() - ScreenContext.time < 120000 }
        if (t.isNullOrBlank()) return "No screen text was captured. Ask the user to open Buddy by holding the power button while on that screen."
        return "Captured ${(System.currentTimeMillis() - ScreenContext.time) / 1000}s ago:\n$t"
    }

    private fun lookAtScreen(question: String): String {
        val shot = ScreenContext.screenshot?.takeIf { System.currentTimeMillis() - ScreenContext.time < 120000 } ?: return "No screenshot was captured. Ask the user to open Buddy by holding the power button while on that screen."
        return Web.vision(question, shot)
    }

    private fun announce(sender: String, on: Boolean): String {
        val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val set = (prefs.getStringSet("announce", emptySet()) ?: emptySet()).toMutableSet()
        if (on) set.add(sender) else set.removeAll { it.equals(sender, true) }
        prefs.edit().putStringSet("announce", set).apply()
        return if (on) "I'll read out new messages from $sender" else "Stopped announcing $sender"
    }

    private fun replyToCaller(message: String): String {
        val number = CallReceiver.number ?: return "I don't know who is calling, so I can't reply."
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return "SMS permission is not granted."
        val ended = telecom { if (it.endCall()) "ok" else "none" }
        val sms = context.getSystemService(SmsManager::class.java)
        sms.sendMultipartTextMessage(number, null, sms.divideMessage(message), null, null)
        return "Declined the call (${ended}) and texted ${contactName(number) ?: number}"
    }

    private fun findContact(name: String): String {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return "Contacts permission not granted. Ask the user to allow it in Buddy settings."
        val found = contactMatches(name, 5) { it.filter { c -> c.isDigit() || c == '+' } }
        if (found.isEmpty()) return "No contact matching $name"
        return found.entries.joinToString("; ") { "${it.value}: ${it.key}" }
    }

    // number -> display name for contacts matching a spoken name. A plain substring match first; if that finds nobody, a sound-alike match,
    // because speech recognition often spells names differently ("Seva" for Siva).
    private fun contactMatches(name: String, limit: Int, normalize: (String) -> String): LinkedHashMap<String, String> {
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val found = LinkedHashMap<String, String>()
        context.contentResolver.query(phone, cols, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$name%"), null)?.use {
            while (it.moveToNext() && found.size < limit) found.putIfAbsent(normalize(it.getString(1)), it.getString(0))
        }
        if (found.isNotEmpty()) return found
        val all = ArrayList<String>()
        context.contentResolver.query(phone, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { while (it.moveToNext()) all.add(it.getString(0)) }
        for (match in NameMatcher.best(name, all.distinct()).take(3)) {
            context.contentResolver.query(phone, cols, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " = ?", arrayOf(match), null)?.use {
                while (it.moveToNext() && found.size < limit) found.putIfAbsent(normalize(it.getString(1)), it.getString(0))
            }
        }
        return found
    }

    private fun openApp(name: String): String {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0)
            .filter { it.loadLabel(pm).toString().contains(name, ignoreCase = true) }
            .minByOrNull { it.loadLabel(pm).length } ?: return "No app named $name"
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return "Can't launch $name"
        return start(intent, "Opened ${match.loadLabel(pm)}")
    }

    private fun flashlight(on: Boolean): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it)
            .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return "No flashlight"
        cm.setTorchMode(id, on)
        torchOn = on
        return if (on) "Flashlight on" else "Flashlight off"
    }

    private fun decl(name: String, description: String, vararg params: Pair<String, String>): JSONObject {
        val props = JSONObject()
        val required = JSONArray()
        for ((p, t) in params) {
            props.put(p, JSONObject().put("type", t.trimEnd('?')))
            if (!t.endsWith("?")) required.put(p)
        }
        val d = JSONObject().put("name", name).put("description", description)
        if (params.isNotEmpty()) d.put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", required))
        return d
    }
}
