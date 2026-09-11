package com.homelab.app.data.repository

import com.homelab.app.data.remote.TlsClientSelector
import com.homelab.app.data.remote.api.WgDashboardApi
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardAddPeerRequest
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardConfiguration
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardConfigurationDetail
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardOverview
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardPeer
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardPeerFile
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardPeersRequest
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardResponse
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardSection
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardSystemStatus
import com.homelab.app.domain.action.ActionRisk
import com.homelab.app.domain.action.ControlledActionRequest
import com.homelab.app.domain.provider.ProviderHealth
import com.homelab.app.domain.provider.ProviderHealthState
import com.homelab.app.domain.provider.ProviderRegistry
import com.homelab.app.util.ServiceType
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import retrofit2.HttpException

/**
 * Every mutating WGDashboard operation with the risk class that drives confirmation and the
 * required actor role in [com.homelab.app.domain.action.ControlledActionPolicy].
 *
 * Bringing a tunnel down is classified HIGH on purpose: for a homelab reached through
 * WireGuard it can cut the very connection the app is talking over.
 */
enum class WgDashboardAction(val actionId: String, val risk: ActionRisk) {
    TUNNEL_START("wireguard.tunnel.start", ActionRisk.MEDIUM),
    TUNNEL_STOP("wireguard.tunnel.stop", ActionRisk.HIGH),
    PEER_CREATE("wireguard.peer.create", ActionRisk.MEDIUM),
    PEER_DELETE("wireguard.peer.delete", ActionRisk.HIGH),
    PEER_RESTRICT("wireguard.peer.restrict", ActionRisk.MEDIUM),
    PEER_ALLOW("wireguard.peer.allow", ActionRisk.MEDIUM);

    val requiresConfirmation: Boolean get() = risk != ActionRisk.LOW

    fun controlledRequest(
        instanceId: String,
        targetRef: String,
        confirmed: Boolean,
        requestId: String = UUID.randomUUID().toString(),
        requestedAt: String = Instant.now().toString(),
        idempotencyKey: String = UUID.randomUUID().toString()
    ) = ControlledActionRequest(
        id = requestId,
        providerRef = "wgdashboard:${instanceId.trim().lowercase(Locale.ROOT)}",
        action = actionId,
        targetRef = targetRef,
        risk = risk,
        requestedAt = requestedAt,
        idempotencyKey = idempotencyKey,
        confirmed = confirmed
    )
}

class WgDashboardApiException(
    val kind: Kind,
    val detail: String? = null,
    override val cause: Throwable? = null
) : Exception(detail ?: kind.name, cause) {
    enum class Kind {
        INVALID_CREDENTIALS,
        API_DISABLED,
        NOT_WGDASHBOARD,
        SERVER_ERROR,
        CONNECTION_ERROR
    }
}

/** What a login probe made of the server's answer. */
sealed interface WgDashboardLoginOutcome {
    data object Accepted : WgDashboardLoginOutcome
    data class Rejected(
        val kind: WgDashboardApiException.Kind,
        val detail: String?
    ) : WgDashboardLoginOutcome
}

