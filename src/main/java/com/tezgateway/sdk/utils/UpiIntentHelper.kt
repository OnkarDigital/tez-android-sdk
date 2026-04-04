package com.tezgateway.sdk.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.tezgateway.sdk.models.PaymentData

object UpiIntentHelper {

    enum class UpiApp(val packageName: String) {
        GOOGLE_PAY("com.google.android.apps.nbu.paisa.user"),
        PHONEPE("com.phonepe.app"),
        PAYTM("net.one97.paytm"),
        BHIM("in.org.npci.upiapp"),
        AMAZON_PAY("in.amazon.mShop.android.shopping"),
        CRED("com.dreamplug.androidapp")
    }

    /**
     * Checks if a specific UPI application is installed on the device.
     * Uses getApplicationInfo with 0 flags — more reliable than GET_ACTIVITIES on Android 11+.
     */
    fun isAppInstalled(context: Context, app: UpiApp): Boolean {
        return try {
            context.packageManager.getApplicationInfo(app.packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Retrieves a list of available (installed) UPI apps on the device that we support natively.
     */
    fun getAvailableUpiApps(context: Context): List<UpiApp> {
        val availableApps = mutableListOf<UpiApp>()
        for (app in UpiApp.values()) {
            if (isAppInstalled(context, app)) {
                availableApps.add(app)
            }
        }
        return availableApps
    }

    /**
     * Attempts to trigger the specific native deep link for the requested UPI App.
     */
    fun startPaymentIntent(context: Context, app: UpiApp, paymentData: PaymentData): Boolean {
        if (!isAppInstalled(context, app)) return false

        val deepLink: String = when (app) {
            UpiApp.GOOGLE_PAY -> paymentData.gpay_link
            UpiApp.PHONEPE -> paymentData.phonepe_link
            UpiApp.PAYTM -> paymentData.paytm_link
            UpiApp.BHIM -> paymentData.bhim_link
            UpiApp.AMAZON_PAY -> paymentData.amazonpay_link
            UpiApp.CRED -> paymentData.cred_link
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(deepLink)
            intent.setPackage(app.packageName)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
