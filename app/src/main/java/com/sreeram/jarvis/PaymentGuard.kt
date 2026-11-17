package com.sreeram.jarvis

import android.content.Context

// UPI and banking apps (Google Pay, PhonePe...) refuse to run while any accessibility service is on, to protect against screen-reading malware.
// Google Pay shows "Turn off Accessibility for all apps" while Buddy's screen control is on. So Buddy switches it off itself whenever one of these
// apps comes to the front, and switches it back on when you say "Hey buddy", lock the phone, open Buddy, or after 20 minutes.
object PaymentGuard {
    val defaultApps = setOf(
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp", "com.dreamplug.androidapp", "com.freecharge.android",
        "com.mobikwik_new",
        "com.sbi.upi", "com.sbi.SBIFreedomPlus", "com.snapwork.hdfc", "com.hdfc.payzapp", "com.csam.icici.bank.imobile", "com.axis.mobile",
        "com.infrasofttech.centralbankofindia", "com.msf.kbank.mobile", "com.fisglobal.bob.bobworld", "com.pnb.pnbone",
    )

    private const val RESUME_AFTER_MS = 20 * 60 * 1000L
    @Volatile private var pausedAt = 0L

    private fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("pause_payments", true)
    fun extraApps(c: Context): Set<String> = (prefs(c).getString("payment_apps", "") ?: "").split(',', '\n', ' ').map { it.trim() }.filter { it.contains('.') }.toSet()
    fun isPaymentApp(c: Context, pkg: String) = pkg in defaultApps || pkg in extraApps(c)
    fun paused() = pausedAt > 0

    fun markPaused(c: Context) { pausedAt = System.currentTimeMillis(); ActivityLog.add(c, "Paused screen control while a payment app is open") }

    // Turns screen control back on if it was paused. Safe to call at any time.
    fun resume(c: Context) {
        if (pausedAt == 0L) return
        pausedAt = 0
        Health.healAccessibility(c)
    }

    fun resumeIfStale(c: Context) { if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > RESUME_AFTER_MS) resume(c) }
}
