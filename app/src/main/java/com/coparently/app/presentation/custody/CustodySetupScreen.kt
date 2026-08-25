package com.coparently.app.presentation.custody

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.CustodyModelType
import com.coparently.app.presentation.common.ParentNames
import com.coparently.app.presentation.common.rememberParentNames
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.presentation.theme.ParentColors
import com.coparently.app.presentation.theme.dimensions
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Screen for setting up custody model/pattern.
 * Allows selection of predefined patterns or custom configuration.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustodySetupScreen(
    onNavigateBack: () -> Unit,
    viewModel: CustodySetupViewModel = hiltViewModel()
) {
    val dims = dimensions()
    val uiState by viewModel.uiState.collectAsState()
    val parentNames = rememberParentNames(viewModel.parents.collectAsState().value)
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }

    // Show error snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Navigate back on successful save
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.custody_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.custody_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Save button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(dims.paddingMedium)
            ) {
                Button(
                    onClick = { viewModel.save() },
                    enabled = uiState.isValid && !uiState.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dims.buttonHeight)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.custody_save_button))
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dims.paddingMedium)
        ) {
            // Model type selection
            Text(
                text = stringResource(R.string.custody_select_schedule_type),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = dims.paddingSmall)
            )

            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(dims.paddingSmall)
            ) {
                CustodyModelType.entries.forEach { modelType ->
                    ModelTypeCard(
                        modelType = modelType,
                        isSelected = uiState.selectedModelType == modelType,
                        onClick = { viewModel.selectModelType(modelType) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(dims.paddingMedium))

            // Start date picker
            Text(
                text = stringResource(R.string.custody_pattern_start_date),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = dims.paddingSmall)
            )
            Text(
                text = stringResource(R.string.custody_start_date_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = dims.paddingSmall)
                    .clickable { showDatePicker = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(dims.paddingMedium),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(dims.paddingMedium))
                    Text(
                        // Locale-aware, not a hardcoded US pattern — day and month names
                        // already follow the app language, their order must too.
                        text = uiState.startDate.format(
                            DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
                        ),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(dims.paddingMedium))

            // Mom first toggle (for non-custom models)
            if (uiState.selectedModelType != CustodyModelType.CUSTOM) {
                Text(
                    text = stringResource(rolesQuestionFor(uiState.selectedModelType)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = dims.paddingSmall)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = dims.paddingSmall),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    if (uiState.momFirst) CoPlanlyColors.MomPink else CoPlanlyColors.DadBlue,
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                rolesAnswerFor(uiState.selectedModelType),
                                parentNames.labelFor(if (uiState.momFirst) "mom" else "dad")
                            ),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Switch(
                        checked = uiState.momFirst,
                        onCheckedChange = { viewModel.setMomFirst(it) }
                    )
                }

                Spacer(modifier = Modifier.height(dims.paddingMedium))
            }

            // Midweek contact — only `výhradní péče se stykem` has one.
            AnimatedVisibility(
                visible = uiState.selectedModelType == CustodyModelType.EVERY_OTHER_WEEKEND,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                MidweekContactSection(
                    uiState = uiState,
                    onEnabledChange = viewModel::setMidweekEnabled,
                    onDayChange = viewModel::setMidweekDay,
                    onEveryWeekChange = viewModel::setMidweekEveryWeek
                )
            }

            // Custom pattern editor
            AnimatedVisibility(
                visible = uiState.selectedModelType == CustodyModelType.CUSTOM,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.custody_custom_pattern),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = dims.paddingSmall)
                    )
                    Text(
                        text = stringResource(
                            R.string.custody_custom_pattern_hint,
                            parentNames.labelFor("mom"),
                            parentNames.labelFor("dad")
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(dims.paddingSmall))

                    // Pattern days grid
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        repeat(uiState.customPatternDays) { dayIndex ->
                            val isMomDay = uiState.customMomDays.contains(dayIndex)
                            val weekNumber = dayIndex / 7 + 1
                            val dayInWeek = dayIndex % 7 + 1

                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isMomDay) CoPlanlyColors.MomPink.copy(alpha = 0.3f)
                                        else CoPlanlyColors.DadBlue.copy(alpha = 0.3f)
                                    )
                                    .border(
                                        width = 2.dp,
                                        color = if (isMomDay) CoPlanlyColors.MomPink else CoPlanlyColors.DadBlue,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { viewModel.toggleCustomMomDay(dayIndex) },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // `MomPink`/`DadBlue` are fill-only — `Color.kt` says so in
                                    // as many words, and they measure 3.7:1-4.6:1, below AA.
                                    // Here they were foreground text *over a 30% wash of the
                                    // same hue*, at 8sp, on the one screen a Czech parent
                                    // cannot skip. `ParentColors.text()` picks the theme-aware
                                    // partner; the type scale's own smallest size is 11sp, so
                                    // the override goes rather than being nudged.
                                    Text(
                                        text = stringResource(R.string.custody_week_abbrev, weekNumber),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ParentColors.text(if (isMomDay) "mom" else "dad")
                                    )
                                    Text(
                                        text = stringResource(R.string.custody_day_abbrev, dayInWeek),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = ParentColors.text(if (isMomDay) "mom" else "dad")
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(dims.paddingSmall))

                    // Quick selection buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(dims.paddingSmall)
                    ) {
                        TextButton(
                            onClick = {
                                // Select first week for mom
                                (0..6).forEach { viewModel.toggleCustomMomDay(it) }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(
                                    R.string.custody_week_to,
                                    1,
                                    parentNames.labelFor("mom")
                                )
                            )
                        }
                        TextButton(
                            onClick = {
                                // Select second week for mom (if exists)
                                if (uiState.customPatternDays > 7) {
                                    (7..13.coerceAtMost(uiState.customPatternDays - 1)).forEach {
                                        viewModel.toggleCustomMomDay(it)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(
                                    R.string.custody_week_to,
                                    2,
                                    parentNames.labelFor("mom")
                                )
                            )
                        }
                    }
                }
            }

            // Preview section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = dims.paddingMedium),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(dims.paddingMedium)
                ) {
                    Text(
                        text = stringResource(R.string.custody_preview),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(dims.paddingSmall))
                    Text(
                        text = custodyPreviewText(uiState, parentNames),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(dims.paddingSmall))

                    // Visual preview - show next 14 days
                    Text(
                        text = stringResource(R.string.custody_next_14_days),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val tempModel = createTempModel(uiState)
                        repeat(14) { dayOffset ->
                            val date = uiState.startDate.plusDays(dayOffset.toLong())
                            val custody = tempModel?.getCustodyFor(date)
                            val color = when (custody) {
                                "mom" -> CoPlanlyColors.MomPink
                                "dad" -> CoPlanlyColors.DadBlue
                                else -> Color.Gray
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    // `heightIn`, not `height`: a fixed box clips its own
                                    // label as soon as the reader's font scale grows.
                                    .heightIn(min = 24.dp)
                                    .background(
                                        color.copy(alpha = 0.7f),
                                        RoundedCornerShape(4.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // Legend
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = dims.paddingSmall),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(CoPlanlyColors.MomPink, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = parentNames.labelFor("mom"),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(CoPlanlyColors.DadBlue, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = parentNames.labelFor("dad"),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp)) // Space for bottom bar
        }
    }

    // Date picker dialog
    if (showDatePicker) {
        // Material3's DatePickerState speaks UTC-midnight millis on both sides; converting
        // through the system zone shifted the anchor a day early west of Greenwich.
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = uiState.startDate
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        )

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            viewModel.setStartDate(date)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.custody_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.custody_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/**
 * Card for displaying a custody model type option.
 */
@Composable
private fun ModelTypeCard(
    modelType: CustodyModelType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val dims = dimensions()

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder().copy(
                width = 2.dp,
                brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
            )
        } else {
            CardDefaults.outlinedCardBorder()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dims.paddingMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null // Handled by card
            )
            Spacer(modifier = Modifier.width(dims.paddingSmall))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = modelTypeLabel(modelType),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = getModelTypeDescription(modelType),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The name of a custody model, in the reader's language.
 *
 * `CustodyModelType.displayName` — which this screen used to render — is an English literal
 * on the enum, so the custody picker showed "Week On / Week Off" to every user in every
 * locale. The translations already existed in all five `custody_strings.xml` files and simply
 * were not wired up, which made this the untranslated screen on the one flow a Czech parent
 * cannot skip: `střídavá péče` is the arrangement most of them are here to describe.
 *
 * `displayName` is left on the enum for logs and debugging, where an English constant is what
 * you want; it must not reach the UI.
 */
/**
 * The midweek-contact controls of `výhradní péče se stykem`.
 *
 * Three decisions, in the order a parent makes them: is there one, which weekday, and is it
 * every week or only the week without the contact weekend.
 *
 * The overnight warning is not decoration. This model assigns a date to exactly one parent, so
 * a midweek "afternoon" — what most Czech orders actually say — becomes a whole day here,
 * handover to handover. Saying it plainly is what keeps the schedule from quietly claiming an
 * overnight the court did not give.
 *
 * @param uiState The current form state
 * @param onEnabledChange Turns the midweek day on or off
 * @param onDayChange Picks the weekday
 * @param onEveryWeekChange Every week, or only the week without the contact weekend
 */
@Composable
private fun MidweekContactSection(
    uiState: CustodySetupUiState,
    onEnabledChange: (Boolean) -> Unit,
    onDayChange: (DayOfWeek) -> Unit,
    onEveryWeekChange: (Boolean) -> Unit
) {
    val dims = dimensions()
    Column {
        Text(
            text = stringResource(R.string.custody_midweek_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = dims.paddingSmall)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = dims.paddingSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.custody_midweek_enable),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = uiState.midweekEnabled,
                onCheckedChange = onEnabledChange
            )
        }

        Text(
            text = stringResource(R.string.custody_midweek_overnight_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (uiState.midweekEnabled) {
            Spacer(modifier = Modifier.height(dims.paddingSmall))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(dims.paddingSmall)
            ) {
                WEEKDAYS.forEach { day ->
                    FilterChip(
                        selected = uiState.midweekDay == day,
                        onClick = { onDayChange(day) },
                        label = {
                            Text(
                                // The device (or app) language decides the name, never a
                                // hardcoded array — the Localization rule in CLAUDE.md.
                                day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                            )
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = dims.paddingSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(
                        if (uiState.midweekEveryWeek) {
                            R.string.custody_midweek_every_week
                        } else {
                            R.string.custody_midweek_off_week_only
                        }
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = uiState.midweekEveryWeek,
                    onCheckedChange = onEveryWeekChange
                )
            }
        }

        Spacer(modifier = Modifier.height(dims.paddingMedium))
    }
}

/** Monday to Friday, the only days a midweek contact may fall on. */
private val WEEKDAYS = listOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY
)

@Composable
private fun modelTypeLabel(modelType: CustodyModelType): String = stringResource(
    when (modelType) {
        CustodyModelType.WEEK_ON_WEEK_OFF -> R.string.custody_model_week_on_week_off
        CustodyModelType.EVERY_OTHER_WEEKEND -> R.string.custody_model_every_other_weekend
        CustodyModelType.TWO_TWO_THREE -> R.string.custody_model_two_two_three
        CustodyModelType.THREE_FOUR_FOUR_THREE -> R.string.custody_model_three_four_four_three
        CustodyModelType.CUSTOM -> R.string.custody_model_custom
    }
)

/**
 * Which question the switch above the preview is actually asking, for the selected pattern.
 *
 * Three of the four patterns alternate blocks of time, so "who starts first" is the whole of
 * it. [CustodyModelType.EVERY_OTHER_WEEKEND] does not alternate: one parent has the child and
 * the other has every second weekend, and asking a parent who "starts" invites them to answer
 * about the first weekend — which sets the switch backwards and hands over the school days.
 * A schedule that is wrong in that direction is exactly the failure this app exists to prevent,
 * so the question changes with the pattern rather than the parent being expected to translate it.
 *
 * `CUSTOM` never reaches here: the switch is not rendered for it.
 */
@StringRes
private fun rolesQuestionFor(modelType: CustodyModelType): Int = when (modelType) {
    CustodyModelType.EVERY_OTHER_WEEKEND -> R.string.custody_who_is_resident
    else -> R.string.custody_who_starts_first
}

/** The matching answer line — see [rolesQuestionFor]. */
@StringRes
private fun rolesAnswerFor(modelType: CustodyModelType): Int = when (modelType) {
    CustodyModelType.EVERY_OTHER_WEEKEND -> R.string.custody_is_resident
    else -> R.string.custody_starts_first
}

/**
 * A brief description of each model type, in the reader's language. See [modelTypeLabel] —
 * these four strings were hardcoded in English beside translations that already existed.
 */
@Composable
private fun getModelTypeDescription(modelType: CustodyModelType): String = stringResource(
    when (modelType) {
        CustodyModelType.WEEK_ON_WEEK_OFF -> R.string.custody_model_week_on_week_off_desc
        CustodyModelType.EVERY_OTHER_WEEKEND -> R.string.custody_model_every_other_weekend_desc
        CustodyModelType.TWO_TWO_THREE -> R.string.custody_model_two_two_three_desc
        CustodyModelType.THREE_FOUR_FOUR_THREE -> R.string.custody_model_three_four_four_three_desc
        CustodyModelType.CUSTOM -> R.string.custody_model_custom_desc
    }
)

/**
 * Creates a temporary CustodyModel from the UI state for preview purposes.
 */
private fun createTempModel(state: CustodySetupUiState): com.coparently.app.domain.model.CustodyModel? {
    return when (state.selectedModelType) {
        CustodyModelType.WEEK_ON_WEEK_OFF ->
            com.coparently.app.domain.model.CustodyModel.weekOnWeekOff(
                id = "preview",
                startDate = state.startDate,
                momFirst = state.momFirst
            )
        CustodyModelType.EVERY_OTHER_WEEKEND ->
            com.coparently.app.domain.model.CustodyModel.everyOtherWeekend(
                id = "preview",
                startDate = state.startDate,
                momIsResident = state.momFirst,
                midweek = state.midweek
            )
        CustodyModelType.TWO_TWO_THREE ->
            com.coparently.app.domain.model.CustodyModel.twoTwoThree(
                id = "preview",
                startDate = state.startDate,
                momStartsFirst = state.momFirst
            )
        CustodyModelType.THREE_FOUR_FOUR_THREE ->
            com.coparently.app.domain.model.CustodyModel.threeFourFourThree(
                id = "preview",
                startDate = state.startDate,
                momStartsFirst = state.momFirst
            )
        CustodyModelType.CUSTOM ->
            if (state.customMomDays.isNotEmpty()) {
                com.coparently.app.domain.model.CustodyModel.custom(
                    id = "preview",
                    startDate = state.startDate,
                    patternDays = state.customPatternDays,
                    momDayIndices = state.customMomDays
                )
            } else null
    }
}

/**
 * The Preview card's one-sentence description of the selected pattern, with both parents named.
 *
 * Formatted here rather than in the ViewModel for the usual reason: a ViewModel has no `Context`
 * and must not acquire one to resolve a string. [CustodySetupUiState] supplies the two slots and
 * this turns them into names, the same shape the "starts first" row two cards up already uses.
 *
 * @param uiState The selected model and its parameters
 * @param parentNames Resolves a slot to that parent's name
 */
@Composable
private fun custodyPreviewText(uiState: CustodySetupUiState, parentNames: ParentNames): String {
    val first = parentNames.labelFor(uiState.firstSlot)
    val second = parentNames.labelFor(uiState.secondSlot)
    return when (uiState.selectedModelType) {
        CustodyModelType.WEEK_ON_WEEK_OFF ->
            stringResource(R.string.custody_preview_week_on_week_off, first)
        CustodyModelType.EVERY_OTHER_WEEKEND -> {
            val midweek = uiState.midweek
            if (midweek == null) {
                stringResource(R.string.custody_preview_every_other_weekend, first, second)
            } else {
                stringResource(
                    if (midweek.everyWeek) {
                        R.string.custody_preview_every_other_weekend_midweek
                    } else {
                        R.string.custody_preview_every_other_weekend_midweek_off_week
                    },
                    first,
                    second,
                    midweek.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                )
            }
        }
        CustodyModelType.TWO_TWO_THREE ->
            stringResource(R.string.custody_preview_two_two_three, first, second)
        CustodyModelType.THREE_FOUR_FOUR_THREE ->
            stringResource(R.string.custody_preview_three_four_four_three, first, second)
        CustodyModelType.CUSTOM -> stringResource(
            R.string.custody_preview_custom,
            uiState.customMomDays.size,
            uiState.customPatternDays,
            parentNames.labelFor("mom")
        )
    }
}
