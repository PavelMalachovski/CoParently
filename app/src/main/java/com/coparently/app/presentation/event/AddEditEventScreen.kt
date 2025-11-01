package com.coparently.app.presentation.event

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.theme.CoParentlyColors
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack

/**
 * Screen for adding or editing an event.
 */
@Composable
fun AddEditEventScreen(
    eventId: String?,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    viewModel: EventViewModel = hiltViewModel()
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var parentOwner by remember { mutableStateOf("mom") }
    var eventType by remember { mutableStateOf("general") }
    var startDate by remember { mutableStateOf(LocalDateTime.now()) }

    val scope = rememberCoroutineScope()

    // Load event if editing
    LaunchedEffect(eventId) {
        if (eventId != null) {
            scope.launch {
                val event = viewModel.getEventById(eventId)
                event?.let {
                    title = it.title
                    description = it.description ?: ""
                    parentOwner = it.parentOwner
                    eventType = it.eventType
                    startDate = it.startDateTime
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (eventId == null) "Add Event" else "Edit Event") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Cancel"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        modifier = Modifier.padding(bottom = 8.dp),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        modifier = Modifier.padding(top = 8.dp),
                        maxLines = 3
                    )

                    // Parent owner selection
                    // Event type selection
                    // Date/time pickers would go here
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        val event = Event(
                            id = eventId ?: UUID.randomUUID().toString(),
                            title = title,
                            description = description.ifEmpty { null },
                            startDateTime = startDate,
                            endDateTime = null,
                            eventType = eventType,
                            parentOwner = parentOwner,
                            isRecurring = false,
                            recurrencePattern = null,
                            createdAt = if (eventId == null) LocalDateTime.now() else LocalDateTime.now(), // TODO: preserve original
                            updatedAt = LocalDateTime.now()
                        )

                        if (eventId == null) {
                            viewModel.createEvent(event)
                        } else {
                            viewModel.updateEvent(event)
                        }
                        onSave()
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp)
            ) {
                Text(if (eventId == null) "Save" else "Update")
            }
        }
    }
}

