package com.homelab.app.ui.unraid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homelab.app.R
import com.homelab.app.data.remote.dto.unraid.UnraidArray
import com.homelab.app.data.remote.dto.unraid.UnraidContainer
import com.homelab.app.data.remote.dto.unraid.UnraidDisk
import com.homelab.app.data.remote.dto.unraid.UnraidInfo
import com.homelab.app.data.remote.dto.unraid.UnraidNotifications
import com.homelab.app.data.remote.dto.unraid.UnraidOverview
import com.homelab.app.data.remote.dto.unraid.UnraidSection
import com.homelab.app.data.remote.dto.unraid.UnraidShare
import com.homelab.app.data.remote.dto.unraid.UnraidVm
import com.homelab.app.data.repository.UnraidAction
import com.homelab.app.ui.common.ErrorScreen
import com.homelab.app.ui.components.ServiceInstancePicker
import com.homelab.app.util.ServiceType
import com.homelab.app.util.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnraidDashboardScreen(
    viewModel: UnraidViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToInstance: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val busyTarget by viewModel.busyTarget.collectAsStateWithLifecycle()

    val currentInstance = instances.find { it.id == viewModel.instanceId }
    val title = currentInstance?.label?.takeIf { it.isNotBlank() } ?: ServiceType.UNRAID.displayName

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingAction by remember { mutableStateOf<PendingUnraidAction?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val onAction: (UnraidAction, String?, String) -> Unit = { action, targetId, targetLabel ->
        if (action.requiresConfirmation) {
            pendingAction = PendingUnraidAction(action, targetId, targetLabel)
        } else {
            viewModel.runAction(action, targetId, confirmed = false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (instances.size > 1) {
                ServiceInstancePicker(
                    instances = instances,
                    selectedInstanceId = viewModel.instanceId,
                    onInstanceSelected = { onNavigateToInstance(it.id) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            when (val current = state) {
                is UiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                is UiState.Error -> ErrorScreen(
                    message = current.message,
                    onRetry = current.retryAction ?: { viewModel.refresh() }
                )

                is UiState.Success -> PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh() }
                ) {
                    UnraidContent(
                        overview = current.data,
                        busyTarget = busyTarget,
                        onAction = onAction
                    )
                }
            }
        }
    }

    pendingAction?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(R.string.unraid_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.unraid_confirm_message,
                        stringResource(pending.action.labelRes),
                        pending.targetLabel
                    )
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.runAction(pending.action, pending.targetId, confirmed = true)
                    pendingAction = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun UnraidContent(
    overview: UnraidOverview,
    busyTarget: String?,
    onAction: (UnraidAction, String?, String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        overview.info?.let { info ->
            item { SystemCard(info) }
        }

        overview.array?.let { array ->
            item { ArrayCard(array, busyTarget, onAction) }
            if (array.parities.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.unraid_parity_disks)) }
                items(array.parities, key = { "parity-${it.id ?: it.displayName}" }) { DiskRow(it) }
            }
            if (array.disks.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.unraid_data_disks)) }
                items(array.disks, key = { "data-${it.id ?: it.displayName}" }) { DiskRow(it) }
            }
            if (array.caches.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.unraid_cache_disks)) }
                items(array.caches, key = { "cache-${it.id ?: it.displayName}" }) { DiskRow(it) }
            }
        }

        if (overview.containers.isNotEmpty()) {
            item {
                SectionHeader(
                    stringResource(
                        R.string.unraid_section_docker_count,
                        overview.runningContainers,
                        overview.containers.size
                    )
                )
            }
            items(overview.containers, key = { "container-${it.id}" }) { container ->
                ContainerRow(container, busyTarget, onAction)
            }
        }

        if (overview.vms.isNotEmpty()) {
            item {
                SectionHeader(
                    stringResource(
                        R.string.unraid_section_vms_count,
                        overview.runningVms,
                        overview.vms.size
                    )
                )
            }
            items(overview.vms, key = { "vm-${it.uuid}" }) { vm ->
                VmRow(vm, busyTarget, onAction)
            }
        }

        if (overview.shares.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.unraid_section_shares)) }
            items(overview.shares, key = { "share-${it.name}" }) { ShareRow(it) }
        }

        overview.notifications?.let { notifications ->
            if (notifications.list.isNotEmpty() || (notifications.overview?.unread?.total ?: 0) > 0) {
                item { SectionHeader(stringResource(R.string.unraid_section_notifications)) }
                item { NotificationsCard(notifications) }
            }
        }

        if (overview.unavailableSections.isNotEmpty()) {
            item { UnavailableSectionsCard(overview.unavailableSections) }
        }
    }
}

