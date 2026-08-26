package com.coparently.app.presentation.pairing

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.PairingInvite
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.pairing.PairingUri
import com.coparently.app.presentation.common.ConfirmationDialog
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.common.SectionRow
import com.coparently.app.presentation.common.SignedInAsRow
import com.coparently.app.presentation.pairing.components.CodeEntryField
import com.coparently.app.presentation.pairing.components.IncomingInviteCard
import com.coparently.app.presentation.pairing.components.InviteCodeCard
import com.coparently.app.presentation.pairing.components.PairedPartnerCard
import kotlinx.coroutines.launch

/**
 * Which of the two pairing jobs the user is doing.
 *
 * The August 2026 design review found both on one scroll separated by an "OR" divider that is
 * easy to blow past — so users typed *their own* code back into the entry field and got an
 * error. Making the two jobs mutually exclusive modes removes the ambiguity entirely.
 */
private enum class PairingMode { SHARE, ENTER }

/**
 * Co-parent pairing: hand over a code, scan a QR, open a shared link, or send
 * an email invitation — and unlink again.
 *
 * @param onNavigateBack Up navigation.
 * @param onCustodyConflict Opens the conflict screen when an accepted pairing found two
 *   custody patterns that disagree. Nothing is written until the user chooses there.
 * @param prefilledCode Code carried by a `coplanly://pair` deep link, if any.
 * @param viewModel Pairing state and actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onNavigateBack: () -> Unit,
    onCustodyConflict: () -> Unit,
    prefilledCode: String? = null,
    viewModel: PairingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val form by viewModel.form.collectAsState()
    val account by viewModel.account.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val codeCopiedMessage = stringResource(R.string.pairing_code_copied)
    val qrScannerLauncher = rememberQrScannerLauncher(viewModel)

    // A deep-linked code means the user is redeeming someone else's, so open on that mode.
    val mode = rememberSaveable(prefilledCode) {
        mutableStateOf(if (prefilledCode.isNullOrEmpty()) PairingMode.SHARE else PairingMode.ENTER)
    }

    val actions = rememberNotPairedActions(
        context = context,
        clipboard = clipboard,
        onCodeCopied = { scope.launch { snackbarHostState.showSnackbar(codeCopiedMessage) } },
        onScanQr = { qrScannerLauncher.launch(Intent(context, QRScannerActivity::class.java)) }
    )

    // rememberSaveable so a config change never silently drops a decision the
    // user hasn't made yet, and keyed on prefilledCode so a second deep link
    // re-arms the consent dialog instead of being swallowed by a stale value.
    // Held as MutableState (rather than `by`-delegated vars) so they can be
    // passed by reference into UnpairFlow/DeepLinkFlow below.
    val showUnpairConfirm = rememberSaveable { mutableStateOf(false) }
    // Whether the paired screen has been expanded to bring in a second co-parent. Collapsed on
    // every entry: one relationship is the ordinary case, and an invite flow already open would
    // read as "you are about to be paired with somebody" to a parent who came here to unpair.
    val addingAnother = rememberSaveable { mutableStateOf(false) }
    val pendingDeepLinkCode = rememberSaveable(prefilledCode) { mutableStateOf(prefilledCode) }

    PairingSideEffects(viewModel, state, prefilledCode, onCustodyConflict)
    ActionErrorSnackbar(form.actionErrorRes, snackbarHostState, context, viewModel::consumeActionError)
    // Same one-shot shape as the error above: sending an email invitation reported
    // nothing at all before, so a delivered and an undelivered invitation looked alike.
    ActionErrorSnackbar(form.actionInfoRes, snackbarHostState, context, viewModel::consumeActionInfo)

    Scaffold(
        topBar = { PairingTopBar(onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // First, above every state: which of the two phones is this? The invite code
            // looks identical on both, so the account is what tells them apart. Kept to a
            // quiet strip so it never competes with the code, which is the real action.
            account?.let { signedIn ->
                item { SignedInAsRow(account = signedIn) }
                item { HorizontalDivider() }
            }

            when (val current = state) {
                is PairingState.Loading ->
                    loadingSection(form, viewModel, actions)
                is PairingState.Paired ->
                    pairedSection(
                        current = current,
                        form = form,
                        viewModel = viewModel,
                        actions = actions,
                        mode = mode,
                        addingAnother = addingAnother
                    ) { showUnpairConfirm.value = true }
                is PairingState.NotPaired ->
                    notPairedSection(current, form, viewModel, actions, mode)
            }
        }
    }

    UnpairFlow(state, showUnpairConfirm, viewModel::unpair)
    DeepLinkFlow(pendingDeepLinkCode, viewModel::redeemCode)
}

/**
 * The screen's effects, in one place: the deep-linked code, the inline QR, and the ViewModel's
 * one-shot events.
 *
 * The event collector is keyed on [viewModel] rather than on a state value — each event is
 * delivered exactly once by the channel behind it, so re-running the effect on every
 * recomposition would be the way to navigate twice.
 */
