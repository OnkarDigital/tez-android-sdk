package com.tezgateway.sdk.network

import com.tezgateway.sdk.models.OrderResponse
import com.tezgateway.sdk.models.SettingsResponse
import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

interface TezApi {

    @FormUrlEncoded
    @POST("api/get_checkout_settings.php")
    fun getCheckoutSettings(
        @Field("user_token") userToken: String
    ): Call<SettingsResponse>

    // Note: If using Option 2, the app developer creates the order from their backend.
    // However, if parsing raw OrderResponse JSON from their backend, they will just deserialize it.
    
    // Status check for polling
    @FormUrlEncoded
    @POST("api/check_order.php")
    fun checkOrderStatus(
        @Field("user_token") userToken: String,
        @Field("order_id") orderId: String
    ): Call<OrderResponse> // Assuming check_order response can be mapped here or to a separate StatusResponse
}
