package com.tezgateway.sdk.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tezgateway.sdk.R
import com.tezgateway.sdk.interfaces.TezPaymentCallback
import com.tezgateway.sdk.models.CheckoutSettings
import com.tezgateway.sdk.models.PaymentData
import com.tezgateway.sdk.network.SettingsClient
import com.tezgateway.sdk.network.StatusPollingService
import com.tezgateway.sdk.utils.UpiIntentHelper
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * TezGateway native BottomSheet checkout UI.
 *
 * States:
 *   1. Payment Selection — user picks a UPI app or scans QR
 *   2. Status Checking   — polling after UPI app return OR "I've Paid" QR tap
 *   3. Result            — success / failure / pending card shown inline
 *
 * QR Fix (v1.0.8):
 *   "I've Paid — Check Status" button inside the QR card directly starts
 *   status polling without requiring the user to leave and return to the app.
 */
class TezCheckoutBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_BASE_URL   = "base_url"
        private const val ARG_USER_TOKEN = "user_token"
        private const val ARG_ORDER_ID   = "order_id"

        private const val LOGO_URL   = "https://tezgateway.com/logo.png"
        private const val SHIELD_URL = "https://tezgateway.com/common/img/logoshild.png"

        fun newInstance(
            baseUrl: String,
            userToken: String,
            orderId: String,
            paymentData: PaymentData,
            settings: CheckoutSettings,
            callback: TezPaymentCallback
        ): TezCheckoutBottomSheet {
            pendingPaymentData = paymentData
            pendingSettings    = settings
            pendingCallback    = callback

            return TezCheckoutBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_BASE_URL,   baseUrl)
                    putString(ARG_USER_TOKEN, userToken)
                    putString(ARG_ORDER_ID,   orderId)
                }
            }
        }

        internal var pendingPaymentData: PaymentData?        = null
        internal var pendingSettings:    CheckoutSettings?   = null
        internal var pendingCallback:    TezPaymentCallback? = null
    }

    private lateinit var paymentData: PaymentData
    private lateinit var settings:    CheckoutSettings
    private lateinit var callback:    TezPaymentCallback
    private lateinit var baseUrl:     String
    private lateinit var userToken:   String
    private lateinit var orderId:     String

    private var pollingService: StatusPollingService? = null
    private var paymentLaunched  = false   // true after launching a UPI app intent
    private var resultDelivered  = false
    private var timerJob: Job?   = null

    // ── Views ──────────────────────────────────────────────────────────
    private lateinit var paymentSection:  View
    private lateinit var amountSection:   View
    private lateinit var checkingSection: View
    private lateinit var statusSpinner:   View
    private lateinit var statusMessage:   TextView
    private lateinit var statusTimer:     TextView
    private lateinit var resultCard:      View
    private lateinit var resultIconText:  TextView
    private lateinit var resultTitle:     TextView
    private lateinit var resultSubtitle:  TextView
    private lateinit var btnCheckStatus:  View
    private lateinit var btnCancel:       Button
    private lateinit var brandingLogo:    ImageView
    private lateinit var spinnerLogo:     ImageView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottomsheet_tez_checkout, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        paymentData = pendingPaymentData ?: run { dismiss(); return }
        settings    = pendingSettings    ?: run { dismiss(); return }
        callback    = pendingCallback    ?: run { dismiss(); return }
        baseUrl     = arguments?.getString(ARG_BASE_URL)   ?: ""
        userToken   = arguments?.getString(ARG_USER_TOKEN) ?: ""
        orderId     = arguments?.getString(ARG_ORDER_ID)   ?: ""

        pendingPaymentData = null
        pendingSettings    = null
        pendingCallback    = null

        bindViews(view)
        setupUI(view)
    }

    private fun bindViews(v: View) {
        paymentSection  = v.findViewById(R.id.tez_payment_section)
        amountSection   = v.findViewById(R.id.tez_amount_section)
        checkingSection = v.findViewById(R.id.tez_checking_section)
        statusSpinner   = v.findViewById(R.id.tez_status_spinner)
        statusMessage   = v.findViewById(R.id.tez_status_message)
        statusTimer     = v.findViewById(R.id.tez_status_timer)
        resultCard      = v.findViewById(R.id.tez_result_card)
        resultIconText  = v.findViewById(R.id.tez_result_icon_text)
        resultTitle     = v.findViewById(R.id.tez_result_title)
        resultSubtitle  = v.findViewById(R.id.tez_result_subtitle)
        btnCheckStatus  = v.findViewById(R.id.btn_check_status)
        btnCancel       = v.findViewById(R.id.btn_cancel)
        brandingLogo    = v.findViewById(R.id.tez_branding_logo)
        spinnerLogo     = v.findViewById(R.id.tez_spinner_logo)
    }

    private fun setupUI(v: View) {
        // ── Header color ──────────────────────────────────────────────
        val headerColor = try { Color.parseColor(settings.headerColor) }
                          catch (e: Exception) { Color.parseColor("#c800b2") }

        v.findViewById<View>(R.id.tez_header_bar).setBackgroundColor(headerColor)

        // ── Amount card ───────────────────────────────────────────────
        if (paymentData.amount.isBlank()) {
            amountSection.visibility = View.GONE
        } else {
            amountSection.visibility = View.VISIBLE
            v.findViewById<TextView>(R.id.tez_amount_label).text = paymentData.amount
        }

        // ── UPI button text color: contrast against body color ────────
        val bodyColor = try { Color.parseColor(settings.bodyColor) }
                        catch (e: Exception) { Color.WHITE }
        val bodyLuminance = (0.299 * Color.red(bodyColor) +
                            0.587 * Color.green(bodyColor) +
                            0.114 * Color.blue(bodyColor)) / 255.0
        // Dark body → white text; Light body → each button's own brand color
        val upiTextColor: Int? = if (bodyLuminance < 0.5) Color.WHITE else null

        // ── Spinner tint ──────────────────────────────────────────────
        try {
            v.findViewById<ProgressBar>(R.id.tez_status_spinner)
                .indeterminateTintList =
                android.content.res.ColorStateList.valueOf(headerColor)
        } catch (e: Exception) { /* keep default */ }

        // ── "Check Status Now" button tint ────────────────────────────
        try {
            val csl = android.content.res.ColorStateList.valueOf(headerColor)
            v.findViewById<com.google.android.material.button.MaterialButton>(
                R.id.btn_check_status).apply {
                strokeColor = csl
                setTextColor(headerColor)
            }
        } catch (e: Exception) { /* keep default */ }

        // ── Branding badge ────────────────────────────────────────────
        v.findViewById<View>(R.id.tez_branding_badge).visibility =
            if (settings.remove_branding) View.GONE else View.VISIBLE

        // ── News ticker ───────────────────────────────────────────────
        val tickerContainer = v.findViewById<View>(R.id.tez_news_ticker_container)
        val tickerText      = v.findViewById<TextView>(R.id.tez_news_ticker)
        if (settings.news.isNotBlank()) {
            tickerText.text       = settings.news
            tickerText.isSelected = true
            tickerContainer.visibility = View.VISIBLE
        } else {
            tickerContainer.visibility = View.GONE
        }

        // ── UPI buttons ───────────────────────────────────────────────
        val ctx = requireContext()

        setupUpiButton(v, R.id.btn_gpay,
            enabled = settings.Show_GpayButton &&
                      UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.GOOGLE_PAY)
        ) { shareQrToGpay() }

        setupUpiButton(v, R.id.btn_phonepe,
            enabled = settings.show_phonepe &&
                      UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.PHONEPE)
        ) { launchUpiApp(UpiIntentHelper.UpiApp.PHONEPE) }

        setupUpiButton(v, R.id.btn_paytm,
            enabled = settings.show_paytmButton &&
                      UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.PAYTM)
        ) { launchUpiApp(UpiIntentHelper.UpiApp.PAYTM) }

        setupUpiButton(v, R.id.btn_bhim,
            enabled = settings.Show_IntentButton &&
                      UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.BHIM)
        ) { launchUpiApp(UpiIntentHelper.UpiApp.BHIM) }

        setupUpiButton(v, R.id.btn_amazonpay,
            enabled = settings.show_amazonpay &&
                      UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.AMAZON_PAY)
        ) { launchUpiApp(UpiIntentHelper.UpiApp.AMAZON_PAY) }

        setupUpiButton(v, R.id.btn_cred,
            enabled = settings.show_cred &&
                      UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.CRED)
        ) { launchUpiApp(UpiIntentHelper.UpiApp.CRED) }

        // Apply text color override on dark body backgrounds
        if (upiTextColor != null) {
            listOf(R.id.btn_gpay, R.id.btn_phonepe, R.id.btn_paytm,
                   R.id.btn_bhim, R.id.btn_amazonpay, R.id.btn_cred).forEach { id ->
                v.findViewById<com.google.android.material.button.MaterialButton>(id)
                    .setTextColor(upiTextColor)
            }
        }

        // ── QR section ────────────────────────────────────────────────
        val qrSection    = v.findViewById<View>(R.id.tez_qr_section)
        val orDivider    = v.findViewById<View>(R.id.tez_or_divider)
        val qrImageView  = v.findViewById<ImageView>(R.id.tez_qr_image)
        val btnQrPaid    = v.findViewById<Button>(R.id.btn_qr_paid)

        if (settings.show_qr && paymentData.qr_image.isNotBlank()) {
            try {
                val base64Data = paymentData.qr_image.substringAfter("base64,")
                val bytes  = Base64.decode(base64Data, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                qrImageView.setImageBitmap(bitmap)

                qrSection.visibility = View.VISIBLE
                orDivider.visibility = View.VISIBLE

                // Tint the "I've Paid" button to match the merchant's header color
                btnQrPaid.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(headerColor)

                // ── QR BUG FIX: tap "I've Paid" → directly start status polling ──
                btnQrPaid.setOnClickListener {
                    showCheckingState()
                    startPolling()
                }
            } catch (e: Exception) {
                qrSection.visibility = View.GONE
                orDivider.visibility = View.GONE
            }
        } else {
            qrSection.visibility = View.GONE
            orDivider.visibility = View.GONE
        }

        // ── Cancel button ─────────────────────────────────────────────
        btnCancel.setOnClickListener {
            if (resultDelivered) {
                dismiss()
            } else {
                pollingService?.stopPolling()
                timerJob?.cancel()
                // Fire-and-forget: mark order as cancelled on server (status=FAILURE, utr=user_cancelled)
                lifecycleScope.launch(Dispatchers.IO) {
                    SettingsClient.cancelOrder(baseUrl, userToken, orderId)
                }
                callback.onPaymentFailed(orderId, "User cancelled")
                dismiss()
            }
        }

        // ── Check Status Now button ───────────────────────────────────
        btnCheckStatus.setOnClickListener { retriggerStatusCheck() }

        // ── Logos ─────────────────────────────────────────────────────
        loadImageInto(LOGO_URL,   brandingLogo)
        loadImageInto(SHIELD_URL, spinnerLogo)
    }

    /** Show/hide a UPI button and set its click listener. */
    private fun setupUpiButton(v: View, id: Int, enabled: Boolean, onClick: () -> Unit) {
        val btn = v.findViewById<Button>(id)
        if (enabled) {
            btn.visibility = View.VISIBLE
            btn.setOnClickListener { onClick() }
        } else {
            btn.visibility = View.GONE
        }
    }

    // ── Image loading ──────────────────────────────────────────────────

    private fun loadImageInto(url: String, imageView: ImageView) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = okhttp3.OkHttpClient()
                    .newCall(okhttp3.Request.Builder().url(url).build())
                    .execute().body?.bytes() ?: return@launch
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                withContext(Dispatchers.Main) { imageView.setImageBitmap(bitmap) }
            } catch (e: Exception) { /* logo is cosmetic — never crash */ }
        }
    }

    // ── UPI app launch helpers ─────────────────────────────────────────

    private fun shareQrToGpay() {
        val qrBase64 = paymentData.qr_image
        if (qrBase64.isBlank()) {
            toast("QR code not available")
            return
        }

        // Decode + write on IO thread to avoid ANR on slow devices
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val base64Data = qrBase64.substringAfter("base64,")
                val bytes  = Base64.decode(base64Data, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: run { withContext(Dispatchers.Main) { toast("Failed to decode QR image") }; return@launch }

                // Delete any leftover tez_qr_*.png files from previous orders
                requireContext().cacheDir
                    .listFiles { f -> f.name.startsWith("tez_qr_") && f.name.endsWith(".png") }
                    ?.forEach { it.delete() }

                // Unique filename per order — never serve a stale cached QR
                val cacheFile = File(requireContext().cacheDir, "tez_qr_${orderId}.png")
                FileOutputStream(cacheFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

                val ctx       = requireContext()
                val authority = "${ctx.packageName}.tezgateway.fileprovider"
                val uri       = FileProvider.getUriForFile(ctx, authority, cacheFile)

                // Explicitly grant read permission to GPay's package — required on many
                // Android versions even when FLAG_GRANT_READ_URI_PERMISSION is set on the intent
                ctx.grantUriPermission(
                    "com.google.android.apps.nbu.paisa.user",
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    // ClipData is required on Android 6+ for the URI permission grant to
                    // propagate to the receiving app — without this GPay opens but can't
                    // read the image (blank / permission-denied crash inside GPay)
                    clipData = android.content.ClipData.newRawUri("QR Code", uri)
                    setPackage("com.google.android.apps.nbu.paisa.user")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // No FLAG_ACTIVITY_NEW_TASK — Fragment.startActivity() uses the host
                    // activity's task, which is correct and avoids task-stack side-effects
                }

                withContext(Dispatchers.Main) {
                    try {
                        startActivity(shareIntent)
                        paymentLaunched = true
                    } catch (e: android.content.ActivityNotFoundException) {
                        toast("Google Pay not installed")
                    }
                }
            } catch (e: IllegalArgumentException) {
                withContext(Dispatchers.Main) { toast("FileProvider error: ${e.message}") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Could not open Google Pay: ${e.message}") }
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    private fun launchUpiApp(app: UpiIntentHelper.UpiApp) {
        if (UpiIntentHelper.startPaymentIntent(requireContext(), app, paymentData)) {
            paymentLaunched = true
        } else {
            Toast.makeText(context, "${app.name} not available", Toast.LENGTH_SHORT).show()
        }
    }

    // ── State transitions ──────────────────────────────────────────────

    /**
     * Switch to status checking UI.
     * Called either in [onResume] (after UPI app intent) or directly from the "I've Paid" button.
     */
    private fun showCheckingState() {
        paymentSection.visibility  = View.GONE
        checkingSection.visibility = View.VISIBLE
        resultCard.visibility      = View.GONE
        statusSpinner.visibility   = View.VISIBLE
        btnCheckStatus.visibility  = View.VISIBLE
        statusMessage.text         = "Verifying your payment..."
        btnCancel.text             = "I'll Check Later"
        startElapsedTimer()
    }

    private fun startElapsedTimer() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch(Dispatchers.Main) {
            var elapsed = 0
            while (elapsed < StatusPollingService.TIMEOUT_SECONDS && isActive) {
                statusTimer.text = "Checking for ${elapsed}s…"
                delay(1000)
                elapsed++
            }
            if (isActive) statusTimer.text = "Checked for ${StatusPollingService.TIMEOUT_SECONDS}s"
        }
    }

    private fun showResult(
        iconText: String, title: String, subtitle: String,
        cardBgColor: Int, titleColor: Int
    ) {
        resultDelivered = true
        timerJob?.cancel()

        statusSpinner.visibility  = View.GONE
        btnCheckStatus.visibility = View.GONE
        statusMessage.text        = ""
        statusTimer.text          = ""

        resultIconText.text = iconText
        resultTitle.text    = title
        resultTitle.setTextColor(titleColor)
        resultSubtitle.text = subtitle
        resultCard.setBackgroundColor(cardBgColor)
        resultCard.visibility = View.VISIBLE

        btnCancel.text = "Close"
    }

    private fun retriggerStatusCheck() {
        pollingService?.stopPolling()
        statusMessage.text = "Rechecking payment status..."
        startPolling()
    }

    // ── Polling ────────────────────────────────────────────────────────

    private fun startPolling() {
        pollingService = StatusPollingService(
            baseUrl   = baseUrl,
            userToken = userToken,
            orderId   = orderId,
            callback  = object : TezPaymentCallback {
                override fun onPaymentSuccess(orderId: String, utr: String) {
                    showResult(
                        iconText    = "✓",
                        title       = "Payment Successful",
                        subtitle    = if (utr.isNotBlank()) "UTR: $utr" else "Order: $orderId",
                        cardBgColor = 0xFFE8F5E9.toInt(),
                        titleColor  = 0xFF2E7D32.toInt()
                    )
                    btnCancel.visibility = View.GONE
                    lifecycleScope.launch {
                        delay(1800)
                        dismiss()
                        callback.onPaymentSuccess(orderId, utr)
                    }
                }
                override fun onPaymentFailed(orderId: String, reason: String) {
                    showResult(
                        iconText    = "✗",
                        title       = "Payment Failed",
                        subtitle    = reason,
                        cardBgColor = 0xFFFFEBEE.toInt(),
                        titleColor  = 0xFFC62828.toInt()
                    )
                    lifecycleScope.launch {
                        delay(2000)
                        dismiss()
                        callback.onPaymentFailed(orderId, reason)
                    }
                }
                override fun onPaymentPending(orderId: String) {
                    showResult(
                        iconText    = "⏳",
                        title       = "Payment Pending",
                        subtitle    = "Your payment is being verified.\nPlease check your UPI app.",
                        cardBgColor = 0xFFFFF8E1.toInt(),
                        titleColor  = 0xFFE65100.toInt()
                    )
                    callback.onPaymentPending(orderId)
                }
            }
        )
        pollingService?.startPolling(lifecycleScope)
    }

    /** After returning from a UPI app intent, start polling automatically. */
    override fun onResume() {
        super.onResume()
        if (paymentLaunched && !resultDelivered) {
            paymentLaunched = false
            showCheckingState()
            startPolling()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollingService?.stopPolling()
        timerJob?.cancel()
    }
}
