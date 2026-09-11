package com.homelab.app.domain.dyndns

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the updater remembers about one instance between runs. */
data class DynDnsInstanceState(
    val autoUpdate: Boolean = false,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val updateIpv4: Boolean = true,
    val updateIpv6: Boolean = true,
    /** The addresses last accepted by OVH, so an unchanged address is not sent again. */
    val lastIpv4: String? = null,
    val lastIpv6: String? = null,
    val lastRunAt: Long = 0L,
    val lastSummary: String? = null,
    val lastRunSucceeded: Boolean = false
) {
    val types: Set<DynDnsRecordType>
        get() = buildSet {
            if (updateIpv4) add(DynDnsRecordType.IPV4)
            if (updateIpv6) add(DynDnsRecordType.IPV6)
        }

    val lastSent: Map<DynDnsRecordType, String?>
        get() = mapOf(
            DynDnsRecordType.IPV4 to lastIpv4,
            DynDnsRecordType.IPV6 to lastIpv6
        )

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 60

        /** WorkManager refuses anything shorter than a quarter of an hour. */
        const val MIN_INTERVAL_MINUTES = 15

        val INTERVAL_CHOICES = listOf(15, 30, 60, 180, 360, 720)
    }
}

/**
 * Stores the updater state per instance. The credentials stay in the secure credential store
 * with the instance itself; only settings and the last result live here.
 */
@Singleton
class DynDnsStateStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ovh_dyndns_state", Context.MODE_PRIVATE)

    fun state(instanceId: String): DynDnsInstanceState = DynDnsInstanceState(
        autoUpdate = prefs.getBoolean(key(instanceId, AUTO_UPDATE), false),
        intervalMinutes = prefs.getInt(key(instanceId, INTERVAL), DynDnsInstanceState.DEFAULT_INTERVAL_MINUTES)
            .coerceAtLeast(DynDnsInstanceState.MIN_INTERVAL_MINUTES),
        updateIpv4 = prefs.getBoolean(key(instanceId, UPDATE_IPV4), true),
        updateIpv6 = prefs.getBoolean(key(instanceId, UPDATE_IPV6), true),
        lastIpv4 = prefs.getString(key(instanceId, LAST_IPV4), null),
        lastIpv6 = prefs.getString(key(instanceId, LAST_IPV6), null),
        lastRunAt = prefs.getLong(key(instanceId, LAST_RUN_AT), 0L),
        lastSummary = prefs.getString(key(instanceId, LAST_SUMMARY), null),
        lastRunSucceeded = prefs.getBoolean(key(instanceId, LAST_SUCCESS), false)
    )

    fun updateSettings(
        instanceId: String,
        autoUpdate: Boolean? = null,
        intervalMinutes: Int? = null,
        updateIpv4: Boolean? = null,
        updateIpv6: Boolean? = null
    ): DynDnsInstanceState {
        prefs.edit().apply {
            autoUpdate?.let { putBoolean(key(instanceId, AUTO_UPDATE), it) }
            intervalMinutes?.let {
                putInt(key(instanceId, INTERVAL), it.coerceAtLeast(DynDnsInstanceState.MIN_INTERVAL_MINUTES))
            }
            updateIpv4?.let { putBoolean(key(instanceId, UPDATE_IPV4), it) }
            updateIpv6?.let { putBoolean(key(instanceId, UPDATE_IPV6), it) }
        }.apply()
        return state(instanceId)
    }

    /**
     * Records what a run did. Only an address OVH actually accepted is remembered, so a failed
     * update is retried on the next run instead of being skipped as unchanged.
     */
    fun recordRun(instanceId: String, report: DynDnsUpdateReport, summary: String) {
        prefs.edit().apply {
            report.outcomes.forEach { outcome ->
                val accepted = when (outcome) {
                    is DynDnsUpdateOutcome.Updated -> outcome.address
                    is DynDnsUpdateOutcome.Unchanged -> outcome.address
                    else -> null
                }
                if (accepted != null) {
                    when (outcome.type) {
                        DynDnsRecordType.IPV4 -> putString(key(instanceId, LAST_IPV4), accepted)
                        DynDnsRecordType.IPV6 -> putString(key(instanceId, LAST_IPV6), accepted)
                    }
                }
            }
            putLong(key(instanceId, LAST_RUN_AT), report.finishedAt)
            putString(key(instanceId, LAST_SUMMARY), summary)
            putBoolean(key(instanceId, LAST_SUCCESS), report.succeeded)
        }.apply()
    }

    /** Drops everything stored for an instance that was removed. */
    fun forget(instanceId: String) {
        prefs.edit().apply {
            listOf(
                AUTO_UPDATE, INTERVAL, UPDATE_IPV4, UPDATE_IPV6,
                LAST_IPV4, LAST_IPV6, LAST_RUN_AT, LAST_SUMMARY, LAST_SUCCESS
            ).forEach { remove(key(instanceId, it)) }
        }.apply()
    }

    private fun key(instanceId: String, suffix: String) = "$instanceId.$suffix"

    private companion object {
        const val AUTO_UPDATE = "autoUpdate"
        const val INTERVAL = "intervalMinutes"
        const val UPDATE_IPV4 = "updateIpv4"
        const val UPDATE_IPV6 = "updateIpv6"
        const val LAST_IPV4 = "lastIpv4"
        const val LAST_IPV6 = "lastIpv6"
        const val LAST_RUN_AT = "lastRunAt"
        const val LAST_SUMMARY = "lastSummary"
        const val LAST_SUCCESS = "lastRunSucceeded"
    }
}
