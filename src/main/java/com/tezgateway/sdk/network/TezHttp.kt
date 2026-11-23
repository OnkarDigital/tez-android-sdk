package com.tezgateway.sdk.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One shared OkHttp client for the whole SDK (connection pool + threads are reused). */
internal object TezHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
