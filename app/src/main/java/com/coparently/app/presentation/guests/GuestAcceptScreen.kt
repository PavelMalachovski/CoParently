package com.coparently.app.presentation.guests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.common.SectionRow
import com.coparently.app.presentation.pairing.components.CodeEntryField
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Redeeming a guest invitation.
 *
 * A separate screen from pairing, not a mode of it, because what it grants is a different
 * thing: read access to one child's record, for a stated length of time, with no parent slot
 * and no co-parent link. The screen says all three of those before the button, so nobody
 * accepts something they did not understand — and says the end date again afterwards, since
 * that is the part a guest will want to remember.
 *
 * @param onDone Leaves the screen, both on cancel and once access has been granted.
 * @param prefilledCode A code carried by a `coplanly://guest` deep link, or null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestAcceptScreen(
    onDone: () -> Unit,
    prefilledCode: String? = null,
    viewModel: GuestAcceptViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(prefilledCode) { viewModel.prefill(prefilledCode) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.guest_accept_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.guest_accept_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val until = state.acceptedUntilMillis
            if (until == null) {
                GuestAcceptForm(state = state, viewModel = viewModel)
            } else {
                GuestAccepted(untilMillis = until, onDone = onDone)
            }
        }
    }
}

/** The code field, with the explanation that has to come before the button. */
@Composable
private fun GuestAcceptForm(state: GuestAcceptUiState, viewModel: GuestAcceptViewModel) {
    Text(
        text = stringResource(R.string.guest_accept_explainer),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    CodeEntryField(
        value = state.code,
        onValueChange = viewModel::onCodeChanged,
        onSubmit = viewModel::accept,
        errorText = state.errorRes?.let { stringResource(it) },
        enabled = !state.isBusy
    )
    Button(
        onClick = viewModel::accept,
        enabled = !state.isBusy && state.code.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.isBusy) {
            CircularProgressIndicator(modifier = Modifier.size(BUSY_SPINNER))
        } else {
            Text(stringResource(R.string.guest_accept_action))
        }
    }
}

/** What a guest sees once the grant exists: what they may read, and until when. */
@Composable
private fun GuestAccepted(untilMillis: Long, onDone: () -> Unit) {
    val until = remember(untilMillis) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .format(Instant.ofEpochMilli(untilMillis).atZone(ZoneId.systemDefault()))
    }
    SectionGroup {
        SectionRow(
            icon = Icons.Default.CheckCircle,
            title = stringResource(R.string.guest_accept_done_title),
            supporting = stringResource(R.string.guest_accept_done_until, until)
        )
    }
    Text(
        text = stringResource(R.string.guest_accept_done_scope),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.guest_accept_done_action))
    }
}

/** Matches the height of the button label it replaces, so the button does not resize. */
private val BUSY_SPINNER = 20.dp
