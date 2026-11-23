package com.tezgateway.sdk.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tezgateway.sdk.R
import com.tezgateway.sdk.TezSession
import com.tezgateway.sdk.network.SettingsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent host for the checkout sheet.
 *
 * Owning our own Activity means the SDK works from ANY host Activity (plain
 * Activity, FlutterActivity, ComponentActivity/Compose, Cordova…) and with ANY host
 * theme — this Activity always uses the SDK's own MaterialComponents theme.
 * It also handles configuration changes itself (see manifest), so rotating the
 * phone or switching dark mode never destroys an in-progress checkout.
 */
internal class TezCheckoutActivity : AppCompatActivity() {

    companion object {
        private const val SHEET_TAG = "TezCheckout"
    }

    private var request: TezSession.Request? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        // Nothing to show (e.g. process was killed and Android restored this screen)
        val req = TezSession.current
        if (req == null || req.isFinished) {
            closeScreen()
            return
        }
        request = req
        setContentView(R.layout.tez_activity_checkout)

        // Back is blocked: the sheet itself is closed only via its Cancel/Close button,
        // and while loading the settings request times out on its own (≤10s).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* intentionally ignored */ }
        })

        if (req.settings != null) showSheet() else loadSettings(req)
    }

    private fun loadSettings(req: TezSession.Request) {
        lifecycleScope.launch {
            try {
                req.settings = withContext(Dispatchers.IO) {
                    SettingsClient.fetchSettings(req.baseUrl, req.userToken, req.orderId)
                }
                showSheet()
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "Could not load the payment screen. Please try again."
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                req.callback.onPaymentFailed(req.orderId, msg)
                closeScreen()
            }
        }
    }

    private fun showSheet() {
        findViewById<View>(R.id.tez_loader_root)?.visibility = View.GONE
        if (supportFragmentManager.findFragmentByTag(SHEET_TAG) == null) {
            TezCheckoutBottomSheet().show(supportFragmentManager, SHEET_TAG)
        }
    }

    /** Called by the sheet when it is dismissed. */
    fun onSheetClosed() = closeScreen()

    private fun closeScreen() {
        if (!isFinishing) {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Safety net: if the screen goes away without any result having been sent,
        // tell the merchant "pending" so their app / bridge promise is never left hanging.
        if (!isChangingConfigurations) {
            request?.let { if (!it.isFinished) it.callback.onPaymentPending(it.orderId) }
        }
    }
}
