package com.sreeram.jarvis

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Calendar
import kotlin.concurrent.thread

object BriefingScheduler {
    private fun pending(context: Context) = PendingIntent.getBroadcast(context, 7001,
        Intent(context, BriefingReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    // time is "HH:mm", or null to switch the daily briefing off.
    fun set(context: Context, time: String?) {
        val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val am = context.getSystemService(AlarmManager::class.java)
        if (time == null) { am.cancel(pending(context)); prefs.edit().remove("briefing").apply(); return }
        prefs.edit().putString("briefing", time).apply()
        schedule(context)
    }

    // Schedules the next occurrence of the saved time. Called again after each briefing and after a reboot.
    fun schedule(context: Context) {
        val time = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("briefing", null) ?: return
        val (h, m) = time.split(":").map { it.toInt() }
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (cal.timeInMillis <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_YEAR, 1)
        val am = context.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending(context))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending(context))
    }
}

class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BriefingScheduler.schedule(context) // line up tomorrow's
        val pending = goAsync()
        thread {
            try {
                val text = PhoneActions(context.applicationContext).spokenBriefing()
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel("briefing", "Daily briefing", NotificationManager.IMPORTANCE_HIGH))
                nm.notify(7002, NotificationCompat.Builder(context, "briefing").setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("Your briefing").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build())
                Speaker.speak(context, text)
            } finally { pending.finish() }
        }
    }
}
