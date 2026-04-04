package com.tezgateway.sdk.network

import android.util.Log
import com.tezgateway.sdk.interfaces.TezPaymentCallback
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Polls check_order.php after user returns from UPI app.
 *
 * Behaviour:
 *  - Polls every [POLL_INTERVAL_MS] ms for up to [TIMEOUT_SECONDS] seconds.
 *  - Stops immediately on SUCCESS or FAILURE — does not wait for the full timeout.
 *  - Only fires onPaymentPending() after the full timeout if still unresolved.
 *  - [startPolling] can be called again after [stopPolling] to restart (used by "Check Now").
 */
class StatusPollingService(
    private val baseUrl:   String,
    private val userToken: String,
    private val orderId:   String,
    private val callback:  TezPaymentCallback
) {
    companion object {
        private const val TAG = "TezStatusPoller"

        /** Total wait window after user returns from UPI app. */
        const val TIMEOUT_SECONDS = 100

        /** Interval between consecutive checks. */
        private const val POLL_INTERVAL_MS = 5_000L

        /** Max attempts = TIMEOUT_SECONDS / (POLL_INTERVAL_MS / 1000) = 20 */
        private val MAX_ATTEMPTS = (TIMEOUT_SECONDS / (POLL_INTERVAL_MS / 1000)).toInt()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10,  TimeUnit.SECONDS)
        .build()

    private var pollingJob: Job? = null

    /**
     * Start (or restart) polling on an IO coroutine.
     * Results are always delivered on the Main thread.
     * Safe to call again after [stopPolling].
     */
    fun startPolling(scope: CoroutineScope) {
        pollingJob?.cancel()
        pollingJob = scope.launch(Dispatchers.IO) {
            var attempts = 0

            while (isActive) {
                attempts++
                Log.d(TAG, "Poll attempt $attempts/$MAX_ATTEMPTS for order $orderId")

                val status = fetchStatus()

                when (status) {
                    "SUCCESS" -> {
                        val utr = fetchUtr() ?: ""
                        withContext(Dispatchers.Main) { callback.onPaymentSuccess(orderId, utr) }
                        return@launch
                    }
                    "FAILURE" -> {
                        withContext(Dispatchers.Main) {
                            callback.onPaymentFailed(orderId, "Transaction failed")
                        }
                        return@launch
                    }
                    else -> {
                        // PENDING or unknown — check timeout
                        if (attempts >= MAX_ATTEMPTS) {
                            Log.d(TAG, "Timeout after ${TIMEOUT_SECONDS}s — still pending")
                            withContext(Dispatchers.Main) { callback.onPaymentPending(orderId) }
                            return@launch
                        }
                        delay(POLL_INTERVAL_MS)
                    }
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ── Private helpers ────────────────────────────────────────────────

    /** Returns the raw txnStatus string ("SUCCESS" | "FAILURE" | "PENDING"), or "" on error. */
    private fun fetchStatus(): String {
        return try {
            val json = callCheckOrder()
            json.optJSONObject("result")?.optString("txnStatus", "") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Poll error: ${e.message}")
            "" // Treat network errors as PENDING — keep retrying
        }
    }

    /** Returns UTR from the same endpoint — called only on SUCCESS. */
    private fun fetchUtr(): String? {
        return try {
            val json = callCheckOrder()
            json.optJSONObject("result")?.optString("utr", "")
        } catch (e: Exception) { null }
    }

    private fun callCheckOrder(): JSONObject {
        val body = FormBody.Builder()
            .add("user_token", userToken)
            .add("order_id",   orderId)
            .build()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/check_order.php")
            .post(body)
            .build()

        val responseBody = client.newCall(request).execute().body?.string() ?: "{}"
        return JSONObject(responseBody)
    }
}
