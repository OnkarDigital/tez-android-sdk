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
import java.io.File
import java.io.FileOutputStream

/**
 * Native BottomSheet checkout UI for TezGateway SDK.
 * Dynamically renders based on CheckoutSettings fetched from the merchant's panel.
 */
class TezCheckoutBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_BASE_URL = "base_url"
        private const val ARG_USER_TOKEN = "user_token"
        private const val ARG_ORDER_ID = "order_id"

        fun newInstance(
            baseUrl: String,
            userToken: String,
            orderId: String,
            paymentData: PaymentData,
            settings: CheckoutSettings,
            callback: TezPaymentCallback
        ): TezCheckoutBottomSheet {
            // Store statically (safe for single-use SDK flow)
            pendingPaymentData = paymentData
            pendingSettings = settings
            pendingCallback = callback

            return TezCheckoutBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_BASE_URL, baseUrl)
                    putString(ARG_USER_TOKEN, userToken)
                    putString(ARG_ORDER_ID, orderId)
                }
            }
        }

        // Temporary holders (cleared after use)
        internal var pendingPaymentData: PaymentData? = null
        internal var pendingSettings: CheckoutSettings? = null
        internal var pendingCallback: TezPaymentCallback? = null
    }

    private lateinit var paymentData: PaymentData
    private lateinit var settings: CheckoutSettings
    private lateinit var callback: TezPaymentCallback
    private lateinit var baseUrl: String
    private lateinit var userToken: String
    private lateinit var orderId: String
    private var pollingService: StatusPollingService? = null
    private var paymentLaunched = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottomsheet_tez_checkout, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        paymentData = pendingPaymentData ?: run { dismiss(); return }
        settings = pendingSettings ?: run { dismiss(); return }
        callback = pendingCallback ?: run { dismiss(); return }
        baseUrl = arguments?.getString(ARG_BASE_URL) ?: ""
        userToken = arguments?.getString(ARG_USER_TOKEN) ?: ""
        orderId = arguments?.getString(ARG_ORDER_ID) ?: ""

        // Clear statics
        pendingPaymentData = null
        pendingSettings = null
        pendingCallback = null

        setupUI(view)
    }

    private fun setupUI(v: View) {
        // --- Header color from merchant settings ---
        val headerBar = v.findViewById<View>(R.id.tez_header_bar)
        try {
            headerBar.setBackgroundColor(Color.parseColor(settings.headerColor))
        } catch (e: Exception) {
            headerBar.setBackgroundColor(Color.parseColor("#c800b2"))
        }

        // --- News ticker ---
        val newsTicker = v.findViewById<TextView>(R.id.tez_news_ticker)
        if (settings.news.isNotBlank()) {
            newsTicker.text = settings.news
            newsTicker.isSelected = true // Enables marquee
            newsTicker.visibility = View.VISIBLE
        } else {
            newsTicker.visibility = View.GONE
        }

        // --- UPI App Buttons (only show installed apps allowed by settings) ---
        val btnGpay = v.findViewById<Button>(R.id.btn_gpay)
        val btnPhonepe = v.findViewById<Button>(R.id.btn_phonepe)
        val btnPaytm = v.findViewById<Button>(R.id.btn_paytm)
        val btnBhim = v.findViewById<Button>(R.id.btn_bhim)
        val btnAmazon = v.findViewById<Button>(R.id.btn_amazonpay)
        val btnCred = v.findViewById<Button>(R.id.btn_cred)

        val ctx = requireContext()

        // GPay — share QR image directly to GPay app
        if (settings.Show_GpayButton && UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.GOOGLE_PAY)) {
            btnGpay.visibility = View.VISIBLE
            btnGpay.setOnClickListener { shareQrToGpay() }
        } else btnGpay.visibility = View.GONE

        // PhonePe
        if (settings.show_phonepe && UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.PHONEPE)) {
            btnPhonepe.visibility = View.VISIBLE
            btnPhonepe.setOnClickListener { launchUpiApp(UpiIntentHelper.UpiApp.PHONEPE) }
        } else btnPhonepe.visibility = View.GONE

        // Paytm
        if (settings.show_paytmButton && UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.PAYTM)) {
            btnPaytm.visibility = View.VISIBLE
            btnPaytm.setOnClickListener { launchUpiApp(UpiIntentHelper.UpiApp.PAYTM) }
        } else btnPaytm.visibility = View.GONE

        // BHIM (always shown if installed and Show_IntentButton is on)
        if (settings.Show_IntentButton && UpiIntentHelper.isAppInstalled(ctx, UpiIntentHelper.UpiApp.BHIM)) {
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

        // --- QR Code section ---
        val qrSection = v.findViewById<LinearLayout>(R.id.tez_qr_section)
        val qrImageView = v.findViewById<ImageView>(R.id.tez_qr_image)

        if (settings.show_qr && paymentData.qr_image.isNotBlank()) {
            qrSection.visibility = View.VISIBLE
            try {
                val base64Data = paymentData.qr_image.substringAfter("base64,")
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                qrImageView.setImageBitmap(bitmap)
            } catch (e: Exception) {
                qrSection.visibility = View.GONE
            }
        } else {
            qrSection.visibility = View.GONE
        }

        // --- Cancel Button ---
        v.findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            pollingService?.stopPolling()
            callback.onPaymentFailed(orderId, "User cancelled")
            dismiss()
        }
    }

    /**
     * Decodes qr_image (base64 PNG) → saves to app cache → shares directly to Google Pay.
     * Falls back to gpay_link deep link if qr_image is unavailable.
     */
    private fun shareQrToGpay() {
        val qrBase64 = paymentData.qr_image
        if (qrBase64.isBlank()) {
            // No QR image — fall back to deep link
            launchUpiApp(UpiIntentHelper.UpiApp.GOOGLE_PAY)
            return
        }
        try {
            val base64Data = qrBase64.substringAfter("base64,")
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
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
            // Polling will start in onResume after user returns from UPI app
        } else {
            Toast.makeText(context, "${app.name} not available", Toast.LENGTH_SHORT).show()
        }
    }

    /** When user comes back from UPI app, start polling for status */
    override fun onResume() {
        super.onResume()
        if (paymentLaunched) {
            paymentLaunched = false
            pollingService = StatusPollingService(baseUrl, userToken, orderId, object : TezPaymentCallback {
                override fun onPaymentSuccess(orderId: String, utr: String) {
                    dismiss()
                    callback.onPaymentSuccess(orderId, utr)
                }
                override fun onPaymentFailed(orderId: String, reason: String) {
                    dismiss()
                    callback.onPaymentFailed(orderId, reason)
                }
                override fun onPaymentPending(orderId: String) {
                    dismiss()
                    callback.onPaymentPending(orderId)
                }
            })
            pollingService?.startPolling(lifecycleScope)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollingService?.stopPolling()
    }
}
