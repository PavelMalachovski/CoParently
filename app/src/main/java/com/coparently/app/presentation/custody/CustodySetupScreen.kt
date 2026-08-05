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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.CustodyModelType
import com.coparently.app.presentation.common.rememberParentNames
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.presentation.theme.dimensions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
                        text = uiState.startDate.format(
                            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
                        ),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(dims.paddingMedium))

            // Mom first toggle (for non-custom models)
            if (uiState.selectedModelType != CustodyModelType.CUSTOM) {
                Text(
                    text = stringResource(R.string.custody_who_starts_first),
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
                                R.string.custody_starts_first,
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
                                    Text(
                                        text = stringResource(R.string.custody_week_abbrev, weekNumber),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 8.sp,
                                        color = if (isMomDay) CoPlanlyColors.MomPink else CoPlanlyColors.DadBlue
                                    )
                                    Text(
                                        text = stringResource(R.string.custody_day_abbrev, dayInWeek),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isMomDay) CoPlanlyColors.MomPink else CoPlanlyColors.DadBlue
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
                        text = uiState.previewText,
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
                                    .height(24.dp)
                                    .background(
                                        color.copy(alpha = 0.7f),
                                        RoundedCornerShape(4.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
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
                        Text(parentNames.labelFor("mom"), style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.width(16.dp))
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(CoPlanlyColors.DadBlue, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(parentNames.labelFor("dad"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp)) // Space for bottom bar
        }
    }

    // Date picker dialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = uiState.startDate
                .atStartOfDay(ZoneId.systemDefault())
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
                                .atZone(ZoneId.systemDefault())
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
                    text = modelType.displayName,
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
 * Returns a brief description for each model type.
 */
private fun getModelTypeDescription(modelType: CustodyModelType): String {
    return when (modelType) {
        CustodyModelType.WEEK_ON_WEEK_OFF ->
            "One parent has the child for a full week, then they switch."
        CustodyModelType.TWO_TWO_THREE ->
            "2 days, 2 days, 3 days pattern. More frequent transitions."
        CustodyModelType.THREE_FOUR_FOUR_THREE ->
            "3 days, 4 days alternating. Balanced mid-week and weekend time."
        CustodyModelType.CUSTOM ->
            "Create your own schedule pattern."
    }
}

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
