package com.coparently.app.presentation.pairing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.PairingInvite
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.pairing.PairingUri
import com.coparently.app.presentation.common.ConfirmationDialog
import com.coparently.app.presentation.pairing.components.CodeEntryField
import com.coparently.app.presentation.pairing.components.IncomingInviteCard
import com.coparently.app.presentation.pairing.components.InviteCodeCard
import com.coparently.app.presentation.pairing.components.PairedPartnerCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Co-parent pairing: hand over a code, scan a QR, open a shared link, or send
 * an email invitation — and unlink again.
 *
 * @param onNavigateBack Up navigation.
 * @param prefilledCode Code carried by a `coplanly://pair` deep link, if any.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onNavigateBack: () -> Unit,
    prefilledCode: String? = null,
    viewModel: PairingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val form by viewModel.form.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val codeCopiedMessage = stringResource(R.string.pairing_code_copied)
    val actions = rememberNotPairedActions(context, clipboard, snackbarHostState, scope, codeCopiedMessage)

    // rememberSaveable so a config change never silently drops a decision the
    // user hasn't made yet, and keyed on prefilledCode so a second deep link
    // re-arms the consent dialog instead of being swallowed by a stale value.
    var showUnpairConfirm by rememberSaveable { mutableStateOf(false) }
    var pendingDeepLinkCode by rememberSaveable(prefilledCode) { mutableStateOf(prefilledCode) }

    LaunchedEffect(prefilledCode) {
        prefilledCode?.let { viewModel.onCodeInputChange(it) }
    }
    ActionErrorSnackbar(form.actionErrorRes, snackbarHostState, context, viewModel::consumeActionError)

    Scaffold(
        topBar = { PairingTopBar(onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val current = state) {
                is PairingState.Loading -> loadingSection(form, viewModel, actions)
                is PairingState.Paired -> pairedSection(current.partner) { showUnpairConfirm = true }
                is PairingState.NotPaired -> notPairedSection(current, form, viewModel, actions)
            }
        }
    }

    if (showUnpairConfirm) {
        val partnerName = (state as? PairingState.Paired)?.partner?.name.orEmpty()
        UnpairConfirmationDialog(
            partnerName = partnerName,
            onConfirm = {
                viewModel.unpair()
                showUnpairConfirm = false
            },
            onDismiss = { showUnpairConfirm = false }
        )
    }

    // A shared link may have been forwarded by a third party — never redeem it
    // without the user saying yes.
    pendingDeepLinkCode?.let { code ->
        DeepLinkConfirmationDialog(
            code = code,
            onConfirm = {
                viewModel.redeemCode()
                pendingDeepLinkCode = null
            },
            onDismiss = { pendingDeepLinkCode = null }
        )
    }

    if (form.showQrDialog && form.qrBitmap != null) {
        QrDialog(bitmap = form.qrBitmap, onDismiss = viewModel::dismissQr)
    }
}

/**
 * Surfaces a field-less action failure (accept/reject/unpair/regenerate) as a
 * snackbar exactly once, then reports it consumed so it doesn't reappear —
 * without this, e.g. a failed `unpair()` would have nowhere to show at all.
 */
@Composable
private fun ActionErrorSnackbar(
    @StringRes actionErrorRes: Int?,
    snackbarHostState: SnackbarHostState,
    context: Context,
    onConsumed: () -> Unit
) {
    LaunchedEffect(actionErrorRes) {
        actionErrorRes?.let { res ->
            snackbarHostState.showSnackbar(context.getString(res))
            onConsumed()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingTopBar(onNavigateBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.pairing_screen_title)) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.pairing_back)
                )
            }
        }
    )
}

/** The screen-level side effects the not-paired/loading form needs but does not own itself. */
private data class NotPairedActions(
    val onShareInvite: (PairingInvite) -> Unit,
    val onCopyCode: (String) -> Unit,
    val onScanQr: () -> Unit
)

@Composable
private fun rememberNotPairedActions(
    context: Context,
    clipboard: ClipboardManager,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    codeCopiedMessage: String
): NotPairedActions = remember(context, clipboard, snackbarHostState, scope, codeCopiedMessage) {
    NotPairedActions(
        onShareInvite = { invite -> context.startActivity(shareIntent(context, invite)) },
        onCopyCode = { code ->
            clipboard.setText(AnnotatedString(code))
            scope.launch { snackbarHostState.showSnackbar(codeCopiedMessage) }
        },
        onScanQr = { context.startActivity(Intent(context, QRScannerActivity::class.java)) }
    )
}

/** Builds the share-sheet intent for an outstanding invite. */
private fun shareIntent(context: Context, invite: PairingInvite): Intent {
    val message = context.getString(
        R.string.pairing_share_message,
        invite.fromUserName,
        invite.code,
        PairingUri.build(invite.code)
    )
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
    }
    return Intent.createChooser(sendIntent, context.getString(R.string.pairing_share_invite))
}

