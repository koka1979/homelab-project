package com.homelab.app.ui.wgdashboard

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardConfiguration
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardConfigurationDetail
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardOverview
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardPeer
import com.homelab.app.data.repository.WgDashboardAction
import com.homelab.app.ui.common.ErrorScreen
import com.homelab.app.ui.components.ServiceInstancePicker
import com.homelab.app.util.ServiceType
import com.homelab.app.util.UiState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WgDashboardScreen(
    viewModel: WgDashboardViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToInstance: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val busyTarget by viewModel.busyTarget.collectAsStateWithLifecycle()
    val selectedConfiguration by viewModel.selectedConfiguration.collectAsStateWithLifecycle()
    val peerState by viewModel.peerState.collectAsStateWithLifecycle()

    val currentInstance = instances.find { it.id == viewModel.instanceId }
    val title = currentInstance?.label?.takeIf { it.isNotBlank() } ?: ServiceType.WGDASHBOARD.displayName

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingAction by remember { mutableStateOf<PendingWgDashboardAction?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val onAction: (PendingWgDashboardAction) -> Unit = { request ->
        if (request.action.requiresConfirmation) {
            pendingAction = request
        } else {
            viewModel.runAction(request, confirmed = false)
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
                    WgDashboardContent(
                        overview = current.data,
                        busyTarget = busyTarget,
                        selectedConfiguration = selectedConfiguration,
                        peerState = peerState,
                        onToggleDetails = { viewModel.toggleConfigurationDetails(it) },
                        onAction = onAction
                    )
                }

                else -> {}
            }
        }
    }

    pendingAction?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(R.string.wgdashboard_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.wgdashboard_confirm_message,
                        stringResource(pending.action.labelRes),
                        pending.targetLabel
                    )
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.runAction(pending, confirmed = true)
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
private fun WgDashboardContent(
    overview: WgDashboardOverview,
    busyTarget: String?,
    selectedConfiguration: String?,
    peerState: UiState<WgDashboardConfigurationDetail>,
    onToggleDetails: (String) -> Unit,
    onAction: (PendingWgDashboardAction) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SummaryCard(overview) }

        if (overview.configurations.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.wgdashboard_no_tunnels),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(overview.configurations, key = { it.name }) { configuration ->
            TunnelCard(
                configuration = configuration,
                busyTarget = busyTarget,
                expanded = selectedConfiguration == configuration.name,
                peerState = peerState,
                onToggleDetails = { onToggleDetails(configuration.name) },
                onAction = onAction
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(overview: WgDashboardOverview) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.wgdashboard_summary_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    overview.version?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricChip(
                    stringResource(
                        R.string.wgdashboard_metric_tunnels,
                        overview.activeTunnels,
                        overview.configurations.size
                    )
                )
                MetricChip(
                    stringResource(
                        R.string.wgdashboard_metric_peers,
                        overview.connectedPeers,
                        overview.totalPeers
                    )
                )
                MetricChip(stringResource(R.string.wgdashboard_metric_traffic, formatGb(overview.totalGb)))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TunnelCard(
    configuration: WgDashboardConfiguration,
    busyTarget: String?,
    expanded: Boolean,
    peerState: UiState<WgDashboardConfigurationDetail>,
    onToggleDetails: () -> Unit,
    onAction: (PendingWgDashboardAction) -> Unit
) {
    val busy = busyTarget == configuration.name
    val action = if (configuration.status) WgDashboardAction.TUNNEL_STOP else WgDashboardAction.TUNNEL_START

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggleDetails() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(active = configuration.status)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = configuration.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            configuration.address?.takeIf { it.isNotBlank() },
                            configuration.listenPort.takeIf { it.isNotBlank() }
                                ?.let { stringResource(R.string.wgdashboard_port, it) },
                            "AmneziaWG".takeIf { configuration.isAmnezia }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.wgdashboard_show_peers),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricChip(
                    stringResource(
                        R.string.wgdashboard_metric_peers,
                        configuration.connectedPeers,
                        configuration.totalPeers
                    )
                )
                configuration.dataUsage?.let {
                    MetricChip(stringResource(R.string.wgdashboard_metric_received, formatGb(it.received)))
                    MetricChip(stringResource(R.string.wgdashboard_metric_sent, formatGb(it.sent)))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onAction(
                            PendingWgDashboardAction(
                                action = action,
                                configurationName = configuration.name,
                                targetLabel = configuration.name
                            )
                        )
                    },
                    enabled = busyTarget == null
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = if (configuration.status) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(action.labelRes))
                }
            }

            if (expanded) {
                Spacer(Modifier.height(2.dp))
                when (val peers = peerState) {
                    is UiState.Loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }

                    is UiState.Error -> Text(
                        text = peers.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )

                    is UiState.Success -> PeerList(
                        configurationName = configuration.name,
                        detail = peers.data,
                        busyTarget = busyTarget,
                        onAction = onAction
                    )

                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun PeerList(
    configurationName: String,
    detail: WgDashboardConfigurationDetail,
    busyTarget: String?,
    onAction: (PendingWgDashboardAction) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (detail.configurationPeers.isEmpty() && detail.configurationRestrictedPeers.isEmpty()) {
            Text(
                text = stringResource(R.string.wgdashboard_no_peers),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        detail.configurationPeers.forEach { peer ->
            PeerRow(
                peer = peer,
                restricted = false,
                busyTarget = busyTarget,
                onAction = {
                    onAction(
                        PendingWgDashboardAction(
                            action = WgDashboardAction.PEER_RESTRICT,
                            configurationName = configurationName,
                            peerIds = listOf(peer.id),
                            targetLabel = peer.displayName
                        )
                    )
                }
            )
        }

        if (detail.configurationRestrictedPeers.isNotEmpty()) {
            Text(
                text = stringResource(R.string.wgdashboard_restricted_peers),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            detail.configurationRestrictedPeers.forEach { peer ->
                PeerRow(
                    peer = peer,
                    restricted = true,
                    busyTarget = busyTarget,
                    onAction = {
                        onAction(
                            PendingWgDashboardAction(
                                action = WgDashboardAction.PEER_ALLOW,
                                configurationName = configurationName,
                                peerIds = listOf(peer.id),
                                targetLabel = peer.displayName
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun PeerRow(
    peer: WgDashboardPeer,
    restricted: Boolean,
    busyTarget: String?,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(active = peer.isConnected && !restricted)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        peer.allowedIp?.takeIf { it.isNotBlank() },
                        peer.handshake
                    ).joinToString(" · ").ifBlank { stringResource(R.string.wgdashboard_peer_never_connected) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.wgdashboard_peer_traffic,
                        formatGb(peer.receivedGb),
                        formatGb(peer.sentGb)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onAction, enabled = busyTarget == null) {
                if (busyTarget == peer.id) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (restricted) Icons.Default.CheckCircle else Icons.Default.Block,
                        contentDescription = stringResource(
                            if (restricted) R.string.wgdashboard_action_allow else R.string.wgdashboard_action_restrict
                        ),
                        tint = if (restricted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricChip(text: String) {
    AssistChip(
        onClick = {},
        label = { Text(text, style = MaterialTheme.typography.labelMedium) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    )
}

@Composable
private fun StatusDot(active: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (active) Color(0xFF22C55E) else MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(10.dp)
    ) {}
}

/** WGDashboard reports traffic in gigabytes; small values read better as megabytes. */
internal fun formatGb(value: Double): String {
    if (value <= 0.0) return "0 MB"
    return if (value < 1.0) {
        String.format(Locale.US, "%.1f MB", value * 1024.0)
    } else {
        String.format(Locale.US, "%.2f GB", value)
    }
}

private val WgDashboardAction.labelRes: Int
    get() = when (this) {
        WgDashboardAction.TUNNEL_START -> R.string.wgdashboard_action_start
        WgDashboardAction.TUNNEL_STOP -> R.string.wgdashboard_action_stop
        WgDashboardAction.PEER_RESTRICT -> R.string.wgdashboard_action_restrict
        WgDashboardAction.PEER_ALLOW -> R.string.wgdashboard_action_allow
    }