// ---------- Cards ----------

@Composable
private fun SystemCard(info: UnraidInfo) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = info.os?.hostname?.takeIf { it.isNotBlank() }
                        ?: info.os?.distro?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.unraid_section_system),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            info.versions?.unraid?.takeIf { it.isNotBlank() }?.let {
                DetailRow(stringResource(R.string.unraid_version), it)
            }
            info.versions?.kernel?.takeIf { it.isNotBlank() }?.let {
                DetailRow(stringResource(R.string.unraid_kernel), it)
            }
            info.cpu?.brand?.takeIf { it.isNotBlank() }?.let { brand ->
                val cores = info.cpu?.cores
                val threads = info.cpu?.threads
                val suffix = if (cores != null && threads != null) " ($cores/$threads)" else ""
                DetailRow(stringResource(R.string.unraid_cpu), brand + suffix)
            }

            val memory = info.memory
            val memoryPercent = memory?.usedPercent
            if (memory != null && memoryPercent != null) {
                val used = memory.used ?: ((memory.total ?: 0L) - (memory.free ?: 0L))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${stringResource(R.string.unraid_memory)}: " +
                                "${formatBytes(used)} / ${formatBytes(memory.total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = { memoryPercent },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ArrayCard(
    array: UnraidArray,
    busyTarget: String?,
    onAction: (UnraidAction, String?, String) -> Unit
) {
    val arrayLabel = stringResource(R.string.unraid_section_array)
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = arrayLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                StateChip(
                    label = array.state.orEmpty().ifBlank { stringResource(R.string.unraid_state_unknown) },
                    positive = array.isStarted
                )
            }

            val capacity = array.capacity?.kilobytes
            val capacityPercent = capacity?.usedPercent
            if (capacity != null && capacityPercent != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        // The Unraid API reports array capacity in kilobytes.
                        text = "${formatBytes(capacity.used?.times(1024))} / ${formatBytes(capacity.total?.times(1024))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { capacityPercent },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                }
            }

            ActionRow(
                busy = busyTarget != null,
                actions = buildList {
                    if (array.isStarted) {
                        add(UnraidAction.ARRAY_STOP)
                        add(UnraidAction.PARITY_CHECK_START)
                        add(UnraidAction.PARITY_CHECK_CANCEL)
                    } else {
                        add(UnraidAction.ARRAY_START)
                    }
                },
                onAction = { action -> onAction(action, null, arrayLabel) }
            )
        }
    }
}

@Composable
private fun DiskRow(disk: UnraidDisk) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = disk.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val details = listOfNotNull(
                        disk.device?.takeIf { it.isNotBlank() },
                        disk.temp?.let { "$it °C" },
                        disk.numErrors?.takeIf { it > 0 }?.let { stringResource(R.string.unraid_disk_errors, it) }
                    ).joinToString(" · ")
                    if (details.isNotBlank()) {
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                StateChip(
                    label = disk.status.orEmpty().ifBlank { stringResource(R.string.unraid_state_unknown) },
                    positive = disk.isHealthy
                )
            }

            disk.usedPercent?.let { percent ->
                LinearProgressIndicator(
                    progress = { percent },
                    modifier = Modifier.fillMaxWidth().height(5.dp)
                )
            }
        }
    }
}

@Composable
private fun ContainerRow(
    container: UnraidContainer,
    busyTarget: String?,
    onAction: (UnraidAction, String?, String) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = container.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    container.image?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                StateChip(
                    label = container.state.orEmpty().ifBlank { stringResource(R.string.unraid_state_unknown) },
                    positive = container.isRunning
                )
            }

            ActionRow(
                busy = busyTarget == container.id,
                actions = if (container.isRunning) {
                    listOf(UnraidAction.CONTAINER_RESTART, UnraidAction.CONTAINER_STOP)
                } else {
                    listOf(UnraidAction.CONTAINER_START)
                },
                onAction = { action -> onAction(action, container.id, container.displayName) }
            )
        }
    }
}

