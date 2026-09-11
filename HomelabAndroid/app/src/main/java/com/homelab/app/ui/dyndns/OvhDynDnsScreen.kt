package com.homelab.app.ui.dyndns

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.homelab.app.domain.dyndns.DynDnsAddresses
import com.homelab.app.domain.dyndns.DynDnsInstanceState
import com.homelab.app.ui.common.ErrorScreen
import com.homelab.app.ui.components.ServiceInstancePicker
import com.homelab.app.util.ServiceType
import com.homelab.app.util.UiState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OvhDynDnsScreen(
    viewModel: OvhDynDnsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToInstance: (String) -> Unit
) {
    val addressState by viewModel.addressState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hostname by viewModel.hostname.collectAsStateWithLifecycle()
    val isUpdating by viewModel.isUpdating.collectAsStateWithLifecycle()
    val instances by viewModel.instances.collectAsStateWithLifecycle()

    val currentInstance = instances.find { it.id == viewModel.instanceId }
    val title = currentInstance?.label?.takeIf { it.isNotBlank() } ?: ServiceType.OVH_DYNDNS.displayName
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
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

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { HostnameCard(hostname, settings) }

                item {
                    when (val current = addressState) {
                        is UiState.Loading -> ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator() }
                        }

                        is UiState.Error -> ErrorScreen(
                            message = current.message,
                            onRetry = current.retryAction ?: { viewModel.refresh() }
                        )

                        is UiState.Success -> AddressCard(current.data)

                        else -> {}
                    }
                }

                item {
                    Button(
                        onClick = { viewModel.updateNow() },
                        enabled = !isUpdating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isUpdating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dyndns_update_now))
                    }
                }

                item {
                    AutomationCard(
                        settings = settings,
                        onAutoUpdateChange = { viewModel.setAutoUpdate(it) },
                        onIntervalChange = { viewModel.setInterval(it) },
                        onFamiliesChange = { ipv4, ipv6 -> viewModel.setFamilies(ipv4, ipv6) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HostnameCard(hostname: String?, settings: DynDnsInstanceState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = hostname ?: stringResource(R.string.dyndns_no_hostname),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (settings.lastRunAt > 0L) {
                Text(
                    text = stringResource(
                        R.string.dyndns_last_run,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(settings.lastRunAt))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                settings.lastSummary?.let { summary ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (settings.lastRunSucceeded) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.dyndns_never_run),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AddressCard(addresses: DynDnsAddresses) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.dyndns_current_addresses),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            AddressRow(
                label = stringResource(R.string.dyndns_ipv4),
                value = addresses.ipv4,
                error = addresses.ipv4Error
            )
            AddressRow(
                label = stringResource(R.string.dyndns_ipv6),
                value = addresses.ipv6,
                error = addresses.ipv6Error
            )
        }
    }
}

@Composable
private fun AddressRow(label: String, value: String?, error: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value ?: error ?: stringResource(R.string.dyndns_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Normal,
            color = if (value != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutomationCard(
    settings: DynDnsInstanceState,
    onAutoUpdateChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onFamiliesChange: (Boolean, Boolean) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dyndns_auto_update),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.dyndns_auto_update_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = settings.autoUpdate, onCheckedChange = onAutoUpdateChange)
            }

            if (settings.autoUpdate) {
                Text(
                    text = stringResource(R.string.dyndns_interval),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DynDnsInstanceState.INTERVAL_CHOICES.forEach { minutes ->
                        FilterChip(
                            selected = settings.intervalMinutes == minutes,
                            onClick = { onIntervalChange(minutes) },
                            label = { Text(intervalLabel(minutes)) }
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.dyndns_families),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.updateIpv4,
                    // At least one family has to stay on, otherwise a run has nothing to send.
                    onCheckedChange = { onFamiliesChange(it, if (it) settings.updateIpv6 else true) }
                )
                Text(stringResource(R.string.dyndns_ipv4))
                Spacer(Modifier.width(16.dp))
                Checkbox(
                    checked = settings.updateIpv6,
                    onCheckedChange = { onFamiliesChange(if (it) settings.updateIpv4 else true, it) }
                )
                Text(stringResource(R.string.dyndns_ipv6))
            }

            AssistChip(
                onClick = {},
                label = { Text(stringResource(R.string.dyndns_record_hint), style = MaterialTheme.typography.labelSmall) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            )
        }
    }
}

@Composable
private fun intervalLabel(minutes: Int): String = when {
    minutes < 60 -> stringResource(R.string.dyndns_interval_minutes, minutes)
    minutes % 60 == 0 -> stringResource(R.string.dyndns_interval_hours, minutes / 60)
    else -> stringResource(R.string.dyndns_interval_minutes, minutes)
}
