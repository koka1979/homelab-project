package com.homelab.app.data.remote.dto.unraid

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ---------- GraphQL envelope ----------

@Serializable
data class UnraidGraphQlRequest(
    val query: String,
    val variables: JsonObject? = null
)

@Serializable
data class UnraidGraphQlResponse(
    val data: JsonObject? = null,
    val errors: List<UnraidGraphQlError>? = null
)

@Serializable
data class UnraidGraphQlError(
    val message: String = ""
)

// ---------- System ----------

@Serializable
data class UnraidInfoData(
    val info: UnraidInfo? = null
)

@Serializable
data class UnraidInfo(
    val os: UnraidOs? = null,
    val cpu: UnraidCpu? = null,
    val memory: UnraidMemory? = null,
    val versions: UnraidVersions? = null
)

@Serializable
data class UnraidOs(
    val platform: String? = null,
    val distro: String? = null,
    val release: String? = null,
    val hostname: String? = null,
    val uptime: String? = null
)

@Serializable
data class UnraidCpu(
    val manufacturer: String? = null,
    val brand: String? = null,
    val cores: Int? = null,
    val threads: Int? = null
)

@Serializable
data class UnraidMemory(
    val total: Long? = null,
    val free: Long? = null,
    val used: Long? = null
) {
    val usedPercent: Float?
        get() {
            val totalBytes = total?.takeIf { it > 0L } ?: return null
            val usedBytes = used ?: totalBytes - (free ?: return null)
            return (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }
}

@Serializable
data class UnraidVersions(
    val unraid: String? = null,
    val kernel: String? = null
)

// ---------- Array and disks ----------

@Serializable
data class UnraidArrayData(
    val array: UnraidArray? = null
)

@Serializable
data class UnraidArray(
    val state: String? = null,
    val capacity: UnraidArrayCapacity? = null,
    val parities: List<UnraidDisk> = emptyList(),
    val disks: List<UnraidDisk> = emptyList(),
    val caches: List<UnraidDisk> = emptyList()
) {
    /** True while the array is started; the only state in which shares and user data are online. */
    val isStarted: Boolean
        get() = state.equals("STARTED", ignoreCase = true)
}

@Serializable
data class UnraidArrayCapacity(
    val kilobytes: UnraidCapacityValues? = null,
    val disks: UnraidCapacityValues? = null
)

@Serializable
data class UnraidCapacityValues(
    val free: Long? = null,
    val used: Long? = null,
    val total: Long? = null
) {
    val usedPercent: Float?
        get() {
            val totalValue = total?.takeIf { it > 0L } ?: return null
            val usedValue = used ?: return null
            return (usedValue.toFloat() / totalValue.toFloat()).coerceIn(0f, 1f)
        }
}

@Serializable
data class UnraidDisk(
    val id: String? = null,
    val idx: Int? = null,
    val name: String? = null,
    val device: String? = null,
    val size: Long? = null,
    val status: String? = null,
    val temp: Int? = null,
    val numErrors: Long? = null,
    val fsSize: Long? = null,
    val fsFree: Long? = null,
    val fsUsed: Long? = null,
    val type: String? = null
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: device?.takeIf { it.isNotBlank() } ?: id.orEmpty()

    /** A disk is healthy when Unraid reports it as present and in a normal operating state. */
    val isHealthy: Boolean
        get() = status == null || status.equals("DISK_OK", ignoreCase = true)

    val usedPercent: Float?
        get() {
            val totalValue = fsSize?.takeIf { it > 0L } ?: return null
            val usedValue = fsUsed ?: (totalValue - (fsFree ?: return null))
            return (usedValue.toFloat() / totalValue.toFloat()).coerceIn(0f, 1f)
        }
}

// ---------- Shares ----------

@Serializable
data class UnraidSharesData(
    val shares: List<UnraidShare> = emptyList()
)

@Serializable
data class UnraidShare(
    val name: String? = null,
    val comment: String? = null,
    val free: Long? = null,
    val used: Long? = null,
    val size: Long? = null
)

// ---------- Docker ----------

@Serializable
data class UnraidDockerData(
    val docker: UnraidDocker? = null
)

@Serializable
data class UnraidDocker(
    val containers: List<UnraidContainer> = emptyList()
)

@Serializable
data class UnraidContainer(
    val id: String = "",
    val names: List<String> = emptyList(),
    val image: String? = null,
    val state: String? = null,
    val status: String? = null,
    val autoStart: Boolean = false
) {
    val displayName: String
        get() = names.firstOrNull()?.trimStart('/')?.takeIf { it.isNotBlank() } ?: id

    val isRunning: Boolean
        get() = state.equals("RUNNING", ignoreCase = true) || state.equals("running", ignoreCase = true)
}

// ---------- Virtual machines ----------

@Serializable
data class UnraidVmsData(
    val vms: UnraidVms? = null
)

@Serializable
data class UnraidVms(
    val domain: List<UnraidVm> = emptyList()
)

@Serializable
data class UnraidVm(
    val uuid: String = "",
    val name: String? = null,
    val state: String? = null
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: uuid

    val isRunning: Boolean
        get() = state.equals("RUNNING", ignoreCase = true)

    val isPaused: Boolean
        get() = state.equals("PAUSED", ignoreCase = true) || state.equals("PMSUSPENDED", ignoreCase = true)
}

// ---------- Notifications ----------

@Serializable
data class UnraidNotificationsData(
    val notifications: UnraidNotifications? = null
)

@Serializable
data class UnraidNotifications(
    val overview: UnraidNotificationOverview? = null,
    val list: List<UnraidNotification> = emptyList()
)

@Serializable
data class UnraidNotificationOverview(
    val unread: UnraidNotificationCounts? = null
)

@Serializable
data class UnraidNotificationCounts(
    val info: Int = 0,
    val warning: Int = 0,
    val alert: Int = 0,
    val total: Int = 0
)

@Serializable
data class UnraidNotification(
    val id: String = "",
    val title: String? = null,
    val subject: String? = null,
    val description: String? = null,
    val importance: String? = null,
    val timestamp: String? = null
)

// ---------- Aggregated dashboard state ----------

/**
 * Every section is fetched independently so an endpoint that a given Unraid build does not
 * expose degrades that card only, instead of failing the whole dashboard.
 */
data class UnraidOverview(
    val info: UnraidInfo? = null,
    val array: UnraidArray? = null,
    val shares: List<UnraidShare> = emptyList(),
    val containers: List<UnraidContainer> = emptyList(),
    val vms: List<UnraidVm> = emptyList(),
    val notifications: UnraidNotifications? = null,
    val unavailableSections: Set<UnraidSection> = emptySet()
) {
    val runningContainers: Int get() = containers.count { it.isRunning }
    val runningVms: Int get() = vms.count { it.isRunning }
}

enum class UnraidSection {
    SYSTEM,
    ARRAY,
    SHARES,
    DOCKER,
    VMS,
    NOTIFICATIONS
}
