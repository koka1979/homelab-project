package com.homelab.app.data.remote.api

import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardAddPeerRequest
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardConfiguration
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardConfigurationDetail
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardPeer
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardPeerFile
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardPeersRequest
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardResponse
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardSystemStatus
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

    /**
     * CPU, memory, swap and disk usage of the machine WGDashboard runs on. Answers slowly - it
     * samples the CPU for a second - so it is never awaited before the tunnels are shown.
     */
    @Headers("Accept: application/json")
    @GET("api/systemStatus")
    suspend fun getSystemStatus(
        @Header("X-Homelab-Instance-Id") instanceId: String
    ): WgDashboardResponse<WgDashboardSystemStatus>

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

    /**
     * Creates a peer. Without a key in the body WGDashboard generates the pair itself and
     * answers with the peers it created.
     */
    @Headers("Accept: application/json")
    @POST("api/addPeers/{configName}")
    suspend fun addPeer(
        @Header("X-Homelab-Instance-Id") instanceId: String,
        @Path("configName") configName: String,
        @Body body: WgDashboardAddPeerRequest
    ): WgDashboardResponse<List<WgDashboardPeer>>

    /** The free addresses of a tunnel, grouped by the subnet they belong to. */
    @Headers("Accept: application/json")
    @GET("api/getAvailableIPs/{configName}")
    suspend fun getAvailableIps(
        @Header("X-Homelab-Instance-Id") instanceId: String,
        @Path("configName") configName: String
    ): WgDashboardResponse<Map<String, List<String>>>

    /** The finished client configuration of one peer, including its private key. */
    @Headers("Accept: application/json")
    @GET("api/downloadPeer/{configName}")
    suspend fun downloadPeer(
        @Header("X-Homelab-Instance-Id") instanceId: String,
        @Path("configName") configName: String,
        @Query("id") peerId: String
    ): WgDashboardResponse<WgDashboardPeerFile>

    /** Removes the given peers from the tunnel for good. */
    @Headers("Accept: application/json")
    @POST("api/deletePeers/{configName}")
    suspend fun deletePeers(
        @Header("X-Homelab-Instance-Id") instanceId: String,
        @Path("configName") configName: String,
        @Body body: WgDashboardPeersRequest
    ): WgDashboardResponse<JsonElement>

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
