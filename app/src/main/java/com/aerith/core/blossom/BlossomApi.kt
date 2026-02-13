package com.aerith.core.blossom

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Streaming

interface BlossomApi {
    @GET("list/{pubkey}")
    suspend fun listBlobs(
        @Path("pubkey") pubkey: String,
        @Header("Authorization") auth: String? = null
    ): List<BlossomBlob>
}
