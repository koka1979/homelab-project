package com.homelab.app.ui.wgdashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homelab.app.R
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardConfiguration
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardConfigurationDetail
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardAddPeerRequest
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardOverview
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardPeerFile
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardSystemStatus
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
    val addPeerState by viewModel.addPeerState.collectAsStateWithLifecycle()
    val peerConfiguration by viewModel.peerConfiguration.collectAsStateWithLifecycle()
    val isLoadingPeerConfiguration by viewModel.isLoadingPeerConfiguration.collectAsStateWithLifecycle()
    val systemState by viewModel.systemState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                        systemStatus = (systemState as? UiState.Success)?.data,
                        busyTarget = busyTarget,
                        selectedConfiguration = selectedConfiguration,
                        peerState = peerState,
                        onToggleDetails = { viewModel.toggleConfigurationDetails(it) },
                        onAddPeer = { viewModel.openAddPeer(it) },
                        onShowPeerConfiguration = { configuration, peerId ->
                            viewModel.showPeerConfiguration(configuration, peerId)
                        },
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

    addPeerState?.let { addState ->
        AddPeerDialog(
            state = addState,
            busy = busyTarget == addState.configurationName,
            onDismiss = { viewModel.dismissAddPeer() },
            onCreate = { request ->
                // The filled-in form is the explicit confirmation for this action, so it does
                // not pop a second dialog that repeats what the user just typed.
                viewModel.runAction(
                    PendingWgDashboardAction(
                        action = WgDashboardAction.PEER_CREATE,
                        configurationName = addState.configurationName,
                        targetLabel = request.name,
                        newPeer = request
                    ),
                    confirmed = true
                )
            }
        )
    }

    if (isLoadingPeerConfiguration && peerConfiguration == null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.wgdashboard_peer_config_title)) },
            text = {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {}
        )
    }

    peerConfiguration?.let { configuration ->
        PeerConfigurationDialog(
            configuration = configuration,
            onCopy = { copyToClipboard(context, configuration) },
            onShare = { shareConfiguration(context, configuration) },
            onDismiss = { viewModel.dismissPeerConfiguration() }
        )
    }
}

private fun copyToClipboard(context: Context, configuration: WgDashboardPeerFile) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(configuration.fileName, configuration.file))
}

private fun shareConfiguration(context: Context, configuration: WgDashboardPeerFile) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TITLE, "${configuration.fileName}.conf")
        putExtra(Intent.EXTRA_TEXT, configuration.file)
    }
    context.startActivity(Intent.createChooser(intent, "${configuration.fileName}.conf"))
}

@Composable
private fun WgDashboardContent(
    overview: WgDashboardOverview,
    systemStatus: WgDashboardSystemStatus?,
    busyTarget: String?,
    selectedConfiguration: String?,
    peerState: UiState<WgDashboardConfigurationDetail>,
    onToggleDetails: (String) -> Unit,
    onAddPeer: (String) -> Unit,
    onShowPeerConfiguration: (String, String) -> Unit,
    onAction: (PendingWgDashboardAction) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        systemStatus?.let { status ->
            item { SystemStatusCard(status) }
        }

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
                onAddPeer = { onAddPeer(configuration.name) },
                onShowPeerConfiguration = { peerId -> onShowPeerConfiguration(configuration.name, peerId) },
                onAction = onAction
            )
        }
    }
}

