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
 *  - Polls every [POLL_INTERVAL_MS] ms for up to [timeoutSeconds] seconds
 *    (defaults to [TIMEOUT_SECONDS] when the caller doesn't pass one — e.g. for
 *    Manual and other reference-based providers that don't have a comparable
 *    server-side auto-match window to sync against).
 *  - Stops immediately on SUCCESS or FAILURE — does not wait for the full timeout.
 *  - Only fires onPaymentPending() after the full timeout if still unresolved.
 *  - [startPolling] can be called again after [stopPolling] to restart (used by "Check Now").
 */
class StatusPollingService(
    private val baseUrl:   String,
    private val userToken: String,
    private val orderId:   String,
    private val callback:  TezPaymentCallback,
    /**
     * Per-instance poll budget in seconds — lets the checkout UI sync this to the
     * server's own remaining auto-match window (CheckoutSettings.manualUtrRevealInSeconds)
     * instead of always using the fixed default, so the app doesn't give up before/long
     * after the backend itself would. New parameter with a default value, so every
     * existing call site (and any code compiled against an older version of this
     * class) keeps working unchanged.
     */
    private val timeoutSeconds: Int = TIMEOUT_SECONDS
) {
    companion object {
        private const val TAG = "TezStatusPoller"

        /** Default total wait window when the caller doesn't specify one. */
        const val TIMEOUT_SECONDS = 100

        /** Interval between consecutive checks. */
        private const val POLL_INTERVAL_MS = 5_000L
    }

    /** Max attempts for *this* instance's budget = timeoutSeconds / (POLL_INTERVAL_MS / 1000), at least 1. */
    private val maxAttempts: Int =
        (timeoutSeconds / (POLL_INTERVAL_MS / 1000)).toInt().coerceAtLeast(1)

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
                try {
                    val json = callCheckOrder()
                    val result = json.optJSONObject("result")
                    val status = result?.optString("txnStatus", "") ?: ""

                    when (status) {
                        "SUCCESS" -> {
                            val utr = result?.optString("utr", "") ?: ""
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
                            attempts++
                            Log.d(TAG, "Poll attempt $attempts/$maxAttempts for order $orderId")
                            if (attempts >= maxAttempts) {
                                Log.d(TAG, "Timeout after ${timeoutSeconds}s — still pending")
                                withContext(Dispatchers.Main) { callback.onPaymentPending(orderId) }
                                return@launch
                            }
                            delay(POLL_INTERVAL_MS)
                        }
                    }
                } catch (ioe: java.io.IOException) {
                    Log.e(TAG, "Network disconnect during polling: ${ioe.message}. Retrying without incrementing attempts.")
                    delay(POLL_INTERVAL_MS)
                } catch (e: Exception) {
                    attempts++
                    Log.e(TAG, "Server/parsing error during polling: ${e.message}. Attempt $attempts/$maxAttempts")
                    if (attempts >= maxAttempts) {
                        Log.d(TAG, "Timeout after ${timeoutSeconds}s — still pending")
                        withContext(Dispatchers.Main) { callback.onPaymentPending(orderId) }
                        return@launch
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * One-shot status check — not part of the polling loop. Used by the checkout
     * UI's visible countdown to do a definitive final check right when the timer
     * hits zero, instead of only relying on the last scheduled poll attempt.
     * Must be called from a background thread (e.g. Dispatchers.IO).
     *
     * New method, purely additive — does not change any existing behaviour.
     */
    fun checkOnce(): OneShotResult {
        return try {
            val json = callCheckOrder()
            val result = json.optJSONObject("result")
            val status = result?.optString("txnStatus", "") ?: ""
            val utr = result?.optString("utr", "") ?: ""
            OneShotResult(status, utr)
        } catch (e: Exception) {
            Log.e(TAG, "checkOnce() failed: ${e.message}")
            OneShotResult("", "")
        }
    }

    /** Result of [checkOnce]. [status] is "SUCCESS" | "FAILURE" | "" (pending/unknown/error). */
    data class OneShotResult(val status: String, val utr: String)

    // ── Private helpers ────────────────────────────────────────────────

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
