package com.homelab.app.data.repository

import com.homelab.app.data.remote.TlsClientSelector
import com.homelab.app.domain.action.ActionRisk
import com.homelab.app.domain.action.ControlledActionRequest
import com.homelab.app.domain.dyndns.DynDnsAddressError
import com.homelab.app.domain.dyndns.DynDnsAddresses
import com.homelab.app.domain.dyndns.DynDnsRecordType
import com.homelab.app.domain.dyndns.DynDnsUpdateOutcome
import com.homelab.app.domain.dyndns.DynDnsUpdateReport
import com.homelab.app.domain.model.ServiceInstance
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** The mutating DynDNS operation, for the controlled-action pipeline. */
enum class DynDnsAction(val actionId: String, val risk: ActionRisk) {
    UPDATE_RECORD("dyndns.record.update", ActionRisk.MEDIUM);

    fun controlledRequest(
        instanceId: String,
        targetRef: String,
        confirmed: Boolean,
        requestId: String = UUID.randomUUID().toString(),
        requestedAt: String = Instant.now().toString(),
        idempotencyKey: String = UUID.randomUUID().toString()
    ) = ControlledActionRequest(
        id = requestId,
        providerRef = "ovh-dyndns:${instanceId.trim().lowercase(Locale.ROOT)}",
        action = actionId,
        targetRef = targetRef,
        risk = risk,
        requestedAt = requestedAt,
        idempotencyKey = idempotencyKey,
        confirmed = confirmed
    )
}

class DynDnsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Carries why one family could not be detected, so the UI can say it in the user's words. */
internal class DynDnsDetectionException(val reason: DynDnsAddressError) : Exception(reason.name)

/**
 * Updates an OVH DynHost record with the address the current network has on the internet.
 *
 * DynHost speaks the plain dyndns2 protocol: a GET against `nic/update` with HTTP basic auth,
 * answered in plain text. One hostname can carry an A and an AAAA record at the same time, and
 * each is updated by sending the matching address - `myip` is mandatory for IPv6 because the
 * endpoint itself is not reachable over IPv6.
 */
