package com.coparently.app.presentation.consent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.domain.telemetry.TelemetryConsent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Records the analytics and crash-reporting answer (REL-5).
 *
 * It only writes. Applying the answer to the SDKs belongs to
 * [com.coparently.app.data.telemetry.TelemetryConsentApplier], which follows the same stored value
 * for the life of the process — so this ViewModel disappearing with its screen changes nothing,
 * and there is still exactly one caller of the collection setters.
 */
@HiltViewModel
class TelemetryConsentViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    /**
     * The stored answer, for the Settings row to render.
     *
     * `Eagerly` rather than `WhileSubscribed`: the value is one already-read in-memory field, the
     * flow behind it never touches disk again, and a Settings row that rendered "unanswered" for a
     * frame would show a parent the wrong state of their own privacy choice.
     */
    val consent: StateFlow<TelemetryConsent> = preferencesRepository.getTelemetryConsentFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, TelemetryConsent.UNANSWERED)

    /**
     * Stores the answer.
     *
     * @param granted Whether the user agreed.
     */
    fun answer(granted: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setTelemetryConsent(
                if (granted) TelemetryConsent.GRANTED else TelemetryConsent.DENIED
            )
        }
    }
}