/**
 * [PairingState.Loading] is reached both on first subscription and after a
 * permanent Firestore failure — Task 5's flow re-emits it from `.catch`
 * rather than terminating, so it is a real, possibly indefinite state, not
 * just a brief flash. The spinner alone would be a dead end if the recovery
 * never comes, so the code-entry, scan-QR and email paths render underneath
 * it too — they only need [form] and the [viewModel] actions, never the
 * active invite or incoming list that only [PairingState.NotPaired] carries.
 */
private fun LazyListScope.loadingSection(
    form: PairingFormState,
    viewModel: PairingViewModel,
    actions: NotPairedActions
) {
    item { CircularProgressIndicator(Modifier.padding(32.dp)) }
    formSection(form, viewModel, actions)
}

private fun LazyListScope.pairedSection(partner: PartnerSummary, onUnpairClick: () -> Unit) {
    item { PairedPartnerCard(partner = partner) }
    item {
        Button(
            onClick = onUnpairClick,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.pairing_unpair_button)) }
    }
}

/**
 * The not-paired state: the hero invite card (when one exists), the shared
 * [formSection], and any incoming invitations.
 */
private fun LazyListScope.notPairedSection(
    current: PairingState.NotPaired,
    form: PairingFormState,
    viewModel: PairingViewModel,
    actions: NotPairedActions
) {
    current.activeInvite?.let { invite ->
        item {
            InviteCodeCard(
                invite = invite,
                onCopy = { actions.onCopyCode(invite.code) },
                onShare = { actions.onShareInvite(invite) },
                onShowQr = viewModel::showQr,
                onRegenerate = viewModel::regenerateInvite
            )
        }
        item { OrDivider() }
    }

    formSection(form, viewModel, actions)

    if (current.incoming.isNotEmpty()) {
        items(current.incoming, key = { it.id }) { invite ->
            IncomingInviteCard(
                invite = invite,
                onAccept = { viewModel.acceptIncoming(invite.id) },
                onReject = { viewModel.rejectIncoming(invite.id) }
            )
        }
    }
}

/**
 * The code-entry, scan-QR and email-invite path — the three ways to link
 * accounts that never depend on this user's own invite existing. Shared by
 * [loadingSection] and [notPairedSection] so the user always has one of
 * these available regardless of which of those two states they're in.
 */
private fun LazyListScope.formSection(
    form: PairingFormState,
    viewModel: PairingViewModel,
    actions: NotPairedActions
) {
    item {
        CodeEntryField(
            value = form.codeInput,
            onValueChange = viewModel::onCodeInputChange,
            onSubmit = viewModel::redeemCode,
            errorText = form.codeErrorRes?.let { stringResource(it) },
            enabled = !form.isBusy
        )
    }
    item { ScanQrButton(onClick = actions.onScanQr) }
    item { EmailInviteSection(form = form, viewModel = viewModel) }
}

/** A labelled divider between "share your code" and "enter a code you were given". */
@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.pairing_or),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ScanQrButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
        Text(
            text = stringResource(R.string.pairing_scan_qr_code),
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun EmailInviteSection(form: PairingFormState, viewModel: PairingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = form.emailInput,
            onValueChange = viewModel::onEmailInputChange,
            label = { Text(stringResource(R.string.pairing_partner_email_label)) },
            isError = form.emailErrorRes != null,
            supportingText = form.emailErrorRes?.let { { Text(stringResource(it)) } },
            singleLine = true,
            enabled = !form.isBusy,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = viewModel::sendEmailInvitation,
            enabled = !form.isBusy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.pairing_invite_by_email)) }
    }
}

/** Confirms ending the co-parent link; lives at the bottom of the screen, never mid-list. */
@Composable
private fun UnpairConfirmationDialog(
    partnerName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ConfirmationDialog(
        title = stringResource(R.string.pairing_unpair_confirm_title),
        message = stringResource(R.string.pairing_unpair_confirm_message, partnerName),
        confirmText = stringResource(R.string.pairing_unpair_confirm_action),
        dismissText = stringResource(R.string.pairing_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/**
 * Confirms redeeming a code carried by a `coplanly://pair` deep link. The
 * sender's identity isn't known until the code is actually redeemed, so this
 * uses its own code-specific copy rather than the name/email pair used
 * elsewhere on this screen — filling that pair with the code and an empty
 * string produced a malformed sentence ("4F7K2M () will see...").
 */
@Composable
private fun DeepLinkConfirmationDialog(
    code: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ConfirmationDialog(
        title = stringResource(R.string.pairing_deep_link_confirm_title),
        message = stringResource(R.string.pairing_deep_link_confirm_message, code),
        confirmText = stringResource(R.string.pairing_link_accounts),
        dismissText = stringResource(R.string.pairing_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/** Shows the active invite's link as a scannable QR code. */
@Composable
private fun QrDialog(bitmap: Bitmap?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pairing_qr_dialog_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.pairing_qr_dialog_message))
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = stringResource(R.string.pairing_qr_code_content_description),
                        modifier = Modifier.size(256.dp).padding(top = 16.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pairing_close))
            }
        }
    )
}
