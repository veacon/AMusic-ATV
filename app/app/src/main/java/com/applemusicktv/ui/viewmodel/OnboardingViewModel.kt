package com.applemusicktv.ui.viewmodel

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.OnboardingPreferences
import com.applemusicktv.media.InAppWebServer
import com.applemusicktv.media.PhoneServerPhase
import com.applemusicktv.media.phoneSetupHintId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingState(
    val phoneUrl: String = "",
    val hasMut: Boolean = false,
    val checking: Boolean = false,
    val tokenStored: Boolean = false,
    val tokenError: String? = null,
    val phoneServerUnavailable: Boolean = false,
    val phoneSetupMessage: Int? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboarding: OnboardingPreferences,
    private val mutPrefs: MutPreferences,
    private val webServer: InAppWebServer,
    private val tokenVerifier: com.applemusicktv.data.auth.TokenVerifier,
) : ViewModel() {
    private val _tokenState = MutableStateFlow(OnboardingState(phoneUrl = webServer.serverUrl()))
    val state: StateFlow<OnboardingState> = combine(_tokenState, webServer.endpoint) { token, endpoint ->
        token.copy(phoneUrl = webServer.serverUrl(), phoneServerUnavailable =
            endpoint.phase == PhoneServerPhase.FAILED || endpoint.phase == PhoneServerPhase.STOPPED,
            phoneSetupMessage = phoneSetupHintId(endpoint))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingState())
    private var mutPollJob: Job? = null

    /** Refresh readiness without navigating away; the user finishes on the TV. */
    fun startMutPolling() {
        if (mutPollJob?.isActive == true) return
        mutPollJob = viewModelScope.launch {
            var lastCheckedToken = ""
            var retryAfter = 0L
            while (isActive) {
                val token = mutPrefs.getMUT()
                if (token.isEmpty()) _tokenState.value = OnboardingState(phoneUrl = webServer.serverUrl())
                else if (mutPrefs.isVerified(token)) _tokenState.value = OnboardingState(webServer.serverUrl(), true, tokenStored = true)
                else if (token != lastCheckedToken || android.os.SystemClock.elapsedRealtime() >= retryAfter) {
                    lastCheckedToken = token
                    _tokenState.value = OnboardingState(webServer.serverUrl(), checking = true, tokenStored = true)
                    val result = tokenVerifier.verify(token)
                    if (mutPrefs.getMUT() == token) {
                        if (result == com.applemusicktv.data.auth.TokenVerification.Valid) mutPrefs.markVerified(token)
                        _tokenState.value = OnboardingState(webServer.serverUrl(), result == com.applemusicktv.data.auth.TokenVerification.Valid,
                            tokenStored = true, tokenError = if (result == com.applemusicktv.data.auth.TokenVerification.Invalid)
                                tr(R.string.msg_078) else if (result == com.applemusicktv.data.auth.TokenVerification.Unavailable)
                                tr(R.string.msg_079) else null)
                    }
                    retryAfter = android.os.SystemClock.elapsedRealtime() + 15_000
                }
                delay(2_000)
            }
        }
    }

    fun stopMutPolling() { mutPollJob?.cancel(); mutPollJob = null }

    fun finish() {
        stopMutPolling()
        onboarding.markCompleted()
    }
}
