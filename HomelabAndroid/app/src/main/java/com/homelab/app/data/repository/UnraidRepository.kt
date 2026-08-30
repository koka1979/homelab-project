package com.homelab.app.data.repository

import com.homelab.app.data.remote.TlsClientSelector
import com.homelab.app.data.remote.api.UnraidApi
import com.homelab.app.data.remote.dto.unraid.UnraidArrayData
import com.homelab.app.data.remote.dto.unraid.UnraidContainer
import com.homelab.app.data.remote.dto.unraid.UnraidDockerData
import com.homelab.app.data.remote.dto.unraid.UnraidGraphQl
import com.homelab.app.data.remote.dto.unraid.UnraidGraphQlRequest
import com.homelab.app.data.remote.dto.unraid.UnraidGraphQlResponse
import com.homelab.app.data.remote.dto.unraid.UnraidInfoData
import com.homelab.app.data.remote.dto.unraid.UnraidNotificationsData
import com.homelab.app.data.remote.dto.unraid.UnraidOverview
import com.homelab.app.data.remote.dto.unraid.UnraidSection
import com.homelab.app.data.remote.dto.unraid.UnraidSharesData
import com.homelab.app.data.remote.dto.unraid.UnraidVm
import com.homelab.app.data.remote.dto.unraid.UnraidVmsData
import com.homelab.app.domain.action.ActionRisk
import com.homelab.app.domain.action.ControlledActionRequest
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * Every mutating Unraid operation, with the risk class that drives confirmation and the
 * required actor role in [com.homelab.app.domain.action.ControlledActionPolicy].
 */
enum class UnraidAction(val actionId: String, val risk: ActionRisk) {
    CONTAINER_START("docker.container.start", ActionRisk.LOW),
    CONTAINER_STOP("docker.container.stop", ActionRisk.MEDIUM),
    CONTAINER_RESTART("docker.container.restart", ActionRisk.MEDIUM),
    VM_START("vm.start", ActionRisk.LOW),
    VM_STOP("vm.stop", ActionRisk.MEDIUM),
    VM_PAUSE("vm.pause", ActionRisk.MEDIUM),
    VM_RESUME("vm.resume", ActionRisk.LOW),
    VM_FORCE_STOP("vm.force-stop", ActionRisk.HIGH),
    VM_REBOOT("vm.reboot", ActionRisk.MEDIUM),
    ARRAY_START("array.start", ActionRisk.HIGH),
    ARRAY_STOP("array.stop", ActionRisk.CRITICAL),
    PARITY_CHECK_START("parity.check.start", ActionRisk.MEDIUM),
    PARITY_CHECK_CANCEL("parity.check.cancel", ActionRisk.MEDIUM);

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
        providerRef = "unraid:${instanceId.trim().lowercase(Locale.ROOT)}",
        action = actionId,
        targetRef = targetRef,
        risk = risk,
        requestedAt = requestedAt,
        idempotencyKey = idempotencyKey,
        confirmed = confirmed
    )
}

class UnraidApiException(
    val kind: Kind,
    val detail: String? = null,
    override val cause: Throwable? = null
) : Exception(detail ?: kind.name, cause) {
    enum class Kind {
        INVALID_CREDENTIALS,
        UNSUPPORTED_OPERATION,
        SERVER_ERROR,
        CONNECTION_ERROR
    }
}

