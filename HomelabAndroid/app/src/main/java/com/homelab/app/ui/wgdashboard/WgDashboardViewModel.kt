package com.homelab.app.ui.wgdashboard

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homelab.app.R
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardConfigurationDetail
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardOverview
import com.homelab.app.data.repository.ServicesRepository
import com.homelab.app.data.repository.WgDashboardAction
import com.homelab.app.data.repository.WgDashboardRepository
import com.homelab.app.domain.action.ActionExecutionState
import com.homelab.app.domain.action.ActionFailureDisposition
import com.homelab.app.domain.action.ActionOperationException
import com.homelab.app.domain.action.ActionRole
import com.homelab.app.domain.action.ControlledActionCoordinator
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.domain.provider.ProviderRegistry
import com.homelab.app.util.ErrorHandler
import com.homelab.app.util.ServiceType
import com.homelab.app.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A mutating request that still needs the user to confirm it before it may run. */
data class PendingWgDashboardAction(
    val action: WgDashboardAction,
    val configurationName: String,
    val peerIds: List<String> = emptyList(),
    val targetLabel: String
)

@HiltViewModel
class WgDashboardViewModel @Inject constructor(
    private val repository: WgDashboardRepository,
    private val servicesRepository: ServicesRepository,
    private val controlledActionCoordinator: ControlledActionCoordinator,
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    val instanceId: String = checkNotNull(savedStateHandle["instanceId"])

    private val _uiState = MutableStateFlow<UiState<WgDashboardOverview>>(UiState.Loading)
    val uiState: StateFlow<UiState<WgDashboardOverview>> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Peers of the tunnel the user expanded; loaded on demand, one tunnel at a time. */
    private val _selectedConfiguration = MutableStateFlow<String?>(null)
    val selectedConfiguration: StateFlow<String?> = _selectedConfiguration.asStateFlow()

    private val _peerState = MutableStateFlow<UiState<WgDashboardConfigurationDetail>>(UiState.Idle)
    val peerState: StateFlow<UiState<WgDashboardConfigurationDetail>> = _peerState.asStateFlow()

    /** Identifies the target currently running an action, so only its controls show a spinner. */
    private val _busyTarget = MutableStateFlow<String?>(null)
    val busyTarget: StateFlow<String?> = _busyTarget.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var refreshJob: Job? = null
    private var peerJob: Job? = null
    private var refreshRequestId: Long = 0L

    val instances: StateFlow<List<ServiceInstance>> = servicesRepository.instancesByType
        .map { it[ServiceType.WGDASHBOARD].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh(forceLoading = true)
    }

    fun refresh(forceLoading: Boolean = false) {
        val requestId = ++refreshRequestId
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (forceLoading || _uiState.value !is UiState.Success) {
                _uiState.value = UiState.Loading
            }
            _isRefreshing.value = true
            try {
                _uiState.value = UiState.Success(repository.getOverview(instanceId))
                _selectedConfiguration.value?.let { loadPeers(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.value = UiState.Error(
                    message = ErrorHandler.getMessage(context, error),
                    retryAction = { refresh(forceLoading = true) }
                )
            } finally {
                if (requestId == refreshRequestId) {
                    _isRefreshing.value = false
                }
            }
        }
    }

    /** Opens a tunnel's peer list, or closes it when it is already the open one. */
    fun toggleConfigurationDetails(configurationName: String) {
        if (_selectedConfiguration.value == configurationName) {
            _selectedConfiguration.value = null
            _peerState.value = UiState.Idle
            peerJob?.cancel()
            return
        }
        _selectedConfiguration.value = configurationName
        loadPeers(configurationName)
    }

    private fun loadPeers(configurationName: String) {
        peerJob?.cancel()
        peerJob = viewModelScope.launch {
            _peerState.value = UiState.Loading
            try {
                _peerState.value = UiState.Success(
                    repository.getConfigurationDetail(instanceId, configurationName)
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _peerState.value = UiState.Error(
                    message = ErrorHandler.getMessage(context, error),
                    retryAction = { loadPeers(configurationName) }
                )
            }
        }
    }

    /**
     * Runs [action] through the controlled-action pipeline. Anything above LOW risk is rejected
     * by policy until the caller passes [confirmed], which the dashboard only does after the
     * user acknowledges the confirmation dialog.
     */
    fun runAction(action: PendingWgDashboardAction, confirmed: Boolean) {
        if (_busyTarget.value != null) return
        val busyKey = busyKeyOf(action)
        viewModelScope.launch {
            _busyTarget.value = busyKey
            try {
                val audit = controlledActionCoordinator.execute(
                    request = action.action.controlledRequest(
                        instanceId = instanceId,
                        targetRef = targetRef(action),
                        confirmed = confirmed
                    ),
                    actorRole = ActionRole.ADMIN,
                    providerCapabilities = ProviderRegistry.capabilities(ServiceType.WGDASHBOARD)
                ) {
                    try {
                        perform(action)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: ActionOperationException) {
                        throw error
                    } catch (error: Exception) {
                        throw ActionOperationException(
                            "wgdashboard-outcome-indeterminate",
                            ActionFailureDisposition.NON_RETRYABLE,
                            error
                        )
                    }
                }
                if (audit.state != ActionExecutionState.SUCCEEDED) {
                    _messages.emit(audit.reasonCode)
                    return@launch
                }
                _messages.emit(context.getString(R.string.wgdashboard_action_sent))
                // Bringing an interface up or down takes a moment on the server, so give it one
                // beat before reading the new state back.
                delay(1200L)
                runCatching { repository.getOverview(instanceId) }
                    .onSuccess { _uiState.value = UiState.Success(it) }
                _selectedConfiguration.value?.let { loadPeers(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _messages.emit(ErrorHandler.getMessage(context, error))
            } finally {
                _busyTarget.value = null
            }
        }
    }

    private suspend fun perform(action: PendingWgDashboardAction) {
        when (action.action) {
            WgDashboardAction.TUNNEL_START,
            WgDashboardAction.TUNNEL_STOP ->
                repository.toggleConfiguration(instanceId, action.configurationName)
            WgDashboardAction.PEER_RESTRICT ->
                repository.restrictPeers(instanceId, action.configurationName, action.peerIds)
            WgDashboardAction.PEER_ALLOW ->
                repository.allowAccessPeers(instanceId, action.configurationName, action.peerIds)
        }
    }

    private fun targetRef(action: PendingWgDashboardAction): String = when (action.action) {
        WgDashboardAction.TUNNEL_START,
        WgDashboardAction.TUNNEL_STOP -> "tunnel/${action.configurationName.lowercase()}"
        WgDashboardAction.PEER_RESTRICT,
        WgDashboardAction.PEER_ALLOW ->
            "tunnel/${action.configurationName.lowercase()}/peer/${action.peerIds.firstOrNull().orEmpty().lowercase()}"
    }

    private fun busyKeyOf(action: PendingWgDashboardAction): String = when (action.action) {
        WgDashboardAction.TUNNEL_START,
        WgDashboardAction.TUNNEL_STOP -> action.configurationName
        WgDashboardAction.PEER_RESTRICT,
        WgDashboardAction.PEER_ALLOW -> action.peerIds.firstOrNull() ?: action.configurationName
    }
}
