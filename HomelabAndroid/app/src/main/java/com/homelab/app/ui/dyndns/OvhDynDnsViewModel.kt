package com.homelab.app.ui.dyndns

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homelab.app.R
import com.homelab.app.data.repository.DynDnsAction
import com.homelab.app.data.repository.OvhDynDnsRepository
import com.homelab.app.data.repository.ServicesRepository
import com.homelab.app.data.repository.hostnameOf
import com.homelab.app.domain.action.ActionExecutionState
import com.homelab.app.domain.action.ActionFailureDisposition
import com.homelab.app.domain.action.ActionOperationException
import com.homelab.app.domain.action.ActionRole
import com.homelab.app.domain.action.ControlledActionCoordinator
import com.homelab.app.domain.dyndns.DynDnsAddresses
import com.homelab.app.domain.dyndns.DynDnsInstanceState
import com.homelab.app.domain.dyndns.DynDnsPublishedRecords
import com.homelab.app.domain.dyndns.DynDnsStateStore
import com.homelab.app.domain.dyndns.DynDnsUpdater
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.domain.provider.ProviderRegistry
import com.homelab.app.util.ErrorHandler
import com.homelab.app.util.ServiceType
import com.homelab.app.util.UiState
import com.homelab.app.work.DynDnsUpdateWorker
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

@HiltViewModel
class OvhDynDnsViewModel @Inject constructor(
    private val repository: OvhDynDnsRepository,
    private val updater: DynDnsUpdater,
    private val stateStore: DynDnsStateStore,
    private val servicesRepository: ServicesRepository,
    private val controlledActionCoordinator: ControlledActionCoordinator,
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    val instanceId: String = checkNotNull(savedStateHandle["instanceId"])

    private val _addressState = MutableStateFlow<UiState<DynDnsAddresses>>(UiState.Loading)
    val addressState: StateFlow<UiState<DynDnsAddresses>> = _addressState.asStateFlow()

    private val _settings = MutableStateFlow(stateStore.state(instanceId))
    val settings: StateFlow<DynDnsInstanceState> = _settings.asStateFlow()

    private val _hostname = MutableStateFlow<String?>(null)
    val hostname: StateFlow<String?> = _hostname.asStateFlow()

    /** What the host name resolves to in the public DNS right now. */
    private val _publishedState = MutableStateFlow<UiState<DynDnsPublishedRecords>>(UiState.Idle)
    val publishedState: StateFlow<UiState<DynDnsPublishedRecords>> = _publishedState.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var detectJob: Job? = null
    private var lookupJob: Job? = null

    val instances: StateFlow<List<ServiceInstance>> = servicesRepository.instancesByType
        .map { it[ServiceType.OVH_DYNDNS].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _hostname.value = servicesRepository.getInstance(instanceId)?.let(::hostnameOf)
            refreshPublishedRecords()
        }
        refresh()
    }

    /** Reads the current public addresses without touching DNS. */
    fun refresh() {
        detectJob?.cancel()
        detectJob = viewModelScope.launch {
            _addressState.value = UiState.Loading
            try {
                _addressState.value = UiState.Success(repository.detectAddresses())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _addressState.value = UiState.Error(
                    message = ErrorHandler.getMessage(context, error),
                    retryAction = { refresh() }
                )
            }
            _settings.value = stateStore.state(instanceId)
        }
        refreshPublishedRecords()
    }

    /** Asks the public DNS what the record carries; no credentials and no change involved. */
    fun refreshPublishedRecords() {
        val hostname = _hostname.value ?: return
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            if (_publishedState.value !is UiState.Success) {
                _publishedState.value = UiState.Loading
            }
            try {
                _publishedState.value = UiState.Success(repository.lookupPublishedRecords(hostname))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _publishedState.value = UiState.Error(
                    message = ErrorHandler.getMessage(context, error),
                    retryAction = { refreshPublishedRecords() }
                )
            }
        }
    }

    /**
     * Sends the current addresses to OVH. Pressing the button is the confirmation for this
     * action, so it is passed as confirmed rather than asking again in a dialog.
     */
    fun updateNow() {
        if (_isUpdating.value) return
        viewModelScope.launch {
            _isUpdating.value = true
            try {
                val audit = controlledActionCoordinator.execute(
                    request = DynDnsAction.UPDATE_RECORD.controlledRequest(
                        instanceId = instanceId,
                        targetRef = "hostname/${(_hostname.value ?: instanceId).lowercase()}",
                        confirmed = true
                    ),
                    actorRole = ActionRole.ADMIN,
                    providerCapabilities = ProviderRegistry.capabilities(ServiceType.OVH_DYNDNS)
                ) {
                    try {
                        // Forced: the user asked for this run, so send even an unchanged address.
                        val result = updater.run(instanceId, force = true)
                        _addressState.value = UiState.Success(result.addresses)
                        _messages.emit(result.summary)
                        // DynHost records live with a TTL of 60 seconds, so the new value is
                        // visible almost at once - give the zone a moment, then read it back.
                        delay(2_000L)
                        refreshPublishedRecords()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        throw ActionOperationException(
                            "dyndns-outcome-indeterminate",
                            ActionFailureDisposition.NON_RETRYABLE,
                            error
                        )
                    }
                }
                if (audit.state != ActionExecutionState.SUCCEEDED) {
                    _messages.emit(audit.reasonCode)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _messages.emit(ErrorHandler.getMessage(context, error))
            } finally {
                _settings.value = stateStore.state(instanceId)
                _isUpdating.value = false
            }
        }
    }

    fun setAutoUpdate(enabled: Boolean) {
        _settings.value = stateStore.updateSettings(instanceId, autoUpdate = enabled)
        applySchedule()
        viewModelScope.launch {
            _messages.emit(
                context.getString(
                    if (enabled) R.string.dyndns_auto_enabled else R.string.dyndns_auto_disabled
                )
            )
        }
    }

    fun setInterval(minutes: Int) {
        _settings.value = stateStore.updateSettings(instanceId, intervalMinutes = minutes)
        applySchedule()
    }

    fun setFamilies(ipv4: Boolean, ipv6: Boolean) {
        _settings.value = stateStore.updateSettings(instanceId, updateIpv4 = ipv4, updateIpv6 = ipv6)
    }

    private fun applySchedule() {
        val state = _settings.value
        if (state.autoUpdate) {
            DynDnsUpdateWorker.schedule(context, instanceId, state.intervalMinutes)
        } else {
            DynDnsUpdateWorker.cancel(context, instanceId)
        }
    }
}
