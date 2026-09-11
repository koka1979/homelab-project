package com.homelab.app.domain.dyndns

import android.content.Context
import com.homelab.app.R
import com.homelab.app.data.repository.DynDnsException
import com.homelab.app.data.repository.OvhDynDnsRepository
import com.homelab.app.data.repository.ServicesRepository
import com.homelab.app.util.ServiceType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when the instance a scheduled run belongs to has been deleted. */
class DynDnsInstanceMissingException(message: String) : Exception(message)

/** One finished run, ready to be shown or stored. */
data class DynDnsRunResult(
    val report: DynDnsUpdateReport,
    val summary: String,
    val addresses: DynDnsAddresses
)

/**
 * Runs one update for an instance: detect the current addresses, send the families the instance
 * is configured for, remember the result.
 *
 * Both the screen and the background worker go through here so a manual and an automatic run
 * behave identically - including the rule that an address OVH already holds is not sent again.
 */
@Singleton
class DynDnsUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: OvhDynDnsRepository,
    private val servicesRepository: ServicesRepository,
    private val stateStore: DynDnsStateStore
) {

    suspend fun run(instanceId: String, force: Boolean): DynDnsRunResult {
        val instance = servicesRepository.getInstance(instanceId)
            ?: throw DynDnsInstanceMissingException(context.getString(R.string.dyndns_error_unknown_instance))
        require(instance.type == ServiceType.OVH_DYNDNS) {
            "Instance $instanceId is not an OVH DynDNS instance"
        }

        val state = stateStore.state(instanceId)
        val types = state.types
        if (types.isEmpty()) {
            throw DynDnsException(context.getString(R.string.dyndns_error_no_family))
        }

        val addresses = repository.detectAddresses()
        val report = repository.update(
            instance = instance,
            addresses = addresses,
            types = types,
            lastSent = state.lastSent,
            force = force
        )
        val summary = summarize(report)
        stateStore.recordRun(instanceId, report, summary)
        return DynDnsRunResult(report = report, summary = summary, addresses = addresses)
    }

    /** One line per family, in the user's language, e.g. "A: updated to 1.2.3.4". */
    fun summarize(report: DynDnsUpdateReport): String =
        report.outcomes.joinToString("\n") { outcome ->
            val record = outcome.type.recordName
            when (outcome) {
                is DynDnsUpdateOutcome.Updated ->
                    context.getString(R.string.dyndns_outcome_updated, record, outcome.address)
                is DynDnsUpdateOutcome.Unchanged ->
                    context.getString(R.string.dyndns_outcome_unchanged, record, outcome.address)
                is DynDnsUpdateOutcome.Skipped -> context.getString(
                    R.string.dyndns_outcome_skipped,
                    record,
                    dynDnsAddressErrorMessage(context, outcome.reason)
                )
                is DynDnsUpdateOutcome.Failed -> context.getString(
                    R.string.dyndns_outcome_failed,
                    record,
                    dynDnsFailureMessage(context, outcome)
                )
            }
        }
}