@Singleton
class WgDashboardRepository @Inject constructor(
    private val api: WgDashboardApi,
    private val tlsClientSelector: TlsClientSelector
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    val providerDescriptor = requireNotNull(ProviderRegistry.descriptor(ServiceType.WGDASHBOARD))

    // ---------- Login ----------

    suspend fun authenticate(
        url: String,
        apiKey: String,
        fallbackUrl: String? = null,
        allowSelfSigned: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val candidates = listOf(url, fallbackUrl)
            .mapNotNull { it?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank) }
            .distinct()

        var lastError: WgDashboardApiException? = null
        for (baseUrl in candidates) {
            try {
                authenticateAgainst(baseUrl, apiKey, allowSelfSigned)
                return@withContext
            } catch (error: WgDashboardApiException) {
                // A rejected key is the same on every address, so there is nothing to gain from
                // trying the fallback URL with it.
                if (error.kind != WgDashboardApiException.Kind.CONNECTION_ERROR) throw error
                lastError = error
            }
        }
        throw lastError ?: WgDashboardApiException(WgDashboardApiException.Kind.CONNECTION_ERROR)
    }

    private fun authenticateAgainst(baseUrl: String, apiKey: String, allowSelfSigned: Boolean) {
        val request = Request.Builder()
            .url("$baseUrl/api/getWireguardConfigurations")
            .get()
            .addHeader("wg-dashboard-apikey", apiKey)
            .addHeader("Accept", "application/json")
            .build()

        try {
            tlsClientSelector.forAllowSelfSigned(allowSelfSigned).newCall(request).execute().use { response ->
                val outcome = classifyWgDashboardLogin(
                    code = response.code,
                    body = runCatching { response.body?.string() }.getOrNull(),
                    json = json
                )
                if (outcome is WgDashboardLoginOutcome.Rejected) {
                    throw WgDashboardApiException(outcome.kind, outcome.detail)
                }
            }
        } catch (error: WgDashboardApiException) {
            throw error
        } catch (error: IOException) {
            throw WgDashboardApiException(WgDashboardApiException.Kind.CONNECTION_ERROR, cause = error)
        } catch (error: Exception) {
            throw WgDashboardApiException(WgDashboardApiException.Kind.CONNECTION_ERROR, cause = error)
        }
    }

    // ---------- Reads ----------

    /**
     * Loads the dashboard. The tunnel list is what the screen is about and is required; the
     * version is decoration, so a build that does not serve it is reported as an unavailable
     * section instead of failing the whole screen.
     */
    suspend fun getOverview(instanceId: String): WgDashboardOverview = coroutineScope {
        val versionJob = async { runCatching { payload { api.getVersion(instanceId) } }.getOrNull() }
        val configurations = payload { api.getConfigurations(instanceId) }.orEmpty()
        val version = versionJob.await()

        WgDashboardOverview(
            version = version?.trim()?.takeIf { it.isNotBlank() },
            configurations = configurations.sortedWith(
                compareByDescending<WgDashboardConfiguration> { it.status }.thenBy { it.name.lowercase(Locale.ROOT) }
            ),
            unavailableSections = buildMap {
                if (version.isNullOrBlank()) put(WgDashboardSection.VERSION, "unavailable")
            }
        )
    }

    /** Load of the machine WGDashboard runs on, for the card above the tunnel list. */
    suspend fun getSystemStatus(instanceId: String): WgDashboardSystemStatus =
        payload { api.getSystemStatus(instanceId) }
            ?: throw WgDashboardApiException(
                WgDashboardApiException.Kind.SERVER_ERROR,
                "The server returned no system status"
            )

    suspend fun getConfigurationDetail(
        instanceId: String,
        configurationName: String
    ): WgDashboardConfigurationDetail =
        payload { api.getConfigurationInfo(instanceId, configurationName) }
            ?: throw WgDashboardApiException(
                WgDashboardApiException.Kind.SERVER_ERROR,
                "The server returned no data for $configurationName"
            )

    suspend fun getNormalizedHealth(instanceId: String): ProviderHealth = runCatching {
        val overview = getOverview(instanceId)
        ProviderHealth(
            providerId = providerDescriptor.id,
            instanceId = instanceId,
            state = ProviderHealthState.HEALTHY,
            message = "WGDashboard API reachable",
            attributes = mapOf(
                "version" to overview.version.orEmpty(),
                "tunnels" to overview.configurations.size.toString(),
                "activeTunnels" to overview.activeTunnels.toString()
            ).filterValues { it.isNotEmpty() }
        )
    }.getOrElse { error ->
        ProviderHealth(
            providerId = providerDescriptor.id,
            instanceId = instanceId,
            state = ProviderHealthState.UNAVAILABLE,
            message = error.message ?: "WGDashboard API unavailable"
        )
    }

    // ---------- Mutations ----------

    /** Toggles the tunnel and returns the status the server reports afterwards. */
    suspend fun toggleConfiguration(instanceId: String, configurationName: String): Boolean =
        call { api.toggleConfiguration(instanceId, configurationName) }.data ?: false

    /**
     * Creates a peer and returns it. The request carries no key material, so WGDashboard
     * generates the key pair itself; [WgDashboardAddPeerRequest] explains why.
     *
     * Note that the server starts a stopped tunnel when a peer is added to it - that is
     * WGDashboard's own behaviour, not something the app asks for.
     */
    suspend fun createPeer(
        instanceId: String,
        configurationName: String,
        request: WgDashboardAddPeerRequest
    ): WgDashboardPeer {
        require(request.name.isNotBlank()) { "A peer name is required" }
        require(request.allowedIps.isNotEmpty()) { "An address is required" }
        val created = payload { api.addPeer(instanceId, configurationName, request) }.orEmpty()
        return created.firstOrNull()
            ?: throw WgDashboardApiException(
                WgDashboardApiException.Kind.SERVER_ERROR,
                "The server accepted the peer but returned none"
            )
    }

    /**
     * The addresses the tunnel still has free, in the order the server offers them. The map is
     * keyed by subnet; the app only needs the flat list to suggest the next address.
     */
    suspend fun getAvailableIps(instanceId: String, configurationName: String): List<String> =
        payload { api.getAvailableIps(instanceId, configurationName) }
            .orEmpty()
            .values
            .flatten()

    /** The client configuration of one peer, ready to be shown as a QR code. */
    suspend fun getPeerConfiguration(
        instanceId: String,
        configurationName: String,
        peerId: String
    ): WgDashboardPeerFile =
        payload { api.downloadPeer(instanceId, configurationName, peerId) }
            ?: throw WgDashboardApiException(
                WgDashboardApiException.Kind.SERVER_ERROR,
                "The server returned no configuration for this peer"
            )

    suspend fun deletePeers(instanceId: String, configurationName: String, peerIds: List<String>) {
        require(peerIds.isNotEmpty()) { "At least one peer is required" }
        call { api.deletePeers(instanceId, configurationName, WgDashboardPeersRequest(peerIds)) }
    }

    suspend fun restrictPeers(instanceId: String, configurationName: String, peerIds: List<String>) {
        require(peerIds.isNotEmpty()) { "At least one peer is required" }
        call { api.restrictPeers(instanceId, configurationName, WgDashboardPeersRequest(peerIds)) }
    }

    suspend fun allowAccessPeers(instanceId: String, configurationName: String, peerIds: List<String>) {
        require(peerIds.isNotEmpty()) { "At least one peer is required" }
        call { api.allowAccessPeers(instanceId, configurationName, WgDashboardPeersRequest(peerIds)) }
    }

    // ---------- Plumbing ----------

    /**
     * Runs one call and unwraps it. Both the transport failure and the envelope are mapped here,
     * so no caller can accidentally let a raw HTTP error through.
     */
    private suspend fun <T> payload(block: suspend () -> WgDashboardResponse<T>): T? = call(block).data

    private suspend fun <T> call(block: suspend () -> WgDashboardResponse<T>): WgDashboardResponse<T> =
        checked(execute(block))

    /**
     * WGDashboard answers a refused request with HTTP 200 and `status: false`, so the envelope
     * has to be inspected on every call - otherwise a rejected action looks like a success.
     */
    private fun <T> checked(response: WgDashboardResponse<T>): WgDashboardResponse<T> {
        if (!response.status) {
            throw WgDashboardApiException(
                WgDashboardApiException.Kind.SERVER_ERROR,
                response.message?.trim()?.takeIf { it.isNotBlank() }
            )
        }
        return response
    }

    private suspend fun <T> execute(block: suspend () -> WgDashboardResponse<T>): WgDashboardResponse<T> {
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            throw httpFailure(error)
        } catch (error: IOException) {
            throw WgDashboardApiException(WgDashboardApiException.Kind.CONNECTION_ERROR, cause = error)
        }
    }

    private fun httpFailure(error: HttpException): WgDashboardApiException {
        val body = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
        val message = wgDashboardMessageOf(body, json)
        return when (error.code()) {
            401, 403 -> WgDashboardApiException(
                WgDashboardApiException.Kind.INVALID_CREDENTIALS,
                message ?: "HTTP ${error.code()}",
                error
            )
            else -> WgDashboardApiException(
                WgDashboardApiException.Kind.SERVER_ERROR,
                listOfNotNull("HTTP ${error.code()}", message).joinToString(": "),
                error
            )
        }
    }
}

