package com.homelab.app.data.remote.api

import com.homelab.app.data.remote.dto.unraid.UnraidGraphQlRequest
import com.homelab.app.data.remote.dto.unraid.UnraidGraphQlResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * The Unraid API exposes a single GraphQL endpoint; every read and every mutation is a POST
 * against it. Responses are handed back as a raw JSON object and decoded per section by
 * [com.homelab.app.data.repository.UnraidRepository].
 */
interface UnraidApi {

    @Headers("Accept: application/json")
    @POST("graphql")
    suspend fun graphql(
        @Header("X-Homelab-Instance-Id") instanceId: String,
        @Body body: UnraidGraphQlRequest
    ): UnraidGraphQlResponse
}