@Composable
private fun PairingSideEffects(
    viewModel: PairingViewModel,
    state: PairingState,
    prefilledCode: String?,
    onCustodyConflict: () -> Unit
) {
    LaunchedEffect(prefilledCode) {
        prefilledCode?.let { viewModel.onCodeInputChange(it) }
    }
    // The QR renders inline now, so it is generated as soon as there is a code to encode
    // rather than waiting for a button. Keyed on the code so regenerating rebuilds it.
    val activeCode = (state as? PairingState.NotPaired)?.activeInvite?.code
    LaunchedEffect(activeCode) {
        if (activeCode != null) viewModel.showQr()
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PairingEvent.ChooseCustodySchedule -> onCustodyConflict()
            }
        }
    }
}

/**
 * Confirms ending the co-parent link once [visible] is set, e.g. from the paired screen's danger
 * action — **twice**. The first dialog is the reversible-sounding one; the second spells out what
 * is lost and takes a red Yes. Two steps are an owner decision (Aug 2026 walkthrough): unpairing
 * severs the shared calendar, and one habitual tap should never be enough.
 */
@Composable
private fun UnpairFlow(state: PairingState, visible: MutableState<Boolean>, onUnpair: () -> Unit) {
    if (!visible.value) return
    // Keyed on visible.value so reopening the flow always starts back at step one; saveable so a
    // config change mid-flow keeps whichever dialog was up.
    val finalStep = rememberSaveable(visible.value) { mutableStateOf(false) }
    if (!finalStep.value) {
        UnpairConfirmationDialog(
            partnerName = (state as? PairingState.Paired)?.partner?.name.orEmpty(),
            onConfirm = { finalStep.value = true },
            onDismiss = { visible.value = false }
        )
    } else {
        ConfirmationDialog(
            title = stringResource(R.string.pairing_unpair_final_title),
            message = stringResource(R.string.pairing_unpair_final_message),
            confirmText = stringResource(R.string.pairing_unpair_final_yes),
            dismissText = stringResource(R.string.pairing_unpair_final_no),
            onConfirm = {
                onUnpair()
                visible.value = false
            },
            onDismiss = { visible.value = false },
            isDestructive = true
        )
    }
}

/**
 * Confirms redeeming a code carried by a `coplanly://pair` deep link. A
 * shared link may have been forwarded by a third party — never redeem it
 * without the user saying yes.
 */
@Composable
private fun DeepLinkFlow(pendingCode: MutableState<String?>, onRedeem: () -> Unit) {
    val code = pendingCode.value ?: return
    DeepLinkConfirmationDialog(
        code = code,
        onConfirm = {
            onRedeem()
            pendingCode.value = null
        },
        onDismiss = { pendingCode.value = null }
    )
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

/**
 * Launches [QRScannerActivity] and, on a successful scan, feeds the returned
 * code straight into the redeem flow — the same path as typing it by hand.
 */
@Composable
private fun rememberQrScannerLauncher(viewModel: PairingViewModel): ActivityResultLauncher<Intent> =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(QRScannerActivity.EXTRA_CODE)?.let { code ->
                viewModel.onCodeInputChange(code)
                viewModel.redeemCode()
            }
        }
    }

