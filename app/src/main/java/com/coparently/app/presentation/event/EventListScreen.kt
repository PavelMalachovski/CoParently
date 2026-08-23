package com.coparently.app.presentation.event

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.common.ParentNames
import com.coparently.app.presentation.common.animations.AnimatedEmptyState
import com.coparently.app.presentation.common.rememberParentNames
import com.coparently.app.domain.events.CalendarVisibility
import com.coparently.app.presentation.theme.CoPlanlyColors
import kotlinx.coroutines.launch

/**
 * Screen displaying a list of all events. Each row swipes left to delete with an
 * Undo snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventListScreen(
    onEventClick: (String) -> Unit,
    onAddEventClick: () -> Unit,
    onNavigateUp: () -> Unit,
    viewModel: EventViewModel = hiltViewModel()
) {
    val allEvents by viewModel.events.collectAsState()

    // Events this parent created that the co-parent has not answered are summarised rather than
    // listed: they are in nobody's calendar yet, so listing them among real events would say they
    // are settled. The strip is what tells their creator "not answered yet" apart from "I never
    // created it" — without it a withheld event is simply gone from the phone that made it.
    val awaitingCoParent = remember(allEvents) { allEvents.filter { it.acceptance.isPending } }
    val events = remember(allEvents) { CalendarVisibility.visible(allEvents) }
    val parentNames = rememberParentNames(viewModel.parents.collectAsState().value)
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Snackbar strings are resolved here, in composable scope, because the
    // delete lambda below is not composable.
    val deletedMessage = stringResource(R.string.event_deleted_message)
    val undoLabel = stringResource(R.string.event_deleted_undo)

    // Delete now, offer Undo. The full event is captured here, so Undo simply
    // re-creates it (the id is preserved on the domain model).
    val deleteWithUndo: (Event) -> Unit = { event ->
        viewModel.deleteEventById(event.id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = deletedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.createEvent(event)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.event_list_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.event_list_back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddEventClick,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.event_list_add)
                )
            }
        }
    ) { paddingValues ->
        when (val state = uiState) {
            is EventUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    Text(
                        text = stringResource(R.string.event_list_loading),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            is EventUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    Text(
                        text = stringResource(R.string.event_list_error, state.message),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Both the settled list (Success) and the transient post-op state show
            // the current events, or an empty state.
            is EventUiState.OperationSuccess,
            is EventUiState.Success -> {
                if (events.isEmpty() && awaitingCoParent.isEmpty()) {
                    AnimatedEmptyState(
                        icon = Icons.Default.Event,
                        title = stringResource(R.string.event_list_empty_title),
                        description = stringResource(R.string.event_list_empty_description),
                        actionText = stringResource(R.string.event_list_empty_action),
                        onActionClick = onAddEventClick
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(horizontal = 16.dp)
                    ) {
                        if (awaitingCoParent.isNotEmpty()) {
                            item(key = "awaiting-strip") {
                                WaitingOnCoParentStrip(count = awaitingCoParent.size)
                            }
                        }
                        items(events, key = { it.id }) { event ->
                            SwipeableEventCard(
                                event = event,
                                parentNames = parentNames,
                                onClick = { onEventClick(event.id) },
                                onDelete = { deleteWithUndo(event) },
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Event row that swipes end-to-start to delete, using the Material 3
 * [SwipeToDismissBox] (with its built-in resistance and fling handling).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableEventCard(
    event: Event,
    parentNames: ParentNames,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.event_delete),
                    tint = MaterialTheme.colorScheme.onError
                )
            }
        }
    ) {
        EventCardContent(event = event, parentNames = parentNames, onClick = onClick)
    }
}

/** Foreground card of a swipeable event row: title, description, date and owner. */
@Composable
private fun EventCardContent(
    event: Event,
    parentNames: ParentNames,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleLarge
            )
            event.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = stringResource(R.string.event_list_date, event.startDateTime.toString()),
                style = MaterialTheme.typography.bodySmall
            )
            val parentLabel = parentNames.labelFor(event.parentOwner)
            Text(
                text = stringResource(R.string.event_list_parent, parentLabel),
                style = MaterialTheme.typography.bodySmall,
                color = when (event.parentOwner) {
                    "mom" -> CoPlanlyColors.MomPink
                    "dad" -> CoPlanlyColors.DadBlue
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}


/**
 * How many of this parent's own events the co-parent has not answered.
 *
 * A count rather than a list. The events themselves are in nobody's calendar until they are
 * answered, and rendering them as cards among settled ones would say they are settled — but their
 * creator still has to be able to tell "they have not answered" from "I never created it", which
 * is the whole reason a declined event is not deleted either.
 *
 * @param count How many are outstanding; always at least one when this is shown.
 */
@Composable
private fun WaitingOnCoParentStrip(count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = if (count == 1) {
                stringResource(R.string.event_acceptance_waiting_strip_one)
            } else {
                stringResource(R.string.event_acceptance_waiting_strip, count)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}
