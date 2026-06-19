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

        internal var cachedLogo: Bitmap? = null
        internal var cachedShield: Bitmap? = null
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

    private lateinit var manualUtrSection: View
    private lateinit var manualUtrInput:   EditText
    private lateinit var btnManualUtrSubmit: Button
    private lateinit var manualUtrMsg:     TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val currentSettings = pendingSettings ?: CheckoutSettings()
        val themeId = if (currentSettings.theme in 1..4) currentSettings.theme else 1
        val layoutRes = when (themeId) {
            2 -> R.layout.bottomsheet_tez_checkout_theme2
            3 -> R.layout.bottomsheet_tez_checkout_theme3
            4 -> R.layout.bottomsheet_tez_checkout_theme4
            else -> R.layout.bottomsheet_tez_checkout
        }
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

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

        manualUtrSection   = v.findViewById(R.id.tez_manual_utr_section)
        manualUtrInput     = v.findViewById(R.id.tez_manual_utr_input)
        btnManualUtrSubmit = v.findViewById(R.id.btn_manual_utr_submit)
        manualUtrMsg       = v.findViewById(R.id.tez_manual_utr_msg)
    }

    private fun setupUI(v: View) {
        // ── Header color ──────────────────────────────────────────────
        val headerColor = try { Color.parseColor(settings.headerColor) }
                          catch (e: Exception) { Color.parseColor("#c800b2") }

        val bodyColor = try { Color.parseColor(settings.bodyColor) }
                        catch (e: Exception) { Color.WHITE }

        val themeId = if (settings.theme in 1..4) settings.theme else 1
        applyTheme(v, themeId, headerColor, bodyColor)

        // ── Amount card ───────────────────────────────────────────────
        if (paymentData.amount.isBlank()) {
            amountSection.visibility = View.GONE
        } else {
            amountSection.visibility = View.VISIBLE
            v.findViewById<TextView>(R.id.tez_amount_label).text = paymentData.amount
        }

        // ── UPI button text color: contrast against body color ────────
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
                if (themeId != 3) {
                    strokeColor = csl
                    setTextColor(headerColor)
                }
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

        // Toggle no-UPI warning if all UPI buttons are GONE
        val hasAnyInstalledUpi = listOf(
            R.id.btn_gpay, R.id.btn_phonepe, R.id.btn_paytm,
            R.id.btn_bhim, R.id.btn_amazonpay, R.id.btn_cred
        ).any { id ->
            v.findViewById<View>(id)?.visibility == View.VISIBLE
        }
        v.findViewById<View>(R.id.tez_no_upi_warning)?.visibility =
            if (hasAnyInstalledUpi) View.GONE else View.VISIBLE

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
                if (themeId == 1 || themeId == 2) {
                    btnQrPaid.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(headerColor)
                }

                // In manual mode, the user must submit UTR. Hide the "I've Paid" check status button.
                if (settings.method.equals("Manual", ignoreCase = true)) {
                    btnQrPaid.visibility = View.GONE
                } else {
                    btnQrPaid.visibility = View.VISIBLE
                    // ── QR BUG FIX: tap "I've Paid" → directly start status polling ──
                    btnQrPaid.setOnClickListener {
                        showCheckingState()
                        startPolling()
                    }
                }
            } catch (e: Exception) {
                qrSection.visibility = View.GONE
                orDivider.visibility = View.GONE
            }
        } else {
            qrSection.visibility = View.GONE
            orDivider.visibility = View.GONE
        }

        // ── Manual UTR section ─────────────────────────────────────────
        if (settings.method.equals("Manual", ignoreCase = true)) {
            manualUtrSection.visibility = View.VISIBLE
            btnManualUtrSubmit.setOnClickListener {
                submitManualUtr()
            }
        } else {
            manualUtrSection.visibility = View.GONE
        }

        // ── Cancel button ─────────────────────────────────────────────
        btnCancel.setOnClickListener {
            if (resultDelivered) {
                dismiss()
                return@setOnClickListener
            }
            
            if (btnCancel.text.toString().equals("I'll Check Later", ignoreCase = true)) {
                pollingService?.stopPolling()
                timerJob?.cancel()
                callback.onPaymentPending(orderId)
                dismiss()
                return@setOnClickListener
            }

            pollingService?.stopPolling()
            timerJob?.cancel()

            // Disable button while cancel API is in-flight
            btnCancel.isEnabled = false
            btnCancel.text = "Cancelling..."

            // Standalone scope — lifecycleScope gets cancelled by dismiss() before HTTP call completes
            CoroutineScope(Dispatchers.IO).launch {
                val cancelled = SettingsClient.cancelOrder(baseUrl, userToken, orderId)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    if (cancelled) {
                        // Server confirmed cancellation
                        callback.onPaymentFailed(orderId, "User cancelled")
                        dismiss()
                    } else {
                        // Cancel failed — order may already be settled, recheck status
                        btnCancel.isEnabled = true
                        btnCancel.text = "Close"
                        showCheckingState()
                        startPolling()
                    }
                }
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
        val cached = if (url == LOGO_URL) cachedLogo else if (url == SHIELD_URL) cachedShield else null
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = okhttp3.OkHttpClient()
                    .newCall(okhttp3.Request.Builder().url(url).build())
                    .execute().body?.bytes() ?: return@launch
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    if (url == LOGO_URL) cachedLogo = bitmap
                    else if (url == SHIELD_URL) cachedShield = bitmap
                    withContext(Dispatchers.Main) { imageView.setImageBitmap(bitmap) }
                }
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
        resultSubtitle.text = subtitle

        val themeId = if (settings.theme in 1..4) settings.theme else 1
        when (themeId) {
            2 -> {
                // Theme 2: Dark Cyberpunk
                val (bg, stroke, text) = when (iconText) {
                    "✓" -> Triple(0xFF152B1E.toInt(), 0xFF2E7D32.toInt(), 0xFF4ADE80.toInt())
                    "✗" -> Triple(0xFF2D1C1C.toInt(), 0xFFEF4444.toInt(), 0xFFFCA5A5.toInt())
                    else -> Triple(0xFF2E251E.toInt(), 0xFFE65100.toInt(), 0xFFFDBA74.toInt())
                }
                setRoundedBackground(resultCard, bg, stroke, 1f, 16f)
                resultTitle.setTextColor(text)
                resultSubtitle.setTextColor(0xFF8E8EA8.toInt())
            }
            3 -> {
                // Theme 3: Neo-Brutalism
                val text = when (iconText) {
                    "✓" -> 0xFF2E7D32.toInt()
                    "✗" -> 0xFFC62828.toInt()
                    else -> 0xFFE65100.toInt()
                }
                setRoundedBackground(resultCard, Color.WHITE, Color.BLACK, 2.5f, 8f)
                resultTitle.setTextColor(text)
                resultSubtitle.setTextColor(Color.BLACK)
            }
            4 -> {
                // Theme 4: Royal Gold
                val text = when (iconText) {
                    "✓" -> 0xFFE6C280.toInt()
                    "✗" -> 0xFFEF4444.toInt()
                    else -> 0xFFE6C280.toInt()
                }
                setRoundedBackground(resultCard, 0xFF1D1916.toInt(), 0xFFD4AF37.toInt(), 1f, 16f)
                resultTitle.setTextColor(text)
                resultSubtitle.setTextColor(0xFF8C7D70.toInt())
            }
            else -> {
                // Theme 1: Classic
                resultCard.setBackgroundColor(cardBgColor)
                resultTitle.setTextColor(titleColor)
                resultSubtitle.setTextColor(0xFF555555.toInt())
            }
        }

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
            if (!settings.method.equals("Manual", ignoreCase = true)) {
                showCheckingState()
                startPolling()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollingService?.stopPolling()
        timerJob?.cancel()
    }

    private fun applyTheme(v: View, themeId: Int, headerColor: Int, bodyColor: Int) {
        val root = v.findViewById<View>(R.id.tez_bottom_sheet_root)
        val dragHandle = v.findViewById<View>(R.id.tez_drag_handle)
        val headerBar = v.findViewById<View>(R.id.tez_header_bar)
        val headerTitle = v.findViewById<TextView>(R.id.tez_header_title)
        val headerSubtitle = v.findViewById<TextView>(R.id.tez_header_subtitle)
        val amountSection = v.findViewById<View>(R.id.tez_amount_section)
        val amountLabelTitle = v.findViewById<TextView>(R.id.tez_amount_label_title)
        val amountLabel = v.findViewById<TextView>(R.id.tez_amount_label)
        val payUpiTitle = v.findViewById<TextView>(R.id.tez_pay_upi_title)
        val trustRow = v.findViewById<LinearLayout>(R.id.tez_trust_row)
        val qrSection = v.findViewById<View>(R.id.tez_qr_section)
        val qrDescription = v.findViewById<TextView>(R.id.tez_qr_description)
        val btnQrPaid = v.findViewById<Button>(R.id.btn_qr_paid)
        val checkingSection = v.findViewById<View>(R.id.tez_checking_section)
        val statusMessage = v.findViewById<TextView>(R.id.tez_status_message)
        val statusTimer = v.findViewById<TextView>(R.id.tez_status_timer)
        val btnCheckStatus = v.findViewById<Button>(R.id.btn_check_status)
        val btnCancel = v.findViewById<Button>(R.id.btn_cancel)
        val lockBadge = v.findViewById<View>(R.id.tez_header_lock_badge)
        val noUpiWarning = v.findViewById<TextView>(R.id.tez_no_upi_warning)

        when (themeId) {
            1 -> {
                root?.setBackgroundColor(bodyColor)
                headerBar?.setBackgroundColor(headerColor)
                headerTitle?.setTextColor(Color.WHITE)
                headerSubtitle?.setTextColor(0xCCFFFFFF.toInt())
                
                if (amountSection != null) {
                    setGradientBackground(
                        view = amountSection,
                        startColor = 0xFFF7E6FF.toInt(),
                        endColor = 0xFFFDFAFF.toInt(),
                        orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        strokeColor = 0xFFDDB8FF.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 20f
                    )
                }
                amountLabelTitle?.setTextColor(0xFF9E00A0.toInt())
                amountLabel?.setTextColor(0xFF1A0030.toInt())
                payUpiTitle?.setTextColor(0xFF888888.toInt())
                
                if (trustRow != null) {
                    setRoundedBackground(
                        view = trustRow,
                        bgColor = 0xFFF6FBF7.toInt(),
                        strokeColor = 0xFFE0F2E9.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 10f
                    )
                    for (i in 0 until trustRow.childCount) {
                        val child = trustRow.getChildAt(i)
                        if (child is TextView) {
                            if (child.text.toString().contains("·")) {
                                child.setTextColor(0xFFA5D6A7.toInt())
                            } else {
                                child.setTextColor(0xFF2E7D32.toInt())
                            }
                        }
                    }
                }
                
                if (qrSection != null) {
                    setRoundedBackground(
                        view = qrSection,
                        bgColor = 0xFFFAFAFA.toInt(),
                        strokeColor = 0xFFE0E0E0.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 16f
                    )
                }
                qrDescription?.setTextColor(0xFF999999.toInt())
                btnQrPaid?.backgroundTintList = android.content.res.ColorStateList.valueOf(headerColor)
                btnQrPaid?.setTextColor(Color.WHITE)

                if (manualUtrSection != null) {
                    setRoundedBackground(
                        view = manualUtrSection,
                        bgColor = 0xFFFAFAFA.toInt(),
                        strokeColor = 0xFFE0E0E0.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 16f
                    )
                }
                if (manualUtrInput != null) {
                    setRoundedBackground(
                        view = manualUtrInput,
                        bgColor = 0xFFF8FAFC.toInt(),
                        strokeColor = 0xFFE2E8F0.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 10f
                    )
                    manualUtrInput.setTextColor(0xFF1A0030.toInt())
                    manualUtrInput.setHintTextColor(0xFF999999.toInt())
                }
                btnManualUtrSubmit?.backgroundTintList = android.content.res.ColorStateList.valueOf(headerColor)
                btnManualUtrSubmit?.setTextColor(Color.WHITE)
                
                if (checkingSection != null) {
                    setRoundedBackground(
                        view = checkingSection,
                        bgColor = 0xFFFAFAFA.toInt(),
                        strokeColor = 0xFFE0E0E0.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 16f
                    )
                }
                statusMessage?.setTextColor(0xFF1A0030.toInt())
                statusTimer?.setTextColor(0xFFAAAAAA.toInt())
                
                if (btnCancel != null) {
                    setRoundedBackground(
                        view = btnCancel,
                        bgColor = 0xFFFFFCFC.toInt(),
                        strokeColor = 0xFFEF4444.toInt(),
                        strokeWidthDp = 1.5f,
                        cornerRadiusDp = 14f
                    )
                }
                btnCancel?.setTextColor(0xFFEF4444.toInt())

                if (noUpiWarning != null) {
                    setRoundedBackground(
                        view = noUpiWarning,
                        bgColor = 0xFFFFEBEE.toInt(),
                        strokeColor = 0xFFFFCDD2.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 12f
                    )
                    noUpiWarning.setTextColor(0xFFC62828.toInt())
                }
            }
            2 -> {
                root?.setBackgroundColor(0xFF0E0E18.toInt())
                headerBar?.setBackgroundColor(0xFF08080F.toInt())
                headerTitle?.setTextColor(Color.WHITE)
                headerSubtitle?.setTextColor(0xFF8E8EA8.toInt())
                dragHandle?.setBackgroundColor(0xFF3E3E50.toInt())

                if (amountSection != null) {
                    setGradientBackground(
                        view = amountSection,
                        startColor = 0x1FFFFFFF.toInt(),
                        endColor = 0x0DFFFFFF.toInt(),
                        orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        strokeColor = 0x33FFFFFF.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 20f
                    )
                }
                amountLabelTitle?.setTextColor(headerColor)
                amountLabel?.setTextColor(Color.WHITE)
                payUpiTitle?.setTextColor(0xFF8E8EA8.toInt())

                if (trustRow != null) {
                    setRoundedBackground(
                        view = trustRow,
                        bgColor = 0x1A4ADE80.toInt(),
                        strokeColor = 0x334ADE80.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 10f
                    )
                    for (i in 0 until trustRow.childCount) {
                        val child = trustRow.getChildAt(i)
                        if (child is TextView) {
                            if (child.text.toString().contains("·")) {
                                child.setTextColor(0xFF1B5E20.toInt())
                            } else {
                                child.setTextColor(0xFF4ADE80.toInt())
                            }
                        }
                    }
                }

                if (qrSection != null) {
                    setRoundedBackground(
                        view = qrSection,
                        bgColor = 0xFF151522.toInt(),
                        strokeColor = 0x33FFFFFF.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 16f
                    )
                }
                qrDescription?.setTextColor(0xFF8E8EA8.toInt())
                btnQrPaid?.backgroundTintList = android.content.res.ColorStateList.valueOf(headerColor)
                btnQrPaid?.setTextColor(Color.WHITE)

                if (manualUtrSection != null) {
                    setRoundedBackground(
                        view = manualUtrSection,
                        bgColor = 0xFF151522.toInt(),
                        strokeColor = 0x33FFFFFF.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 16f
                    )
                }
                if (manualUtrInput != null) {
                    setRoundedBackground(
                        view = manualUtrInput,
                        bgColor = 0xFF0E0E18.toInt(),
                        strokeColor = 0x33FFFFFF.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 10f
                    )
                    manualUtrInput.setTextColor(Color.WHITE)
                    manualUtrInput.setHintTextColor(0xFF8E8EA8.toInt())
                }
                btnManualUtrSubmit?.backgroundTintList = android.content.res.ColorStateList.valueOf(headerColor)
                btnManualUtrSubmit?.setTextColor(Color.WHITE)

                if (checkingSection != null) {
                    setRoundedBackground(
                        view = checkingSection,
                        bgColor = 0xFF151522.toInt(),
                        strokeColor = 0x33FFFFFF.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 16f
                    )
                }
                statusMessage?.setTextColor(Color.WHITE)
                statusTimer?.setTextColor(0xFF8E8EA8.toInt())

                if (btnCancel != null) {
                    setRoundedBackground(
                        view = btnCancel,
                        bgColor = 0x2AEF4444.toInt(),
                        strokeColor = 0xFFEF4444.toInt(),
                        strokeWidthDp = 1.5f,
                        cornerRadiusDp = 14f
                    )
                }
                btnCancel?.setTextColor(0xFFEF4444.toInt())

                val upiButtons = listOf(R.id.btn_gpay, R.id.btn_phonepe, R.id.btn_paytm,
                                        R.id.btn_bhim, R.id.btn_amazonpay, R.id.btn_cred)
                upiButtons.forEach { id ->
                    val btn = v.findViewById<com.google.android.material.button.MaterialButton>(id) ?: return@forEach
                    btn.setTextColor(Color.WHITE)
                    setRoundedBackground(
                        view = btn,
                        bgColor = 0x1AFFFFFF.toInt(),
                        strokeColor = 0x33FFFFFF.toInt(),
                        strokeWidthDp = 1.5f,
                        cornerRadiusDp = 16f
                    )
                }

                if (noUpiWarning != null) {
                    setRoundedBackground(
                        view = noUpiWarning,
                        bgColor = 0x1AEF4444.toInt(),
                        strokeColor = 0xFFEF4444.toInt(),
                        strokeWidthDp = 1.5f,
                        cornerRadiusDp = 16f
                    )
                    noUpiWarning.setTextColor(0xFFFCA5A5.toInt())
                }
            }
            3 -> {
                root?.setBackgroundColor(0xFFFAF9F6.toInt())
                headerBar?.setBackgroundColor(Color.WHITE)
                headerTitle?.setTextColor(Color.BLACK)
                headerSubtitle?.setTextColor(0xFF555555.toInt())
                dragHandle?.setBackgroundColor(Color.BLACK)
                
                if (lockBadge != null) {
                    setRoundedBackground(
                        view = lockBadge,
                        bgColor = Color.WHITE,
                        strokeColor = Color.BLACK,
                        strokeWidthDp = 2f,
                        cornerRadiusDp = 4f
                    )
                }

                if (amountSection != null) {
                    setRoundedBackground(
                        view = amountSection,
                        bgColor = Color.WHITE,
                        strokeColor = Color.BLACK,
                        strokeWidthDp = 2.5f,
                        cornerRadiusDp = 8f
                    )
                }
                amountLabelTitle?.setTextColor(Color.BLACK)
                amountLabel?.setTextColor(Color.BLACK)
                payUpiTitle?.setTextColor(Color.BLACK)

                if (trustRow != null) {
                    setRoundedBackground(
                        view = trustRow,
                        bgColor = Color.WHITE,
                        strokeColor = Color.BLACK,
                        strokeWidthDp = 2.5f,
                        cornerRadiusDp = 8f
                    )
                    for (i in 0 until trustRow.childCount) {
                        val child = trustRow.getChildAt(i)
                        if (child is TextView) {
                            child.setTextColor(Color.BLACK)
                        }
                    }
                }

                if (qrSection != null) {
                    setRoundedBackground(
                        view = qrSection,
                        bgColor = Color.WHITE,
                        strokeColor = Color.BLACK,
                        strokeWidthDp = 2.5f,
                        cornerRadiusDp = 8f
                    )
                }
                qrDescription?.setTextColor(0xFF555555.toInt())
                btnQrPaid?.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00FF66.toInt())
                btnQrPaid?.setTextColor(Color.BLACK)
                if (btnQrPaid != null) {
                    setRoundedBackground(
                        view = btnQrPaid,
                        bgColor = 0xFF00FF66.toInt(),
                        strokeColor = Color.BLACK,
                        strokeWidthDp = 2.5f,
                        cornerRadiusDp = 8f
                    )
                }

                if (manualUtrSection != null) {
                    setRoundedBackground(
                        view = manualUtrSection,
                        bgColor = Color.WHITE,
                        strokeColor = Color.BLACK,
                        strokeWidthDp = 2.5f,
                        cornerRadiusDp = 8f
                    )
                }
                if (manualUtrInput != null) {
                    setRoundedBackground(
                        view = manualUtrInput,
                        bgColor = Color.WHITE,
                        strokeColor = Color.BLACK,
                        strokeWidthDp = 2.5f,
                        cornerRadiusDp = 8f
                    )
                    manualUtrInput.setTextColor(Color.BLACK)
                    manualUtrInput.setHintTextColor(0xFF555555.toInt())
                }
                if (btnManualUtrSubmit != null) {
                    setRoundedBackground(
                        view = btnManualUtrSubmit,
                        bgColor = 0xFF00FF66.toInt(),
                        strokeColor = Color.BLACK,
                        strokeWidthDp = 2.5f,
                        cornerRadiusDp = 8f
                    )
                    btnManualUtrSubmit.setTextColor(Color.BLACK)
                }

                if (checkingSection != null) {
                    setRoundedBackground(
                        view = checkingSection,
                        bgColor = Color.WHITE,
                        strokeColor = Color.BLACK,
                        strokeWidthDp = 2.5f,
                        cornerRadiusDp = 8f
                    )
                }
                statusMessage?.setTextColor(Color.BLACK)
                statusTimer?.setTextColor(0xFF555555.toInt())

                if (btnCheckStatus != null) {
                    setRoundedBackground(
                        view = btnCheckStatus,
                        bgColor = Color.WHITE,
                        strokeColor = Color.BLACK,
                        strokeWidthDp = 2.5f,
                        cornerRadiusDp = 8f
                    )
                    btnCheckStatus.setTextColor(Color.BLACK)
                }

                if (btnCancel != null) {
                    setRoundedBackground(
                        view = btnCancel,
                        bgColor = 0xFFEF4444.toInt(),
                        strokeColor = Color.BLACK,
                        strokeWidthDp = 2.5f,
                        cornerRadiusDp = 8f
                    )
                }
                btnCancel?.setTextColor(Color.WHITE)

                val upiButtons = listOf(R.id.btn_gpay, R.id.btn_phonepe, R.id.btn_paytm,
                                        R.id.btn_bhim, R.id.btn_amazonpay, R.id.btn_cred)
                upiButtons.forEach { id ->
                    val btn = v.findViewById<com.google.android.material.button.MaterialButton>(id) ?: return@forEach
                    btn.setTextColor(Color.BLACK)
                    setRoundedBackground(
                        view = btn,
                        bgColor = Color.WHITE,
                        strokeColor = Color.BLACK,
                        strokeWidthDp = 2.5f,
                        cornerRadiusDp = 8f
                    )
                }

                if (noUpiWarning != null) {
                    setRoundedBackground(
                        view = noUpiWarning,
                        bgColor = Color.WHITE,
                        strokeColor = Color.BLACK,
                        strokeWidthDp = 2.5f,
                        cornerRadiusDp = 8f
                    )
                    noUpiWarning.setTextColor(0xFFC62828.toInt())
                }
            }
            4 -> {
                root?.setBackgroundColor(0xFF121212.toInt())
                if (headerBar != null) {
                    setGradientBackground(
                        view = headerBar,
                        startColor = 0xFF1A1612.toInt(),
                        endColor = 0xFF3D3020.toInt(),
                        orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                    )
                }
                headerTitle?.setTextColor(0xFFD4AF37.toInt())
                headerSubtitle?.setTextColor(0xFFCDB38C.toInt())
                dragHandle?.setBackgroundColor(0xFFD4AF37.toInt())

                if (amountSection != null) {
                    setRoundedBackground(
                        view = amountSection,
                        bgColor = 0xFF1D1916.toInt(),
                        strokeColor = 0xFFD4AF37.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 20f
                    )
                }
                amountLabelTitle?.setTextColor(0xFFD4AF37.toInt())
                amountLabel?.setTextColor(0xFFF5EAD6.toInt())
                payUpiTitle?.setTextColor(0xFFA3907F.toInt())

                if (trustRow != null) {
                    setRoundedBackground(
                        view = trustRow,
                        bgColor = 0xFF1D1916.toInt(),
                        strokeColor = 0xFFAA7C11.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 10f
                    )
                    for (i in 0 until trustRow.childCount) {
                        val child = trustRow.getChildAt(i)
                        if (child is TextView) {
                            if (child.text.toString().contains("·")) {
                                child.setTextColor(0xFFAA7C11.toInt())
                            } else {
                                child.setTextColor(0xFFE6C280.toInt())
                            }
                        }
                    }
                }

                if (qrSection != null) {
                    setRoundedBackground(
                        view = qrSection,
                        bgColor = 0xFF1D1916.toInt(),
                        strokeColor = 0xFFD4AF37.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 16f
                    )
                }
                qrDescription?.setTextColor(0xFF8C7D70.toInt())
                btnQrPaid?.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFD4AF37.toInt())
                btnQrPaid?.setTextColor(0xFF121212.toInt())
                if (btnQrPaid != null) {
                    setRoundedBackground(
                        view = btnQrPaid,
                        bgColor = 0xFFD4AF37.toInt(),
                        strokeColor = Color.TRANSPARENT,
                        strokeWidthDp = 0f,
                        cornerRadiusDp = 16f
                    )
                }

                if (manualUtrSection != null) {
                    setRoundedBackground(
                        view = manualUtrSection,
                        bgColor = 0xFF1D1916.toInt(),
                        strokeColor = 0xFFD4AF37.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 16f
                    )
                }
                if (manualUtrInput != null) {
                    setRoundedBackground(
                        view = manualUtrInput,
                        bgColor = 0xFF121212.toInt(),
                        strokeColor = 0xFFD4AF37.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 10f
                    )
                    manualUtrInput.setTextColor(0xFFF5EAD6.toInt())
                    manualUtrInput.setHintTextColor(0xFF8C7D70.toInt())
                }
                if (btnManualUtrSubmit != null) {
                    setRoundedBackground(
                        view = btnManualUtrSubmit,
                        bgColor = 0xFFD4AF37.toInt(),
                        strokeColor = Color.TRANSPARENT,
                        strokeWidthDp = 0f,
                        cornerRadiusDp = 16f
                    )
                    btnManualUtrSubmit.setTextColor(0xFF121212.toInt())
                }

                if (checkingSection != null) {
                    setRoundedBackground(
                        view = checkingSection,
                        bgColor = 0xFF1D1916.toInt(),
                        strokeColor = 0xFFD4AF37.toInt(),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 16f
                    )
                }
                statusMessage?.setTextColor(0xFFF5EAD6.toInt())
                statusTimer?.setTextColor(0xFF8C7D70.toInt())

                if (btnCheckStatus != null) {
                    setRoundedBackground(
                        view = btnCheckStatus,
                        bgColor = 0xFF1D1916.toInt(),
                        strokeColor = 0xFFD4AF37.toInt(),
                        strokeWidthDp = 1.5f,
                        cornerRadiusDp = 16f
                    )
                    btnCheckStatus.setTextColor(0xFFD4AF37.toInt())
                }

                if (btnCancel != null) {
                    setRoundedBackground(
                        view = btnCancel,
                        bgColor = 0xFF1D1916.toInt(),
                        strokeColor = 0xFFEF4444.toInt(),
                        strokeWidthDp = 1.5f,
                        cornerRadiusDp = 16f
                    )
                }
                btnCancel?.setTextColor(0xFFEF4444.toInt())

                val upiButtons = listOf(R.id.btn_gpay, R.id.btn_phonepe, R.id.btn_paytm,
                                        R.id.btn_bhim, R.id.btn_amazonpay, R.id.btn_cred)
                upiButtons.forEach { id ->
                    val btn = v.findViewById<com.google.android.material.button.MaterialButton>(id) ?: return@forEach
                    btn.setTextColor(0xFFE6C280.toInt())
                    setRoundedBackground(
                        view = btn,
                        bgColor = 0xFF1D1916.toInt(),
                        strokeColor = 0xFFAA7C11.toInt(),
                        strokeWidthDp = 1.5f,
                        cornerRadiusDp = 16f
                    )
                }

                if (noUpiWarning != null) {
                    setRoundedBackground(
                        view = noUpiWarning,
                        bgColor = 0xFF1D1916.toInt(),
                        strokeColor = 0xFFD4AF37.toInt(),
                        strokeWidthDp = 1.5f,
                        cornerRadiusDp = 16f
                    )
                    noUpiWarning.setTextColor(0xFFE6C280.toInt())
                }
            }
        }
    }

    private fun setRoundedBackground(
        view: View,
        bgColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidthDp: Float = 0f,
        cornerRadiusDp: Float = 0f
    ) {
        val density = resources.displayMetrics.density
        val strokeWidthPx = (strokeWidthDp * density).toInt()
        val cornerRadiusPx = cornerRadiusDp * density

        val drawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(bgColor)
            setCornerRadius(cornerRadiusPx)
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
        view.background = drawable
    }

    private fun setGradientBackground(
        view: View,
        startColor: Int,
        endColor: Int,
        orientation: android.graphics.drawable.GradientDrawable.Orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidthDp: Float = 0f,
        cornerRadiusDp: Float = 0f
    ) {
        val density = resources.displayMetrics.density
        val strokeWidthPx = (strokeWidthDp * density).toInt()
        val cornerRadiusPx = cornerRadiusDp * density

        val drawable = android.graphics.drawable.GradientDrawable(
            orientation,
            intArrayOf(startColor, endColor)
        ).apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setCornerRadius(cornerRadiusPx)
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
        view.background = drawable
    }

    private fun submitManualUtr() {
        val utr = manualUtrInput.text.toString().trim()
        
        // Validation: length 12 to 30, alphanumeric only
        val utrRegex = Regex("^[A-Za-z0-9]{12,30}$")
        if (!utrRegex.matches(utr)) {
            showUtrMessage("Please enter a valid 12 to 30 character alphanumeric UTR", isError = true)
            return
        }

        // Hide message/clear state
        showUtrMessage("", isError = false)

        // Disable input and button to prevent double submission
        manualUtrInput.isEnabled = false
        btnManualUtrSubmit.isEnabled = false
        btnManualUtrSubmit.text = "..."

        lifecycleScope.launch(Dispatchers.IO) {
            val (success, message) = SettingsClient.submitUtr(
                baseUrl = baseUrl,
                userToken = userToken,
                orderId = orderId,
                utr = utr
            )
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                
                if (success) {
                    // Success, transition to checking state and start polling
                    showCheckingState()
                    startPolling()
                } else {
                    // Fail, re-enable views and show error message
                    manualUtrInput.isEnabled = true
                    btnManualUtrSubmit.isEnabled = true
                    btnManualUtrSubmit.text = "Submit"
                    showUtrMessage(message.ifBlank { "Failed to submit UTR. Please try again." }, isError = true)
                }
            }
        }
    }

    private fun showUtrMessage(msg: String, isError: Boolean) {
        if (msg.isBlank()) {
            manualUtrMsg.visibility = View.GONE
            return
        }
        manualUtrMsg.text = msg
        val themeId = if (settings.theme in 1..4) settings.theme else 1
        val color = when (themeId) {
            2 -> if (isError) 0xFFEF4444.toInt() else 0xFF4ADE80.toInt()
            3 -> if (isError) Color.RED else 0xFF2E7D32.toInt()
            4 -> if (isError) 0xFFEF4444.toInt() else 0xFFE6C280.toInt()
            else -> if (isError) Color.RED else 0xFF2E7D32.toInt()
        }
        manualUtrMsg.setTextColor(color)
        manualUtrMsg.visibility = View.VISIBLE
    }
}