/** The screen-level side effects the not-paired/loading form needs but does not own itself. */
private data class NotPairedActions(
    val onShareInvite: (PairingInvite) -> Unit,
    val onCopyCode: (String) -> Unit,
    val onScanQr: () -> Unit,
)

@Composable
@Suppress("LongParameterList") // one parameter per side effect the form cannot perform itself
private fun rememberNotPairedActions(
    context: Context,
    clipboard: ClipboardManager,
    onCodeCopied: () -> Unit,
    onScanQr: () -> Unit
): NotPairedActions = remember(context, clipboard, onCodeCopied, onScanQr) {
    NotPairedActions(
        onShareInvite = { invite -> context.startActivity(shareIntent(context, invite)) },
        onCopyCode = { code ->
            clipboard.setText(AnnotatedString(code))
            onCodeCopied()
        },
        onScanQr = onScanQr
    )
}

/**
 * Where a recipient without the app installed goes to get it.
 *
 * **This listing does not exist yet** (REL-6 in `docs/BACKLOG.md` is the Play Console work), so
 * the link answers "item not found" until the app is published. Shipped anyway, on the owner's
 * decision: the deep link alone strands exactly the person an invitation is for — someone who
 * does not have the app — and the code in the same message still works by hand. When the listing
 * goes live nothing here needs to change, which is why the URL is built from the applicationId
 * rather than pasted.
 */
private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=app.coplanly"

