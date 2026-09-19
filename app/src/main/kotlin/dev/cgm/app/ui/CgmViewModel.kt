package dev.cgm.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cgm.app.data.CgmState
import dev.cgm.app.data.GlucoseRepository
import dev.cgm.app.data.SecureSettings
import dev.cgm.core.GlucoseReading
import dev.cgm.llu.LibreLinkUpCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CgmViewModel(
    private val repository: GlucoseRepository,
    private val settings: SecureSettings,
) : ViewModel() {

    val state: StateFlow<CgmState> = repository.state

    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    /** Last three hours, which is what the watch graph will show in stage 5. */
    val history: StateFlow<List<GlucoseReading>> =
        repository.historySince(System.currentTimeMillis() - GRAPH_WINDOW_MILLIS)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { repository.refreshConfiguration() }
    }

    /**
     * Saves credentials and immediately proves they work, so the user learns
     * about a typo here rather than from a silent service that never updates.
     */
    fun signIn(email: String, password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _signingIn.value = true
            try {
                settings.saveCredentials(
                    LibreLinkUpCredentials(email.trim(), password)
                )
                repository.refreshConfiguration()
                val outcome = withContext(Dispatchers.IO) { repository.pollOnce() }
                val ok = repository.state.value.error?.needsUser != true
                if (!ok) settings.clearCredentials()
                onDone(ok)
            } finally {
                _signingIn.value = false
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch { withContext(Dispatchers.IO) { repository.pollOnce() } }
    }

    fun signOut() {
        viewModelScope.launch { repository.signOut() }
    }

    companion object {
        const val GRAPH_WINDOW_MILLIS = 3L * 60 * 60 * 1000

        fun factory(repository: GlucoseRepository, settings: SecureSettings) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CgmViewModel(repository, settings) as T
            }
    }
}
