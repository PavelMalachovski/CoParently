package com.coparently.app.presentation.pairing

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.presentation.common.ParentNames
import com.coparently.app.presentation.common.Parents
import com.coparently.app.presentation.common.rememberParentNames
import com.coparently.app.presentation.theme.ParentColors
import com.coparently.app.presentation.theme.dimensions
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The pairing conflict screen: two custody patterns, side by side, one action each.
 *
 * Shown only when both parents have an active pattern and the two are not equivalent — the
 * decision is [CustodyConflictResolver]'s, made the moment pairing was accepted. What arrives
 * here is already resolved in one crucial respect: this device's pattern has been complemented
 * for the slot pairing moved it to, so "my days" still means my days. Comparing before
 * complementing would show a parent their own schedule inverted.
 *
 * **There is no Back.** No up arrow, and the system gesture is swallowed. A conflict screen the
 * accepter can dismiss silently keeps whichever pattern happened to be on this phone — which is
 * the same as deciding for them, only without telling them. Two actions, one per pattern, and
 * no third exit.
 *
 * @param onResolved Leaves the screen: after a choice is written, and immediately if there is no
 *   conflict left to show (the process was restarted while it was open).
 * @param viewModel The two patterns, the two parents, and the write.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustodyConflictScreen(
    onResolved: () -> Unit,
    viewModel: CustodyConflictViewModel = hiltViewModel()
) {
    val prompt by viewModel.prompt.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val resolved by viewModel.resolved.collectAsState()
    val saveFailed by viewModel.saveFailed.collectAsState()
    val parents = viewModel.parents.collectAsState().value
    val parentNames = rememberParentNames(parents)
    val snackbarHostState = remember { SnackbarHostState() }
    val dims = dimensions()

    CustodyConflictEffects(
        viewModel = viewModel,
        shouldLeave = prompt == null || resolved,
        saveFailed = saveFailed,
        snackbarHostState = snackbarHostState,
        onResolved = onResolved
    )

    val pending = prompt ?: return
    val legend = rememberLegend(mySlot = pending.mySlot, parents = parents, parentNames = parentNames)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.custody_conflict_title)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(dims.paddingMedium),
            verticalArrangement = Arrangement.spacedBy(dims.paddingMedium)
        ) {
            conflictOptions(
                conflict = pending.conflict,
                legend = legend,
                enabled = !isSaving,
                onChoose = viewModel::choose
            )
        }
    }
}

/**
 * The explanation and the two options, in the order they are asked about: this phone's pattern
 * first, because the accepter knows that one and is being asked whether to give it up.
 *
 * Neither option is preselected. A default here would be a decision made on the user's behalf on
 * the one screen that exists because no client may make it.
 */
