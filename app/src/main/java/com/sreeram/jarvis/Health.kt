package com.sreeram.jarvis

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

// Everything Buddy needs in order to work, checked in one place, with a way to fix each item.
object Health {
    class Check(val label: String, val ok: Boolean, val why: String, val fix: Intent?, val permission: String? = null)

    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun accessibilityComponent(c: Context) = ComponentName(c, JarvisAccessibilityService::class.java).flattenToString()

    fun accessibilityOn(c: Context): Boolean =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.contains(accessibilityComponent(c)) == true &&
            JarvisAccessibilityService.instance != null

    // Android switches screen control off whenever Buddy is reinstalled or updated. If Buddy was given the one-time permission to
    // change that setting (see README: pm grant ... WRITE_SECURE_SETTINGS), it turns itself back on.
    fun healAccessibility(c: Context): Boolean {
        if (accessibilityOn(c)) return true
        if (!granted(c, "android.permission.WRITE_SECURE_SETTINGS")) return false
        return try {
            val cur = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = accessibilityComponent(c)
            if (!cur.split(':').contains(me)) Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isBlank()) me else "$cur:$me")
            Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            ActivityLog.add(c, "Turned screen control back on")
            true
        } catch (e: Exception) { false }
    }

    fun checks(c: Context): List<Check> {
        val pm = c.getSystemService(PowerManager::class.java)
        val alarms = c.getSystemService(AlarmManager::class.java)
        val app = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:${c.packageName}"))
        return listOf(
            Check("Microphone", granted(c, Manifest.permission.RECORD_AUDIO), "Needed to hear you", app, Manifest.permission.RECORD_AUDIO),
            Check("Notifications", granted(c, Manifest.permission.POST_NOTIFICATIONS), "Needed for alerts and the listening notice", app, Manifest.permission.POST_NOTIFICATIONS),
            Check("Screen control", accessibilityOn(c), "Lets Buddy tap and type in other apps", Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            Check("Read notifications", NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName), "Needed to read and reply to messages, and for Memory", Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
            Check("Default assistant", Settings.Secure.getString(c.contentResolver, "assistant")?.contains(c.packageName) == true, "Lets the power button and \"Hey Buddy\" open Buddy", Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)),
            Check("Runs in background", pm.isIgnoringBatteryOptimizations(c.packageName), "Stops Android from putting Buddy to sleep", Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
            Check("Contacts", granted(c, Manifest.permission.READ_CONTACTS), "So \"call Amma\" works", app, Manifest.permission.READ_CONTACTS),
            Check("Phone and texts", granted(c, Manifest.permission.READ_PHONE_STATE) && granted(c, Manifest.permission.SEND_SMS), "Needed for Away mode and calls", app, Manifest.permission.SEND_SMS),
            Check("Alarms and reminders", alarms.canScheduleExactAlarms(), "So reminders go off on time", Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(android.net.Uri.parse("package:${c.packageName}"))),
            Check("AI connection", BuildConfig.API_KEY.isNotBlank(), "Needs the Gemini key in local.properties", null)
        )
    }
}
