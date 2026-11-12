package com.sreeram.jarvis

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class JarvisNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        activeNotifications?.forEach { ingest(it, announce = false) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = ingest(sbn, announce = true)

    private fun ingest(sbn: StatusBarNotification, announce: Boolean) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString() ?: return
        n.actions?.forEach { a ->
            val ri = a.remoteInputs?.firstOrNull()
            if (ri != null && a.actionIntent != null && a.title?.contains("reply", ignoreCase = true) == true)
                NotificationStore.rememberReply(NotificationStore.ReplyTarget(title, a.actionIntent, ri))
        }
        val isNew = NotificationStore.add(NotificationStore.Msg(sbn.packageName, title, text, sbn.postTime))
        if (isNew) {
            MemoryStore.add(this, sbn.packageName, title, text, sbn.postTime)
            if (announce && Driving.active(this) && isChat(sbn.packageName)) Driving.onMessage(this, title, text)
        }
        if (announce && isNew && getSystemService(android.os.PowerManager::class.java).isInteractive) {
            val watched = getSharedPreferences("jarvis", MODE_PRIVATE).getStringSet("announce", emptySet()) ?: emptySet()
            if (watched.any { title.contains(it, ignoreCase = true) }) Speaker.speak(this, "Message from $title: $text")
        }
    }

    private fun isChat(pkg: String) = listOf("whatsapp", "messaging", "mms", "telegram", "signal", "sms").any { pkg.contains(it) }
}
