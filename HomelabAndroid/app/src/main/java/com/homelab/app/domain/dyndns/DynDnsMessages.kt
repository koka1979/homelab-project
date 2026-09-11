package com.homelab.app.domain.dyndns

import android.content.Context
import com.homelab.app.R

/**
 * Turns the protocol's keywords into something a person can act on.
 *
 * The keyword is what the server said; the text here is what the user should do about it. An
 * answer nobody has seen before keeps the server's own wording rather than being hidden behind
 * a generic phrase.
 */
fun dynDnsFailureMessage(context: Context, failure: DynDnsUpdateOutcome.Failed): String =
    when (failure.code) {
        "badauth" -> context.getString(R.string.dyndns_fail_badauth)
        "nohost" -> context.getString(R.string.dyndns_fail_nohost, failure.type.recordName)
        "notfqdn" -> context.getString(R.string.dyndns_fail_notfqdn)
        "abuse" -> context.getString(R.string.dyndns_fail_abuse)
        "badsys", "badagent" -> context.getString(R.string.dyndns_fail_rejected)
        "dnserr", "911" -> context.getString(R.string.dyndns_fail_server)
        "connection" -> context.getString(R.string.dyndns_fail_connection)
        else -> failure.message
    }

/** Why an address family is missing, in the user's words. */
fun dynDnsAddressErrorMessage(context: Context, error: DynDnsAddressError): String =
    when (error) {
        DynDnsAddressError.NO_ADDRESS -> context.getString(R.string.dyndns_address_none)
        DynDnsAddressError.UNREACHABLE -> context.getString(R.string.dyndns_address_unreachable)
        DynDnsAddressError.UNEXPECTED_ANSWER -> context.getString(R.string.dyndns_address_unexpected)
    }