@Composable
private fun SystemStatusCard(status: WgDashboardSystemStatus) {
    val memory = status.memory?.virtual
    val swap = status.memory?.swap
    val disk = status.primaryDisk

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricGauge(
                    label = stringResource(R.string.wgdashboard_system_cpu),
                    percent = status.cpu?.percent ?: 0.0,
                    caption = status.cpu?.perCpu
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { stringResource(R.string.wgdashboard_system_cores, it.size) },
                    perUnit = status.cpu?.perCpu.orEmpty(),
                    modifier = Modifier.weight(1f)
                )
                MetricGauge(
                    label = stringResource(R.string.wgdashboard_system_storage),
                    percent = disk?.percent ?: 0.0,
                    caption = disk?.let {
                        stringResource(
                            R.string.wgdashboard_system_usage,
                            formatBytes(it.used),
                            formatBytes(it.total)
                        )
                    },
                    perUnit = status.disks.map { it.percent },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricGauge(
                    label = stringResource(R.string.wgdashboard_system_memory),
                    percent = memory?.percent ?: 0.0,
                    caption = memory?.let {
                        stringResource(
                            R.string.wgdashboard_system_usage,
                            formatBytes(it.used),
                            formatBytes(it.total)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                MetricGauge(
                    label = stringResource(R.string.wgdashboard_system_swap),
                    percent = swap?.percent ?: 0.0,
                    caption = swap?.let {
                        stringResource(
                            R.string.wgdashboard_system_usage,
                            formatBytes(it.used),
                            formatBytes(it.total)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * One reading of the system card: a percentage with its bar, an optional caption and - for CPU
 * cores and disks - a small bar per unit, the way the web UI shows them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricGauge(
    label: String,
    percent: Double,
    caption: String?,
    modifier: Modifier = Modifier,
    perUnit: List<Double> = emptyList()
) {
    val fraction = (percent / 100.0).coerceIn(0.0, 1.0).toFloat()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = String.format(Locale.US, "%.1f%%", percent),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {}
        )
        if (perUnit.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                perUnit.forEach { unit ->
                    LinearProgressIndicator(
                        progress = { (unit / 100.0).coerceIn(0.0, 1.0).toFloat() },
                        modifier = Modifier.width(16.dp).height(4.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                }
            }
        }
        caption?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
    onAddPeer: () -> Unit,
    onShowPeerConfiguration: (String) -> Unit,
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

                OutlinedButton(onClick = onAddPeer, enabled = busyTarget == null) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.wgdashboard_add_peer))
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
                        onShowPeerConfiguration = onShowPeerConfiguration,
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
    onShowPeerConfiguration: (String) -> Unit,
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
                onShowConfiguration = { onShowPeerConfiguration(peer.id) },
                onDelete = {
                    onAction(
                        PendingWgDashboardAction(
                            action = WgDashboardAction.PEER_DELETE,
                            configurationName = configurationName,
                            peerIds = listOf(peer.id),
                            targetLabel = peer.displayName
                        )
                    )
                },
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
                    onShowConfiguration = { onShowPeerConfiguration(peer.id) },
                    onDelete = {
                        onAction(
                            PendingWgDashboardAction(
                                action = WgDashboardAction.PEER_DELETE,
                                configurationName = configurationName,
                                peerIds = listOf(peer.id),
                                targetLabel = peer.displayName
                            )
                        )
                    },
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
    onShowConfiguration: () -> Unit,
    onDelete: () -> Unit,
    onAction: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
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

            Box {
                IconButton(onClick = { menuExpanded = true }, enabled = busyTarget == null) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.wgdashboard_peer_more)
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.wgdashboard_peer_show_config)) },
                        leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onShowConfiguration()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.wgdashboard_action_delete)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
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

/** Byte counts from the system endpoint, rendered the way a file manager would. */
internal fun formatBytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var remaining = value.toDouble()
    var unitIndex = 0
    while (remaining >= 1024.0 && unitIndex < units.lastIndex) {
        remaining /= 1024.0
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", remaining, units[unitIndex])
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

@Composable
private fun AddPeerDialog(
    state: WgDashboardAddPeerState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (WgDashboardAddPeerRequest) -> Unit
) {
    var name by remember(state.configurationName) { mutableStateOf("") }
    var address by remember(state.configurationName) { mutableStateOf("") }
    var dns by remember(state.configurationName) { mutableStateOf("") }
    var mtu by remember(state.configurationName) { mutableStateOf("") }
    var keepalive by remember(state.configurationName) { mutableStateOf("") }
    var notes by remember(state.configurationName) { mutableStateOf("") }
    var showAdvanced by remember(state.configurationName) { mutableStateOf(false) }

    // Prefill with the first address the server still has free, once it is known.
    LaunchedEffect(state.availableIps) {
        if (address.isBlank()) {
            address = state.availableIps.firstOrNull().orEmpty()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.wgdashboard_add_peer_title, state.configurationName)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.wgdashboard_peer_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.wgdashboard_peer_address)) },
                    singleLine = true,
                    supportingText = {
                        when {
                            state.isLoading -> Text(stringResource(R.string.wgdashboard_loading_addresses))
                            state.error != null -> Text(state.error)
                            state.availableIps.isEmpty() -> Text(stringResource(R.string.wgdashboard_no_free_address))
                            else -> Text(
                                stringResource(R.string.wgdashboard_free_addresses, state.availableIps.size)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(
                        stringResource(
                            if (showAdvanced) R.string.wgdashboard_hide_advanced
                            else R.string.wgdashboard_show_advanced
                        )
                    )
                }

                if (showAdvanced) {
                    OutlinedTextField(
                        value = dns,
                        onValueChange = { dns = it },
                        label = { Text(stringResource(R.string.wgdashboard_peer_dns)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = mtu,
                        onValueChange = { mtu = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.wgdashboard_peer_mtu)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = keepalive,
                        onValueChange = { keepalive = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.wgdashboard_peer_keepalive)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text(stringResource(R.string.wgdashboard_peer_notes)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.wgdashboard_advanced_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = stringResource(R.string.wgdashboard_add_peer_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        WgDashboardAddPeerRequest(
                            name = name.trim(),
                            allowedIps = listOf(address.trim()),
                            dns = dns.trim().takeIf { it.isNotBlank() },
                            mtu = mtu.toIntOrNull(),
                            keepalive = keepalive.toIntOrNull(),
                            notes = notes.trim().takeIf { it.isNotBlank() }
                        )
                    )
                },
                enabled = !busy && name.isNotBlank() && address.isNotBlank()
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.wgdashboard_action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun PeerConfigurationDialog(
    configuration: WgDashboardPeerFile,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    val qr = remember(configuration.file) { wireGuardQrBitmap(configuration.file, 640) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(configuration.fileName.ifBlank { stringResource(R.string.wgdashboard_peer_config_title) }) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (qr != null) {
                    Surface(color = Color.White, shape = RoundedCornerShape(8.dp)) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = stringResource(R.string.wgdashboard_peer_qr),
                            modifier = Modifier.size(240.dp).padding(8.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.wgdashboard_peer_qr_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = configuration.file,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.wgdashboard_peer_secret_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy) { Text(stringResource(R.string.wgdashboard_copy)) }
                TextButton(onClick = onShare) { Text(stringResource(R.string.wgdashboard_share)) }
            }
        }
    )
}

private val WgDashboardAction.labelRes: Int
    get() = when (this) {
        WgDashboardAction.TUNNEL_START -> R.string.wgdashboard_action_start
        WgDashboardAction.TUNNEL_STOP -> R.string.wgdashboard_action_stop
        WgDashboardAction.PEER_CREATE -> R.string.wgdashboard_action_create
        WgDashboardAction.PEER_DELETE -> R.string.wgdashboard_action_delete
        WgDashboardAction.PEER_RESTRICT -> R.string.wgdashboard_action_restrict
        WgDashboardAction.PEER_ALLOW -> R.string.wgdashboard_action_allow
    }
