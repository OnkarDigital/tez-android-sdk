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
 * Polls every POLL_INTERVAL_MS for max MAX_ATTEMPTS times.
 */
class StatusPollingService(
    private val baseUrl: String,
    private val userToken: String,
    private val orderId: String,
    private val callback: TezPaymentCallback
) {
    companion object {
        private const val TAG = "TezStatusPoller"
        private const val MAX_ATTEMPTS = 8
        private const val POLL_INTERVAL_MS = 3000L // 3 seconds
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var pollingJob: Job? = null

    /** Start polling on an IO coroutine; delivers result on Main thread */
    fun startPolling(scope: CoroutineScope) {
        pollingJob?.cancel()
        pollingJob = scope.launch(Dispatchers.IO) {
            var attempts = 0
            while (attempts < MAX_ATTEMPTS && isActive) {
                attempts++
                Log.d(TAG, "Polling attempt $attempts for order $orderId")

                try {
                    val body = FormBody.Builder()
                        .add("user_token", userToken)
                        .add("order_id", orderId)
                        .build()

                    val request = Request.Builder()
                        .url("${baseUrl.trimEnd('/')}/api/check_order.php")
                        .post(body)
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string() ?: ""
                    val json = JSONObject(responseBody)

                    val result = json.optJSONObject("result")
                    val txnStatus = result?.optString("txnStatus", "") ?: ""
                    val utr = result?.optString("utr", "") ?: ""

                    when (txnStatus.uppercase()) {
                        "SUCCESS" -> {
                            withContext(Dispatchers.Main) {
                                callback.onPaymentSuccess(orderId, utr)
                            }
                            return@launch
                        }
                        "FAILURE" -> {
                            withContext(Dispatchers.Main) {
                                callback.onPaymentFailed(orderId, "Transaction Failed")
                            }
                            return@launch
                        }
                        else -> {
                            // PENDING — wait before next attempt
                            delay(POLL_INTERVAL_MS)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error: ${e.message}")
                    delay(POLL_INTERVAL_MS)
                }
            }

            // Max attempts reached — still pending
            withContext(Dispatchers.Main) {
                callback.onPaymentPending(orderId)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
}
