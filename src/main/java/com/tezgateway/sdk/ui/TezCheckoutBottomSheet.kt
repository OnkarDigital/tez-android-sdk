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
import com.tezgateway.sdk.network.StatusPollingService
import com.tezgateway.sdk.utils.UpiIntentHelper
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * TezGateway native BottomSheet checkout UI.
 *
 * States:
 *   1. Payment Selection  — user picks a UPI app or scans QR
 *   2. Status Checking    — after returning from UPI app; polls until result
 *   3. Result             — success / failure / pending card shown inline
 */
class TezCheckoutBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_BASE_URL   = "base_url"
        private const val ARG_USER_TOKEN = "user_token"
        private const val ARG_ORDER_ID   = "order_id"

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
    private var paymentLaunched  = false
    private var resultDelivered  = false
    private var timerJob: Job?   = null

    // ── Views ──────────────────────────────────────────────────────────
    private lateinit var paymentSection:  View
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

        // Clear statics immediately
        pendingPaymentData = null
        pendingSettings    = null
        pendingCallback    = null

        bindViews(view)
        setupUI(view)
    }

    private fun bindViews(v: View) {
        paymentSection  = v.findViewById(R.id.tez_payment_section)
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
    }

    private fun setupUI(v: View) {
        // ── Header color ──────────────────────────────────────────────
        val headerBar = v.findViewById<View>(R.id.tez_header_bar)
        try {
            headerBar.setBackgroundColor(Color.parseColor(settings.headerColor))
        } catch (e: Exception) {
            headerBar.setBackgroundColor(Color.parseColor("#c800b2"))
        }

        // ── Spinner tint matches header color ─────────────────────────
        try {
            val pb = v.findViewById<android.widget.ProgressBar>(R.id.tez_status_spinner)
            val color = Color.parseColor(settings.headerColor)
            pb.indeterminateTintList =
                android.content.res.ColorStateList.valueOf(color)
        } catch (e: Exception) { /* keep default */ }

        // ── "Powered by TezGateway" badge ─────────────────────────────
        val brandingBadge = v.findViewById<View>(R.id.tez_branding_badge)
        brandingBadge.visibility =
            if (settings.remove_branding) View.GONE else View.VISIBLE

        // ── "Check Status Now" button stroke color ────────────────────
        try {
            val checkBtn = v.findViewById<com.google.android.material.button.MaterialButton>(
                R.id.btn_check_status)
            val color = Color.parseColor(settings.headerColor)
            val csl = android.content.res.ColorStateList.valueOf(color)
            checkBtn.strokeColor = csl
            checkBtn.setTextColor(color)
        } catch (e: Exception) { /* keep default */ }

        // ── News ticker ───────────────────────────────────────────────
        val newsTicker = v.findViewById<TextView>(R.id.tez_news_ticker)
        if (settings.news.isNotBlank()) {
            newsTicker.text      = settings.news
            newsTicker.isSelected = true
            newsTicker.visibility = View.VISIBLE
        } else {
            newsTicker.visibility = View.GONE
        }

        // ── UPI buttons ───────────────────────────────────────────────
        val ctx = requireContext()

        val btnGpay    = v.findViewById<Button>(R.id.btn_gpay)
        val btnPhonepe = v.findViewById<Button>(R.id.btn_phonepe)
        val btnPaytm   = v.findViewById<Button>(R.id.btn_paytm)
        val btnBhim    = v.findViewById<Button>(R.id.btn_bhim)
        val btnAmazon  = v.findViewById<Button>(R.id.btn_amazonpay)
        val btnCred    = v.findViewById<Button>(R.id.btn_cred)

        // GPay — shares QR image directly
        if (settings.Show_GpayButton &&
            UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.GOOGLE_PAY)) {
            btnGpay.visibility = View.VISIBLE
            btnGpay.setOnClickListener { shareQrToGpay() }
        } else btnGpay.visibility = View.GONE

        // PhonePe
        if (settings.show_phonepe &&
            UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.PHONEPE)) {
            btnPhonepe.visibility = View.VISIBLE
            btnPhonepe.setOnClickListener { launchUpiApp(UpiIntentHelper.UpiApp.PHONEPE) }
        } else btnPhonepe.visibility = View.GONE

        // Paytm
        if (settings.show_paytmButton &&
            UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.PAYTM)) {
            btnPaytm.visibility = View.VISIBLE
            btnPaytm.setOnClickListener { launchUpiApp(UpiIntentHelper.UpiApp.PAYTM) }
        } else btnPaytm.visibility = View.GONE

        // BHIM
        if (settings.Show_IntentButton &&
            UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.BHIM)) {
            btnBhim.visibility = View.VISIBLE
            btnBhim.setOnClickListener { launchUpiApp(UpiIntentHelper.UpiApp.BHIM) }
        } else btnBhim.visibility = View.GONE

        // Amazon Pay
        if (UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.AMAZON_PAY)) {
            btnAmazon.visibility = View.VISIBLE
            btnAmazon.setOnClickListener { launchUpiApp(UpiIntentHelper.UpiApp.AMAZON_PAY) }
        } else btnAmazon.visibility = View.GONE

        // CRED
        if (UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.CRED)) {
            btnCred.visibility = View.VISIBLE
            btnCred.setOnClickListener { launchUpiApp(UpiIntentHelper.UpiApp.CRED) }
        } else btnCred.visibility = View.GONE

        // ── QR section ────────────────────────────────────────────────
        val qrSection  = v.findViewById<View>(R.id.tez_qr_section)
        val qrDivider  = v.findViewById<View>(R.id.tez_qr_divider)
        val qrImageView = v.findViewById<ImageView>(R.id.tez_qr_image)

        if (settings.show_qr && paymentData.qr_image.isNotBlank()) {
            try {
                val base64Data = paymentData.qr_image.substringAfter("base64,")
                val bytes  = Base64.decode(base64Data, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                qrImageView.setImageBitmap(bitmap)
                qrSection.visibility = View.VISIBLE
                qrDivider.visibility = View.VISIBLE
            } catch (e: Exception) {
                qrSection.visibility = View.GONE
            }
        } else {
            qrSection.visibility = View.GONE
        }

        // ── Cancel button ─────────────────────────────────────────────
        btnCancel.setOnClickListener {
            if (resultDelivered) {
                dismiss()
            } else {
                pollingService?.stopPolling()
                timerJob?.cancel()
                callback.onPaymentFailed(orderId, "User cancelled")
                dismiss()
            }
        }

        // ── Check Status Now button ───────────────────────────────────
        btnCheckStatus.setOnClickListener { retriggerStatusCheck() }
    }

    // ── Payment launch helpers ─────────────────────────────────────────

    private fun shareQrToGpay() {
        val qrBase64 = paymentData.qr_image
        if (qrBase64.isBlank()) {
            launchUpiApp(UpiIntentHelper.UpiApp.GOOGLE_PAY)
            return
        }
        try {
            val base64Data = qrBase64.substringAfter("base64,")
            val bytes  = Base64.decode(base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            val cacheFile = File(requireContext().cacheDir, "tez_qr_pay.png")
            FileOutputStream(cacheFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.tezgateway.fileprovider",
                cacheFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                setPackage("com.google.android.apps.nbu.paisa.user")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            paymentLaunched = true
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open Google Pay", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchUpiApp(app: UpiIntentHelper.UpiApp) {
        val success = UpiIntentHelper.startPaymentIntent(requireContext(), app, paymentData)
        if (success) {
            paymentLaunched = true
        } else {
            Toast.makeText(context, "${app.name} not available", Toast.LENGTH_SHORT).show()
        }
    }

    // ── State transitions ──────────────────────────────────────────────

    /**
     * Switch from payment selection UI → status checking UI.
     * Called in onResume after user returns from a UPI app.
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

    /**
     * Updates the timer text every second for up to 100 seconds.
     */
    private fun startElapsedTimer() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch(Dispatchers.Main) {
            var elapsed = 0
            while (elapsed < StatusPollingService.TIMEOUT_SECONDS && isActive) {
                statusTimer.text = "Checking for ${elapsed}s…"
                delay(1000)
                elapsed++
            }
            if (isActive) statusTimer.text = "Checking for ${StatusPollingService.TIMEOUT_SECONDS}s…"
        }
    }

    /**
     * Show the result card (success / failure / pending).
     * Hides the spinner and Check Status button.
     */
    private fun showResult(
        iconText: String,
        title: String,
        subtitle: String,
        cardBgColor: Int,
        titleColor: Int
    ) {
        resultDelivered = true
        timerJob?.cancel()

        statusSpinner.visibility  = View.GONE
        btnCheckStatus.visibility = View.GONE
        statusMessage.text        = ""
        statusTimer.text          = ""

        resultIconText.text       = iconText
        resultTitle.text          = title
        resultTitle.setTextColor(titleColor)
        resultSubtitle.text       = subtitle
        resultCard.setBackgroundColor(cardBgColor)
        resultCard.visibility     = View.VISIBLE

        btnCancel.text = "Close"
    }

    /**
     * Stop current polling and restart immediately.
     * Used by the "Check Status Now" button.
     */
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
                        iconText      = "✓",
                        title         = "Payment Successful",
                        subtitle      = if (utr.isNotBlank()) "UTR: $utr" else "Order: $orderId",
                        cardBgColor   = 0xFFE8F5E9.toInt(),
                        titleColor    = 0xFF2E7D32.toInt()
                    )
                    // Brief delay so user can see the success card, then deliver callback
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

    /** When user comes back from UPI app, show checking state and start polling. */
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
