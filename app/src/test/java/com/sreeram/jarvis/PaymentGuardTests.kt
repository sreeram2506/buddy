package com.sreeram.jarvis

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PaymentGuardTests {
    // Just enough of a Context for PaymentGuard: preferences from a map, and WRITE_SECURE_SETTINGS not granted so resume() cannot touch the system.
    private class FakeContext(val values: MutableMap<String, Any> = mutableMapOf()) : ContextWrapper(null) {
        private val prefs = object : SharedPreferences {
            override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
            override fun getAll(): MutableMap<String, *> = values
            override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
            override fun getInt(key: String?, defValue: Int) = defValue
            override fun getLong(key: String?, defValue: Long) = defValue
            override fun getFloat(key: String?, defValue: Float) = defValue
            override fun contains(key: String?) = values.containsKey(key)
            override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        private val dir = File(System.getProperty("java.io.tmpdir"), "buddy-paymentguard-test").apply { mkdirs() }
        override fun getFilesDir(): File = dir
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_DENIED
    }

    private val ctx: Context = FakeContext()

    @After fun reset() = PaymentGuard.resume(ctx)

    @Test fun commonPaymentAppsAreCovered() {
        listOf("com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp")
            .forEach { assertTrue(it, PaymentGuard.isPaymentApp(ctx, it)) }
    }

    @Test fun ordinaryAppsAreNotTreatedAsPayment() {
        listOf("com.whatsapp", "com.naukri.jobs", "com.sreeram.jarvis").forEach { assertFalse(it, PaymentGuard.isPaymentApp(ctx, it)) }
    }

    @Test fun pausingIsOnByDefaultAndCanBeSwitchedOff() {
        assertTrue(PaymentGuard.enabled(ctx))
        assertFalse(PaymentGuard.enabled(FakeContext(mutableMapOf("pause_payments" to false))))
    }

    @Test fun extraAppsComeFromSettingsSplitOnCommaSpaceOrNewline() {
        val c = FakeContext(mutableMapOf("payment_apps" to "com.mybank.app, com.other.pay\ncom.third.upi"))
        listOf("com.mybank.app", "com.other.pay", "com.third.upi").forEach { assertTrue(it, PaymentGuard.isPaymentApp(c, it)) }
    }

    @Test fun extraAppsIgnoreJunkThatIsNotAPackageName() {
        val c = FakeContext(mutableMapOf("payment_apps" to "gpay, ,notapackage"))
        assertFalse(PaymentGuard.isPaymentApp(c, "gpay"))
        assertFalse(PaymentGuard.isPaymentApp(c, "notapackage"))
    }

    @Test fun markPausedThenResumeClearsThePausedFlag() {
        assertFalse(PaymentGuard.paused())
        PaymentGuard.markPaused(ctx)
        assertTrue(PaymentGuard.paused())
        PaymentGuard.resume(ctx)
        assertFalse(PaymentGuard.paused())
    }

    @Test fun resumeWhenNotPausedIsSafe() {
        PaymentGuard.resume(ctx)
        assertFalse(PaymentGuard.paused())
    }

    @Test fun aFreshPauseIsNotResumedEarly() {
        PaymentGuard.markPaused(ctx)
        PaymentGuard.resumeIfStale(ctx)
        assertTrue(PaymentGuard.paused())
    }
}
