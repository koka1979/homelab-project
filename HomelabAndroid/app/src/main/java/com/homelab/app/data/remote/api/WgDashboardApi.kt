package com.homelab.app.data.remote.api

import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardConfiguration
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardConfigurationDetail
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardPeersRequest
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardResponse
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * WGDashboard's REST API. Every call carries the instance id so [com.homelab.app.data.remote.AuthInterceptor]
 * can attach the `wg-dashboard-apikey` header and the base URL of that instance.
 */
interface WgDashboardApi {

    @Headers("Accept: application/json")
    @GET("api/getWireguardConfigurations")
    suspend fun getConfigurations(
        @Header("X-Homelab-Instance-Id") instanceId: String
    ): WgDashboardResponse<List<WgDashboardConfiguration>>

    @Headers("Accept: application/json")
    @GET("api/getWireguardConfigurationInfo")
    suspend fun getConfigurationInfo(
        @Header("X-Homelab-Instance-Id") instanceId: String,
        @Query("configurationName") configurationName: String
    ): WgDashboardResponse<WgDashboardConfigurationDetail>

    @Headers("Accept: application/json")
    @GET("api/getDashboardVersion")
    suspend fun getVersion(
        @Header("X-Homelab-Instance-Id") instanceId: String
    ): WgDashboardResponse<String>

    /** Brings the tunnel up when it is down and down when it is up; returns the new status. */
    @Headers("Accept: application/json")
    @GET("api/toggleWireguardConfiguration")
    suspend fun toggleConfiguration(
        @Header("X-Homelab-Instance-Id") instanceId: String,
        @Query("configurationName") configurationName: String
    ): WgDashboardResponse<Boolean>

    /** Blocks the given peers without deleting them. */
    @Headers("Accept: application/json")
    @POST("api/restrictPeers/{configName}")
    suspend fun restrictPeers(
        @Header("X-Homelab-Instance-Id") instanceId: String,
        @Path("configName") configName: String,
        @Body body: WgDashboardPeersRequest
    ): WgDashboardResponse<JsonElement>

    /** Lets previously restricted peers connect again. */
    @Headers("Accept: application/json")
    @POST("api/allowAccessPeers/{configName}")
    suspend fun allowAccessPeers(
        @Header("X-Homelab-Instance-Id") instanceId: String,
        @Path("configName") configName: String,
        @Body body: WgDashboardPeersRequest
    ): WgDashboardResponse<JsonElement>
}
