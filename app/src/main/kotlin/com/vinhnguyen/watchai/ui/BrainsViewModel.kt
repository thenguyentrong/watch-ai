package com.vinhnguyen.watchai.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinhnguyen.watchai.AppGraph
import com.vinhnguyen.watchai.CustomTabLauncher
import com.vinhnguyen.watchai.SignInKeepAliveService
import com.vinhnguyen.watchai.brain.Availability
import com.vinhnguyen.watchai.brain.chatgpt.api.UsageSnapshot
import com.vinhnguyen.watchai.brain.chatgpt.auth.AuthState
import com.vinhnguyen.watchai.brain.chatgpt.auth.DeviceSignInState
import com.vinhnguyen.watchai.brain.chatgpt.auth.SignInError
import com.vinhnguyen.watchai.brain.chatgpt.auth.SignInException
import com.vinhnguyen.watchai.ondevice.ModelSpec
import com.vinhnguyen.watchai.ondevice.ModelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BrainsViewModel(
    private val graph: AppGraph,
) : ViewModel() {
    data class DeviceCodeUi(
        val userCode: String,
        val url: String,
        val expiresAtEpochSeconds: Long,
    )

    data class State(
        val signingIn: Boolean = false,
        val message: String? = null,
        val deviceCode: DeviceCodeUi? = null,
        val nanoOptIn: Boolean = false,
        val nanoAvailability: Availability? = null,
        val gemmaAvailability: Availability? = null,
        val ramGb: Double = 0.0,
    )

    val auth: StateFlow<AuthState> = graph.session.state
    val usage: StateFlow<UsageSnapshot?> = graph.chatGpt.usage
    val spec: ModelSpec get() = graph.models.spec(graph.onDeviceSettings.modelId)

    /** Every offline model on offer, smallest first. */
    val models: List<ModelSpec> = graph.models.specs.sortedBy { it.sizeBytes }

    /** Each model's download state, by id. */
    val modelStates: StateFlow<Map<String, ModelState>> =
        combine(models.map { spec -> graph.models.observe(spec).map { spec.id to it } }) { it.toMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _inUse = MutableStateFlow(graph.onDeviceSettings.modelId)

    /** The model Gemma uses: for chat without a connection and for reading private things. */
    val inUse: StateFlow<String> = _inUse.asStateFlow()

    /** The biggest model this phone has the memory for. */
    val recommended: String get() = models.lastOrNull { graph.models.deviceRamGb() + 0.5 >= it.minRamGb }?.id ?: models.first().id

    fun use(spec: ModelSpec) {
        graph.onDeviceSettings.modelId = spec.id
        _inUse.value = spec.id
        refresh()
    }

    fun download(spec: ModelSpec) = graph.models.startDownload(spec)

    fun cancelDownload(spec: ModelSpec) = graph.models.cancelDownload(spec)

    fun delete(spec: ModelSpec) {
        graph.models.delete(spec)
        refresh()
    }

    private val _state = MutableStateFlow(State(nanoOptIn = graph.onDeviceSettings.nanoOptIn, ramGb = graph.models.deviceRamGb()))
    val state: StateFlow<State> = _state.asStateFlow()
    private var signInJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            graph.session.isSignedIn()
            _state.update {
                it.copy(nanoAvailability = graph.nano.availability(), gemmaAvailability = graph.gemma.availability())
            }
        }
    }

    fun signInWithBrowser(activity: Activity) {
        if (_state.value.signingIn) return
        val appContext = activity.applicationContext
        val launcher = CustomTabLauncher(activity)
        signInJob =
            viewModelScope.launch {
                _state.update { it.copy(signingIn = true, message = null) }
                SignInKeepAliveService.start(appContext)
                try {
                    val signedIn = graph.signIn.withBrowser(launcher)
                    _state.update { it.copy(message = "Signed in${signedIn.planType?.let { p -> " ($p plan)" } ?: ""}.") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SignInException) {
                    _state.update { it.copy(message = Texts.signIn(e.error)) }
                } finally {
                    SignInKeepAliveService.stop(appContext)
                    _state.update { it.copy(signingIn = false) }
                }
            }
    }

    fun signInWithCode() {
        if (_state.value.signingIn) return
        signInJob =
            viewModelScope.launch {
                _state.update { it.copy(signingIn = true, message = null) }
                try {
                    graph.signIn.withDeviceCode().collect { step ->
                        when (step) {
                            is DeviceSignInState.ShowCode -> {
                                _state.update {
                                    it.copy(
                                        deviceCode = DeviceCodeUi(step.userCode, step.verificationUrl, step.expiresAtEpochSeconds),
                                    )
                                }
                            }

                            is DeviceSignInState.Done -> {
                                _state.update { it.copy(deviceCode = null, message = "Signed in.") }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SignInException) {
                    _state.update { it.copy(message = Texts.signIn(e.error)) }
                } finally {
                    _state.update { it.copy(signingIn = false, deviceCode = null) }
                }
            }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        _state.update { it.copy(signingIn = false, deviceCode = null, message = Texts.signIn(SignInError.Cancelled)) }
    }

    fun signOut() {
        viewModelScope.launch {
            graph.session.signOut()
            _state.update { it.copy(message = "Signed out on this phone. To end other sessions: ChatGPT → Settings → Security.") }
        }
    }

    fun setNanoOptIn(enabled: Boolean) {
        graph.onDeviceSettings.nanoOptIn = enabled
        _state.update { it.copy(nanoOptIn = enabled) }
        refresh()
    }
}
