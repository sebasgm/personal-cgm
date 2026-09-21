package dev.cgm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cgm.app.R
import dev.cgm.core.AccessibilityPreferences
import dev.cgm.core.ColorVision
import dev.cgm.core.ReadingFont
import dev.cgm.core.Zone
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt

/**
 * Reading settings.
 *
 * Ordered by how much difference each control actually makes, which is not the
 * order people expect: size and spacing first, typeface after. The evidence for
 * dyslexia-specific fonts is contested, while the evidence for larger text and
 * looser spacing is not — and those help every reader rather than one group.
 *
 * Everything is previewed live, because no description of a typeface is worth as
 * much as seeing a glucose reading rendered in it.
 */
@Composable
fun AccessibilityScreen(viewModel: CgmViewModel) {
    val prefs by viewModel.accessibility.collectAsState()
    fun update(block: (AccessibilityPreferences) -> AccessibilityPreferences) =
        viewModel.updateAccessibility(block(prefs))

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Preview()

        Spacer(Modifier.height(12.dp))
        SettingsSectionHeader(stringResource(R.string.a11y_text_size))
        Text(
            stringResource(R.string.a11y_adjust_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ScaleSlider(
            label = stringResource(R.string.a11y_text_size),
            value = prefs.textScale,
            range = AccessibilityPreferences.MIN_TEXT_SCALE..AccessibilityPreferences.MAX_TEXT_SCALE,
            format = { "${(it * 100).roundToInt()}%" },
        ) { update { p -> p.copy(textScale = it) } }

        ScaleSlider(
            label = stringResource(R.string.a11y_letter_spacing),
            value = prefs.letterSpacingEm,
            range = 0f..AccessibilityPreferences.MAX_LETTER_SPACING_EM,
            format = { "+%.2f em".format(it) },
        ) { update { p -> p.copy(letterSpacingEm = it) } }

        ScaleSlider(
            label = stringResource(R.string.a11y_line_spacing),
            value = prefs.lineSpacing,
            range = 1f..AccessibilityPreferences.MAX_LINE_SPACING,
            format = { "%.2f×".format(it) },
        ) { update { p -> p.copy(lineSpacing = it) } }

        Spacer(Modifier.height(16.dp))
        SettingsSectionHeader(stringResource(R.string.a11y_font))
        ReadingFont.entries.forEach { font ->
            ChoiceRow(
                selected = prefs.font == font,
                title = when (font) {
                    ReadingFont.SYSTEM -> stringResource(R.string.a11y_font_system)
                    ReadingFont.HYPERLEGIBLE -> stringResource(R.string.a11y_font_hyperlegible)
                    ReadingFont.DYSLEXIC -> stringResource(R.string.a11y_font_dyslexic)
                },
                note = when (font) {
                    ReadingFont.SYSTEM -> null
                    ReadingFont.HYPERLEGIBLE -> stringResource(R.string.a11y_font_hyperlegible_note)
                    ReadingFont.DYSLEXIC -> stringResource(R.string.a11y_font_dyslexic_note)
                },
            ) { update { p -> p.copy(font = font) } }
        }

        Spacer(Modifier.height(16.dp))
        SettingsSectionHeader(stringResource(R.string.a11y_colour_vision))
        ColorVision.entries.forEach { vision ->
            ChoiceRow(
                selected = prefs.colorVision == vision,
                title = when (vision) {
                    ColorVision.DEFAULT -> stringResource(R.string.a11y_colour_default)
                    ColorVision.COLOR_BLIND_SAFE -> stringResource(R.string.a11y_colour_safe)
                    ColorVision.HIGH_CONTRAST -> stringResource(R.string.a11y_colour_contrast)
                },
                note = when (vision) {
                    ColorVision.DEFAULT -> stringResource(R.string.a11y_colour_default_note)
                    ColorVision.COLOR_BLIND_SAFE -> stringResource(R.string.a11y_colour_safe_note)
                    ColorVision.HIGH_CONTRAST -> stringResource(R.string.a11y_colour_contrast_note)
                },
                swatches = { Swatches(vision) },
            ) { update { p -> p.copy(colorVision = vision) } }
        }

        Text(
            stringResource(R.string.a11y_redundant_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * A reading rendered with the current settings.
 *
 * The big number is here too, not just prose: the typeface has to be judged on
 * the thing it will actually be read on.
 */
@Composable
private fun Preview() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "168",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = ZoneColors.of(Zone.IN_RANGE),
            )
            Text(
                stringResource(R.string.a11y_preview),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The five zone colours of a palette, so the choice can be seen rather than read. */
@Composable
private fun Swatches(vision: ColorVision) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Zone.entries.forEach { zone ->
            Spacer(
                Modifier
                    .size(18.dp)
                    .background(ZoneColors.of(zone, vision), CircleShape)
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/**
 * A continuous adjustment, showing the value under the thumb as it moves.
 *
 * Separate from the alarm sliders because these are fractions rather than whole
 * mg/dL, and because the result is visible in the preview above as you drag —
 * which is the only way to judge whether a spacing is right.
 */
@Composable
private fun ScaleSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    var live by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(format(live), fontWeight = FontWeight.Medium)
        }
        Slider(
            value = live,
            // Applied as it moves rather than on release: the preview is the point,
            // and a preview that updates only after letting go is not a preview.
            onValueChange = { live = it; onChange(it) },
            valueRange = range,
        )
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    note: String?,
    swatches: @Composable (() -> Unit)? = null,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            swatches?.let {
                Spacer(Modifier.height(6.dp))
                it()
            }
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    HorizontalDivider()
}