@Singleton
class UnraidRepository @Inject constructor(
    private val api: UnraidApi,
    private val json: Json,
    private val tlsClientSelector: TlsClientSelector
) {

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

        var lastError: UnraidApiException? = null
        for (baseUrl in candidates) {
            try {
                authenticateAgainst(baseUrl, apiKey, allowSelfSigned)
                return@withContext
            } catch (error: UnraidApiException) {
                lastError = error
                if (error.kind == UnraidApiException.Kind.INVALID_CREDENTIALS) throw error
            }
        }
        throw lastError ?: UnraidApiException(UnraidApiException.Kind.CONNECTION_ERROR)
    }

    private fun authenticateAgainst(baseUrl: String, apiKey: String, allowSelfSigned: Boolean) {
        val payload = json.encodeToString(
            UnraidGraphQlRequest.serializer(),
            UnraidGraphQlRequest(query = UnraidGraphQl.PING.first())
        )
        val request = Request.Builder()
            .url("$baseUrl/graphql")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("x-api-key", apiKey)
            .addHeader("Accept", "application/json")
            .build()

        try {
            tlsClientSelector.forAllowSelfSigned(allowSelfSigned).newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    throw UnraidApiException(UnraidApiException.Kind.INVALID_CREDENTIALS)
                }
                if (!response.isSuccessful) {
                    throw UnraidApiException(UnraidApiException.Kind.SERVER_ERROR)
                }
                val body = response.body?.string().orEmpty()
                val decoded = runCatching {
                    json.decodeFromString(UnraidGraphQlResponse.serializer(), body)
                }.getOrElse {
                    throw UnraidApiException(UnraidApiException.Kind.SERVER_ERROR, cause = it)
                }
                val failure = decoded.errors?.firstOrNull()?.message
                if (failure != null && !UnraidGraphQl.isSchemaMismatch(failure)) {
                    throw UnraidApiException(classifyGraphQlError(failure), failure)
                }
                // A schema mismatch still proves the endpoint answered an authenticated request.
                if (failure == null && decoded.data == null) {
                    throw UnraidApiException(UnraidApiException.Kind.SERVER_ERROR)
                }
            }
        } catch (error: UnraidApiException) {
            throw error
        } catch (error: IOException) {
            throw UnraidApiException(UnraidApiException.Kind.CONNECTION_ERROR, cause = error)
        } catch (error: Exception) {
            throw UnraidApiException(UnraidApiException.Kind.CONNECTION_ERROR, cause = error)
        }
    }

    // ---------- Reads ----------

    suspend fun getOverview(instanceId: String): UnraidOverview = coroutineScope {
        val systemJob = async { sectionOrNull(instanceId, UnraidGraphQl.SYSTEM) }
        val arrayJob = async { sectionOrNull(instanceId, UnraidGraphQl.ARRAY) }
        val sharesJob = async { sectionOrNull(instanceId, UnraidGraphQl.SHARES) }
        val dockerJob = async { sectionOrNull(instanceId, UnraidGraphQl.DOCKER) }
        val vmsJob = async { sectionOrNull(instanceId, UnraidGraphQl.VMS) }
        val notificationsJob = async { sectionOrNull(instanceId, UnraidGraphQl.NOTIFICATIONS) }

        val system = systemJob.await()
        val array = arrayJob.await()
        val shares = sharesJob.await()
        val docker = dockerJob.await()
        val vms = vmsJob.await()
        val notifications = notificationsJob.await()

        // The system section is the one every supported build answers: if it is missing while
        // the request itself succeeded, the endpoint is not an Unraid API.
        if (system == null && array == null && docker == null) {
            throw UnraidApiException(UnraidApiException.Kind.UNSUPPORTED_OPERATION)
        }

        val unavailable = buildSet {
            if (system == null) add(UnraidSection.SYSTEM)
            if (array == null) add(UnraidSection.ARRAY)
            if (shares == null) add(UnraidSection.SHARES)
            if (docker == null) add(UnraidSection.DOCKER)
            if (vms == null) add(UnraidSection.VMS)
            if (notifications == null) add(UnraidSection.NOTIFICATIONS)
        }

        UnraidOverview(
            info = system?.let { decode(it, UnraidInfoData.serializer()) }?.info,
            array = array?.let { decode(it, UnraidArrayData.serializer()) }?.array,
            shares = shares?.let { decode(it, UnraidSharesData.serializer()) }?.shares.orEmpty(),
            containers = docker?.let { decode(it, UnraidDockerData.serializer()) }?.docker?.containers.orEmpty(),
            vms = vms?.let { decode(it, UnraidVmsData.serializer()) }?.vms?.domain.orEmpty(),
            notifications = notifications?.let { decode(it, UnraidNotificationsData.serializer()) }?.notifications,
            unavailableSections = unavailable
        )
    }

    suspend fun getContainers(instanceId: String): List<UnraidContainer> {
        val data = sectionOrNull(instanceId, UnraidGraphQl.DOCKER) ?: return emptyList()
        return decode(data, UnraidDockerData.serializer()).docker?.containers.orEmpty()
    }

    suspend fun getVms(instanceId: String): List<UnraidVm> {
        val data = sectionOrNull(instanceId, UnraidGraphQl.VMS) ?: return emptyList()
        return decode(data, UnraidVmsData.serializer()).vms?.domain.orEmpty()
    }

    // ---------- Mutations ----------

    suspend fun startContainer(instanceId: String, containerId: String) =
        mutate(instanceId, UnraidGraphQl.startContainer(containerId))

    suspend fun stopContainer(instanceId: String, containerId: String) =
        mutate(instanceId, UnraidGraphQl.stopContainer(containerId))

    /**
     * Builds without a `restart` mutation are served by stopping and starting the container,
     * which is what the Unraid web UI does for the same button.
     */
    suspend fun restartContainer(instanceId: String, containerId: String) {
        try {
            mutate(instanceId, UnraidGraphQl.restartContainer(containerId))
        } catch (error: UnraidApiException) {
            if (error.kind != UnraidApiException.Kind.UNSUPPORTED_OPERATION) throw error
            mutate(instanceId, UnraidGraphQl.stopContainer(containerId))
            mutate(instanceId, UnraidGraphQl.startContainer(containerId))
        }
    }

    suspend fun startVm(instanceId: String, vmId: String) =
        mutate(instanceId, UnraidGraphQl.startVm(vmId))

    suspend fun stopVm(instanceId: String, vmId: String) =
        mutate(instanceId, UnraidGraphQl.stopVm(vmId))

    suspend fun pauseVm(instanceId: String, vmId: String) =
        mutate(instanceId, UnraidGraphQl.pauseVm(vmId))

    suspend fun resumeVm(instanceId: String, vmId: String) =
        mutate(instanceId, UnraidGraphQl.resumeVm(vmId))

    suspend fun forceStopVm(instanceId: String, vmId: String) =
        mutate(instanceId, UnraidGraphQl.forceStopVm(vmId))

    suspend fun rebootVm(instanceId: String, vmId: String) =
        mutate(instanceId, UnraidGraphQl.rebootVm(vmId))

    suspend fun startArray(instanceId: String) = mutate(instanceId, UnraidGraphQl.START_ARRAY)

    suspend fun stopArray(instanceId: String) = mutate(instanceId, UnraidGraphQl.STOP_ARRAY)

    suspend fun startParityCheck(instanceId: String, correcting: Boolean) =
        mutate(instanceId, UnraidGraphQl.startParityCheck(correcting))

    suspend fun cancelParityCheck(instanceId: String) =
        mutate(instanceId, UnraidGraphQl.CANCEL_PARITY_CHECK)

    // ---------- GraphQL plumbing ----------

    /** Returns null when no candidate document is understood by this Unraid build. */
    private suspend fun sectionOrNull(instanceId: String, documents: List<String>): JsonObject? {
        return try {
            execute(instanceId, documents)
        } catch (error: UnraidApiException) {
            if (error.kind == UnraidApiException.Kind.UNSUPPORTED_OPERATION) null else throw error
        }
    }

    private suspend fun mutate(instanceId: String, documents: List<String>) {
        execute(instanceId, documents)
    }

    private suspend fun execute(instanceId: String, documents: List<String>): JsonObject {
        require(documents.isNotEmpty()) { "At least one GraphQL document is required" }

        var mismatch: String? = null
        for (document in documents) {
            val response = try {
                api.graphql(instanceId, UnraidGraphQlRequest(query = document))
            } catch (error: CancellationException) {
                throw error
            } catch (error: HttpException) {
                // The Unraid API rejects a document it cannot validate with HTTP 400 rather than
                // a 200 carrying an errors array, so the candidate fallback has to inspect the
                // error body too. Without this, one unknown field fails the whole section.
                val failure = graphQlErrorFrom(error)
                when {
                    error.code() == 401 || error.code() == 403 ->
                        throw UnraidApiException(UnraidApiException.Kind.INVALID_CREDENTIALS, failure, error)
                    failure != null && UnraidGraphQl.isSchemaMismatch(failure) -> {
                        mismatch = failure
                        continue
                    }
                    else -> throw UnraidApiException(
                        UnraidApiException.Kind.SERVER_ERROR,
                        listOfNotNull("HTTP ${error.code()}", failure).joinToString(": "),
                        error
                    )
                }
            } catch (error: Exception) {
                throw translate(error)
            }

            val failure = response.errors?.firstOrNull()?.message
            if (failure != null) {
                if (UnraidGraphQl.isSchemaMismatch(failure)) {
                    mismatch = failure
                    continue
                }
                throw UnraidApiException(classifyGraphQlError(failure), failure)
            }

            return response.data ?: JsonObject(emptyMap())
        }

        throw UnraidApiException(UnraidApiException.Kind.UNSUPPORTED_OPERATION, mismatch)
    }

    private fun <T> decode(data: JsonObject, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T {
        return try {
            json.decodeFromJsonElement(deserializer, data)
        } catch (error: SerializationException) {
            throw UnraidApiException(UnraidApiException.Kind.SERVER_ERROR, cause = error)
        }
    }

    private fun classifyGraphQlError(message: String): UnraidApiException.Kind {
        val normalized = message.lowercase()
        val unauthorized = normalized.contains("unauthorized") ||
            normalized.contains("unauthenticated") ||
            normalized.contains("forbidden") ||
            normalized.contains("api key") ||
            normalized.contains("permission")
        return if (unauthorized) {
            UnraidApiException.Kind.INVALID_CREDENTIALS
        } else {
            UnraidApiException.Kind.SERVER_ERROR
        }
    }

    private fun translate(error: Throwable): UnraidApiException = when (error) {
        is UnraidApiException -> error
        is HttpException -> when (error.code()) {
            401, 403 -> UnraidApiException(
                UnraidApiException.Kind.INVALID_CREDENTIALS,
                graphQlErrorFrom(error),
                error
            )
            else -> UnraidApiException(
                UnraidApiException.Kind.SERVER_ERROR,
                listOfNotNull("HTTP ${error.code()}", graphQlErrorFrom(error)).joinToString(": "),
                error
            )
        }
        // Surface the parser complaint: an unexpected field type is the difference between
        // "your server is broken" and "this build reports a value in another shape".
        is SerializationException -> UnraidApiException(
            UnraidApiException.Kind.SERVER_ERROR,
            error.message?.take(300),
            error
        )
        is IOException -> UnraidApiException(UnraidApiException.Kind.CONNECTION_ERROR, cause = error)
        else -> UnraidApiException(UnraidApiException.Kind.SERVER_ERROR, error.message?.take(300), error)
    }

    /**
     * Reads the GraphQL error message out of a non-2xx response, falling back to a short excerpt
     * of the raw body so a non-GraphQL error page still reaches the user instead of a bare code.
     */
    private fun graphQlErrorFrom(error: HttpException): String? {
        val body = runCatching { error.response()?.errorBody()?.string() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val parsed = runCatching {
            json.decodeFromString(UnraidGraphQlResponse.serializer(), body).errors?.firstOrNull()?.message
        }.getOrNull()
        return parsed?.takeIf { it.isNotBlank() } ?: body.take(300)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
