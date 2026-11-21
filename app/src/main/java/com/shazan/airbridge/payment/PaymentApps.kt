package com.shazan.airbridge.payment

/** Packages whose apps refuse to pay while an accessibility service is on. */
object PaymentApps {
    val packages: Set<String> = setOf(
        "net.one97.paytm",                              // Paytm
        "com.phonepe.app",                              // PhonePe
        "com.google.android.apps.nbu.paisa.user",       // Google Pay
        "in.org.npci.upiapp",                           // BHIM
    )

    /** More packages to treat the same way (comma-separated pref `PAYMENT_PAUSE_EXTRA`: another UPI or bank app). */
    @Volatile var extra: Set<String> = emptySet()

    fun isPayment(pkg: String?): Boolean = pkg != null && (pkg in packages || pkg in extra)
}