private fun LazyListScope.conflictOptions(
    conflict: CustodyConflict.Conflict,
    legend: List<LegendEntry>,
    enabled: Boolean,
    onChoose: (CustodyModel) -> Unit
) {
    item {
        Text(
            text = stringResource(R.string.custody_conflict_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    item {
        PatternOption(
            choice = PatternChoice(
                labelRes = R.string.custody_conflict_local_label,
                actionRes = R.string.custody_conflict_keep_local,
                model = conflict.mine
            ),
            legend = legend,
            enabled = enabled,
            onChoose = onChoose
        )
    }
    item {
        PatternOption(
            choice = PatternChoice(
                labelRes = R.string.custody_conflict_shared_label,
                actionRes = R.string.custody_conflict_use_shared,
                model = conflict.theirs
            ),
            legend = legend,
            enabled = enabled,
            onChoose = onChoose
        )
    }
}

/**
 * The screen's effects: refusing Back, leaving once there is nothing left to decide, and saying
 * so when a choice could not be written.
 *
 * [BackHandler] is registered here, above the screen's early return, so it also covers the frame
 * in which the prompt has gone and the screen is on its way out — a gap in which Back would
 * otherwise pop to whatever is beneath.
 *
 * @param shouldLeave True once a choice has been written, or when there is no prompt to show at
 *   all — the process was restarted while this screen was open.
 * @param saveFailed True when a choice could not be written; surfaced once, then consumed.
 */
@Composable
private fun CustodyConflictEffects(
    viewModel: CustodyConflictViewModel,
    shouldLeave: Boolean,
    saveFailed: Boolean,
    snackbarHostState: SnackbarHostState,
    onResolved: () -> Unit
) {
    // Back is not an exit here — see the screen's own comment. Enabled unconditionally so
    // predictive back never previews a destination this screen does not offer.
    BackHandler { }

    LaunchedEffect(shouldLeave) {
        if (shouldLeave) onResolved()
    }

    // A choice that could not be written must not look like one that was: the screen stays, both
    // options come back enabled, and this says why.
    val saveFailedMessage = stringResource(R.string.custody_conflict_save_failed)
    LaunchedEffect(saveFailed) {
        if (saveFailed) {
            snackbarHostState.showSnackbar(saveFailedMessage)
            viewModel.consumeSaveFailure()
        }
    }
}

/**
 * One of the two options on the screen.
 *
 * The caption is a noun label, never a sentence a name is the subject of — Czech, Russian and
 * Ukrainian inflect verbs for gender and a name cannot agree with one, and this screen puts two
 * people beside two schedules, which is exactly where that trap lives. So [labelRes] says where
 * a pattern came from, not who wrote it.
 *
 * @property labelRes What this pattern is.
 * @property actionRes The button's text.
 * @property model The pattern itself.
 */
private data class PatternChoice(
    @StringRes val labelRes: Int,
    @StringRes val actionRes: Int,
    val model: CustodyModel
)

/**
 * One of the two patterns: what it is called, what it does to the next fortnight, and the single
 * action that adopts it.
 *
 * @param choice The option to render.
 * @param legend Which colour is which parent, resolved once for the screen.
 * @param enabled False while a choice is being written, so neither option can be double-tapped.
 * @param onChoose Adopts the pattern this option carries.
 */
@Composable
private fun PatternOption(
    choice: PatternChoice,
    legend: List<LegendEntry>,
    enabled: Boolean,
    onChoose: (CustodyModel) -> Unit
) {
    val dims = dimensions()
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(dims.paddingMedium),
            verticalArrangement = Arrangement.spacedBy(dims.paddingSmall)
        ) {
            Text(
                text = stringResource(choice.labelRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.custody_next_14_days),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FortnightPreview(choice.model)
            PatternLegend(legend)
            Button(
                onClick = { onChoose(choice.model) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (enabled) {
                    Text(stringResource(choice.actionRes))
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(BUTTON_SPINNER_SIZE),
                        strokeWidth = BUTTON_SPINNER_STROKE
                    )
                }
            }
        }
    }
}

/**
 * The next fourteen days under [model], as two rows of seven.
 *
 * Both options are drawn over the **same** fourteen dates, starting today, so the two grids are
 * directly comparable — a preview anchored to each pattern's own start date would put different
 * days under the same column and make two identical arrangements look different.
 *
 * The wash is the parent's hue at the calendar's custody alpha and the text is the theme-aware
 * foreground for the same hue, so a cell here and a day cell in the month grid read as one
 * system rather than two pinks.
 */
@Composable
private fun FortnightPreview(model: CustodyModel) {
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(DAY_CELL_GAP)) {
        repeat(FORTNIGHT_DAYS / DAYS_PER_ROW) { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(DAY_CELL_GAP)) {
                repeat(DAYS_PER_ROW) { dayInRow ->
                    val date = today.plusDays((week * DAYS_PER_ROW + dayInRow).toLong())
                    DayCell(date = date, slot = model.getCustodyFor(date), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One day of a preview: whose it is, in that parent's colour.
 *
 * @param date The day being previewed.
 * @param slot The parent slot that holds custody on it.
 */
@Composable
private fun DayCell(date: LocalDate, slot: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(DAY_CELL_HEIGHT)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(ParentColors.container(slot)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = ParentColors.text(slot),
                maxLines = 1
            )
        }
    }
}

/**
 * One line of the legend: a colour and the person it stands for.
 *
 * @property slot The parent slot the colour comes from.
 * @property name That person's name, already resolved.
 */
private data class LegendEntry(val slot: String, val name: String)

/**
 * Which colour is which parent, resolved once for the whole screen.
 *
 * The signed-in parent's entry comes first, because "which of these days are mine" is the
 * question the screen exists to answer.
 *
 * Two different keys, deliberately. The **slot** — and therefore the colour — is [mySlot], the
 * one the accept callable just reported, not `Parents.me.slot`: `UserRepositoryImpl` preserves
 * an existing Room row's role on every write, so a freshly re-slotted accepter's local row still
 * reads their old slot until the periodic sync catches up, which is long after this screen has
 * been read and dismissed. The **name** goes by uid, through [ParentNames.labelForUid], for the
 * reason this branch already settled for the custody-change banner: a slot is not a person, and
 * on a pair that has not been separated into distinct slots yet it collapses both parents onto
 * one.
 *
 * @param mySlot This device's slot, straight from the accept response.
 * @param parents The two parents, for their uids.
 * @param parentNames Resolves a uid to that person's name.
 */
@Composable
private fun rememberLegend(
    mySlot: String,
    parents: Parents,
    parentNames: ParentNames
): List<LegendEntry> = remember(mySlot, parents, parentNames) {
    val theirSlot = if (mySlot == SLOT_DAD) SLOT_MOM else SLOT_DAD
    listOf(
        LegendEntry(slot = mySlot, name = parentNames.labelForUid(parents.me?.uid)),
        LegendEntry(slot = theirSlot, name = parentNames.labelForUid(parents.coParent?.uid))
    )
}

/** Which colour is which parent, so the two grids can be read at all. */
@Composable
private fun PatternLegend(legend: List<LegendEntry>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dimensions().paddingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        legend.forEach { entry -> LegendRow(entry = entry, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun LegendRow(entry: LegendEntry, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LEGEND_DOT_GAP),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(LEGEND_DOT_SIZE)
                .background(ParentColors.fill(entry.slot), CircleShape)
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Slot identifier for parent one. Never shown; the screen names people, not slots. */
private const val SLOT_MOM = "mom"

/** Slot identifier for parent two. */
private const val SLOT_DAD = "dad"

/** Days previewed per option — two weeks, the shortest window every shipped pattern repeats in. */
private const val FORTNIGHT_DAYS = 14

/** Days per preview row. */
private const val DAYS_PER_ROW = 7

/** Height of one preview day cell. */
private val DAY_CELL_HEIGHT = 40.dp

/** Gap between preview day cells, horizontally and vertically. */
private val DAY_CELL_GAP = 4.dp

/** Diameter of a legend colour dot. */
private val LEGEND_DOT_SIZE = 10.dp

/** Gap between a legend dot and the name it stands for. */
private val LEGEND_DOT_GAP = 6.dp

/** Size of the in-button spinner shown while a choice is being written. */
private val BUTTON_SPINNER_SIZE = 16.dp

/** Stroke of that spinner. */
private val BUTTON_SPINNER_STROKE = 2.dp