/** Builds the share-sheet intent for an outstanding invite. */
private fun shareIntent(context: Context, invite: PairingInvite): Intent {
    val message = context.getString(
        R.string.pairing_share_message,
        invite.fromUserName,
        invite.code,
        PairingUri.build(invite.code),
        PLAY_STORE_URL
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
    // There is no invite to share yet, so only the "enter a code" half is meaningful here.
    enterCodeSection(form, viewModel, actions)
    item { TrustPanel() }
}

@Suppress("LongParameterList") // section state, split out of the screen body for readability
private fun LazyListScope.pairedSection(
    current: PairingState.Paired,
    form: PairingFormState,
    viewModel: PairingViewModel,
    actions: NotPairedActions,
    mode: MutableState<PairingMode>,
    addingAnother: MutableState<Boolean>,
    onUnpairClick: () -> Unit
) {
    item { PairedPartnerCard(partner = current.partner) }

    // **Being paired is no longer the end of this screen.** A person may co-parent with more
    // than one other adult, and this row is the only way in: the server accepts a second
    // pairing, but until now the screen showed nothing but "manage", so the feature was
    // unreachable however willing the server was.
    //
    // Collapsed by default, and behind a row rather than always open, because a family of one
    // relationship is the ordinary case — the same "appears when it is wanted, not always"
    // rule the child filter follows.
    item {
        SectionGroup {
            SectionRow(
                icon = Icons.Default.PersonAdd,
                title = stringResource(R.string.pairing_add_another_title),
                supporting = stringResource(R.string.pairing_add_another_description),
                onClick = { addingAnother.value = !addingAnother.value }
            )
        }
    }

    if (addingAnother.value) {
        inviteSection(
            activeInvite = current.activeInvite,
            incoming = current.incoming,
            form = form,
            viewModel = viewModel,
            actions = actions,
            mode = mode
        )
    }

    item {
        // Same anatomy as signing out of the app: a destructive action is a red text row that
        // confirms, not a filled error button competing with the partner card above it.
        // The confirmation already existed; only the affordance changed.
        SectionGroup {
            SectionRow(
                icon = Icons.Default.LinkOff,
                iconTint = MaterialTheme.colorScheme.error,
                title = stringResource(R.string.pairing_unpair_button),
                titleColor = MaterialTheme.colorScheme.error,
                onClick = onUnpairClick
            )
        }
    }
}

/**
 * The not-paired state: a mode toggle, then whichever of the two jobs the user picked, then
 * the trust panel and any incoming invitations.
 *
 * @param current The unpaired state, carrying this user's own invite and any incoming ones
 * @param form Local form state
 * @param viewModel Pairing actions
 * @param actions Screen-level side effects the form cannot perform itself
 * @param mode Which of the two pairing jobs is showing
 */
@Suppress("LongParameterList") // section state, split out of the screen body for readability
private fun LazyListScope.notPairedSection(
    current: PairingState.NotPaired,
    form: PairingFormState,
    viewModel: PairingViewModel,
    actions: NotPairedActions,
    mode: MutableState<PairingMode>
) {
    inviteSection(
        activeInvite = current.activeInvite,
        incoming = current.incoming,
        form = form,
        viewModel = viewModel,
        actions = actions,
        mode = mode
    )
}

/**
 * Everything needed to bring one more co-parent in: share a code, or enter theirs.
 *
 * Shared verbatim by both states rather than duplicated, and that is the point — an unpaired
 * account and a paired one adding a second relationship are doing the *same* job, so a second
 * copy would be two invite flows to keep in step, and the one behind the "add another" row is
 * exactly the one nobody would remember to update.
 *
 * @param activeInvite This user's own outstanding invite, if any.
 * @param incoming Invitations addressed to this user's email.
 * @param form Local form state.
 * @param viewModel Pairing actions.
 * @param actions Screen-level side effects the form cannot perform itself.
 * @param mode Which of the two pairing jobs is showing.
 */
@Suppress("LongParameterList") // section state, split out of the screen body for readability
private fun LazyListScope.inviteSection(
    activeInvite: PairingInvite?,
    incoming: List<PairingInvite>,
    form: PairingFormState,
    viewModel: PairingViewModel,
    actions: NotPairedActions,
    mode: MutableState<PairingMode>
) {
    // With no invite of their own yet, "share my code" has nothing to show — so the toggle
    // only appears once there is something to switch between.
    if (activeInvite != null) {
        item {
            PairingModeToggle(
                selected = mode.value,
                onSelect = { mode.value = it }
            )
        }
    }

    if (activeInvite != null && mode.value == PairingMode.SHARE) {
        item {
            InviteCodeCard(
                invite = activeInvite,
                qrBitmap = form.qrBitmap,
                onCopy = { actions.onCopyCode(activeInvite.code) },
                onShare = { actions.onShareInvite(activeInvite) },
                onRegenerate = viewModel::regenerateInvite
            )
        }
    } else {
        enterCodeSection(form, viewModel, actions)
    }

    item { TrustPanel() }

    if (incoming.isNotEmpty()) {
        items(incoming, key = { it.id }) { invite ->
            IncomingInviteCard(
                invite = invite,
                onAccept = { viewModel.acceptIncoming(invite.id) },
                onReject = { viewModel.rejectIncoming(invite.id) }
            )
        }
    }
}

/**
 * The "enter a code you were given" half: type it, or scan the other parent's QR.
 */
private fun LazyListScope.enterCodeSection(
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
}

/**
 * The two pairing jobs as mutually exclusive modes.
 *
 * Replaces an "OR" divider between two stacked sections — which users blew straight past,
 * then typed their own code into the entry field below it.
 *
 * @param selected Currently active mode
 * @param onSelect Called with the mode the user tapped
 */
@Composable
private fun PairingModeToggle(selected: PairingMode, onSelect: (PairingMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = selected == PairingMode.SHARE,
            onClick = { onSelect(PairingMode.SHARE) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = {},
            label = { Text(stringResource(R.string.pairing_mode_share)) }
        )
        SegmentedButton(
            selected = selected == PairingMode.ENTER,
            onClick = { onSelect(PairingMode.ENTER) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = {},
            label = { Text(stringResource(R.string.pairing_mode_enter)) }
        )
    }
}

/**
 * States plainly what linking accounts shares — and what it does not.
 *
 * The audit's one genuinely missing thing on this screen: nothing said whether pairing exposes
 * the calendar, the money, the messages, or private events, at exactly the moment a user is
 * deciding whether to trust the other household with their data.
 */
@Composable
private fun TrustPanel() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = TRUST_PANEL_ALPHA)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.pairing_trust_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Text(
                text = stringResource(R.string.pairing_trust_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Tint strength of the trust panel's background. */
private const val TRUST_PANEL_ALPHA = 0.10f

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
