package com.tezgateway.sdk

import androidx.fragment.app.FragmentActivity
import com.tezgateway.sdk.interfaces.TezPaymentCallback
import com.tezgateway.sdk.models.CheckoutSettings
import com.tezgateway.sdk.models.PaymentData
import com.tezgateway.sdk.network.SettingsClient
import com.tezgateway.sdk.ui.TezCheckoutBottomSheet
import kotlinx.coroutines.*

/**
 * TezGateway SDK — Main Entry Point
 *
 * ════════════════════════════════════════
 *  OPTION 2 (Server-to-Server) Flow:
 *  1. Merchant's own backend calls create_order1.php
 *  2. Backend returns PaymentData to the Android App
 *  3. App calls TezGateway.startPayment() with that data
 *  4. SDK fetches UI settings, shows native checkout, polls status
 * ════════════════════════════════════════
 *
 * Kotlin:
 *   TezGateway.startPayment(activity, userToken, orderId, paymentData, callback)
 *
 * Java:
 *   TezGateway.startPayment(activity, userToken, orderId, paymentData, callback)
 */
object TezGateway {

    /** Default base URL — can be overridden by calling configure() */
    private var baseUrl: String = "https://tezgateway.com"

    /**
     * Optional: Override the default gateway base URL.
     * Call once in Application.onCreate() if using a custom domain.
     */
    @JvmStatic
    fun configure(baseUrl: String) {
        this.baseUrl = baseUrl.trimEnd('/')
    }

    /**
     * Launch the TezGateway native payment checkout UI.
     *
     * @param activity      The calling Activity (must be FragmentActivity)
     * @param userToken     Merchant's API token (used ONLY for settings + status check)
     * @param orderId       Order ID received from your server after calling create_order1.php
     * @param paymentData   Payment deep-links from your server (bhim_link, gpay_link, qr_image, etc.)
     * @param callback      TezPaymentCallback to receive success/failure/pending result
     */
    @JvmStatic
    fun startPayment(
        activity: FragmentActivity,
        userToken: String,
        orderId: String,
        paymentData: PaymentData,
        callback: TezPaymentCallback
    ) {
        // Fetch merchant's UI settings from get_checkout_settings.php then show UI
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsClient.fetchSettings(baseUrl, userToken, orderId)
                withContext(Dispatchers.Main) {
                    val sheet = TezCheckoutBottomSheet.newInstance(
                        baseUrl = baseUrl,
                        userToken = userToken,
                        orderId = orderId,
                        paymentData = paymentData,
                        settings = settings,
                        callback = callback
                    )
                    sheet.show(activity.supportFragmentManager, "TezCheckout")
                }
            } catch (e: Exception) {
                val errorMessage = e.message ?: "Failed to fetch settings"
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(activity, errorMessage, android.widget.Toast.LENGTH_LONG).show()
                    callback.onPaymentFailed(orderId, errorMessage)
                }
            }
        }
    }
}
