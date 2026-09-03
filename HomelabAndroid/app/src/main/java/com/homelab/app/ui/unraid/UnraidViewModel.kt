package com.homelab.app.ui.unraid

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homelab.app.R
import com.homelab.app.data.remote.dto.unraid.UnraidOverview
import com.homelab.app.data.repository.ServicesRepository
import com.homelab.app.data.repository.UnraidAction
import com.homelab.app.data.repository.UnraidRepository
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
data class PendingUnraidAction(
    val action: UnraidAction,
    val targetId: String?,
    val targetLabel: String
)

@HiltViewModel
class UnraidViewModel @Inject constructor(
    private val repository: UnraidRepository,
    private val servicesRepository: ServicesRepository,
    private val controlledActionCoordinator: ControlledActionCoordinator,
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    val instanceId: String = checkNotNull(savedStateHandle["instanceId"])

    private val _uiState = MutableStateFlow<UiState<UnraidOverview>>(UiState.Loading)
    val uiState: StateFlow<UiState<UnraidOverview>> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Identifies the target currently running an action, so only its controls show a spinner. */
    private val _busyTarget = MutableStateFlow<String?>(null)
    val busyTarget: StateFlow<String?> = _busyTarget.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var refreshJob: Job? = null
    private var refreshRequestId: Long = 0L

    val instances: StateFlow<List<ServiceInstance>> = servicesRepository.instancesByType
        .map { it[ServiceType.UNRAID].orEmpty() }
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

    /**
     * Runs [action] through the controlled-action pipeline. Anything above [ActionRisk.LOW] is
     * rejected by policy until the caller passes [confirmed], which the dashboard only does after
     * the user acknowledges the confirmation dialog.
     */
    fun runAction(action: UnraidAction, targetId: String?, confirmed: Boolean) {
        if (_busyTarget.value != null) return
        val busyKey = targetId ?: action.actionId
        viewModelScope.launch {
            _busyTarget.value = busyKey
            try {
                val audit = controlledActionCoordinator.execute(
                    request = action.controlledRequest(
                        instanceId = instanceId,
                        targetRef = targetRef(action, targetId),
                        confirmed = confirmed
                    ),
                    actorRole = ActionRole.ADMIN,
                    providerCapabilities = ProviderRegistry.capabilities(ServiceType.UNRAID)
                ) {
                    try {
                        perform(action, targetId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: ActionOperationException) {
                        throw error
                    } catch (error: Exception) {
                        throw ActionOperationException(
                            "unraid-outcome-indeterminate",
                            ActionFailureDisposition.NON_RETRYABLE,
                            error
                        )
                    }
                }
                if (audit.state != ActionExecutionState.SUCCEEDED) {
                    _messages.emit(audit.reasonCode)
                    return@launch
                }
                _messages.emit(context.getString(R.string.unraid_action_sent))
                // Unraid applies container and VM state changes asynchronously; poll briefly so
                // the card settles on the new state without the user pulling to refresh.
                repeat(3) {
                    delay(1500L)
                    runCatching { repository.getOverview(instanceId) }
                        .onSuccess { _uiState.value = UiState.Success(it) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _messages.emit(ErrorHandler.getMessage(context, error))
            } finally {
                _busyTarget.value = null
            }
        }
    }

    private suspend fun perform(action: UnraidAction, targetId: String?) {
        when (action) {
            UnraidAction.CONTAINER_START -> repository.startContainer(instanceId, requireTarget(targetId))
            UnraidAction.CONTAINER_STOP -> repository.stopContainer(instanceId, requireTarget(targetId))
            UnraidAction.CONTAINER_RESTART -> repository.restartContainer(instanceId, requireTarget(targetId))
            UnraidAction.VM_START -> repository.startVm(instanceId, requireTarget(targetId))
            UnraidAction.VM_STOP -> repository.stopVm(instanceId, requireTarget(targetId))
            UnraidAction.VM_PAUSE -> repository.pauseVm(instanceId, requireTarget(targetId))
            UnraidAction.VM_RESUME -> repository.resumeVm(instanceId, requireTarget(targetId))
            UnraidAction.VM_FORCE_STOP -> repository.forceStopVm(instanceId, requireTarget(targetId))
            UnraidAction.VM_REBOOT -> repository.rebootVm(instanceId, requireTarget(targetId))
            UnraidAction.ARRAY_START -> repository.startArray(instanceId)
            UnraidAction.ARRAY_STOP -> repository.stopArray(instanceId)
            UnraidAction.PARITY_CHECK_START -> repository.startParityCheck(instanceId, correcting = true)
            UnraidAction.PARITY_CHECK_CANCEL -> repository.cancelParityCheck(instanceId)
        }
    }

    private fun targetRef(action: UnraidAction, targetId: String?): String = when (action) {
        UnraidAction.CONTAINER_START,
        UnraidAction.CONTAINER_STOP,
        UnraidAction.CONTAINER_RESTART -> "container/${requireTarget(targetId).lowercase()}"
        UnraidAction.VM_START,
        UnraidAction.VM_STOP,
        UnraidAction.VM_PAUSE,
        UnraidAction.VM_RESUME,
        UnraidAction.VM_FORCE_STOP,
        UnraidAction.VM_REBOOT -> "vm/${requireTarget(targetId).lowercase()}"
        UnraidAction.ARRAY_START, UnraidAction.ARRAY_STOP -> "array"
        UnraidAction.PARITY_CHECK_START, UnraidAction.PARITY_CHECK_CANCEL -> "parity-check"
    }

    private fun requireTarget(targetId: String?): String =
        requireNotNull(targetId?.takeIf { it.isNotBlank() }) { "Action requires a target id" }
}
