package com.tezgateway.sdk.ui

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tezgateway.sdk.R
import com.tezgateway.sdk.TezSession
import com.tezgateway.sdk.interfaces.TezPaymentCallback
import com.tezgateway.sdk.models.CheckoutSettings
import com.tezgateway.sdk.models.PaymentData
import com.tezgateway.sdk.network.SettingsClient
import com.tezgateway.sdk.network.StatusPollingService
import com.tezgateway.sdk.network.TezHttp
import com.tezgateway.sdk.utils.QrGenerator
import com.tezgateway.sdk.utils.SdkVersion
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
        private const val TAG = "TezCheckout"
        private const val GPAY_PACKAGE = "com.google.android.apps.nbu.paisa.user"

        private const val LOGO_URL   = "https://tezgateway.com/logo.png"
        private const val SHIELD_URL = "https://tezgateway.com/common/img/logoshild.png"

        /**
         * Even if the user took so long to pay that the server's own auto-match
         * window has already closed by the time checking starts, still give this
         * many seconds for one honest check — a payment that just genuinely
         * completed shouldn't show "Pending" in under this.
         */
        private const val MIN_AUTO_CHECK_SECONDS = 20

        /**
         * Legacy entry (≤ 1.0.30 showed the sheet directly on the merchant's Activity).
         * [com.tezgateway.sdk.TezGateway.startPayment] no longer uses it — it opens
         * [TezCheckoutActivity] instead — but it still works for anyone calling it.
         */
        fun newInstance(
            baseUrl: String,
            userToken: String,
            orderId: String,
            paymentData: PaymentData,
            settings: CheckoutSettings,
            callback: TezPaymentCallback
        ): TezCheckoutBottomSheet {
            TezSession.current = TezSession.Request(baseUrl, userToken, orderId, paymentData, callback)
                .also { it.settings = settings }
            return TezCheckoutBottomSheet()
        }

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
    /** True while a result card (success / failure / pending) is showing. */
    private var resultDelivered  = false
    private var timerJob: Job?   = null

    /** Last resolved outcome ("SUCCESS" | "FAILURE" | "PENDING") — sent to the merchant by [deliverResult]. */
    private var resolvedStatus: String? = null
    private var resolvedUtr    = ""
    private var resolvedReason = ""

    /** Decoded server QR (or null) — decoded off the main thread, re-used for the GPay share. */
    private var qrBitmap: Bitmap? = null
    /** QR file shared with GPay — removed (and its permission revoked) once the checkout ends. */
    private var sharedQrUri: Uri? = null

    /** Timer chip colour from the theme — restored after the "last 10s" red nudge. */
    private var timerDefaultColor: Int? = null

    // ── Effects (W7–W11) ──
    private var ring: TezCountdownRingView? = null
    private var ringArcColor: Int = Color.GRAY
    private var resultIcon: TezResultIconView? = null
    private var logoPulse: android.animation.Animator? = null
    private var pendingPulse: android.animation.Animator? = null
    private var qrDialog: Dialog? = null
    private var themeIdCurrent = 1
    private var headerColorCurrent = Color.GRAY

    /** Checking-card background/padding — removed while a result card shows (no card-in-card). */
    private var checkingCardBg: android.graphics.drawable.Drawable? = null
    private var checkingCardPadding: IntArray? = null

    /** Wall-clock time the sheet started showing — anchor for [computeAutoCheckBudgetSeconds]. */
    private var sheetShownAtMillis: Long = 0L

    /**
     * Poll budget (seconds) for the *current* checking session — read by both
     * startElapsedTimer() (visual countdown) and startPolling() (actual network
     * loop), so the two always agree. Defaults to the SDK's fixed constant;
     * recomputed from the server's own remaining auto-match window right before
     * the first genuine check of a payment attempt (see onResume() / the QR
     * "I've Paid" button in setupUI()).
     */
    private var currentCheckBudgetSeconds: Int = StatusPollingService.TIMEOUT_SECONDS

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
    private var versionTag:       TextView? = null   // nullable: absent in an old host app's cached/overridden layout
    private var spinnerWrap:      View? = null

    private lateinit var manualUtrSection: View
    private lateinit var manualUtrInput:   EditText
    private lateinit var btnManualUtrSubmit: Button
    private lateinit var manualUtrMsg:     TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val currentSettings = TezSession.current?.settings ?: CheckoutSettings()
        val themeId = if (currentSettings.theme in 1..4) currentSettings.theme else 1
        val layoutRes = when (themeId) {
            2 -> R.layout.bottomsheet_tez_checkout_theme2
            3 -> R.layout.bottomsheet_tez_checkout_theme3
            4 -> R.layout.bottomsheet_tez_checkout_theme4
            else -> R.layout.bottomsheet_tez_checkout
        }
        return inflater.inflate(layoutRes, container, false)
    }

    /**
     * The payment window closes only via the Cancel/Close button — not by swiping
     * it down, tapping outside, or the system back button. A payment sheet is easy
     * to dismiss by accident with any of those, which then reads to the merchant
     * app as an unresolved order even though the user never actually meant to
     * leave (and, worse, without the explicit-cancel flow that tells the server
     * to mark it FAILURE — see btnCancel's own click listener in setupUI()).
     *
     * Because it cannot be dragged, it must open fully expanded: left in the default
     * collapsed (peek-height) state, everything below the fold was unreachable. A
     * sheet taller than the screen is capped at the screen height and its middle
     * section (tez_scroll_view, 0dp + weight 1 in every theme layout) scrolls instead.
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setCanceledOnTouchOutside(false)
        // Keyboard shrinks the sheet (its middle scrolls) instead of covering the UTR box
        @Suppress("DEPRECATION")
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        (dialog as? BottomSheetDialog)?.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                BottomSheetBehavior.from(it).apply {
                    isDraggable   = false
                    skipCollapsed = true
                    state         = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Data lives in TezSession (not one-shot statics), so a re-created sheet still has it.
        // Nothing there = the payment already ended (or the process was restarted) → just close.
        val req = TezSession.current
        val reqSettings = req?.settings
        if (req == null || reqSettings == null || req.isFinished) {
            safeDismiss()
            return
        }
        paymentData = req.paymentData
        settings    = reqSettings
        callback    = req.callback          // once-only wrapper around the merchant's callback
        baseUrl     = req.baseUrl
        userToken   = req.userToken
        orderId     = req.orderId

        sheetShownAtMillis = System.currentTimeMillis()

        bindViews(view)
        setupUI(view)
    }

    /** Dismiss that never throws, even if the app went to background (onSaveInstanceState done). */
    private fun safeDismiss() {
        if (isAdded) dismissAllowingStateLoss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? TezCheckoutActivity)?.onSheetClosed()
    }

    override fun onDestroy() {
        // A result already shown but not yet sent (e.g. screen closed during the success
        // animation) still reaches the merchant — with the real status, not "pending".
        deliverResult()
        clearSharedQr()
        super.onDestroy()
    }

    /** Sends [resolvedStatus] to the merchant (no-op if nothing resolved; once-only via TezSession). */
    private fun deliverResult() {
        if (!::callback.isInitialized) return
        when (resolvedStatus) {
            "SUCCESS" -> callback.onPaymentSuccess(orderId, resolvedUtr)
            "FAILURE" -> callback.onPaymentFailed(orderId, resolvedReason.ifBlank { "Transaction failed" })
            "PENDING" -> callback.onPaymentPending(orderId)
        }
    }

    /**
     * How long the SDK should auto-check for THIS payment attempt, synced to the
     * server's own remaining auto-match window instead of always using the fixed
     * default — see settings.manualUtrRevealInSeconds (computed server-side from
     * StatusCheckService.matchingWindowSeconds() + the order's real creation time).
     *
     * Falls back to the fixed default when the server doesn't send a value (Manual,
     * any reference/order-id-based provider like HDFC/PhonePe, or an older backend).
     * Floored at [MIN_AUTO_CHECK_SECONDS] so a slow payer whose window already
     * closed by the time they finish paying still gets one honest check.
     */
    private fun computeAutoCheckBudgetSeconds(): Int {
        val configuredAtFetch = settings.manualUtrRevealInSeconds
            ?: return StatusPollingService.TIMEOUT_SECONDS
        val elapsedSeconds = ((System.currentTimeMillis() - sheetShownAtMillis) / 1000).toInt()
        return (configuredAtFetch - elapsedSeconds).coerceAtLeast(MIN_AUTO_CHECK_SECONDS)
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
        versionTag      = v.findViewById<TextView?>(R.id.tez_version_tag)
        spinnerWrap     = v.findViewById(R.id.tez_spinner_wrap)

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
        timerDefaultColor = statusTimer.currentTextColor

        // The sheet can't be dragged (see onCreateDialog), so a drag handle only misleads
        v.findViewById<View>(R.id.tez_drag_handle)?.visibility = View.GONE
        roundSheetTop(v)

        themeIdCurrent     = themeId
        headerColorCurrent = headerColor
        setupCountdownRing(themeId, headerColor)
        setupResultIcon()

        // UTR box: keyboard "Done" submits; keep the box above the keyboard
        manualUtrInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (btnManualUtrSubmit.isEnabled) submitManualUtr()
                true
            } else false
        }
        manualUtrInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                v.findViewById<ScrollView>(R.id.tez_scroll_view)?.postDelayed({
                    if (isAdded) v.findViewById<ScrollView>(R.id.tez_scroll_view)
                        ?.smoothScrollTo(0, manualUtrSection.bottom)
                }, 300)
            }
        }

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
        // remove_branding hides only "Powered by" + logo — the badge itself stays,
        // so the SDK version / "update available" tag inside it keeps showing.
        val brandingVisibility = if (settings.remove_branding) View.GONE else View.VISIBLE
        v.findViewById<View>(R.id.tez_branding_badge)?.visibility = View.VISIBLE
        v.findViewById<View>(R.id.tez_powered_by_text)?.visibility = brandingVisibility
        brandingLogo.visibility = brandingVisibility

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
        v.findViewById<TextView>(R.id.tez_no_upi_warning)?.apply {
            visibility = if (hasAnyInstalledUpi) View.GONE else View.VISIBLE
            // Only point to the QR when a QR will actually be shown
            val qrShown = settings.show_qr && paymentData.qr_image.isNotBlank()
            text = when {
                // Non-VIP: the server switched the app buttons off — the phone is fine
                !settings.isVip && qrShown ->
                    "UPI app payment is not enabled for this merchant. Please scan the QR code below with any UPI app."
                !settings.isVip ->
                    "UPI app payment is not enabled for this merchant. Please contact the merchant to complete this payment."
                qrShown ->
                    "No supported UPI app (Google Pay, PhonePe, Paytm…) found on this phone. Please scan the QR code below with any UPI app."
                else ->
                    "No supported UPI app (Google Pay, PhonePe, Paytm…) found on this phone. Please install a UPI app to complete this payment."
            }
        }

        // Grids keep a cell for every hidden (GONE) button → gaps between the visible ones.
        // Take hidden buttons out and size the grid to what is left.
        compactUpiGrid(v)

        // ── QR section ────────────────────────────────────────────────
        val qrSection    = v.findViewById<View>(R.id.tez_qr_section)
        val orDivider    = v.findViewById<View>(R.id.tez_or_divider)
        val qrImageView  = v.findViewById<ImageView>(R.id.tez_qr_image)
        val btnQrPaid    = v.findViewById<Button>(R.id.btn_qr_paid)

        // Hidden until the QR is decoded (off the main thread — no jank while the sheet opens)
        qrSection.visibility = View.GONE
        orDivider.visibility = View.GONE

        if (paymentData.qr_image.isNotBlank()) {
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.Default) { decodeServerQr() }
                qrBitmap = bitmap
                if (bitmap == null || !settings.show_qr || !isAdded) return@launch

                qrImageView.setImageBitmap(bitmap)
                qrSection.visibility = View.VISIBLE
                orDivider.visibility = View.VISIBLE

                // W11 — tap the QR → full-screen, max brightness (easy to scan from another phone)
                qrImageView.contentDescription = "Payment QR code. Tap to enlarge."
                qrImageView.setOnClickListener { showQrFullScreen(bitmap) }
                v.findViewById<TextView>(R.id.tez_qr_description)?.let {
                    it.text = "${it.text}  ·  Tap QR to enlarge"
                }

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
                        currentCheckBudgetSeconds = computeAutoCheckBudgetSeconds()
                        showCheckingState()
                        startPolling()
                    }
                }
            }
        }

        // ── Manual UTR section ─────────────────────────────────────────
        // Manual: always visible immediately (admin/human reviews whatever UTR
        // comes in, so there's no auto-match window to wait out).
        // Bharatpe/Mobikwik/Paynearby/Flipkart/Bankconnect: same fallback box,
        // but hidden until manualUtrRevealInSeconds — mirrors the web pay-page's
        // "only offer manual entry once the live auto-match window has closed".
        val revealDelay = settings.manualUtrRevealInSeconds
        when {
            settings.method.equals("Manual", ignoreCase = true) -> {
                manualUtrSection.visibility = View.VISIBLE
                btnManualUtrSubmit.setOnClickListener { submitManualUtr() }
            }
            revealDelay != null -> {
                manualUtrSection.visibility = View.GONE
                btnManualUtrSubmit.setOnClickListener { submitManualUtr() }
                scheduleManualUtrReveal(revealDelay)
            }
            else -> {
                manualUtrSection.visibility = View.GONE
            }
        }

        // ── Cancel button ─────────────────────────────────────────────
        btnCancel.setOnClickListener {
            if (resultDelivered) {
                // "Close" on a result card — send that result now (failure / pending)
                deliverResult()
                safeDismiss()
                return@setOnClickListener
            }

            if (btnCancel.text.toString().equals("I'll Check Later", ignoreCase = true)) {
                pollingService?.stopPolling()
                timerJob?.cancel()
                callback.onPaymentPending(orderId)
                safeDismiss()
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
                        safeDismiss()
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
        btnCheckStatus.setOnClickListener {
            if (resultDelivered && resolvedStatus == "PENDING") {
                // "Check Again" on the pending card → back to checking
                resultDelivered = false
                resolvedStatus  = null
                currentCheckBudgetSeconds = computeAutoCheckBudgetSeconds()
                showCheckingState()
                startPolling()
            } else {
                retriggerStatusCheck()
            }
        }

        // ── Logos ─────────────────────────────────────────────────────
        loadImageInto(LOGO_URL,   brandingLogo)
        loadImageInto(SHIELD_URL, spinnerLogo)

        applySdkVersionTag()
    }

    /**
     * Reveals the manual-UTR fallback box [afterSeconds] after settings were
     * fetched — mirrors the web pay-page's JS reveal timer. No-op if the sheet
     * is gone or the order already resolved by the time this fires (checking the
     * UTR box would be pointless once a result is already showing).
     */
    private fun scheduleManualUtrReveal(afterSeconds: Int) {
        lifecycleScope.launch {
            delay(afterSeconds.coerceAtLeast(0).toLong() * 1000)
            if (isAdded && !resultDelivered) {
                manualUtrSection.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Small "v1.0.25" caption near the branding logo; turns into an "update
     * available" hint when get_checkout_settings.php reports a newer version.
     * Cosmetic only — never blocks or alters the payment flow either way.
     */
    private fun applySdkVersionTag() {
        val tag = versionTag ?: return
        val updateAvailable = SdkVersion.isUpdateAvailable(settings.latestSdkVersion)
        if (updateAvailable) {
            tag.text = "⬆ v${SdkVersion.CURRENT} update available"
            tag.setTextColor(0xFFB45309.toInt()) // amber-700 — consistent across all themes, it's a system notice, not a theme colour
            tag.alpha = 1f
            setRoundedBackground(tag, bgColor = 0xFFFEF3C7.toInt(), cornerRadiusDp = 8f) // amber-100 pill
        } else {
            tag.text = "v${SdkVersion.CURRENT}"
            tag.background = null
        }
    }

    /**
     * Themes 1 & 3 lay UPI buttons out in a GridLayout, which still reserves a cell
     * for GONE children. Remove the hidden ones and use at most as many columns as
     * there are visible buttons (1 or 2 apps then fill the row instead of leaving holes).
     */
    private fun compactUpiGrid(v: View) {
        val grid = v.findViewById<View>(R.id.btn_gpay)?.parent as? GridLayout ?: return
        val maxColumns = grid.columnCount
        for (i in grid.childCount - 1 downTo 0) {
            val child = grid.getChildAt(i)
            if (child.visibility == View.GONE) grid.removeViewAt(i)
        }
        val visible = grid.childCount
        if (visible == 0) {
            grid.visibility = View.GONE
            return
        }
        grid.columnCount = minOf(maxColumns, visible).coerceAtLeast(1)
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
                val bytes = TezHttp.client
                    .newCall(okhttp3.Request.Builder().url(url).build())
                    .execute().use { it.body?.bytes() } ?: return@launch
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

    /** Decodes the server's Base64 `qr_image`; null if blank or invalid. Call off the main thread. */
    private fun decodeServerQr(): Bitmap? = try {
        val base64Data = paymentData.qr_image.substringAfter("base64,")
        if (base64Data.isBlank()) null
        else Base64.decode(base64Data, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    } catch (e: Exception) {
        Log.w(TAG, "Invalid qr_image: ${e.message}")
        null
    }

    /**
     * Google Pay accepts only a QR image (no deep link) — so share one:
     * the server's QR if present, otherwise one generated locally from the UPI link.
     */
    private fun shareQrToGpay() {
        val appCtx = requireContext().applicationContext

        // Decode / generate + write on a background thread to avoid ANR on slow devices
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = qrBitmap
                    ?: decodeServerQr()
                    ?: QrGenerator.upiUri(paymentData)?.let { QrGenerator.encode(it) }
                    ?: run {
                        withContext(Dispatchers.Main) {
                            toast("Google Pay is not available for this payment. Please choose another app.")
                        }
                        return@launch
                    }
                qrBitmap = bitmap

                // Delete any leftover tez_qr_*.png files from previous orders
                appCtx.cacheDir
                    .listFiles { f -> f.name.startsWith("tez_qr_") && f.name.endsWith(".png") }
                    ?.forEach { it.delete() }

                // Unique filename per order — never serve a stale cached QR
                val cacheFile = File(appCtx.cacheDir, "tez_qr_${orderId}.png")
                FileOutputStream(cacheFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

                val ctx       = appCtx
                val authority = "${ctx.packageName}.tezgateway.fileprovider"
                val uri       = FileProvider.getUriForFile(ctx, authority, cacheFile)
                sharedQrUri   = uri

                // Explicitly grant read permission to GPay's package — required on many
                // Android versions even when FLAG_GRANT_READ_URI_PERMISSION is set on the intent
                ctx.grantUriPermission(
                    GPAY_PACKAGE,
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
                    setPackage(GPAY_PACKAGE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // No FLAG_ACTIVITY_NEW_TASK — Fragment.startActivity() uses the host
                    // activity's task, which is correct and avoids task-stack side-effects
                }

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    try {
                        startActivity(shareIntent)
                        paymentLaunched = true
                    } catch (e: android.content.ActivityNotFoundException) {
                        toast("Google Pay is not installed on this phone.")
                    }
                }
            } catch (e: Exception) {
                // Technical detail goes to Logcat, the user gets a plain message
                Log.e(TAG, "Could not share QR to Google Pay", e)
                withContext(Dispatchers.Main) {
                    toast("Could not open Google Pay. Please scan the QR or choose another app.")
                }
            }
        }
    }

    /** Deletes the QR file shared with GPay and revokes GPay's read access to it. */
    private fun clearSharedQr() {
        val ctx = context?.applicationContext ?: return
        sharedQrUri?.let {
            try { ctx.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        }
        sharedQrUri = null
        try {
            ctx.cacheDir
                .listFiles { f -> f.name.startsWith("tez_qr_") && f.name.endsWith(".png") }
                ?.forEach { it.delete() }
        } catch (_: Exception) { }
    }

    private fun toast(msg: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    private fun launchUpiApp(app: UpiIntentHelper.UpiApp) {
        if (UpiIntentHelper.startPaymentIntent(requireContext(), app, paymentData)) {
            paymentLaunched = true
        } else {
            toast("${app.displayName} could not be opened. Please choose another app.")
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
        // Countdown ring replaces the plain spinner when available
        statusSpinner.visibility   = if (ring != null) View.GONE else View.VISIBLE
        spinnerWrap?.visibility    = View.VISIBLE
        ring?.reset()
        ring?.setArcColor(ringArcColor)
        stopPulse(pendingPulse, resultIconText); pendingPulse = null
        stopPulse(logoPulse, spinnerLogo)
        logoPulse = pulse(spinnerLogo, to = 1.08f, periodMs = 900)
        statusMessage.visibility   = View.VISIBLE
        statusTimer.visibility     = View.VISIBLE
        restoreCheckingCard()
        btnCheckStatus.visibility  = View.VISIBLE
        (btnCheckStatus as? TextView)?.text = "Check Status Now"
        statusMessage.text         = "Verifying your payment..."
        btnCancel.text             = "I'll Check Later"
        startElapsedTimer()
    }

    /**
     * Small visible countdown (v1: was an elapsed "Checking for Xs…" counter —
     * now counts DOWN to 0, matching the web pay-page's QR countdown pattern).
     * When it reaches zero, [performFinalStatusCheck] runs a definitive last
     * check before the sheet gives up, instead of silently relying on whichever
     * scheduled poll happened to land nearest the deadline.
     */
    private fun startElapsedTimer() {
        timerJob?.cancel()
        timerDefaultColor?.let { statusTimer.setTextColor(it) }   // undo a previous "last 10s" red
        // Set the first value right now — otherwise the layout's placeholder text flashes for a frame
        statusTimer.text = formatCountdown(currentCheckBudgetSeconds)
        timerJob = lifecycleScope.launch(Dispatchers.Main) {
            val total = currentCheckBudgetSeconds.coerceAtLeast(1)
            var remaining = currentCheckBudgetSeconds
            while (remaining > 0 && isActive) {
                statusTimer.text = formatCountdown(remaining)
                // Ring glides to the next second's position over this second
                ring?.animateTo((remaining - 1).toFloat() / total, 1000)
                // Last 10s — nudge the chip red, same "getting urgent" cue as the
                // web pay-page's QR countdown, so the user isn't caught off guard
                // when the final check (and possibly a close) is about to happen.
                if (remaining <= 10) {
                    statusTimer.setTextColor(0xFFEF4444.toInt())
                    ring?.setArcColor(0xFFEF4444.toInt())
                }
                delay(1000)
                remaining--
            }
            if (isActive && !resultDelivered) {
                statusTimer.text = formatCountdown(0)
                performFinalStatusCheck()
            }
        }
    }

    private fun formatCountdown(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "⏱ %d:%02d".format(m, s)
    }

    /**
     * One last direct status check right when the visible countdown hits zero —
     * mirrors the pay-page web flow's "final check before declaring timeout"
     * instead of only trusting whichever scheduled poll attempt happened last.
     * No-op if the order already resolved by the time this fires.
     */
    private fun performFinalStatusCheck() {
        if (resultDelivered) return
        pollingService?.stopPolling()
        statusMessage.text = "Doing one final check…"

        val poller = pollingService ?: StatusPollingService(
            baseUrl = baseUrl, userToken = userToken, orderId = orderId,
            callback = object : TezPaymentCallback {
                override fun onPaymentSuccess(orderId: String, utr: String) {}
                override fun onPaymentFailed(orderId: String, reason: String) {}
                override fun onPaymentPending(orderId: String) {}
            }
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val result = poller.checkOnce()
            withContext(Dispatchers.Main) {
                if (!isAdded || resultDelivered) return@withContext
                handleResolvedStatus(result.status, result.utr, result.message)
            }
        }
    }

    /**
     * Shared SUCCESS/FAILURE/PENDING UI handling — used by both the normal
     * polling callback ([startPolling]) and the final check ([performFinalStatusCheck]),
     * so the two never show inconsistent results for the same outcome.
     */
    private fun handleResolvedStatus(status: String, utr: String, reason: String = "") {
        when (status) {
            "SUCCESS" -> {
                resolvedStatus = "SUCCESS"
                resolvedUtr    = utr
                showResult(
                    iconText    = "✓",
                    title       = "Payment Successful",
                    subtitle    = if (utr.isNotBlank()) "UTR: $utr" else "Order: $orderId",
                    cardBgColor = 0xFFE8F5E9.toInt(),
                    titleColor  = 0xFF2E7D32.toInt()
                )
                btnCancel.visibility = View.GONE
                lifecycleScope.launch {
                    delay(2200)   // long enough for the ✓ + confetti to play (W8)
                    safeDismiss()
                    deliverResult()
                }
            }
            "FAILURE" -> {
                resolvedStatus = "FAILURE"
                resolvedReason = reason.ifBlank { "Transaction failed" }
                showResult(
                    iconText    = "✗",
                    title       = "Payment Failed",
                    subtitle    = resolvedReason,
                    cardBgColor = 0xFFFFEBEE.toInt(),
                    titleColor  = 0xFFC62828.toInt()
                )
                lifecycleScope.launch {
                    delay(2000)
                    safeDismiss()
                    deliverResult()
                }
            }
            else -> {
                // Pending: not sent yet — the user can "Check Again" or "Close"
                // (Close / screen exit sends onPaymentPending).
                resolvedStatus = "PENDING"
                showResult(
                    iconText    = "⏳",
                    title       = "Payment Pending",
                    subtitle    = "Your payment is being verified.\nPlease check your UPI app.",
                    cardBgColor = 0xFFFFF8E1.toInt(),
                    titleColor  = 0xFFE65100.toInt()
                )
                btnCheckStatus.visibility = View.VISIBLE
                (btnCheckStatus as? TextView)?.text = "Check Again"
            }
        }
    }

    private fun showResult(
        iconText: String, title: String, subtitle: String,
        cardBgColor: Int, titleColor: Int
    ) {
        resultDelivered = true
        timerJob?.cancel()

        // Hide the whole "checking" block (spinner frame + logo, message, timer chip) —
        // hiding only the spinner left an empty 72dp frame, the logo and an empty pill.
        statusSpinner.visibility  = View.GONE
        spinnerWrap?.visibility   = View.GONE
        ring?.stop()
        stopPulse(logoPulse, spinnerLogo); logoPulse = null
        qrDialog?.dismiss()
        btnCheckStatus.visibility = View.GONE
        statusMessage.text        = ""
        statusTimer.text          = ""
        statusMessage.visibility  = View.GONE
        statusTimer.visibility    = View.GONE
        flattenCheckingCard()

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
                // Theme 1: Classic — keep the rounded card (setBackgroundColor made it square)
                setRoundedBackground(
                    resultCard, cardBgColor,
                    strokeColor = (titleColor and 0x00FFFFFF) or 0x33000000,
                    strokeWidthDp = 1f, cornerRadiusDp = 16f
                )
                resultTitle.setTextColor(titleColor)
                resultSubtitle.setTextColor(0xFF555555.toInt())
            }
        }

        resultCard.visibility = View.VISIBLE
        btnCancel.text = "Close"
        playResultEffect(iconText)
    }

    // ── W7–W11 effects ─────────────────────────────────────────────────

    /** W7 — swap the plain spinner for a countdown ring in the theme's accent colour. */
    private fun setupCountdownRing(themeId: Int, headerColor: Int) {
        val wrap = spinnerWrap as? FrameLayout ?: return
        val ctx = context ?: return
        val size = (72 * resources.displayMetrics.density).toInt()

        // Very light header colour would vanish on light themes → use a darker shade of it
        val accent = if (luminance(headerColor) > 0.8) mixWithBlack(headerColor, 0.45f) else headerColor
        val (arc, track) = when (themeId) {
            2    -> accent to 0x26FFFFFF
            3    -> Color.BLACK to 0x1F000000
            4    -> 0xFFD4AF37.toInt() to 0x33D4AF37
            else -> accent to ((accent and 0x00FFFFFF) or 0x26000000)
        }
        ringArcColor = arc
        ring = TezCountdownRingView(ctx).apply { setColors(arc, track) }
        wrap.addView(ring, 0, FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER))
        statusSpinner.visibility = View.GONE
    }

    /** W8/W9 — animated ✓ / ✗ icon, placed where the text icon sits in every theme. */
    private fun setupResultIcon() {
        val parent = resultIconText.parent as? ViewGroup ?: return
        val ctx = context ?: return
        val size = (68 * resources.displayMetrics.density).toInt()
        val icon = TezResultIconView(ctx).apply { visibility = View.GONE }
        val lp = LinearLayout.LayoutParams(size, size).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = (10 * resources.displayMetrics.density).toInt()
        }
        parent.addView(icon, parent.indexOfChild(resultIconText), lp)
        resultIcon = icon
    }

    /** Success: circle pops, ✓ draws, confetti + haptic. Failure: ✗ draws, card shakes. Pending: ⏳ pulses. */
    private fun playResultEffect(iconText: String) {
        stopPulse(pendingPulse, resultIconText); pendingPulse = null
        val icon = resultIcon
        val success = iconText == "✓"
        val failure = iconText == "✗"

        if (icon == null || (!success && !failure)) {
            icon?.visibility = View.GONE
            resultIconText.visibility = View.VISIBLE
            if (!success && !failure) pendingPulse = pulse(resultIconText)   // W10
            return
        }

        resultIconText.visibility = View.GONE
        icon.visibility = View.VISIBLE
        icon.kind = if (success) TezResultIconView.Kind.SUCCESS else TezResultIconView.Kind.FAILURE

        when (themeIdCurrent) {
            2 -> icon.setColors(if (success) 0xFF22C55E.toInt() else 0xFFEF4444.toInt(), Color.WHITE)
            3 -> icon.setColors(if (success) 0xFF00C853.toInt() else 0xFFEF4444.toInt(), Color.BLACK, Color.BLACK)
            4 -> icon.setColors(if (success) 0xFFD4AF37.toInt() else 0xFFEF4444.toInt(),
                                if (success) 0xFF121212.toInt() else Color.WHITE)
            else -> icon.setColors(if (success) 0xFF2E7D32.toInt() else 0xFFC62828.toInt(), Color.WHITE)
        }

        // post → the icon is laid out, so its position for the confetti origin is known
        icon.post {
            if (!isAdded) return@post
            icon.play {
                if (success) {
                    hapticSuccess(icon)
                    val root = view ?: return@play
                    val (x, y) = positionIn(icon, root)
                    ConfettiDrawable.burst(root, x + icon.width / 2f, y + icon.height / 2f, confettiColors())
                } else {
                    hapticReject(icon)
                    shake(resultCard)
                }
            }
        }
    }

    /** Top-left of [child] relative to [ancestor]. */
    private fun positionIn(child: View, ancestor: View): Pair<Float, Float> {
        val c = IntArray(2); val a = IntArray(2)
        child.getLocationInWindow(c); ancestor.getLocationInWindow(a)
        return (c[0] - a[0]).toFloat() to (c[1] - a[1]).toFloat()
    }

    private fun confettiColors(): IntArray = when (themeIdCurrent) {
        2 -> intArrayOf(0xFF22D3EE.toInt(), 0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFF4ADE80.toInt(), 0xFFFACC15.toInt())
        3 -> intArrayOf(0xFF00C853.toInt(), 0xFFFFD600.toInt(), 0xFF2962FF.toInt(), 0xFFFF1744.toInt(), Color.BLACK)
        4 -> intArrayOf(0xFFD4AF37.toInt(), 0xFFF5D67B.toInt(), 0xFFAA7C11.toInt(), 0xFFF5EAD6.toInt())
        else -> intArrayOf(headerColorCurrent, 0xFF22C55E.toInt(), 0xFFFFC107.toInt(), 0xFF3B82F6.toInt(), 0xFFEC4899.toInt())
    }

    /** W11 — full-screen QR on a white page with the screen at full brightness. */
    private fun showQrFullScreen(bitmap: Bitmap) {
        val ctx = context ?: return
        qrDialog?.dismiss()
        val d = resources.displayMetrics.density
        val dm = resources.displayMetrics
        val side = (minOf(dm.widthPixels, dm.heightPixels) * 0.82f).toInt()
        fun dp(v: Int) = (v * d).toInt()

        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setOnClickListener { dialog.dismiss() }
        }
        if (paymentData.amount.isNotBlank()) {
            page.addView(TextView(ctx).apply {
                text = paymentData.amount
                textSize = 28f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF1A1A2E.toInt())
                gravity = android.view.Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(18) })
        }
        page.addView(ImageView(ctx).apply {
            // Nearest-neighbour scaling keeps the QR modules crisp when enlarged
            setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false })
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Payment QR code"
        }, LinearLayout.LayoutParams(side, side))
        page.addView(TextView(ctx).apply {
            text = "Scan with any UPI app"
            textSize = 15f
            setTextColor(0xFF555555.toInt())
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        page.addView(TextView(ctx).apply {
            text = "Close"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(36), dp(14), dp(36), dp(14))
            setRoundedBackground(this, 0xFF1A1A2E.toInt(), cornerRadiusDp = 14f)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })

        dialog.setContentView(page)
        dialog.window?.let { w ->
            w.attributes = w.attributes.apply { screenBrightness = 1f }   // only while this page is open
        }
        dialog.setOnDismissListener { if (qrDialog === dialog) qrDialog = null }
        qrDialog = dialog
        dialog.show()
    }

    /** Result card is the only card while a result shows — drop the outer checking card. */
    private fun flattenCheckingCard() {
        if (checkingCardPadding == null) {
            checkingCardBg = checkingSection.background
            checkingCardPadding = intArrayOf(
                checkingSection.paddingLeft, checkingSection.paddingTop,
                checkingSection.paddingRight, checkingSection.paddingBottom
            )
        }
        checkingSection.background = null
        checkingSection.setPadding(0, 0, 0, 0)
    }

    private fun restoreCheckingCard() {
        val p = checkingCardPadding ?: return
        checkingSection.background = checkingCardBg
        checkingSection.setPadding(p[0], p[1], p[2], p[3])
        checkingCardPadding = null
        checkingCardBg = null
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
                override fun onPaymentSuccess(orderId: String, utr: String) = handleResolvedStatus("SUCCESS", utr)
                override fun onPaymentFailed(orderId: String, reason: String) = handleResolvedStatus("FAILURE", "", reason)
                override fun onPaymentPending(orderId: String) = handleResolvedStatus("", "")
            },
            timeoutSeconds = currentCheckBudgetSeconds
        )
        pollingService?.startPolling(lifecycleScope)
    }

    /** After returning from a UPI app intent, start polling automatically. */
    override fun onResume() {
        super.onResume()
        if (paymentLaunched && !resultDelivered) {
            paymentLaunched = false
            if (!settings.method.equals("Manual", ignoreCase = true)) {
                currentCheckBudgetSeconds = computeAutoCheckBudgetSeconds()
                showCheckingState()
                startPolling()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollingService?.stopPolling()
        timerJob?.cancel()
        logoPulse?.cancel(); logoPulse = null
        pendingPulse?.cancel(); pendingPulse = null
        qrDialog?.dismiss(); qrDialog = null
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
                
                // Amount card follows the merchant's header colour (was always purple)
                if (amountSection != null) {
                    setGradientBackground(
                        view = amountSection,
                        startColor = mixWithWhite(headerColor, 0.90f),
                        endColor = mixWithWhite(headerColor, 0.97f),
                        orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        strokeColor = mixWithWhite(headerColor, 0.70f),
                        strokeWidthDp = 1f,
                        cornerRadiusDp = 20f
                    )
                }
                // Very light header colour would be unreadable as text → use a dark shade of it
                amountLabelTitle?.setTextColor(
                    if (luminance(headerColor) > 0.75) mixWithBlack(headerColor, 0.55f) else headerColor
                )
                amountLabel?.setTextColor(0xFF1A1A2E.toInt())
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
                statusTimer?.setTextColor(0xFF7A7A8C.toInt())
                statusTimer?.let { setRoundedBackground(it, bgColor = 0xFFF0F0F5.toInt(), cornerRadiusDp = 20f) }
                
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
                statusTimer?.setTextColor(0xFFB8B8CC.toInt())
                statusTimer?.let { setRoundedBackground(it, bgColor = 0x1AFFFFFF.toInt(), cornerRadiusDp = 20f) }

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
                statusTimer?.setTextColor(Color.BLACK)
                statusTimer?.let { setRoundedBackground(it, bgColor = Color.WHITE, strokeColor = Color.BLACK, strokeWidthDp = 2f, cornerRadiusDp = 6f) }

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
                statusTimer?.setTextColor(0xFFE6C280.toInt())
                statusTimer?.let { setRoundedBackground(it, bgColor = 0xFF1D1916.toInt(), strokeColor = 0xFFD4AF37.toInt(), strokeWidthDp = 1f, cornerRadiusDp = 20f) }

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
        applyCustomBackground(view, drawable)
    }

    /**
     * Sets [drawable] as [view]'s background.
     *
     * For buttons (every `<Button>` here is inflated as a MaterialButton): MaterialButton
     * re-applies its own backgroundTint onto a custom background (SRC_IN), which painted
     * the whole shape in one colour — fill AND border — e.g. Cancel became a solid
     * colorPrimary block and outlined buttons turned invisible. So the tint is cleared,
     * and a ripple is added back since a custom background has no touch feedback.
     */
    private fun applyCustomBackground(view: View, drawable: android.graphics.drawable.Drawable) {
        if (view is Button) {
            view.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22000000), drawable, null
            )
            view.backgroundTintList = null
        } else {
            view.background = drawable
        }
    }

    /**
     * Rounded top corners for the whole sheet (header included) — clips the root to a
     * rounded rect that extends past the bottom edge, so only the top corners round.
     */
    private fun roundSheetTop(root: View, radiusDp: Float = 20f) {
        val r = radiusDp * resources.displayMetrics.density
        root.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height + r.toInt(), r)
            }
        }
        root.clipToOutline = true
    }

    /** [color] blended toward white by [ratio] (0 = unchanged, 1 = white). */
    private fun mixWithWhite(color: Int, ratio: Float): Int = Color.rgb(
        (Color.red(color)   + (255 - Color.red(color))   * ratio).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * ratio).toInt(),
        (Color.blue(color)  + (255 - Color.blue(color))  * ratio).toInt()
    )

    /** [color] blended toward black by [ratio] (0 = unchanged, 1 = black). */
    private fun mixWithBlack(color: Int, ratio: Float): Int = Color.rgb(
        (Color.red(color)   * (1 - ratio)).toInt(),
        (Color.green(color) * (1 - ratio)).toInt(),
        (Color.blue(color)  * (1 - ratio)).toInt()
    )

    /** Perceived brightness 0..1. */
    private fun luminance(color: Int): Double =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0

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
        applyCustomBackground(view, drawable)
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
                    // Success, transition to checking state and start polling.
                    // Fixed budget here (not computeAutoCheckBudgetSeconds()) — this
                    // check is for the exact-UTR match, which isn't time-window bound,
                    // so it doesn't need to be synced to the server's auto-match window
                    // the way the very first automatic check does.
                    currentCheckBudgetSeconds = StatusPollingService.TIMEOUT_SECONDS
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
