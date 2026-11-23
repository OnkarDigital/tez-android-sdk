package com.tezgateway.sdk.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tezgateway.sdk.models.PaymentData

/**
 * Builds a UPI QR locally when the server didn't send `qr_image` —
 * Google Pay only accepts a QR image share, not a deep link.
 */
internal object QrGenerator {

    /**
     * A standard `upi://pay?...` string for this order, taken from the deep links:
     * prefers a link that already is `upi://pay`, otherwise re-uses the query part
     * (pa, pn, am, tn, tr…) of any app-specific link. Null when nothing usable exists.
     */
    fun upiUri(data: PaymentData): String? {
        val links = listOf(
            data.bhim_link, data.gpay_link, data.phonepe_link,
            data.paytm_link, data.amazonpay_link, data.cred_link
        ).map { it.trim() }.filter { it.isNotEmpty() }

        links.firstOrNull { it.startsWith("upi://pay", ignoreCase = true) }?.let { return it }

        val query = links
            .map { it.substringAfter('?', "") }
            .firstOrNull { q -> q.split('&').any { it.startsWith("pa=") } }
            ?: return null
        return "upi://pay?$query"
    }

    /** Encodes [text] as a black-on-white QR bitmap. Call off the main thread. */
    fun encode(text: String, sizePx: Int = 720): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
