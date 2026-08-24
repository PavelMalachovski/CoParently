package com.coparently.app.presentation.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.friends.FriendRole
import com.coparently.app.presentation.common.PillChip
import com.coparently.app.presentation.theme.CoPlanlyColors

/**
 * The friend's own profile, authored by them (item 16).
 *
 * Everything here is the friend saying who they are — the two parents read it and can never
 * write it, which is what stops a family filling in a grandmother's details on her behalf.
 *
 * The blood group and phone sit beside the name for the same reason the child record carries
 * them: the moment they are needed is the moment nobody has time to look them up.
 *
 * @param onNavigateUp Returns wherever the friend came from.
 * @param viewModel Screen state.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FriendProfileScreen(
    onNavigateUp: () -> Unit,
    viewModel: FriendViewModel = hiltViewModel()
) {
    val stored by viewModel.myProfile.collectAsState()
    val saveError by viewModel.saveError.collectAsState()

    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(FriendRole.FRIEND) }
    var phone by remember { mutableStateOf("") }
    var bloodGroup by remember { mutableStateOf("") }

    // Fills the form once the stored profile arrives, and again if it changes underneath —
    // keyed on the value rather than on Unit so a slow first read does not leave a blank form
    // the friend then saves over their own details.
    LaunchedEffect(stored) {
        stored?.let { profile ->
            name = profile.name
            role = profile.role
            phone = profile.phones.firstOrNull().orEmpty()
            bloodGroup = profile.bloodGroup.orEmpty()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.friend_profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.friend_profile_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(R.string.friend_section_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FriendRole.entries.forEach { option ->
                    PillChip(
                        label = stringResource(option.labelRes()),
                        container = if (option == role) {
                            CoPlanlyColors.FriendTeal.copy(alpha = 0.15f)
                        } else {
                            null
                        },
                        contentColor = if (option == role) {
                            CoPlanlyColors.FriendTeal
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        onClick = { role = option }
                    )
                }
            }

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text(stringResource(R.string.friend_profile_phone)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = bloodGroup,
                onValueChange = { bloodGroup = it },
                label = { Text(stringResource(R.string.friend_profile_blood_group)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            saveError?.let { res ->
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = {
                    viewModel.clearSaveError()
                    viewModel.saveProfile(
                        name = name,
                        role = role,
                        phones = listOf(phone),
                        bloodGroup = bloodGroup,
                        // Photo upload arrives with the Storage wiring; a button that did
                        // nothing would be exactly the promise this project's design rules
                        // forbid, so the existing value is carried rather than a control shown.
                        photoUrl = stored?.photoUrl
                    )
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.friend_profile_save))
            }
        }
    }
}

/** The localized name of a role. */
@androidx.annotation.StringRes
private fun FriendRole.labelRes(): Int = when (this) {
    FriendRole.GUARDIAN -> R.string.friend_role_guardian
    FriendRole.FRIEND -> R.string.friend_role_friend
    FriendRole.GRANDPARENT -> R.string.friend_role_grandparent
}