/** Reads the `message` of an envelope, ignoring bodies that are not the expected JSON. */
internal fun wgDashboardMessageOf(body: String?, json: Json): String? {
    val trimmed = body?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val parsed = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
    return runCatching { parsed["message"]?.jsonPrimitive?.content }.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

/**
 * Turns the answer of the login probe into a verdict.
 *
 * WGDashboard rejects a key it does not know with HTTP 401 and "API Key does not exist". It
 * answers the same status with "Unauthorized access." when the key feature itself is switched
 * off in the dashboard settings - a different fix for the user, so the two are kept apart.
 * Anything that is not the JSON envelope means the URL is not a WGDashboard API.
 */
internal fun classifyWgDashboardLogin(code: Int, body: String?, json: Json): WgDashboardLoginOutcome {
    val message = wgDashboardMessageOf(body, json)
    if (code == 401 || code == 403) {
        val kind = if (message != null && message.contains("unauthorized", ignoreCase = true)) {
            WgDashboardApiException.Kind.API_DISABLED
        } else {
            WgDashboardApiException.Kind.INVALID_CREDENTIALS
        }
        return WgDashboardLoginOutcome.Rejected(kind, message)
    }
    if (code !in 200..299) {
        return WgDashboardLoginOutcome.Rejected(
            WgDashboardApiException.Kind.SERVER_ERROR,
            listOfNotNull("HTTP $code", message).joinToString(": ")
        )
    }

    val envelope = runCatching {
        json.decodeFromString(WgDashboardResponse.serializer(kotlinx.serialization.json.JsonElement.serializer()), body.orEmpty())
    }.getOrNull()
        ?: return WgDashboardLoginOutcome.Rejected(
            WgDashboardApiException.Kind.NOT_WGDASHBOARD,
            null
        )

    if (!envelope.status) {
        return WgDashboardLoginOutcome.Rejected(
            WgDashboardApiException.Kind.SERVER_ERROR,
            envelope.message?.trim()?.takeIf { it.isNotBlank() }
        )
    }
    return WgDashboardLoginOutcome.Accepted
}
