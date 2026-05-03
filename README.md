# TezGateway Android SDK 🚀

[![](https://jitpack.io/v/OnkarDigital/tez-android-sdk.svg)](https://jitpack.io/#OnkarDigital/tez-android-sdk)

A native Android SDK for integrating TezGateway UPI payment gateway — **no WebView, no Custom Tabs, pure native Android Intents.**

## Features

- ⚡ **Native UPI Intents** — launches GPay, PhonePe, Paytm, BHIM, Amazon Pay, CRED directly
- 🎨 **Dynamic UI** — checkout colors and buttons auto-configured from your merchant panel
- 🔐 **Secure by Design** — secret `user_token` never goes inside the Android app
- 📱 **Smart App Detection** — only shows buttons for UPI apps installed on device
- 🔄 **Auto Status Polling** — polls `check_order.php` when user returns from UPI app
- ☕ **Java + Kotlin** — fully compatible with both languages

---

## Integration (Option 2 — Server-to-Server, Recommended)

### Step 1 — Add Dependency

```gradle
dependencies {
    implementation 'com.github.OnkarDigital:tez-android-sdk:1.0.9'
}
```

### Step 2 — Your Backend Creates the Order

Your backend server calls `https://tezgateway.com/api/create_order1.php` with your secret `user_token` and returns the deep-link response to your Android app.

### Step 3 — Launch SDK (Kotlin)

```kotlin
import com.tezgateway.sdk.TezGateway
import com.tezgateway.sdk.models.PaymentData
import com.tezgateway.sdk.interfaces.TezPaymentCallback

val paymentData = PaymentData(
    bhim_link    = serverResponse.data.bhim_link,
    phonepe_link = serverResponse.data.phonepe_link,
    paytm_link   = serverResponse.data.paytm_link,
    gpay_link    = serverResponse.data.gpay_link,
    qr_image     = serverResponse.data.qr_image
)

TezGateway.startPayment(
    activity    = this,
    userToken   = "YOUR_MERCHANT_TOKEN",
    orderId     = serverResponse.result.orderId,
    paymentData = paymentData,
    callback    = object : TezPaymentCallback {
        override fun onPaymentSuccess(orderId: String, utr: String) {
            // ✅ Payment SUCCESS — verified via check_order.php
        }
        override fun onPaymentFailed(orderId: String, reason: String) {
            // ❌ Payment Failed / User Cancelled
        }
        override fun onPaymentPending(orderId: String) {
            // ⏳ Status still pending after polling
        }
    }
)
```

### Step 3 — Launch SDK (Java)

```java
TezGateway.startPayment(
    MainActivity.this,
    "YOUR_MERCHANT_TOKEN",
    serverResponse.getResult().getOrderId(),
    paymentData,
    new TezPaymentCallback() {
        @Override
        public void onPaymentSuccess(String orderId, String utr) { }

        @Override
        public void onPaymentFailed(String orderId, String reason) { }

        @Override
        public void onPaymentPending(String orderId) { }
    }
);
```

### Optional — Custom Base URL

If you run TezGateway on a custom domain:

```kotlin
// Call once in Application.onCreate()
// Note: SDK is fully compatible with both the legacy PHP panel and the new Laravel panel!
TezGateway.configure("https://your-custom-domain.com")
```

---

## How It Works Internally

```
App → Your Server → create_order1.php
                 ↓
         PaymentData (deep-links)
                 ↓
     TezGateway.startPayment()
             ↓         ↓
   get_checkout_settings.php  →  Dynamic UI rendered
             ↓
   User taps GPay / PhonePe / Paytm
             ↓  (native Android Intent)
   User completes payment in UPI app
             ↓  (returns to app)
   check_order.php polled every 3s
             ↓
   onPaymentSuccess / Failed / Pending
```

---

## SDK Files

| File | Purpose |
|------|---------|
| `TezGateway.kt` | Main entry point |
| `TezPaymentCallback.kt` | Result callback interface |
| `TezCheckoutBottomSheet.kt` | Native payment UI |
| `UpiIntentHelper.kt` | UPI app detection + intent trigger |
| `StatusPollingService.kt` | Polls check_order.php after payment |
| `SettingsClient.kt` | Fetches merchant UI config |
| `Models.kt` | All data models (PaymentData, CheckoutSettings, WebhookPayload) |

---

## Required AndroidManifest Permissions

The SDK's `AndroidManifest.xml` automatically merges these:

```xml
<uses-permission android:name="android.permission.INTERNET" />

<!-- Android 11+ requires explicit package queries for UPI apps -->
<queries>
    <package android:name="com.google.android.apps.nbu.paisa.user" />  <!-- GPay -->
    <package android:name="com.phonepe.app" />
    <package android:name="net.one97.paytm" />
    <package android:name="in.org.npci.upiapp" />
    <package android:name="in.amazon.mShop.android.shopping" />
    <package android:name="com.dreamplug.androidapp" />
</queries>
```

---

## Webhook (Server-Side)

Configure your Webhook URL in the TezGateway merchant panel. When a payment settles, the server sends:

```json
{
  "status": "SUCCESS",
  "utr": "104097179160",
  "order_id": "ORDR977135546",
  "amount": "500.00",
  "customer_mobile": "9876543210",
  "method": "Googlepay",
  "remark1": "custom_data",
  "remark2": "more_data"
}
```

---

## Minimum Requirements

| | |
|---|---|
| **Min SDK** | API 21 (Android 5.0) |
| **Target SDK** | API 34 |
| **Language** | Kotlin · Java |
| **Architecture** | BottomSheet + Coroutines + OkHttp |

---

*TezGateway Android SDK · v1.0.20*
