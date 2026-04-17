package com.tezgateway.sdk.network

import com.tezgateway.sdk.models.CheckoutSettings
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches merchant UI configuration from get_checkout_settings.php.
 * Called internally by TezGateway.startPayment() before showing the checkout UI.
 */
internal object SettingsClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Blocking call — must be called from a background thread (IO Dispatcher).
     * @param orderId Optional — when provided, server computes show_phonepe dynamically
     *                based on the order's payment method and Paytm cookie state.
     * @throws Exception if network fails (caller should handle and use defaults)
     */
    fun fetchSettings(baseUrl: String, userToken: String, orderId: String = ""): CheckoutSettings {
        val bodyBuilder = FormBody.Builder().add("user_token", userToken)
        if (orderId.isNotBlank()) bodyBuilder.add("order_id", orderId)
        val body = bodyBuilder.build()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/get_checkout_settings.php")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val json = JSONObject(response.body?.string() ?: "{}")
        val s = json.getJSONObject("settings")

        return CheckoutSettings(
            theme               = s.optInt("theme", 1),
            show_qr             = s.optBoolean("show_qr", true),
            show_paytmButton    = s.optBoolean("show_paytmButton", true),
            show_help           = s.optBoolean("show_help", true),
            remove_branding     = s.optBoolean("remove_branding", false),
            show_payment_logos  = s.optBoolean("show_payment_logos", true),
            Show_IntentButton   = s.optBoolean("Show_IntentButton", true),
            show_upiRequest     = s.optBoolean("show_upiRequest", true),
            show_download_qr    = s.optBoolean("show_download_qr", true),
            Show_GpayButton     = s.optBoolean("Show_GpayButton", true),
            show_phonepe        = s.optBoolean("show_phonepe", true),
            show_phonepe_intent = s.optBoolean("show_phonepe_intent", true),
            show_amazonpay      = s.optBoolean("show_amazonpay", false),
            show_cred           = s.optBoolean("show_cred", false),
            headerColor         = s.optString("headerColor", "#c800b2"),
            bodyColor           = s.optString("bodyColor", "#ffffff"),
            display_header_footer   = s.optBoolean("display_header_footer", true),
            display_loading_screen  = s.optBoolean("display_loading_screen", true),
            news                = s.optString("news", ""),
            phonepe_default_lang = s.optString("phonepe_default_lang", "hi")
        )
    }

    /**
     * Cancels a PENDING order on the server.
     * Blocking — call from a background thread.
     * @return true if server confirmed cancellation, false if already settled / error.
     */
    fun cancelOrder(baseUrl: String, userToken: String, orderId: String): Boolean {
        return try {
            val body = FormBody.Builder()
                .add("user_token", userToken)
                .add("order_id", orderId)
                .build()
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/cancel_order.php")
                .post(body)
                .build()
            val responseBody = client.newCall(request).execute().body?.string() ?: return false
            JSONObject(responseBody).optBoolean("status", false)
        } catch (_: Exception) { false }
    }
}
