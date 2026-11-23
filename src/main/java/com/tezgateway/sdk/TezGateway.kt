package com.tezgateway.sdk

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.tezgateway.sdk.interfaces.TezPaymentCallback
import com.tezgateway.sdk.models.PaymentData
import com.tezgateway.sdk.ui.TezCheckoutActivity

/**
 * TezGateway SDK — Main Entry Point
 *
 * ════════════════════════════════════════
 *  OPTION 2 (Server-to-Server) Flow:
 *  1. Merchant's own backend calls create_order
 *  2. Backend returns PaymentData to the Android App
 *  3. App calls TezGateway.startPayment() with that data
 *  4. SDK shows a loader instantly, fetches UI settings, shows native checkout, polls status
 * ════════════════════════════════════════
 *
 * Kotlin / Java:
 *   TezGateway.startPayment(activity, userToken, orderId, paymentData, callback)
 *
 * Any Activity works (AppCompatActivity, FlutterActivity, ComponentActivity…) and the
 * host app's theme does not matter — the checkout runs in the SDK's own screen.
 */
object TezGateway {

    private const val TAG = "TezGateway"

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
     * The callback is delivered exactly once, on the main thread.
     * While one payment is open, further calls are ignored (protects against double taps).
     *
     * @param activity      Any Activity of your app
     * @param userToken     Merchant's API token (used ONLY for settings + status check)
     * @param orderId       Order ID received from your server after calling create_order
     * @param paymentData   Payment deep-links from your server (bhim_link, gpay_link, qr_image, etc.)
     * @param callback      TezPaymentCallback to receive success/failure/pending result
     */
    @JvmStatic
    fun startPayment(
        activity: Activity,
        userToken: String,
        orderId: String,
        paymentData: PaymentData,
        callback: TezPaymentCallback
    ) {
        val running = TezSession.current
        if (running != null && !running.isFinished && !running.isStale) {
            Log.w(TAG, "startPayment($orderId) ignored — payment ${running.orderId} is already open")
            return
        }

        TezSession.current = TezSession.Request(
            baseUrl = baseUrl,
            userToken = userToken,
            orderId = orderId,
            paymentData = paymentData,
            merchantCallback = callback
        )

        activity.startActivity(Intent(activity, TezCheckoutActivity::class.java))
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    /**
     * Same as the [Activity] version — kept so apps and plugins compiled against
     * SDK ≤ 1.0.30 (which required a FragmentActivity) keep working unchanged.
     */
    @JvmStatic
    fun startPayment(
        activity: FragmentActivity,
        userToken: String,
        orderId: String,
        paymentData: PaymentData,
        callback: TezPaymentCallback
    ) = startPayment(activity as Activity, userToken, orderId, paymentData, callback)
}
