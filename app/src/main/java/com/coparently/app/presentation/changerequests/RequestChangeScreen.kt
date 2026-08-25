package com.coparently.app.presentation.changerequests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.components.TimePickerDialog
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Form for proposing a new date/time for [eventId] to the co-parent.
 *
 * It no longer takes a conversation id. The chat card used to be posted only when one was passed
 * — that is, only when the request was started from the chat screen — so a change proposed from
 * the calendar reached the thread not at all. `ActivityAnnouncer` resolves the pair's thread from
 * the two uids itself, so there is nothing left for a caller to supply or to forget.
 */
@Suppress("LongMethod") // Compose screen: state wiring + Scaffold in one place
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestChangeScreen(
    eventId: String,
    onBack: () -> Unit,
    viewModel: RequestChangeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(eventId) {
        viewModel.loadEvent(eventId)
    }

    LaunchedEffect(uiState) {
        if (uiState is RequestChangeUiState.Saved) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.change_request_form_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.change_request_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is RequestChangeUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is RequestChangeUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            is RequestChangeUiState.Ready, is RequestChangeUiState.Sending -> {
                val event = when (state) {
                    is RequestChangeUiState.Ready -> state.event
                    is RequestChangeUiState.Sending -> state.event
                    else -> return@Scaffold
                }
                RequestChangeForm(
                    event = event,
                    isSending = state is RequestChangeUiState.Sending,
                    onSubmit = { start, end, note ->
                        viewModel.submit(event, start, end, note)
                    },
                    modifier = Modifier.padding(padding)
                )
            }

            is RequestChangeUiState.Saved -> Unit // navigating away
        }
    }
}

@Suppress("LongMethod") // Compose form: fields + three picker dialogs
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestChangeForm(
    event: Event,
    isSending: Boolean,
    onSubmit: (LocalDateTime, LocalDateTime?, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    var proposedDate by remember { mutableStateOf(event.startDateTime.toLocalDate()) }
    var proposedStartTime by remember { mutableStateOf(event.startDateTime.toLocalTime()) }
    var proposedEndTime by remember {
        mutableStateOf(event.endDateTime?.toLocalTime())
    }
    var note by remember { mutableStateOf("") }

    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium
                )
                // The resource takes the date and the time as two arguments; the end time,
                // when the event has one, is appended to the second one as a range.
                val currentTime = event.startDateTime.format(timeFormatter) +
                    (event.endDateTime?.let { " – " + it.format(timeFormatter) } ?: "")
                Text(
                    text = stringResource(
                        R.string.change_request_currently,
                        event.startDateTime.format(dateFormatter),
                        currentTime
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            text = stringResource(R.string.change_request_proposed_new_time),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )

        OutlinedButton(
            onClick = { showDatePicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(proposedDate.format(dateFormatter))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showStartTimePicker = true },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    stringResource(
                        R.string.change_request_from_time,
                        proposedStartTime.format(timeFormatter)
                    )
                )
            }
            OutlinedButton(
                onClick = { showEndTimePicker = true },
                modifier = Modifier.weight(1f)
            ) {
                val endTime = proposedEndTime
                Text(
                    if (endTime != null) {
                        stringResource(R.string.change_request_to_time, endTime.format(timeFormatter))
                    } else {
                        stringResource(R.string.change_request_to_unset)
                    }
                )
            }
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text(stringResource(R.string.change_request_note_label)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val start = LocalDateTime.of(proposedDate, proposedStartTime)
                val end = proposedEndTime?.let { LocalDateTime.of(proposedDate, it) }
                onSubmit(start, end, note)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSending
        ) {
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(stringResource(R.string.change_request_send))
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = proposedDate
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
                            proposedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        }
                        showDatePicker = false
                    }
                ) { Text(stringResource(R.string.change_request_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.change_request_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showStartTimePicker) {
        TimePickerDialog(
            initialTime = proposedStartTime,
            onTimeSelected = {
                proposedStartTime = it
                showStartTimePicker = false
            },
            onDismiss = { showStartTimePicker = false }
        )
    }

    if (showEndTimePicker) {
        TimePickerDialog(
            initialTime = proposedEndTime ?: proposedStartTime.plusHours(1),
            onTimeSelected = {
                proposedEndTime = it
                showEndTimePicker = false
            },
            onDismiss = { showEndTimePicker = false }
        )
    }
}
