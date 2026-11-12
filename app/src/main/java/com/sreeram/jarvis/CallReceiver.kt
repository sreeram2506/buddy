package com.sreeram.jarvis

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.voice.VoiceInteractionSession
import android.telephony.TelephonyManager
import kotlin.concurrent.thread

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                ringing = true
                // The first "ringing" signal often has no number; the number arrives in a later one. Only a number that
                // came with THIS ring is used, so a new caller is never mistaken for the previous one.
                intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.let {
                    if (it != number) { number = it; awayHandled = false; autoHandled = false; drivingHandled = false; announced = false } // a different caller: treat as a new ring
                }
                val n = number
                android.util.Log.d("Jarvis", "phone state RINGING, number ${n?.let { Away.key(it) } ?: "not known yet"}")
                if (n != null && !drivingHandled && Driving.active(context)) { drivingHandled = true; Driving.onRinging(context, n) }
                if (n != null && !autoHandled && CallAgent.active(context)) {
                    autoHandled = true
                    CallAgent.onRinging(context, n)
                    return
                }
                if (n != null && !awayHandled && !CallAgent.active(context) && Away.active(context)) {
                    awayHandled = true
                    val pending = goAsync()
                    thread { try { Away.onRinging(context, n) } finally { pending.finish() } }
                    return
                }
                if (n != null && !announced && !Away.active(context) && !CallAgent.active(context)) {
                    announced = true
                    Speaker.speak(context, "${Contacts.name(context, n) ?: "Unknown number"} is calling. Say Buddy, then answer or decline.")
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                ringing = false
                announced = false
                Away.onOffHook(context) // answered (or you made a call): no text needed
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                ringing = false
                announced = false
                awayHandled = false
                autoHandled = false
                drivingHandled = false
                number = null // forget this caller so the next call cannot inherit their number
                CallAgent.onCallEnded(context)
                val pending = goAsync()
                thread { try { Away.onIdle(context) } finally { pending.finish() } }
            }
        }
    }

    companion object {
        @Volatile var ringing = false
        @Volatile var number: String? = null
        @Volatile private var announced = false
        @Volatile private var awayHandled = false
        @Volatile private var autoHandled = false
        @Volatile private var drivingHandled = false
    }
}
