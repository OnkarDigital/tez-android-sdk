package com.tezgateway.sdk.models

import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────
// ORDER RESPONSE — from your server (which called create_order1.php)
// ─────────────────────────────────────────────────────────────────

data class OrderResponse(
    val status: Boolean,
    val message: String,
    val is_vip: Boolean,
    val result: OrderResult?
)

data class OrderResult(
    val method: String,
    val orderId: String,
    val payment_url: String,
    val data: PaymentData?
)

/**
 * Deep-links returned by create_order1.php.
 * Pass this to TezGateway.startPayment() after receiving it from your server.
 *
 * Easiest: `PaymentData.fromJson(serverResponseJson, "₹500.00")`.
 * Java can also pass only the leading values it needs (@JvmOverloads) —
 * the original 9-value constructor keeps working as before.
 */
data class PaymentData @JvmOverloads constructor(
    val intPermit: String = "",
    val bhim_link: String = "",
    val phonepe_link: String = "",
    val paytm_link: String = "",
    val gpay_link: String = "",
    val amazonpay_link: String = "",
    val cred_link: String = "",
    val qr_image: String = "",
    val amount: String = ""
) {
    companion object {
        /**
         * Builds PaymentData straight from your server's create_order response.
         *
         * Accepts any of these shapes:
         *  - the full response   `{ "status": true, "result": { "orderId": …, "data": { … } } }`
         *  - the `result` object `{ "orderId": …, "data": { … } }`
         *  - the `data` object   `{ "bhim_link": …, "gpay_link": …, "qr_image": … }`
         * Missing / null fields become "". [amount] is optional ("" hides the amount card).
         *
         * @throws org.json.JSONException if [json] is not valid JSON.
         */
        @JvmStatic
        @JvmOverloads
        fun fromJson(json: String, amount: String = ""): PaymentData =
            fromJson(JSONObject(json), amount)

        /** Same as [fromJson] (String) for an already-parsed [JSONObject]. */
        @JvmStatic
        @JvmOverloads
        fun fromJson(json: JSONObject, amount: String = ""): PaymentData {
            val data = json.optJSONObject("result")?.optJSONObject("data")
                ?: json.optJSONObject("data")
                ?: json

            fun s(key: String): String =
                if (data.isNull(key)) "" else data.optString(key, "")

            return PaymentData(
                intPermit      = s("intPermit"),
                bhim_link      = s("bhim_link"),
                phonepe_link   = s("phonepe_link"),
                paytm_link     = s("paytm_link"),
                gpay_link      = s("gpay_link"),
                amazonpay_link = s("amazonpay_link"),
                cred_link      = s("cred_link"),
                qr_image       = s("qr_image"),
                amount         = amount
            )
        }

        /**
         * The `orderId` from your server's create_order response (full response or
         * `result` object). Returns "" if it isn't there.
         */
        @JvmStatic
        fun orderIdFromJson(json: String): String {
            val obj = JSONObject(json)
            return (obj.optJSONObject("result") ?: obj).optString("orderId", "")
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// STATUS RESPONSE — from check_order.php  (polled internally by SDK)
// ─────────────────────────────────────────────────────────────────

data class StatusResponse(
    val status: Boolean,
    val message: String,
    val result: StatusResult?
)

data class StatusResult(
    /** "SUCCESS" | "FAILURE" | "PENDING" */
    val txnStatus: String,
    val orderId: String,
    val amount: String?,
    val date: String?,
    /** UTR / transaction reference — non-null only on SUCCESS */
    val utr: String?,
    val customer_mobile: String?,
    val remark1: String?,
    val remark2: String?
)

// ─────────────────────────────────────────────────────────────────
// CHECKOUT SETTINGS — from get_checkout_settings.php
// ─────────────────────────────────────────────────────────────────

data class SettingsResponse(
    val status: Boolean,
    val message: String,
    val is_vip: Boolean,
    val settings: CheckoutSettings?
)

/**
 * Mirrors user_checkout_settings DB table.
 * Fetched automatically by TezGateway SDK before showing the UI.
 */
data class CheckoutSettings(
    val theme: Int = 1,
    val show_qr: Boolean = true,
    val show_paytmButton: Boolean = true,
    val show_help: Boolean = true,
    val remove_branding: Boolean = false,
    val show_payment_logos: Boolean = true,
    val Show_IntentButton: Boolean = true,
    val show_upiRequest: Boolean = true,
    val show_download_qr: Boolean = true,
    val Show_GpayButton: Boolean = true,
    val show_phonepe: Boolean = true,
    val show_phonepe_intent: Boolean = true,
    val show_amazonpay: Boolean = false,
    val show_cred: Boolean = false,
    val headerColor: String = "#c800b2",
    val bodyColor: String = "#ffffff",
    val display_header_footer: Boolean = true,
    val display_loading_screen: Boolean = true,
    val news: String = "",
    val phonepe_default_lang: String = "hi",
    val method: String = "",
    /**
     * Latest published SDK version, as reported by get_checkout_settings.php.
     * Blank when the server doesn't send it (older backend) — SDK then simply
     * shows no "update available" badge. New field, appended last with a
     * default, so it can't break any existing positional constructor call.
     */
    val latestSdkVersion: String = "",
    /**
     * Seconds from settings-fetch-time until the manual-UTR fallback box should
     * reveal itself — mirrors the web pay-page's manualUtrEligibleAfterSeconds.
     * Null when this order's method doesn't use amount+time-window matching at
     * all (Manual, or any reference/order-id-based provider), in which case no
     * timed box is shown by the SDK (Manual keeps its own always-visible box).
     */
    val manualUtrRevealInSeconds: Int? = null
)

// ─────────────────────────────────────────────────────────────────
// WEBHOOK PAYLOAD — received by merchant's server from TezGateway
// ─────────────────────────────────────────────────────────────────

/**
 * Data class representing the POST/GET callback body sent by TezGateway
 * to the merchant's configured Webhook URL when a payment is settled.
 *
 * Merchant's server can deserialize the incoming webhook JSON into this model.
 *
 * Example (using Gson on merchant's backend):
 *   val payload = Gson().fromJson(requestBody, WebhookPayload::class.java)
 */
data class WebhookPayload(
    /** "SUCCESS" | "FAILURE" | "PENDING" */
    val status: String,
    /** Bank UTR / reference number — present only for SUCCESS */
    val utr: String?,
    val order_id: String,
    val amount: String,
    val customer_mobile: String?,
    /** Payment method: "Googlepay" | "Phonepe" | "Paytm" | "Bharatpe" | etc. */
    val method: String,
    /** Merchant's UPI-linked mobile number */
    val merchentMobile: String?,
    /** Custom data sent during create_order1.php (remark1 field) */
    val remark1: String?,
    /** Custom data sent during create_order1.php (remark2 field) */
    val remark2: String?
)
