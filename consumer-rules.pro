# Keep TezGateway SDK public API (so Retrofit/OkHttp work after minification)
-keep class com.tezgateway.sdk.** { *; }
-keep interface com.tezgateway.sdk.** { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep JSON field names intact (for JSONObject parsing)
-keepclassmembers class com.tezgateway.sdk.models.** {
    <fields>;
}
