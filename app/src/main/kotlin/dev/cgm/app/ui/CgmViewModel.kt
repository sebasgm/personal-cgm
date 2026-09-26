package dev.cgm.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cgm.core.InsulinReminder
import dev.cgm.core.InsulinReminders
import dev.cgm.app.reminder.ReminderScheduler
import dev.cgm.app.service.RelayConfig
import dev.cgm.app.Features
import dev.cgm.app.data.CgmState
import dev.cgm.app.data.GlucoseRepository
import dev.cgm.app.Locales
import dev.cgm.app.data.SecureSettings
import dev.cgm.core.AccessibilityPreferences
import dev.cgm.core.AlarmKind
import dev.cgm.core.AlarmSetting
import dev.cgm.core.AlarmSettings
import dev.cgm.core.CarbEntry
import dev.cgm.core.ChartHistory
import dev.cgm.core.ChartZoom
import dev.cgm.core.ContinuityReport
import dev.cgm.core.Forecast
import dev.cgm.core.ForecastCalibration
import dev.cgm.core.ForecastCalibrator
import dev.cgm.core.ForecastModel
import dev.cgm.core.GlucoseReading
import dev.cgm.core.GlucoseStatistics
import dev.cgm.core.Report
import dev.cgm.core.ReportPreferences
import dev.cgm.core.ReportSpec
import dev.cgm.core.ReportSplit
import dev.cgm.core.GlucoseThresholds
import dev.cgm.core.GlucoseUnit
import dev.cgm.core.InsulinDose
import dev.cgm.core.InsulinKind
import dev.cgm.core.StatisticsCalculator
import dev.cgm.core.TimeOfDayProfile
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class CgmViewModel(
    private val repository: GlucoseRepository,
    private val settings: SecureSettings,
    private val scheduler: ReminderScheduler? = null,
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
     * Where the chart's window ends, or null while it is following the clock.
     *
     * Null rather than "now" on purpose: storing an instant would freeze the chart
     * the moment it was set, and every new reading would appear to fall outside the
     * window. Null means *keep asking the clock*.
     */
    private val _endMillis = MutableStateFlow<Long?>(null)

    /** True while the chart is showing the live edge rather than browsing back. */
    val isLive: StateFlow<Boolean> = _endMillis
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** The window's end as an instant, resolving live to the current clock. */
    val endMillis: StateFlow<Long> = _endMillis
        .map { it ?: clock() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), clock())

    /**
     * Readings inside the current window, re-queried when span or end changes.
     *
     * No `distinctUntilChanged` here: a StateFlow is already distinct by equality,
     * so a pinch that lands on the span it started from costs nothing, and
     * flatMapLatest cancels the query a further pinch supersedes.
     */
    val history: StateFlow<List<GlucoseReading>> = combine(_spanMillis, _endMillis) { s, e -> s to e }
        .flatMapLatest { (span, end) ->
            if (end == null) {
                repository.historySince(clock() - span)
            } else {
                repository.historyBetween(end - span, end)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Stats over the same window the graph shows, so the strip and the picture
     * can never disagree.
     */
    val statistics: StateFlow<GlucoseStatistics> = combine(
        history,
        _spanMillis,
        _endMillis,
        state,
    ) { readings, span, end, state ->
        // The window the stats describe follows the chart wherever it is browsed
        // to. Computing them against now while the chart showed last Tuesday would
        // report a coverage figure for a window nobody is looking at.
        val windowEnd = end ?: clock()
        StatisticsCalculator.compute(
            readings = readings,
            thresholds = state.snapshot?.thresholds ?: GlucoseThresholds.Default,
            windowStartMillis = windowEnd - span,
            windowEndMillis = windowEnd,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlucoseStatistics.Empty)

    // -- alarms (issue #1) -------------------------------------------------

    val alarmSettings: StateFlow<AlarmSettings> = settings.alarmSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmSettings.Default)

    /**
     * Thresholds of the glucose alarms that are switched on, for the chart.
     *
     * Only enabled ones: a line for an alarm that will not fire would be drawing a
     * warning nobody is going to get.
     */
    val alarmLevels: StateFlow<List<Double>> = alarmSettings
        .map { settings ->
            AlarmKind.entries
                .filter { it.isGlucose && settings[it].enabled }
                .map { settings[it].thresholdMgdl }
                .sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /** Choose a unit, or pass null to follow the LibreLinkUp account again. */
    fun setUnit(unit: GlucoseUnit?) {
        viewModelScope.launch { repository.setUnitOverride(unit) }
    }

    fun resetThresholds() {
        viewModelScope.launch { repository.setThresholdOverrides(ThresholdOverrides.None) }
    }

    // -- insulin doses (issue #5) -------------------------------------------

    val doses: StateFlow<List<InsulinDose>> = repository.recentDoses(DOSE_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Records a dose. [onResult] reports whether it was accepted, so the form can
     * keep what was typed if it was not.
     */
    fun logDose(
        kind: InsulinKind,
        units: Double,
        givenAtMillis: Long,
        note: String?,
        onResult: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            onResult(
                repository.saveDose(
                    InsulinDose(
                        kind = kind,
                        units = InsulinDose.roundUnits(units),
                        givenAtMillis = givenAtMillis,
                        note = InsulinDose.cleanNote(note),
                    )
                )
            )
        }
    }

    fun deleteDose(dose: InsulinDose) {
        viewModelScope.launch { repository.deleteDose(dose) }
    }

    // -- carbohydrates ------------------------------------------------------

    val carbs: StateFlow<List<CarbEntry>> = repository.recentCarbs(CARB_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Records a meal. [onResult] reports whether it was accepted, so the form can
     * keep what was typed if it was not.
     */
    fun logCarbs(
        grams: Double,
        eatenAtMillis: Long,
        note: String?,
        onResult: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            onResult(
                repository.saveCarbs(
                    CarbEntry(
                        grams = CarbEntry.roundGrams(grams),
                        eatenAtMillis = eatenAtMillis,
                        note = CarbEntry.cleanNote(note),
                    )
                )
            )
        }
    }

    fun deleteCarbs(entry: CarbEntry) {
        viewModelScope.launch { repository.deleteCarbs(entry) }
    }

    // -- language -----------------------------------------------------------

    /** The chosen language tag, or null while following the device. */
    val languageTag: StateFlow<String?> = settings.languageTag
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Locales.current)

    /**
     * Change the app's language.
     *
     * [onApplied] runs once the choice is stored and cached, and is where the caller
     * recreates the activity — resources are resolved when a context is attached, so
     * nothing already on screen can re-resolve itself in the new language.
     */
    fun setLanguage(tag: String?, onApplied: () -> Unit) {
        viewModelScope.launch {
            settings.saveLanguageTag(tag)
            Locales.current = tag
            onApplied()
        }
    }

    // -- disclaimer ---------------------------------------------------------

    /**
     * Null until the stored value has been read.
     *
     * Three states, not two: showing the disclaimer while still loading would flash
     * it at everyone who already accepted it on every cold start.
     */
    val disclaimerAccepted: StateFlow<Boolean?> = settings.disclaimerAcceptedVersion
        .map { it >= Disclaimer.VERSION }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun acceptDisclaimer() {
        viewModelScope.launch { settings.acceptDisclaimer(Disclaimer.VERSION) }
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

    private val _timeOfDay = MutableStateFlow(TimeOfDayProfile.Empty)
    val timeOfDay: StateFlow<TimeOfDayProfile> = _timeOfDay.asStateFlow()

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
            _timeOfDay.value = withContext(Dispatchers.IO) {
                repository.timeOfDayProfile(now - _period.value.millis, now)
            }
        }
    }

    // -- report ------------------------------------------------------------------

    /**
     * How the report is cut, as chosen in Settings.
     *
     * Persisted rather than held on the screen: the report is configured in one
     * tab and read in another, so the choice has to outlive both.
     */
    val reportPreferences: StateFlow<ReportPreferences> = settings.reportPreferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReportPreferences.Default)

    private val _report = MutableStateFlow<Report?>(null)
    val report: StateFlow<Report?> = _report.asStateFlow()

    private val _reportBuilding = MutableStateFlow(false)
    val reportBuilding: StateFlow<Boolean> = _reportBuilding.asStateFlow()

    fun saveReportPreferences(preferences: ReportPreferences) {
        viewModelScope.launch { settings.saveReportPreferences(preferences) }
    }

    /**
     * Rebuild the report for the stored choices.
     *
     * Thresholds and unit come from the live snapshot so the exported file says
     * what the screen says. A report built against defaults while the account
     * runs on different targets would disagree with every other view in the app,
     * and the file is the copy that gets shown to someone else.
     */
    fun refreshReport(preferences: ReportPreferences = reportPreferences.value) {
        viewModelScope.launch {
            _reportBuilding.value = true
            try {
                val now = clock()
                val snapshot = state.value.snapshot
                val spec = ReportSpec(
                    startMillis = now - preferences.periodMillis,
                    endMillis = now,
                    split = preferences.split,
                    rangeCount = preferences.rangeCount,
                    thresholds = snapshot?.thresholds ?: GlucoseThresholds.Default,
                    unit = snapshot?.unit ?: GlucoseUnit.MGDL,
                )
                _report.value = withContext(Dispatchers.IO) { repository.buildReport(spec, now) }
            } finally {
                _reportBuilding.value = false
            }
        }
    }

    // -- relay -------------------------------------------------------------------

    val relayConfig: StateFlow<RelayConfig> = settings.relayConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RelayConfig.Empty)

    fun saveRelay(config: RelayConfig) {
        viewModelScope.launch { settings.saveRelayConfig(config) }
    }

    fun clearRelay() {
        viewModelScope.launch { settings.clearRelay() }
    }

    // -- insulin reminders -----------------------------------------------------

    val reminders: StateFlow<InsulinReminders> = settings.reminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsulinReminders.Empty)

    /**
     * Save and re-lay the alarms in one step.
     *
     * Never separately: a reminder saved but not scheduled is a row in a list
     * that will never fire, and nothing on screen would distinguish it from one
     * that will.
     */
    fun saveReminder(reminder: InsulinReminder) = updateReminders { it.with(reminder) }

    fun deleteReminder(id: String) = updateReminders { it.without(id) }

    fun setReminderEnabled(reminder: InsulinReminder, enabled: Boolean) =
        updateReminders { it.with(reminder.copy(enabled = enabled)) }

    private fun updateReminders(change: (InsulinReminders) -> InsulinReminders) {
        viewModelScope.launch {
            val updated = change(settings.remindersOnce())
            settings.saveReminders(updated)
            scheduler?.rescheduleAll(updated)
        }
    }

    // -- accessibility --------------------------------------------------------

    val accessibility: StateFlow<AccessibilityPreferences> = settings.accessibility
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AccessibilityPreferences.Default,
        )

    fun updateAccessibility(preferences: AccessibilityPreferences) {
        viewModelScope.launch { settings.saveAccessibility(preferences) }
    }

    // -- forecast -----------------------------------------------------------

    /**
     * Whether the projection is on.
     *
     * The build flag overrides the stored preference rather than sitting beside
     * it: somebody who switched the projection on before it was disabled should
     * not still have it, and this is the single place that decides.
     */
    val forecastEnabled: StateFlow<Boolean> = settings.forecastEnabled
        .map { it && Features.PROJECTION }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setForecastEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setForecastEnabled(enabled)
            if (enabled) refreshCalibration()
        }
    }

    /**
     * Whether the projection warning has been acknowledged **this run**.
     *
     * Deliberately not persisted. The disclaimer is a one-time agreement about
     * what the app is; this is a reminder about a specific line on a specific
     * chart, and it reappears every time the app opens with the projection on.
     * A guess about the future should not become wallpaper.
     */
    private val _forecastWarningAccepted = MutableStateFlow(false)
    val forecastWarningAccepted: StateFlow<Boolean> = _forecastWarningAccepted.asStateFlow()

    fun acceptForecastWarning() {
        _forecastWarningAccepted.value = true
    }

    private val _calibration = MutableStateFlow<ForecastCalibration?>(null)
    val calibration: StateFlow<ForecastCalibration?> = _calibration.asStateFlow()

    /**
     * Refit the band against measured error.
     *
     * Backtesting weeks of readings is not cheap, and the answer moves slowly, so
     * it runs once per session rather than per reading.
     */
    fun refreshCalibration() {
        viewModelScope.launch {
            _calibration.value = withContext(Dispatchers.Default) {
                val history = repository.historySince(clock() - CALIBRATION_WINDOW_MILLIS).first()
                ForecastCalibrator.calibrate(history)
            }
        }
    }

    /**
     * The projection, recomputed as readings arrive.
     *
     * Only produced while the window is live and the toggle is on; a forecast from
     * the end of a window the user has browsed back to is a hypothetical about a
     * question already answered.
     */
    val forecast: StateFlow<Forecast?> = combine(
        history,
        forecastEnabled,
        _calibration,
        _forecastWarningAccepted,
    ) { readings, enabled, calibration, accepted ->
        // Gated on the acknowledgement, not merely accompanied by it: until the
        // warning is accepted there is no projection on the chart at all.
        if (!enabled || !accepted) null
        else ForecastModel.forecast(readings, clock(), calibration)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True when the projection is on but its warning has not been acknowledged yet. */
    val forecastWarningDue: StateFlow<Boolean> =
        combine(forecastEnabled, _forecastWarningAccepted) { enabled, accepted ->
            enabled && !accepted
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // -- periodic reminder ---------------------------------------------------

    private val _reminderDue = MutableStateFlow(false)
    val reminderDue: StateFlow<Boolean> = _reminderDue.asStateFlow()

    /**
     * Show the short reminder about once a month.
     *
     * On a fresh install the timer starts rather than firing: someone who has
     * just read the full disclaimer does not need the short one a minute later.
     */
    private fun evaluateReminder() {
        viewModelScope.launch {
            if (settings.reminderSuppressed.first()) return@launch
            val lastShown = settings.reminderLastShownMillis.first()
            val now = clock()
            if (lastShown == 0L) {
                settings.markReminderShown(now)
                return@launch
            }
            _reminderDue.value = now - lastShown >= REMINDER_INTERVAL_MILLIS
        }
    }

    fun dismissReminder(dontShowAgain: Boolean) {
        _reminderDue.value = false
        viewModelScope.launch {
            settings.markReminderShown(clock())
            if (dontShowAgain) settings.setReminderSuppressed(true)
        }
    }

    // -- diagnostics --------------------------------------------------------

    private val _continuity = MutableStateFlow(ContinuityReport.Empty)
    val continuity: StateFlow<ContinuityReport> = _continuity.asStateFlow()

    /**
     * How much of the last day actually got recorded.
     *
     * Worth surfacing because a gap is permanent: LibreLinkUp serves about twelve
     * hours of 15-minute history and nothing older, so a minute we failed to poll
     * is gone at the resolution we poll at.
     */
    fun refreshContinuity() {
        viewModelScope.launch {
            _continuity.value = withContext(Dispatchers.IO) {
                repository.continuity(CONTINUITY_WINDOW_MILLIS)
            }
        }
    }

    init {
        viewModelScope.launch {
            repository.refreshConfiguration()
            // So Home opens on the last known reading instead of an em-dash.
            repository.primeFromStorage()
        }
        refreshPeriodStats()
        refreshContinuity()
        refreshCalibration()
        evaluateReminder()
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

    // -- browsing history (from the main chart view) -------------------------

    /**
     * A horizontal drag on the chart, as a fraction of its width.
     *
     * Dragging back off the live edge detaches the window from the clock; dragging
     * forward until it reaches now re-attaches it, so returning to live needs no
     * separate gesture — though the button is there for when the span is a week and
     * dragging would take a while.
     */
    fun panByFraction(fractionOfSpan: Float) {
        val now = clock()
        val panned = ChartHistory.panned(
            endMillis = _endMillis.value ?: now,
            spanMillis = _spanMillis.value,
            fractionOfSpan = fractionOfSpan,
            nowMillis = now,
        )
        _endMillis.value = if (ChartHistory.isLive(panned, now)) null else panned
    }

    fun stepDays(days: Int) {
        val now = clock()
        val stepped = ChartHistory.steppedDays(_endMillis.value ?: now, days, now)
        _endMillis.value = if (ChartHistory.isLive(stepped, now)) null else stepped
    }

    /** Jump to a date from the picker, landing at the end of that day. */
    fun showDayEnding(endOfDayMillis: Long) {
        val now = clock()
        val clamped = ChartHistory.clampEnd(endOfDayMillis, now)
        _endMillis.value = if (ChartHistory.isLive(clamped, now)) null else clamped
    }

    fun goLive() {
        _endMillis.value = null
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
        const val CONTINUITY_WINDOW_MILLIS = 24L * 60 * 60 * 1000
        const val REMINDER_INTERVAL_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** Enough history to fit a band and still hold a week back to check it. */
        const val CALIBRATION_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** Enough to cover several weeks of dosing without paging. */
        const val DOSE_LIMIT = 300

        /** Meals outnumber doses only slightly; the same few weeks either way. */
        const val CARB_LIMIT = 300

        fun factory(
                repository: GlucoseRepository,
                settings: SecureSettings,
                scheduler: ReminderScheduler? = null,
            ) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CgmViewModel(repository, settings, scheduler) as T
            }
    }
}