@Composable
private fun VmRow(
    vm: UnraidVm,
    busyTarget: String?,
    onAction: (UnraidAction, String?, String) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = vm.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StateChip(
                    label = vm.state.orEmpty().ifBlank { stringResource(R.string.unraid_state_unknown) },
                    positive = vm.isRunning
                )
            }

            ActionRow(
                busy = busyTarget == vm.uuid,
                actions = when {
                    vm.isPaused -> listOf(UnraidAction.VM_RESUME, UnraidAction.VM_STOP)
                    vm.isRunning -> listOf(
                        UnraidAction.VM_PAUSE,
                        UnraidAction.VM_REBOOT,
                        UnraidAction.VM_STOP,
                        UnraidAction.VM_FORCE_STOP
                    )
                    else -> listOf(UnraidAction.VM_START)
                },
                onAction = { action -> onAction(action, vm.uuid, vm.displayName) }
            )
        }
    }
}

@Composable
private fun ShareRow(share: UnraidShare) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = share.name.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                share.comment?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                // Share sizes are reported in kilobytes like the array capacity.
                text = "${formatBytes(share.used?.times(1024))} / ${formatBytes(share.size?.times(1024))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NotificationsCard(notifications: UnraidNotifications) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            notifications.overview?.unread?.let { unread ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.unraid_notifications_summary,
                            unread.total,
                            unread.alert,
                            unread.warning
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            notifications.list.take(10).forEach { notification ->
                Column {
                    Text(
                        text = notification.title.orEmpty().ifBlank { notification.subject.orEmpty() },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    notification.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnavailableSectionsCard(sections: Set<UnraidSection>) {
    val names = sections.map { stringResource(it.labelRes) }.sorted().joinToString(", ")
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.unraid_sections_unavailable, names),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- Small building blocks ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(
    busy: Boolean,
    actions: List<UnraidAction>,
    onAction: (UnraidAction) -> Unit
) {
    if (actions.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action ->
            if (action.isDestructive) {
                OutlinedButton(onClick = { onAction(action) }, enabled = !busy) {
                    ActionLabel(action, busy)
                }
            } else {
                Button(onClick = { onAction(action) }, enabled = !busy) {
                    ActionLabel(action, busy)
                }
            }
        }
    }
}

@Composable
private fun ActionLabel(action: UnraidAction, busy: Boolean) {
    if (busy) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    } else {
        Text(stringResource(action.labelRes))
    }
}

@Composable
private fun StateChip(label: String, positive: Boolean) {
    val color = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    AssistChip(
        onClick = {},
        enabled = false,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = color,
            disabledContainerColor = color.copy(alpha = 0.12f)
        )
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

/** Byte formatter for values the Unraid API reports as plain counts. */
private fun formatBytes(value: Long?): String {
    val bytes = value ?: return "—"
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var remaining = bytes.toDouble()
    var unitIndex = 0
    while (remaining >= 1024.0 && unitIndex < units.lastIndex) {
        remaining /= 1024.0
        unitIndex++
    }
    return String.format(java.util.Locale.US, "%.1f %s", remaining, units[unitIndex])
}

private val UnraidAction.labelRes: Int
    get() = when (this) {
        UnraidAction.CONTAINER_START, UnraidAction.VM_START, UnraidAction.ARRAY_START ->
            R.string.unraid_action_start
        UnraidAction.CONTAINER_STOP, UnraidAction.VM_STOP, UnraidAction.ARRAY_STOP ->
            R.string.unraid_action_stop
        UnraidAction.CONTAINER_RESTART, UnraidAction.VM_REBOOT -> R.string.unraid_action_restart
        UnraidAction.VM_PAUSE -> R.string.unraid_action_pause
        UnraidAction.VM_RESUME -> R.string.unraid_action_resume
        UnraidAction.VM_FORCE_STOP -> R.string.unraid_action_force_stop
        UnraidAction.PARITY_CHECK_START -> R.string.unraid_action_parity_start
        UnraidAction.PARITY_CHECK_CANCEL -> R.string.unraid_action_parity_cancel
    }

private val UnraidAction.isDestructive: Boolean
    get() = this == UnraidAction.CONTAINER_STOP ||
        this == UnraidAction.VM_STOP ||
        this == UnraidAction.VM_FORCE_STOP ||
        this == UnraidAction.ARRAY_STOP ||
        this == UnraidAction.PARITY_CHECK_CANCEL

private val UnraidSection.labelRes: Int
    get() = when (this) {
        UnraidSection.SYSTEM -> R.string.unraid_section_system
        UnraidSection.ARRAY -> R.string.unraid_section_array
        UnraidSection.SHARES -> R.string.unraid_section_shares
        UnraidSection.DOCKER -> R.string.unraid_section_docker
        UnraidSection.VMS -> R.string.unraid_section_vms
        UnraidSection.NOTIFICATIONS -> R.string.unraid_section_notifications
    }
