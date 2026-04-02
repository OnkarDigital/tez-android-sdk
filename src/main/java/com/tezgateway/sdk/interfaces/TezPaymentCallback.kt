package com.tezgateway.sdk.interfaces

/**
 * Callback interface for TezGateway payment results.
 * Implement this in your Activity or Fragment.
 *
 * Java Usage:
 *   new TezPaymentCallback() { ... }
 *
 * Kotlin Usage:
 *   object : TezPaymentCallback { ... }
 */
interface TezPaymentCallback {

    /** Called when payment is verified as SUCCESS via check_order.php */
    fun onPaymentSuccess(orderId: String, utr: String)

    /** Called when payment status is FAILURE or user cancelled */
    fun onPaymentFailed(orderId: String, reason: String)

    /** Called when payment is still PENDING after max polling attempts */
    fun onPaymentPending(orderId: String)
}
