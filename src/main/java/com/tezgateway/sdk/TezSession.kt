package com.tezgateway.sdk

import android.os.SystemClock
import com.tezgateway.sdk.interfaces.TezPaymentCallback
import com.tezgateway.sdk.models.CheckoutSettings
import com.tezgateway.sdk.models.PaymentData

/**
 * Holds the one payment that is currently running.
 *
 * - Survives the checkout Activity/Fragment being re-created, so the sheet never
 *   silently loses its data (and the merchant never loses the callback).
 * - Delivers the merchant's callback **at most once** per payment, whichever
 *   path (polling, final check, Close button, Activity destroyed…) gets there first.
 */
internal object TezSession {

    /** A session older than this is treated as abandoned and may be replaced. */
    private const val STALE_AFTER_MS = 15 * 60 * 1000L

    @Volatile
    var current: Request? = null

    class Request(
        val baseUrl: String,
        val userToken: String,
        val orderId: String,
        val paymentData: PaymentData,
        merchantCallback: TezPaymentCallback
    ) {
        @Volatile var settings: CheckoutSettings? = null

        private val startedAt = SystemClock.elapsedRealtime()
        private var target: TezPaymentCallback? = merchantCallback

        val isFinished: Boolean get() = target == null
        val isStale: Boolean get() = SystemClock.elapsedRealtime() - startedAt > STALE_AFTER_MS

        /** Once-only wrapper handed to the UI — extra calls after the first are ignored. */
        val callback: TezPaymentCallback = object : TezPaymentCallback {
            override fun onPaymentSuccess(orderId: String, utr: String) { take()?.onPaymentSuccess(orderId, utr) }
            override fun onPaymentFailed(orderId: String, reason: String) { take()?.onPaymentFailed(orderId, reason) }
            override fun onPaymentPending(orderId: String) { take()?.onPaymentPending(orderId) }
        }

        @Synchronized
        private fun take(): TezPaymentCallback? {
            val t = target
            target = null
            if (current === this) current = null
            return t
        }
    }
}