@Singleton
class OvhDynDnsRepository @Inject constructor(
    private val tlsClientSelector: TlsClientSelector
) {

    // ---------- Address detection ----------

    /**
     * Asks two single-stack echo services for the address this device shows to the internet.
     * The hosts resolve to one family only, which is what pins each request to that family;
     * a family the network does not carry fails and is reported as unavailable rather than
     * taking the other one down with it.
     */
    suspend fun detectAddresses(): DynDnsAddresses = coroutineScope {
        val ipv4Job = async { runCatching { fetchAddress(IPV4_ECHO_URL, DynDnsRecordType.IPV4) } }
        val ipv6Job = async { runCatching { fetchAddress(IPV6_ECHO_URL, DynDnsRecordType.IPV6) } }

        val ipv4 = ipv4Job.await()
        val ipv6 = ipv6Job.await()

        DynDnsAddresses(
            ipv4 = ipv4.getOrNull(),
            ipv6 = ipv6.getOrNull(),
            ipv4Error = ipv4.exceptionOrNull()?.let(::classifyDetectionFailure),
            ipv6Error = ipv6.exceptionOrNull()?.let(::classifyDetectionFailure)
        )
    }

    private suspend fun fetchAddress(url: String, expected: DynDnsRecordType): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().addHeader("Accept", "text/plain").build()
            val body = tlsClientSelector.forAllowSelfSigned(false).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DynDnsDetectionException(DynDnsAddressError.UNEXPECTED_ANSWER)
                }
                response.body?.string().orEmpty()
            }

            val candidate = body.trim()
            if (!matchesFamily(candidate, expected)) {
                throw DynDnsDetectionException(DynDnsAddressError.UNEXPECTED_ANSWER)
            }
            candidate
        }

    // ---------- Updates ----------

    /**
     * Sends every address in [addresses] the record is supposed to carry. A family that the
     * network does not have, or that already points at the same address, is skipped: OVH treats
     * repeated identical updates as abuse.
     */
    suspend fun update(
        instance: ServiceInstance,
        addresses: DynDnsAddresses,
        types: Set<DynDnsRecordType>,
        lastSent: Map<DynDnsRecordType, String?> = emptyMap(),
        force: Boolean = false
    ): DynDnsUpdateReport {
        val hostname = hostnameOf(instance)
            ?: throw DynDnsException("This instance has no hostname configured")
        val username = instance.username?.trim().orEmpty()
        val password = instance.password?.trim().orEmpty()
        if (username.isBlank() || password.isBlank()) {
            throw DynDnsException("DynHost user name and password are required")
        }

        val outcomes = types.sortedBy { it.ordinal }.map { type ->
            val address = addresses.addressFor(type)
            when {
                address == null -> DynDnsUpdateOutcome.Skipped(
                    type,
                    addresses.errorFor(type) ?: DynDnsAddressError.NO_ADDRESS
                )
                !force && lastSent[type] == address -> DynDnsUpdateOutcome.Unchanged(type, address)
                else -> send(hostname, username, password, type, address)
            }
        }
        return DynDnsUpdateReport(hostname = hostname, outcomes = outcomes)
    }

    private suspend fun send(
        hostname: String,
        username: String,
        password: String,
        type: DynDnsRecordType,
        address: String
    ): DynDnsUpdateOutcome = withContext(Dispatchers.IO) {
        val credentials = Base64.getEncoder()
            .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

        var lastError: DynDnsUpdateOutcome.Failed? = null
        for (endpoint in UPDATE_ENDPOINTS) {
            val url = endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("system", "dyndns")
                .addQueryParameter("hostname", hostname)
                .addQueryParameter("myip", address)
                .build()
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Basic $credentials")
                .addHeader("User-Agent", USER_AGENT)
                .build()

            try {
                val outcome = tlsClientSelector.forAllowSelfSigned(false)
                    .newCall(request)
                    .execute()
                    .use { response ->
                        parseDynDnsResponse(
                            type = type,
                            code = response.code,
                            body = runCatching { response.body?.string() }.getOrNull(),
                            address = address
                        )
                    }
                // The two endpoints are different gateways in front of the same service and do
                // not always know the same records, so a "no record" from one is worth asking
                // the other about. Every other verdict is final.
                if (outcome is DynDnsUpdateOutcome.Failed && outcome.code == "nohost") {
                    lastError = outcome
                    continue
                }
                return@withContext outcome
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                lastError = DynDnsUpdateOutcome.Failed(
                    type,
                    "connection",
                    error.message ?: "No connection"
                )
            }
        }
        lastError ?: DynDnsUpdateOutcome.Failed(type, "connection", "No connection")
    }

    /**
     * A name that does not resolve is the normal answer of an IPv4-only network to the IPv6-only
     * echo host: the family is simply absent, which is not an error worth a technical message.
     */
    private fun classifyDetectionFailure(error: Throwable): DynDnsAddressError = when (error) {
        is DynDnsDetectionException -> error.reason
        is UnknownHostException -> DynDnsAddressError.NO_ADDRESS
        is ConnectException, is NoRouteToHostException -> DynDnsAddressError.NO_ADDRESS
        is IOException -> DynDnsAddressError.UNREACHABLE
        else -> DynDnsAddressError.UNEXPECTED_ANSWER
    }

    companion object {
        /** OVH's current DynHost endpoint, with the legacy host as a fallback. */
        val UPDATE_ENDPOINTS = listOf(
            "https://dns.eu.ovhapis.com/nic/update",
            "https://www.ovh.com/nic/update"
        )
        const val IPV4_ECHO_URL = "https://api4.ipify.org"
        const val IPV6_ECHO_URL = "https://api6.ipify.org"
        private const val USER_AGENT = "Homelab-Android DynDNS/1.0"
    }
}

/**
 * The hostname of a DynHost instance. It is stored as the instance URL, so both a bare
 * `home.example.com` and a pasted `https://home.example.com/` end up as the same host.
 */
internal fun hostnameOf(instance: ServiceInstance): String? {
    val raw = instance.url.trim().takeIf { it.isNotBlank() } ?: return null
    val host = runCatching { URI(raw).host }.getOrNull()
        ?: raw.substringAfter("://").substringBefore('/').substringBefore(':')
    return host.trim().trimEnd('.').takeIf { it.isNotBlank() }
}

