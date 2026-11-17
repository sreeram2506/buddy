package com.sreeram.jarvis

import android.app.NotificationChannel
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

// Tap-to-confirm. Every action that needs the user's yes (send, call, SOS, anything that pays) is also offered here as a notification with
// Approve and Cancel buttons, so approval does not depend only on a speech transcript that can be wrong or come from someone else in the room.
// A tap is the approval: the voice-only checks (new turn, "yes" in the transcript) are skipped, the expiry and the "is it still the same
// action" checks are not. Only the newest offer is shown; the older one can still be approved by voice as before.
object Approvals {
    class Offer(val id: Long, val title: String, val time: Long, val approve: () -> String, val cancel: () -> Unit)

    const val TTL_MS = 180_000L
    const val NOT_WAITING = "That request is no longer waiting."
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    // Called whenever the current offer is settled (approved, cancelled or cleared), so a shown notification can be removed.
    @Volatile var onSettled: () -> Unit = {}

    private var current: Offer? = null
    private var nextId = 1L
    private var tapped: Pair<String, Long>? = null

    @Synchronized fun offer(title: String, approve: () -> String, cancel: () -> Unit): Offer {
        val o = Offer(nextId++, title, clock(), approve, cancel)
        current = o
        tapped = null
        return o
    }

    @Synchronized fun current(): Offer? = current?.takeIf { clock() - it.time <= TTL_MS }

    // Runs the action only if this is still the newest offer and has not expired. The action runs outside the lock because it can take seconds.
    fun approve(id: Long): String {
        val o = synchronized(this) {
            val c = current
            if (c == null || c.id != id) return NOT_WAITING
            current = null
            if (clock() - c.time > TTL_MS) { onSettled(); return "That request expired. Ask Buddy again." }
            c
        }
        onSettled()
        val result = o.approve()
        synchronized(this) { tapped = result to clock() }
        return result
    }

    fun cancel(id: Long): Boolean {
        val o = synchronized(this) {
            val c = current
            if (c == null || c.id != id) return false
            current = null
            c
        }
        onSettled()
        o.cancel()
        return true
    }

    // Called when the action was settled some other way (the user said yes out loud), so the notification does not linger.
    @Synchronized fun clear() { if (current != null) { current = null; onSettled() } }

    // If the user already approved by tapping, a later spoken "yes" must say so instead of reporting that nothing is waiting.
    @Synchronized fun tappedResult(): String? = tapped?.takeIf { clock() - it.second <= TTL_MS }?.first?.let { "The user already approved this by tapping the notification. Result: $it" }
}

// The Android side: shows the notification and handles its buttons.
object ApprovalUi {
    private const val CHANNEL = "approvals"
    private const val NOTIFICATION_ID = 21
    const val ACTION_APPROVE = "com.sreeram.jarvis.APPROVE"
    const val ACTION_CANCEL = "com.sreeram.jarvis.CANCEL"
    const val EXTRA_ID = "approval_id"

    @Volatile var appContext: Context? = null

    fun offer(title: String, approve: () -> String, cancel: () -> Unit) {
        val o = Approvals.offer(title, approve, cancel)
        val c = appContext ?: return
        Approvals.onSettled = { try { dismiss(c) } catch (e: Exception) { } }
        try { show(c, o) } catch (e: Exception) { ActivityLog.add(c, "Could not show the approval notification: ${e.message}", failed = true) }
    }

    fun dismiss(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

    private fun button(c: Context, action: String, id: Long, code: Int) = PendingIntent.getBroadcast(
        c, code, Intent(c, ApprovalReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun show(c: Context, o: Approvals.Offer) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Approvals", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Buddy needs your OK").setContentText(o.title).setStyle(NotificationCompat.BigTextStyle().bigText(o.title))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setTimeoutAfter(Approvals.TTL_MS)
            .addAction(0, "Yes, go ahead", button(c, ACTION_APPROVE, o.id, 21))
            .addAction(0, "No, cancel", button(c, ACTION_CANCEL, o.id, 22)).build())
    }

    internal fun showResult(c: Context, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Approvals", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Buddy").setContentText(text.take(200)).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setTimeoutAfter(15_000).build())
    }
}

class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ApprovalUi.EXTRA_ID, -1)
        val app = context.applicationContext
        val pending = goAsync()
        // The action can drive the screen and wait, so it must not run on the main thread.
        Thread {
            try {
                if (intent.action == ApprovalUi.ACTION_APPROVE) {
                    // Some phones send one tap twice. The second finds nothing waiting; it must not be logged or shown as an approval.
                    if (Approvals.current()?.id != id) return@Thread
                    // A tap on the notification shade leaves the shade open over the app the action needs on screen.
                    if (Build.VERSION.SDK_INT >= 31 && JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) == true) Thread.sleep(500)
                    val r = Approvals.approve(id)
                    if (r == Approvals.NOT_WAITING) return@Thread
                    ActivityLog.add(app, "Approved by tap: $r")
                    ApprovalUi.showResult(app, r)
                } else {
                    val cancelled = Approvals.cancel(id)
                    if (cancelled) ActivityLog.add(app, "Cancelled by tap")
                    ApprovalUi.dismiss(app)
                }
            } finally { pending.finish() }
        }.start()
    }
}
