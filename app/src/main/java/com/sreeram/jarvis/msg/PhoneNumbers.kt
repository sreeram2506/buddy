package com.sreeram.jarvis.msg

import com.google.i18n.phonenumbers.PhoneNumberUtil

// Phone numbers in one standard form (digits only, with country code, as wa.me wants). Uses Google's libphonenumber, which knows every
// country's rules, instead of guessing "10 digits means India".
object PhoneNumbers {
    private val util by lazy { PhoneNumberUtil.getInstance() }

    // "98765 43210", "09876543210", "+91 98765 43210" -> "919876543210". Falls back to the digits alone if it cannot be parsed.
    fun digitsWithCountryCode(raw: String, region: String = "IN"): String {
        val digits = raw.filter { it.isDigit() }
        return try {
            val n = util.parse(raw, region)
            if (util.isPossibleNumber(n)) "${n.countryCode}${n.nationalNumber}" else digits
        } catch (e: Exception) { digits }
    }

    // The country a bare number belongs to: where the SIM is, then the network, and only then the phone's language, which says nothing about
    // where the number is from (an English (UK) phone with an Indian SIM must read 8309078104 as +91, not +44).
    fun pickRegion(sim: String?, network: String?, locale: String?): String =
        listOf(sim, network, locale).firstOrNull { !it.isNullOrBlank() }?.uppercase() ?: "IN"

    fun sameNumber(a: String, b: String, region: String = "IN") = digitsWithCountryCode(a, region) == digitsWithCountryCode(b, region)
}
