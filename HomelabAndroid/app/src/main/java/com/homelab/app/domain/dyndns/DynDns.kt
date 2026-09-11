package com.homelab.app.domain.dyndns

/** The two address families a DynHost record can carry. */
enum class DynDnsRecordType(val recordName: String) {
    IPV4("A"),
    IPV6("AAAA")
}

/** Why one address family could not be determined. */
enum class DynDnsAddressError {
    /** The network carries no address of this family - the usual case for IPv6 on mobile data. */
    NO_ADDRESS,

    /** The address service could not be reached at all. */
    UNREACHABLE,

    /** Something answered, but not with an address. */
    UNEXPECTED_ANSWER
}

/** The public addresses of the current network, as seen from the internet. */
data class DynDnsAddresses(
    val ipv4: String? = null,
    val ipv6: String? = null,
    /** Why a family could not be determined, e.g. a network without IPv6. */
    val ipv4Error: DynDnsAddressError? = null,
    val ipv6Error: DynDnsAddressError? = null
) {
    val hasAny: Boolean get() = ipv4 != null || ipv6 != null

    fun addressFor(type: DynDnsRecordType): String? = when (type) {
        DynDnsRecordType.IPV4 -> ipv4
        DynDnsRecordType.IPV6 -> ipv6
    }

    fun errorFor(type: DynDnsRecordType): DynDnsAddressError? = when (type) {
        DynDnsRecordType.IPV4 -> ipv4Error
        DynDnsRecordType.IPV6 -> ipv6Error
    }
}

/**
 * What the DynHost endpoint made of one update. The protocol answers in plain text, so the
 * server's own keyword is kept in [code]: it is the only thing that tells a wrong password
 * (`badauth`) from a hostname that is not a DynHost record (`nohost`).
 */
sealed interface DynDnsUpdateOutcome {
    val type: DynDnsRecordType

    /** The record now points at [address]. */
    data class Updated(override val type: DynDnsRecordType, val address: String) : DynDnsUpdateOutcome

    /** The record already pointed at [address]; OVH counts repeated updates as abuse. */
    data class Unchanged(override val type: DynDnsRecordType, val address: String) : DynDnsUpdateOutcome

    /** The update was refused. [code] is the server's keyword, [message] the readable reason. */
    data class Failed(
        override val type: DynDnsRecordType,
        val code: String,
        val message: String
    ) : DynDnsUpdateOutcome

    /** This family was skipped because the network has no address of it. */
    data class Skipped(
        override val type: DynDnsRecordType,
        val reason: DynDnsAddressError
    ) : DynDnsUpdateOutcome
}

/** The result of one run over both families. */
data class DynDnsUpdateReport(
    val hostname: String,
    val outcomes: List<DynDnsUpdateOutcome>,
    val finishedAt: Long = System.currentTimeMillis()
) {
    val succeeded: Boolean
        get() = outcomes.any { it is DynDnsUpdateOutcome.Updated || it is DynDnsUpdateOutcome.Unchanged } &&
            outcomes.none { it is DynDnsUpdateOutcome.Failed }

    val failures: List<DynDnsUpdateOutcome.Failed>
        get() = outcomes.filterIsInstance<DynDnsUpdateOutcome.Failed>()
}
