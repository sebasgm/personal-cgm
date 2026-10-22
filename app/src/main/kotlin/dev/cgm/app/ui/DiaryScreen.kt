package dev.cgm.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.cgm.app.data.ZoneWatcher
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.text.input.KeyboardType
import java.time.ZoneOffset
import dev.cgm.app.R
import dev.cgm.core.CarbEntry
import dev.cgm.app.data.MealPhotos
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import dev.cgm.core.InsulinDay
import dev.cgm.core.InsulinDayTotals
import dev.cgm.core.InsulinDiary
import dev.cgm.core.InsulinDose
import dev.cgm.core.InsulinKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The diary: insulin (issue #5) and carbohydrates, and what has been recorded.
 *
 * Called the diary rather than the log because "log" is already taken twice over —
 * by the Logbook of readings the sensor produced, and by the notion of a device log.
 * This screen is the opposite kind of record: nothing here is measured, everything
 * is something a person did and typed in. A diary is exactly that distinction, and
 * "food diary" is what the thing is already called outside software.
 *
 * Insulin and food share one screen because they are logged in the same breath — a
 * meal and the bolus for it are one event to the person having them, and making
 * that two tabs away from each other guarantees that on a busy day only one of them
 * gets recorded. They do *not* share a table, a unit or a total: units and grams are
 * different quantities and nothing here ever adds them.
 *
 * Amounts are typed on a numeric keypad, which puts the decimal separator back in
 * play — the one real hazard on this screen. A Spanish keyboard produces "6,5" where
 * an English one produces "6.5", and `toDoubleOrNull` accepts only the period. So
 * both fields keep either separator while filtering everything else, and the parsers
 * in :core normalise them: a comma must never read as nothing, and must certainly
 * never turn 6,5 into 65.
 *
 * The time is shared between the two forms and can be nudged with the relative chips
 * or set exactly with a date and then a time picker. Two dialogs rather than one
 * because Material offers no combined control, and an entry filed against the wrong
 * day is a worse error than an extra tap.
 */
@Composable
fun DiaryScreen(viewModel: CgmViewModel) {
    val doses by viewModel.doses.collectAsState()
    val carbs by viewModel.carbs.collectAsState()
    // Observed rather than remembered: a day boundary drawn in the zone you
    // left is not a day.
    val zone by ZoneWatcher.zone.collectAsState()

    var entry by remember { mutableStateOf(DiaryEntryKind.INSULIN) }

    var kind by remember { mutableStateOf(InsulinKind.BOLUS) }
    var unitsText by remember { mutableStateOf("") }
    var gramsText by remember { mutableStateOf("") }
    // The chosen image is copied into app storage as soon as it is picked, so the
    // transient read permission on the picker's Uri cannot expire before saving.
    var photoName by remember { mutableStateOf<String?>(null) }
    var viewingPhoto by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var minutesAgo by remember { mutableStateOf(0) }
    /** Set only when a date and time were chosen explicitly; otherwise the chips rule. */
    var pickedAtMillis by remember { mutableStateOf<Long?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }

    val units = InsulinDose.parseUnits(unitsText)
    val grams = CarbEntry.parseGrams(gramsText)
    val acceptable = when (entry) {
        DiaryEntryKind.INSULIN -> units != null &&
            InsulinDose(kind = kind, units = units, givenAtMillis = 1).isPlausible
        // An empty field is a meal with no count, which is allowed and is the
        // reason the button is not gated on a number. A field with something
        // unparseable in it is still refused: that is a typo, not an omission.
        DiaryEntryKind.FOOD -> (gramsText.isBlank() || grams != null) &&
            CarbEntry(grams = grams, eatenAtMillis = 1).isPlausible
    }
    // Resolved at save time when no explicit instant was picked, so a form left open
    // for ten minutes still records "now" as now.
    fun effectiveMillis(): Long =
        pickedAtMillis ?: (System.currentTimeMillis() - minutesAgo * 60_000L)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        // Insulin or food, before anything else on the screen: it decides what every
        // field below means, so it cannot sit among them.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            DiaryEntryKind.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == entry,
                    onClick = { entry = option },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = DiaryEntryKind.entries.size,
                    ),
                    label = { Text(stringResource(option.labelRes)) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(
                when (entry) {
                    DiaryEntryKind.INSULIN -> R.string.dose_log_title
                    DiaryEntryKind.FOOD -> R.string.diary_food_title
                }
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        when (entry) {
            DiaryEntryKind.INSULIN -> {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == InsulinKind.BOLUS,
                        onClick = { kind = InsulinKind.BOLUS },
                        label = { Text(stringResource(R.string.dose_bolus)) },
                    )
                    FilterChip(
                        selected = kind == InsulinKind.BASAL,
                        onClick = { kind = InsulinKind.BASAL },
                        label = { Text(stringResource(R.string.dose_basal)) },
                    )
                }

                Spacer(Modifier.height(16.dp))
                AmountField(
                    value = unitsText,
                    onValueChange = { unitsText = it },
                    labelRes = R.string.dose_units,
                    suffixRes = R.string.dose_units_suffix,
                    isError = unitsText.isNotEmpty() && !acceptable,
                )
                if (unitsText.isNotEmpty() && !acceptable) {
                    FieldError(
                        stringResource(
                            R.string.dose_invalid,
                            formatUnits(InsulinDose.MAX_PLAUSIBLE_UNITS),
                        )
                    )
                }
            }

            DiaryEntryKind.FOOD -> {
                Spacer(Modifier.height(16.dp))
                AmountField(
                    value = gramsText,
                    onValueChange = { gramsText = it },
                    labelRes = R.string.carb_grams,
                    suffixRes = R.string.carb_grams_suffix,
                    isError = gramsText.isNotBlank() && !acceptable,
                )
                if (gramsText.isNotBlank() && !acceptable) {
                    FieldError(
                        stringResource(
                            R.string.carb_invalid,
                            formatUnits(CarbEntry.MAX_PLAUSIBLE_GRAMS),
                        )
                    )
                } else {
                    // Said on the form rather than left to be discovered by
                    // pressing save with an empty field and seeing it work.
                    Text(
                        stringResource(R.string.carb_grams_optional),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                MealPhotoPicker(
                    photoName = photoName,
                    onPicked = { photoName = it },
                    onRemoved = {
                        photoName?.let { name ->
                            MealPhotos.delete(MealPhotos.directoryIn(context), name)
                        }
                        photoName = null
                    },
                    onView = { viewingPhoto = it },
                )

                // Carbohydrates are almost always estimated in round numbers, and
                // typing 45 on a keypad while holding a plate is the friction that
                // stops a food log being kept. The chips fill the field rather than
                // saving, so an estimate can still be adjusted before it is stored.
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.carb_quick),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    COMMON_GRAMS.forEach { amount ->
                        FilterChip(
                            selected = grams == amount.toDouble(),
                            onClick = { gramsText = amount.toString() },
                            label = { Text("$amount") },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.dose_when), style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Entering a forgotten injection or a meal an hour ago is ordinary, and it
            // has to land at the time it actually happened or every chart built on it
            // is wrong.
            listOf(
                0 to R.string.dose_now,
                15 to R.string.dose_minus_15,
                30 to R.string.dose_minus_30,
                60 to R.string.dose_minus_60,
            ).forEach { (offset, labelRes) ->
                FilterChip(
                    selected = pickedAtMillis == null && minutesAgo == offset,
                    onClick = {
                        minutesAgo = offset
                        pickedAtMillis = null
                    },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }

        TextButton(onClick = { pickingDate = true }) {
            Text(stringResource(R.string.dose_pick_datetime))
        }
        Text(
            stringResource(
                R.string.dose_will_record_at,
                Instant.ofEpochMilli(effectiveMillis()).atZone(zone).format(DIARY_TIME_FORMAT),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text(stringResource(R.string.dose_note)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val at = effectiveMillis()
                when (entry) {
                    DiaryEntryKind.INSULIN -> viewModel.logDose(
                        kind = kind,
                        units = units ?: return@Button,
                        givenAtMillis = at,
                        note = note,
                    )
                    DiaryEntryKind.FOOD -> viewModel.logCarbs(
                        grams = grams,
                        eatenAtMillis = at,
                        note = note,
                        photoName = photoName,
                    )
                }
                // Reset only what should not carry over. The kind usually repeats —
                // a bolus is followed by another bolus — so it stays.
                unitsText = ""
                gramsText = ""
                // Handed to the saved entry, so it must not be deleted here.
                photoName = null
                minutesAgo = 0
                pickedAtMillis = null
                note = ""
            },
            enabled = acceptable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.dose_save))
        }

        Spacer(Modifier.height(24.dp))
        TodayTotals(doses, carbs, zone)

        Spacer(Modifier.height(16.dp))
        ByDaySummary(doses, carbs, zone)

        Spacer(Modifier.height(16.dp))
        CsvExportRow(
            titleRes = R.string.diary_export,
            noteRes = R.string.diary_export_note,
            kind = "insulin",
            load = viewModel::treatmentsExportCsv,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.diary_recent),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            stringResource(R.string.diary_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(4.dp))

        // One list, in time order, rather than a column each. A bolus at 13:05 and the
        // 60 g it covered at 13:00 only make sense beside each other, and that is also
        // how you spot the meal you forgot to dose for.
        val recent = remember(doses, carbs) { merged(doses, carbs) }
        if (recent.isEmpty()) {
            Text(
                stringResource(R.string.diary_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        recent.forEach { row ->
            when (row) {
                is DiaryRow.Dose -> DoseRow(
                    dose = row.dose,
                    zone = zone,
                    onDelete = { viewModel.deleteDose(row.dose) },
                )
                is DiaryRow.Carbs -> CarbRow(
                    entry = row.entry,
                    zone = zone,
                    onDelete = { viewModel.deleteCarbs(row.entry) },
                    onViewPhoto = { viewingPhoto = it },
                )
            }
            HorizontalDivider()
        }
    }

    // Date first, then time. Two steps rather than one combined control because
    // Material offers no combined picker, and an entry made for the wrong day is a
    // worse error than one extra tap.
    if (pickingDate) {
        DiaryDatePicker(
            initialMillis = effectiveMillis(),
            zone = zone,
            onPick = { dayStart ->
                pickingDate = false
                pickingTime = dayStart
            },
            onDismiss = { pickingDate = false },
        )
    }
    pickingTime?.let { dayStart ->
        DiaryTimePicker(
            initialMillis = effectiveMillis(),
            zone = zone,
            onPick = { hour, minute ->
                pickedAtMillis = Instant.ofEpochMilli(dayStart)
                    .atZone(zone)
                    .withHour(hour)
                    .withMinute(minute)
                    .withSecond(0)
                    .toInstant()
                    .toEpochMilli()
                pickingTime = null
            },
            onDismiss = { pickingTime = null },
        )
    }
    viewingPhoto?.let { name ->
        MealPhotoDialog(name = name, onDismiss = { viewingPhoto = null })
    }
}

/** What is being written down. Two kinds, and the screen is one of them at a time. */
private enum class DiaryEntryKind(val labelRes: Int) {
    INSULIN(R.string.diary_insulin),
    FOOD(R.string.diary_food),
}

/** A row in the history: whichever of the two things happened at that moment. */
private sealed interface DiaryRow {
    val atMillis: Long

    data class Dose(val dose: InsulinDose) : DiaryRow {
        override val atMillis: Long get() = dose.givenAtMillis
    }

    data class Carbs(val entry: CarbEntry) : DiaryRow {
        override val atMillis: Long get() = entry.eatenAtMillis
    }
}

/** Both records interleaved, newest first. */
private fun merged(doses: List<InsulinDose>, carbs: List<CarbEntry>): List<DiaryRow> =
    (doses.map(DiaryRow::Dose) + carbs.map(DiaryRow::Carbs))
        .sortedByDescending { it.atMillis }

/**
 * The amount field, for both units and grams.
 *
 * Filtered as it is typed rather than validated afterwards: neither field has any
 * use for letters, and both separators are kept because which one the keyboard
 * offers depends on the language.
 */
@Composable
private fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    suffixRes: Int,
    isError: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { typed ->
            onValueChange(typed.filter { it.isDigit() || it == '.' || it == ',' }.take(6))
        },
        label = { Text(stringResource(labelRes)) },
        suffix = { Text(stringResource(suffixRes)) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FieldError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** Picks the day. The picker speaks UTC midnight, so it is converted to local. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiaryDatePicker(
    initialMillis: Long,
    zone: ZoneId,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = state.selectedDateMillis
                    if (picked == null) {
                        onDismiss()
                    } else {
                        val date = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        onPick(date.atStartOfDay(zone).toInstant().toEpochMilli())
                    }
                }
            ) { Text(stringResource(R.string.home_picker_show)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_picker_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

/** Picks the time of day, on the date already chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiaryTimePicker(
    initialMillis: Long,
    zone: ZoneId,
    onPick: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = remember(initialMillis) { Instant.ofEpochMilli(initialMillis).atZone(zone) }
    val state = rememberTimePickerState(
        initialHour = start.hour,
        initialMinute = start.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(state.hour, state.minute) }) {
                Text(stringResource(R.string.dose_time_set))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_picker_cancel)) } },
        text = { TimePicker(state = state) },
    )
}

/**
 * Today's insulin and today's carbohydrates, side by side and never added up.
 *
 * Both are what a day is reasoned about in, and they are two figures rather than a
 * total because units and grams are not the same quantity.
 */
@Composable
private fun TodayTotals(doses: List<InsulinDose>, carbs: List<CarbEntry>, zone: ZoneId) {
    val today = LocalDate.now(zone)
    fun isToday(millis: Long) =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() == today

    val totals = InsulinDayTotals.of(doses.filter { isToday(it.givenAtMillis) })
    val grams = CarbEntry.totalGrams(carbs.filter { isToday(it.eatenAtMillis) })

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                R.string.diary_today,
                formatUnits(totals.basalUnits),
                formatUnits(totals.bolusUnits),
                formatUnits(grams),
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun DoseRow(dose: InsulinDose, zone: ZoneId, onDelete: () -> Unit) {
    val time = remember(dose.givenAtMillis) {
        Instant.ofEpochMilli(dose.givenAtMillis).atZone(zone).format(DIARY_TIME_FORMAT)
    }
    val kindLabel = stringResource(
        if (dose.kind == InsulinKind.BASAL) R.string.dose_basal else R.string.dose_bolus
    )

    EntryRow(
        title = "${formatUnits(dose.units)} ${stringResource(R.string.dose_units_suffix)} " +
            "· $kindLabel",
        subtitle = dose.note?.let { stringResource(R.string.dose_given_at, time, it) } ?: time,
        onDelete = onDelete,
    )
}

@Composable
private fun CarbRow(
    entry: CarbEntry,
    zone: ZoneId,
    onDelete: () -> Unit,
    onViewPhoto: (String) -> Unit,
) {
    val time = remember(entry.eatenAtMillis) {
        Instant.ofEpochMilli(entry.eatenAtMillis).atZone(zone).format(DIARY_TIME_FORMAT)
    }

    EntryRow(
        title = entry.grams
            ?.let { stringResource(R.string.carb_row, formatUnits(it)) }
            // Named rather than shown as "0 g" or "— g": the meal happened, and
            // only the count is missing.
            ?: stringResource(R.string.carb_row_uncounted),
        subtitle = entry.note?.let { stringResource(R.string.dose_given_at, time, it) } ?: time,
        onDelete = onDelete,
        leading = entry.photoName?.let { name ->
            { MealThumbnail(name = name, onClick = { onViewPhoto(name) }) }
        },
    )
}

/**
 * A meal's photograph, small, in the diary row.
 *
 * Decoded at thumbnail size rather than full size and scaled down by the layout:
 * a list of meals that decodes a 1024px bitmap per row runs the heap out, and the
 * row only ever shows 48dp of it.
 */
@Composable
private fun MealThumbnail(name: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, name) {
        value = withContext(Dispatchers.IO) {
            MealPhotos.load(MealPhotos.directoryIn(context), name, THUMBNAIL_PX)
        }
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.carb_photo_open),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Pick a photo for the meal being logged.
 *
 * The system photo picker, which needs no permission at all: it hands back one
 * image the user chose and never gives the app access to the gallery. A camera
 * capture would mean a camera permission and a FileProvider round trip for a
 * picture the camera app has already saved anyway.
 */
@Composable
private fun MealPhotoPicker(
    photoName: String?,
    onPicked: (String) -> Unit,
    onRemoved: () -> Unit,
    onView: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            // Copied and downscaled off the main thread: a phone photo is several
            // megabytes and decoding it on the UI thread drops frames visibly.
            val saved = withContext(Dispatchers.IO) {
                MealPhotos.save(
                    resolver = context.contentResolver,
                    uri = uri,
                    directory = MealPhotos.directoryIn(context),
                )
            }
            saved?.let(onPicked)
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        photoName?.let { MealThumbnail(name = it, onClick = { onView(it) }) }
        if (photoName != null) Spacer(Modifier.width(10.dp))
        TextButton(
            onClick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        ) {
            Text(
                stringResource(
                    if (photoName == null) R.string.carb_photo_add
                    else R.string.carb_photo_change
                )
            )
        }
        if (photoName != null) {
            TextButton(onClick = onRemoved) {
                Text(stringResource(R.string.carb_photo_remove))
            }
        }
    }
    Text(
        stringResource(R.string.carb_photo_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A meal's photograph, full size. */
@Composable
private fun MealPhotoDialog(name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, name) {
        value = withContext(Dispatchers.IO) {
            MealPhotos.load(MealPhotos.directoryIn(context), name, FULL_PX)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.carb_photo_open)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.carb_photo_close))
            }
        },
        text = {
            val loaded = bitmap
            if (loaded == null) {
                // Says so rather than showing an empty frame: a photo pruned at
                // ninety days, or lost to a restore, is a fact worth stating.
                Text(
                    stringResource(R.string.carb_photo_missing),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = stringResource(R.string.carb_photo_open),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

private const val THUMBNAIL_PX = 160
private const val FULL_PX = 1024

@Composable
private fun EntryRow(
    title: String,
    subtitle: String,
    onDelete: () -> Unit,
    /** Shown before the text. Only a meal with a photograph has one. */
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.dose_delete)) }
    }
}

/**
 * Amounts people actually estimate in: a piece of fruit, a sandwich, a plate of
 * pasta. Presets, not a food database — this app has no business guessing what was
 * on the plate, only recording what the person decided it came to.
 */
private val COMMON_GRAMS = listOf(15, 30, 45, 60)

/**
 * Drops the decimal when there isn't one, so "6 U" rather than "6.0 U" and "45 g"
 * rather than "45.0 g".
 *
 * Uses the default locale, so a Spanish UI shows "6,5" — the same separator the rest
 * of the screen uses.
 */
private fun formatUnits(units: Double): String =
    if (units % 1.0 == 0.0) units.toInt().toString() else String.format("%.1f", units)

private val DIARY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

/**
 * Insulin per calendar day, most recent first.
 *
 * The counterpart to today's figures above: the question "how much do I take on a
 * normal day" needs several days side by side, and reading it off the merged
 * entry list means adding up by eye.
 *
 * Days with nothing logged are absent rather than shown as zero. A missing day
 * means nothing was recorded, which is not the same claim as no insulin having
 * been taken — and in a diary the difference is the whole point.
 */
@Composable
private fun ByDaySummary(doses: List<InsulinDose>, carbs: List<CarbEntry>, zone: ZoneId) {
    val days = remember(doses, carbs, zone) {
        InsulinDiary.byDay(doses, carbs, zone, limitDays = BY_DAY_LIMIT)
    }

    Text(
        stringResource(R.string.diary_by_day),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
    )
    Text(
        stringResource(R.string.diary_by_day_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
    Spacer(Modifier.height(8.dp))

    if (days.isEmpty()) {
        Text(
            stringResource(R.string.diary_by_day_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    days.forEach { day ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    dayLabel(day.date, zone),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                // Grams sit under the units as context, and say plainly when some
                // of the day's meals carry no count at all.
                carbNote(day)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(
                    R.string.diary_day_units,
                    formatUnits(day.totals.totalUnits),
                    formatUnits(day.totals.basalUnits),
                    formatUnits(day.totals.bolusUnits),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
private fun carbNote(day: InsulinDay): String? = when {
    !day.hasCarbs -> null
    day.countedMeals == 0 -> stringResource(R.string.diary_day_carbs_none, day.uncountedMeals)
    day.uncountedMeals > 0 -> stringResource(
        R.string.diary_day_carbs_uncounted,
        formatUnits(day.carbGrams),
        day.countedMeals,
        day.uncountedMeals,
    )
    else -> stringResource(
        R.string.diary_day_carbs,
        formatUnits(day.carbGrams),
        day.countedMeals,
    )
}

@Composable
private fun dayLabel(date: LocalDate, zone: ZoneId): String {
    val today = LocalDate.now(zone)
    return when (date) {
        today -> stringResource(R.string.diary_day_today)
        today.minusDays(1) -> stringResource(R.string.diary_day_yesterday)
        else -> date.format(BY_DAY_FORMAT)
    }
}

private val BY_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

/** Two weeks: enough to see a normal day, short enough to stay a summary. */
private const val BY_DAY_LIMIT = 14
