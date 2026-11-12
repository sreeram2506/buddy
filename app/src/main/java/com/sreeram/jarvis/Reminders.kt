package com.sreeram.jarvis

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object ReminderScheduler {
    private fun pending(context: Context, id: Int, text: String) = PendingIntent.getBroadcast(
        context, id, Intent(context, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(context: Context, r: ReminderStore.Reminder) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, r.id, r.text)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.time, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.time, pi)
    }

    fun cancel(context: Context, r: ReminderStore.Reminder) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, r.id, r.text))
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        ReminderStore(context).remove(intent.getIntExtra("id", -1))
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("reminders", "Reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(("r" + text + System.currentTimeMillis()).hashCode(), NotificationCompat.Builder(context, "reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Reminder").setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build())
        Speaker.speak(context, "Reminder: $text")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Health.healAccessibility(context) // after a restart or an update Android switches screen control off; turn it back on
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("wake", false)) WakeWordService.start(context)
        BriefingScheduler.schedule(context)
        Housekeeping.run(context)
        Away.rescheduleAfterBoot(context)
        val now = System.currentTimeMillis()
        ReminderStore(context).all().forEach {
            // A reminder that came due while the phone was off fires shortly after boot.
            ReminderScheduler.schedule(context, if (it.time < now) it.copy(time = now + 15000) else it)
        }
    }
}
