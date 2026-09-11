package com.homelab.app.data.remote.dto.wgdashboard

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

// ---------- Response envelope ----------

/**
 * Every WGDashboard endpoint answers with the same envelope:
 * `{"status": true, "message": null, "data": ...}`. A rejected request keeps the shape and
 * explains itself in `message`, so the message is what the UI shows on failure.
 */
@Serializable
data class WgDashboardResponse<T>(
    val status: Boolean = false,
    val message: String? = null,
    val data: T? = null
)

// ---------- Lenient values ----------

/**
 * WGDashboard reads the configuration file with a raw config parser, so `ListenPort` arrives as
 * a quoted string on one server and as a number on the next. Decoding either into a strict
 * String fails the whole response, so the text fields that come from the config file go through
 * this serializer.
 */
object LenientStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WgDashboardLenientString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return ""
        return primitive.content
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/**
 * Traffic counters are doubles in the database but come back as strings from some SQLite
 * builds; an unreadable counter decodes to 0 so one odd peer cannot break the whole tunnel.
 */
object LenientDoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WgDashboardLenientDouble", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return 0.0
        return primitive.content.trim().toDoubleOrNull() ?: 0.0
    }

    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
}

// ---------- Configurations ----------

/** Traffic totals of one tunnel. WGDashboard reports them in gigabytes. */
@Serializable
data class WgDashboardDataUsage(
    @SerialName("Total") @Serializable(with = LenientDoubleSerializer::class) val total: Double = 0.0,
    @SerialName("Sent") @Serializable(with = LenientDoubleSerializer::class) val sent: Double = 0.0,
    @SerialName("Receive") @Serializable(with = LenientDoubleSerializer::class) val received: Double = 0.0
)

/** One WireGuard tunnel as listed by `getWireguardConfigurations`. */
@Serializable
data class WgDashboardConfiguration(
    @SerialName("Name") val name: String = "",
    @SerialName("Status") val status: Boolean = false,
    @SerialName("PublicKey") val publicKey: String? = null,
    @SerialName("Address") val address: String? = null,
    @SerialName("ListenPort")
    @Serializable(with = LenientStringSerializer::class)
    val listenPort: String = "",
    @SerialName("Protocol") val protocol: String? = null,
    @SerialName("ConnectedPeers") val connectedPeers: Int = 0,
    @SerialName("TotalPeers") val totalPeers: Int = 0,
    @SerialName("DataUsage") val dataUsage: WgDashboardDataUsage? = null
) {
    /** True for AmneziaWG tunnels, which WGDashboard serves next to the plain WireGuard ones. */
    val isAmnezia: Boolean get() = protocol.equals("awg", ignoreCase = true)

    val totalGb: Double get() = dataUsage?.total ?: 0.0
}

// ---------- Peers ----------

/**
 * One peer of a tunnel. The names are WGDashboard's database columns, and every counter is in
 * gigabytes: `total_*` is what the running interface reports, `cumu_*` what earlier sessions
 * added up to, so the lifetime total is the sum of both.
 */
@Serializable
data class WgDashboardPeer(
    val id: String = "",
    val name: String? = null,
    val status: String? = null,
    @SerialName("allowed_ip") val allowedIp: String? = null,
    @SerialName("latest_handshake") val latestHandshake: String? = null,
    val endpoint: String? = null,
    @SerialName("remote_endpoint") val remoteEndpoint: String? = null,
    @SerialName("total_receive") @Serializable(with = LenientDoubleSerializer::class) val totalReceive: Double = 0.0,
    @SerialName("total_sent") @Serializable(with = LenientDoubleSerializer::class) val totalSent: Double = 0.0,
    @SerialName("cumu_receive") @Serializable(with = LenientDoubleSerializer::class) val cumulativeReceive: Double = 0.0,
    @SerialName("cumu_sent") @Serializable(with = LenientDoubleSerializer::class) val cumulativeSent: Double = 0.0,
    val notes: String? = null
) {
    /** WGDashboard marks a peer "running" once the interface reports a recent handshake. */
    val isConnected: Boolean get() = status.equals("running", ignoreCase = true)

    /** The peer name, falling back to a shortened public key for peers that were never named. */
    val displayName: String
        get() = name?.trim()?.takeIf { it.isNotBlank() } ?: id.take(12).ifBlank { "?" }

    val receivedGb: Double get() = totalReceive + cumulativeReceive
    val sentGb: Double get() = totalSent + cumulativeSent
    val totalGb: Double get() = receivedGb + sentGb

    /** `latest_handshake` is empty or "No Handshake" while a peer has never connected. */
    val handshake: String?
        get() = latestHandshake?.trim()?.takeIf { it.isNotBlank() && !it.equals("No Handshake", ignoreCase = true) }
}

/** Payload of `getWireguardConfigurationInfo` for one tunnel. */
@Serializable
data class WgDashboardConfigurationDetail(
    val configurationInfo: WgDashboardConfiguration? = null,
    val configurationPeers: List<WgDashboardPeer> = emptyList(),
    val configurationRestrictedPeers: List<WgDashboardPeer> = emptyList()
)

/** Body of the peer endpoints, which take the public keys of the peers to act on. */
@Serializable
data class WgDashboardPeersRequest(
    val peers: List<String>
)

// ---------- UI models ----------

/**
 * What the dashboard renders. [unavailableSections] keeps the reason a part could not be loaded
 * so the rest of the screen still works - a server without the version endpoint still shows its
 * tunnels.
 */
data class WgDashboardOverview(
    val version: String? = null,
    val configurations: List<WgDashboardConfiguration> = emptyList(),
    val unavailableSections: Map<WgDashboardSection, String> = emptyMap()
) {
    val activeTunnels: Int get() = configurations.count { it.status }
    val connectedPeers: Int get() = configurations.sumOf { it.connectedPeers }
    val totalPeers: Int get() = configurations.sumOf { it.totalPeers }
    val totalGb: Double get() = configurations.sumOf { it.totalGb }
}

enum class WgDashboardSection {
    VERSION,
    CONFIGURATIONS
}
