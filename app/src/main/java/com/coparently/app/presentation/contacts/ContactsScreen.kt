package com.coparently.app.presentation.contacts

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.contacts.ContactGroup
import com.coparently.app.domain.contacts.DirectoryContact
import com.coparently.app.presentation.common.GroupLabel
import com.coparently.app.presentation.common.PillChip
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.common.SectionRow
import kotlinx.coroutines.launch

/** How the numbers on one row are joined. Punctuation, not text — nothing to translate. */
private const val NUMBER_SEPARATOR = " · "

/**
 * Important phone numbers, one tap from the dialler: grandparents, doctors, a friend's parents.
 *
 * The list is the children's own `emergencyContacts` — one list, widened in meaning. A doctor is
 * an emergency contact by any reasonable reading, and two parallel lists would make a parent
 * guess which one grandma went into. `relationship` is free text and already carries
 * "grandmother" or "paediatrician".
 *
 * **Dialling goes through [Intent.ACTION_DIAL], never `ACTION_CALL`.** `DIAL` opens the dialler
 * pre-filled and needs no permission; `CALL` places the call immediately and requires
 * `CALL_PHONE`. Asking a separated parent for permission to place calls, to save one tap, is not
 * a trade this app should make.
 *
 * @param onNavigateUp Goes back
 * @param viewModel Screen state
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onNavigateUp: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel()
) {
    val groups by viewModel.groups.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Resolved in composable scope: the failure message is shown from a coroutine, which is not
    // one.
    val noDialerMessage = stringResource(R.string.contacts_no_dialer)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.contacts_back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (groups.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.contacts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.contacts_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(items = groups, key = { it.childId }) { group ->
                ChildContacts(
                    group = group,
                    onDial = { number ->
                        if (!context.dial(number)) {
                            scope.launch { snackbarHostState.showSnackbar(noDialerMessage) }
                        }
                    }
                )
            }
        }
    }
}

/**
 * One child's contacts, as a labelled group of rows.
 *
 * @param group The child and their contacts
 * @param onDial Opens the dialler on a number
 */
@Composable
private fun ChildContacts(
    group: ContactGroup,
    onDial: (String) -> Unit
) {
    Column {
        GroupLabel(group.childName)
        SectionGroup {
            group.contacts.forEachIndexed { index, contact ->
                ContactRow(contact = contact, onDial = onDial)
                if (index != group.contacts.lastIndex) Divider()
            }
        }
    }
}

/**
 * One person: their name, how they relate to the child, their numbers, and a call button.
 *
 * The whole row taps to dial, not only the chip — this is the screen someone opens with a hurt
 * child in the other arm, and a 48dp target beats a 24dp one. A contact with no number has
 * neither: the row is inert and carries no call action, because a button that opens an empty
 * dialler is worse than no button.
 *
 * The row shows *every* number recorded rather than only the one it dials, so a second number is
 * at least readable. It stays one action, per [SectionRow]'s one-trailing-control rule.
 *
 * @param contact The person
 * @param onDial Opens the dialler on a number
 */
@Composable
private fun ContactRow(
    contact: DirectoryContact,
    onDial: (String) -> Unit
) {
    val dialable = contact.dialable
    val supporting = (listOf(contact.relationship) + contact.numbers)
        .filter { it.isNotBlank() }
        .joinToString(NUMBER_SEPARATOR)
    val callLabel = stringResource(R.string.contacts_call)

    SectionRow(
        title = contact.name,
        icon = Icons.Default.Person,
        supporting = supporting.ifBlank { null },
        onClick = dialable?.let { number -> { onDial(number) } },
        trailing = dialable?.let { number ->
            {
                PillChip(
                    label = callLabel,
                    icon = Icons.Default.Call,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = { onDial(number) }
                )
            }
        }
    )
}

/**
 * Opens the dialler pre-filled with [number].
 *
 * @return false when the device has no dialler to open, which is rare but real — a tablet with
 *   no telephony has none, and a silent no-op there looks exactly like a broken button.
 */
private fun android.content.Context.dial(number: String): Boolean {
    val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
    return try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        android.util.Log.w("ContactsScreen", "No dialler for tel: intent", e)
        false
    }
}
