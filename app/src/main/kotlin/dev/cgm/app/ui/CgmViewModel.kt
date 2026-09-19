package dev.cgm.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cgm.app.data.CgmState
import dev.cgm.app.data.GlucoseRepository
import dev.cgm.app.data.SecureSettings
import dev.cgm.core.AlarmKind
import dev.cgm.core.AlarmSetting
import dev.cgm.core.AlarmSettings
import dev.cgm.core.ChartZoom
import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseStatistics
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.StatisticsCalculator
import dev.cgm.core.ThresholdBoundary
import dev.cgm.core.ThresholdOverrides
import dev.cgm.llu.LibreLinkUpCredentials
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class CgmViewModel(
    private val repository: GlucoseRepository,
    private val settings: SecureSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val state: StateFlow<CgmState> = repository.state

    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    /**
     * How much time the chart shows.
     *
     * A continuous span rather than one of four chips, because pinch zoom makes it
     * continuous. The chips remain as presets that set it to a round number.
     */
    private val _spanMillis = MutableStateFlow(GraphWindow.Default.millis)
    val spanMillis: StateFlow<Long> = _spanMillis.asStateFlow()

    /**
     * The preset the span currently matches exactly, or null once a pinch has
     * moved it off one. Drives which chip looks selected — after zooming, none
     * should, because claiming "3h" while showing 1h 47m would be a lie.
     */
    val preset: StateFlow<GraphWindow?> = _spanMillis
        .map { millis -> GraphWindow.entries.firstOrNull { it.millis == millis } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GraphWindow.Default)

    /**
     * Readings inside the current span, re-queried when it changes.
     *
     * No `distinctUntilChanged` here: a StateFlow is already distinct by equality,
     * so a pinch that lands on the span it started from costs nothing, and
     * flatMapLatest cancels the query a further pinch supersedes.
     */
    val history: StateFlow<List<GlucoseReading>> = _spanMillis
        .flatMapLatest { repository.historySince(clock() - it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Stats over the same window the graph shows, so the strip and the picture
     * can never disagree.
     */
    val statistics: StateFlow<GlucoseStatistics> = combine(
        history,
        _spanMillis,
        state,
    ) { readings, span, state ->
        val now = clock()
        StatisticsCalculator.compute(
            readings = readings,
            thresholds = state.snapshot?.thresholds ?: GlucoseThresholds.Default,
            windowStartMillis = now - span,
            windowEndMillis = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlucoseStatistics.Empty)

    // -- alarms (issue #1) -------------------------------------------------

    val alarmSettings: StateFlow<AlarmSettings> = settings.alarmSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmSettings.Default)

    fun updateAlarm(kind: AlarmKind, setting: AlarmSetting) {
        viewModelScope.launch {
            settings.saveAlarmSettings(alarmSettings.value.with(kind, setting))
        }
    }

    // -- ranges -------------------------------------------------------------

    /**
     * Take one boundary over from the account, or hand it back with a null.
     *
     * Reads the overrides out of [state] rather than keeping a second copy, so
     * there is exactly one answer to "which boundaries are mine" and a poll
     * landing mid-edit cannot resurrect a stale set.
     */
    fun setThreshold(boundary: ThresholdBoundary, mgdl: Double?) {
        viewModelScope.launch {
            repository.setThresholdOverrides(state.value.overrides.with(boundary, mgdl))
        }
    }

    fun resetThresholds() {
        viewModelScope.launch { repository.setThresholdOverrides(ThresholdOverrides.None) }
    }

    // -- logbook (issue #10) -----------------------------------------------

    val logbook: StateFlow<List<GlucoseReading>> = repository.recentReadings(LOGBOOK_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // -- trends -------------------------------------------------------------

    private val _period = MutableStateFlow(TrendPeriod.Default)
    val period: StateFlow<TrendPeriod> = _period.asStateFlow()

    private val _periodStats = MutableStateFlow(GlucoseStatistics.Empty)
    val periodStats: StateFlow<GlucoseStatistics> = _periodStats.asStateFlow()

    fun selectPeriod(period: TrendPeriod) {
        _period.value = period
        refreshPeriodStats()
    }

    fun refreshPeriodStats() {
        viewModelScope.launch {
            val now = clock()
            _periodStats.value = withContext(Dispatchers.IO) {
                repository.statistics(
                    startMillis = now - _period.value.millis,
                    endMillis = now,
                    thresholds = state.value.snapshot?.thresholds ?: GlucoseThresholds.Default,
                )
            }
        }
    }

    init {
        viewModelScope.launch { repository.refreshConfiguration() }
        refreshPeriodStats()
    }

    fun selectWindow(window: GraphWindow) {
        _spanMillis.value = window.millis
    }

    /**
     * A pinch on the chart.
     *
     * Called continuously through the gesture with each event's incremental scale,
     * so the span is scaled repeatedly rather than set once — which is what makes
     * the zoom track the fingers instead of jumping when they lift.
     */
    fun zoomBy(factor: Float) {
        _spanMillis.value = ChartZoom.zoomed(_spanMillis.value, factor)
    }

    /**
     * Saves credentials and immediately proves they work, so a typo surfaces
     * here rather than as a service that silently never updates.
     */
    fun signIn(email: String, password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _signingIn.value = true
            try {
                settings.saveCredentials(LibreLinkUpCredentials(email.trim(), password))
                repository.refreshConfiguration()
                withContext(Dispatchers.IO) { repository.pollOnce() }
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
        const val LOGBOOK_LIMIT = 500

        fun factory(repository: GlucoseRepository, settings: SecureSettings) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CgmViewModel(repository, settings) as T
            }
    }
}
