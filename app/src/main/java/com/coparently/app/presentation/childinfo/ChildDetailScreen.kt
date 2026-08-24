package com.coparently.app.presentation.childinfo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.presentation.childinfo.components.GuestInviteSheet
import com.coparently.app.presentation.common.ListSkeleton

/**
 * One child's record: everything the family holds about them, read-only, every row opening the
 * editor.
 *
 * Split out of `ChildInfoScreen` when that became a list. The screen it came from showed
 * `childInfoList.first()`, so with two children the second was unreachable — and, less obviously,
 * so was their guest access: `openGuestInvite` was bound to the first record's id, meaning a
 * grandparent could never be granted access to a second child.
 *
 * The child is looked up **by id** from the list this ViewModel already holds, never from
 * `currentChildInfo`. That state is the editor's, written by `loadChildInfoById`, and a screen
 * that fed it would reintroduce the CQ-9 defect where an edit of one child landed on another.
 * A read-only screen needs no per-id observation of its own: the list flow already re-emits on
 * every write.
 *
 * @param childInfoId The child to show.
 * @param onNavigateBack Up-arrow.
 * @param onEditClick Opens the editor, by id.
 * @param viewModel ViewModel for child information.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildDetailScreen(
    childInfoId: String,
    onNavigateBack: () -> Unit,
    onEditClick: (String) -> Unit,
    viewModel: ChildInfoViewModel = hiltViewModel()
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val guestInvite by viewModel.guestInvite.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // A failed revoke has to be said out loud. The row vanishes from the list either way once
    // Room is written, so silence here would leave the parent believing access is gone.
    val revokeFailed by viewModel.guestRevokeFailed.collectAsState()
    val revokeFailedMessage = stringResource(R.string.guest_revoke_failed)
    LaunchedEffect(revokeFailed) {
        if (revokeFailed) {
            snackbarHostState.showSnackbar(revokeFailedMessage)
            viewModel.clearGuestRevokeFailed()
        }
    }

    val childInfo = (uiState as? ChildInfoUiState.Success)
        ?.childInfoList
        ?.firstOrNull { it.id == childInfoId }

    GuestInviteSheet(
        state = guestInvite,
        onChooseDuration = viewModel::chooseGuestDuration,
        onCreate = viewModel::createGuestInvite,
        onShare = { link -> context.startActivity(guestShareIntent(context, link)) },
        onDismiss = viewModel::dismissGuestInvite
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(childInfo?.childName ?: stringResource(R.string.childinfo_screen_title))
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigateBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.childinfo_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState is ChildInfoUiState.Loading -> ListSkeleton(rows = 3)
                childInfo != null -> ChildInfoContent(
                    childInfo = childInfo,
                    onEditClick = onEditClick,
                    onInviteGuest = { viewModel.openGuestInvite(childInfo.id) },
                    onRevokeGuest = { uid -> viewModel.revokeGuest(childInfo, uid) }
                )
                else -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // The record was deleted, here or on the co-parent's phone, while this screen
                    // was open. Saying so beats an empty page that looks like a failed load.
                    Text(
                        text = stringResource(R.string.childinfo_empty_state),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}