private val IPV4_PATTERN =
    Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

/** Everything an IPv6 literal may consist of - deliberately no letters beyond hex digits. */
private val IPV6_CHARACTERS = Regex("^[0-9A-Fa-f:.]+$")

/**
 * True when [candidate] is a literal address of exactly [expected]'s family.
 *
 * The check never resolves names: an echo service answering with an error page or a host name
 * must not end up published as the record's address, and `InetAddress.getByName` would happily
 * look such a name up. The IPv6 branch may use it because a string full of colons can never be
 * a host name, so there is nothing to resolve.
 */
internal fun matchesFamily(candidate: String, expected: DynDnsRecordType): Boolean {
    if (candidate.isBlank() || candidate.any { it.isWhitespace() }) return false
    return when (expected) {
        DynDnsRecordType.IPV4 -> IPV4_PATTERN.matches(candidate)
        DynDnsRecordType.IPV6 -> candidate.contains(':') &&
            IPV6_CHARACTERS.matches(candidate) &&
            runCatching { InetAddress.getByName(candidate) }.getOrNull() is Inet6Address
    }
}

/**
 * Reads one dyndns2 answer. The body carries the verdict even when the status code is 200, so
 * the keyword decides - and every refusal keeps its keyword, because that is what tells the user
 * whether to fix the password, the hostname or the record type.
 */
/** OVH answers some refusals as JSON rather than dyndns2 text; this pulls out its message. */
private val JSON_MESSAGE = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"")

internal fun parseDynDnsResponse(
    type: DynDnsRecordType,
    code: Int,
    body: String?,
    address: String
): DynDnsUpdateOutcome {
    val text = body?.trim().orEmpty()
    val keyword = text.substringBefore('\n').trim().substringBefore(' ').lowercase(Locale.ROOT)
    val returnedAddress = text.substringBefore('\n').trim().substringAfter(' ', "").trim()
    val jsonMessage = JSON_MESSAGE.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    val detail = jsonMessage ?: text

    return when {
        keyword == "good" -> DynDnsUpdateOutcome.Updated(type, returnedAddress.ifBlank { address })
        keyword == "nochg" -> DynDnsUpdateOutcome.Unchanged(type, returnedAddress.ifBlank { address })
        keyword == "badauth" || code == 401 -> DynDnsUpdateOutcome.Failed(
            type,
            "badauth",
            "The DynHost user name or password was rejected"
        )
        // A host name without a DynHost record of this family is answered as `nohost` by the
        // dyndns2 endpoint and as a JSON 404 by the newer one; both mean the same thing.
        keyword == "nohost" ||
            code == 404 ||
            jsonMessage?.contains("no record found", ignoreCase = true) == true ->
            DynDnsUpdateOutcome.Failed(
                type,
                "nohost",
                "This host name has no DynHost ${type.recordName} record"
            )
        keyword == "notfqdn" -> DynDnsUpdateOutcome.Failed(
            type,
            "notfqdn",
            "The host name is not a fully qualified domain name"
        )
        keyword == "abuse" -> DynDnsUpdateOutcome.Failed(
            type,
            "abuse",
            "OVH blocked this host name for too many updates"
        )
        keyword == "badsys" -> DynDnsUpdateOutcome.Failed(type, "badsys", "The server rejected the request")
        keyword == "badagent" -> DynDnsUpdateOutcome.Failed(type, "badagent", "The server rejected this client")
        keyword == "dnserr" || keyword == "911" -> DynDnsUpdateOutcome.Failed(
            type,
            keyword,
            "OVH reported a problem on their side, try again later"
        )
        code !in 200..299 -> DynDnsUpdateOutcome.Failed(
            type,
            "http-$code",
            listOf("HTTP $code", detail).filter { it.isNotBlank() }.joinToString(": ")
        )
        else -> DynDnsUpdateOutcome.Failed(
            type,
            "unknown",
            detail.ifBlank { "The server gave no answer" }
        )
    }
}
