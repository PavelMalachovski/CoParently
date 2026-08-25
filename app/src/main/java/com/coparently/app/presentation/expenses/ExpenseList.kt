package com.coparently.app.presentation.expenses

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.coparently.app.R
import com.coparently.app.domain.expenses.SplitRatio
import com.coparently.app.domain.expenses.bothSlotsKnown
import com.coparently.app.domain.expenses.isTwoWaySplit
import com.coparently.app.domain.model.Expense
import com.coparently.app.presentation.common.FullScreenImageDialog
import com.coparently.app.presentation.common.ParentNames
import com.coparently.app.presentation.theme.ParentColors
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Alpha of the payer-tinted circle behind a row's leading icon. */
private const val PAYER_TINT_ALPHA = 0.18f

/** Leading tile / receipt thumbnail size — at or above the 44–48dp touch-target floor. */
private val TILE_SIZE = 40.dp

/**
 * Corner radius of an expense row **and** of the delete surface behind it.
 *
 * The two must be the same value: the swipe backdrop sits directly under the row at rest, so a
 * backdrop with tighter corners shows as a red outline around every row in the list.
 */
private val ROW_CORNER = 12.dp

/**
 * Slot 1, whose share the stored ratio counts. Never shown as the word — [ParentNames] turns it
 * into that person's name, which is the whole reason the row can print a ratio at all.
 */
private const val MOM_SLOT = "mom"

/**
 * List of expenses for the period. Each row swipes left to delete, matching [EventListScreen].
 *
 * @param expenses Expenses to show, already ordered
 * @param roleByUid Map of payer uid to slot; a missing entry just omits the payer
 * @param parentNames Resolves a slot to that parent's name
 * @param onDelete Invoked with the swiped expense; null hides the affordance
 * @param onExpenseClick Invoked when a row is tapped (opens the editor); null makes rows inert
 * @param canModify Whether this user may edit or delete a given row. A co-parent's expense
 *   renders with no swipe and no tap-to-edit — only its creator changes an expense (owner
 *   decision, Aug 2026 walkthrough), and `firestore.rules` enforces the same server-side, so an
 *   affordance here would only promise a write the server rejects. The receipt viewer stays: it
 *   is a read.
 * @param modifier Modifier for the list
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // list-level composable: its callbacks are its API surface
fun ExpenseList(
    expenses: List<Expense>,
    roleByUid: Map<String, String>,
    parentNames: ParentNames,
    onDelete: ((Expense) -> Unit)? = null,
    onExpenseClick: ((Expense) -> Unit)? = null,
    canModify: (Expense) -> Boolean = { true },
    modifier: Modifier = Modifier
) {
    // Receipt being viewed full-screen; transient UI state, deliberately local.
    var viewedReceiptUrl by remember { mutableStateOf<String?>(null) }

    // Whether the two parents can be told apart, which is what decides whether a row may print a
    // ratio at all — the same fact `calculateExpenseBalance` keys the share it charges on. While
    // both parents still read one slot the balance divides evenly, so a row claiming 70/30 would
    // contradict the summary directly above it.
    val splitKnown = remember(roleByUid) { bothSlotsKnown(roleByUid) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items(expenses, key = { it.id }) { expense ->
            val modifiable = canModify(expense)
            val row: @Composable () -> Unit = {
                ExpenseItem(
                    expense = expense,
                    payerRole = roleByUid[expense.paidBy],
                    parentNames = parentNames,
                    splitKnown = splitKnown,
                    onClick = onExpenseClick?.takeIf { modifiable }?.let { { it(expense) } },
                    onReceiptClick = { url -> viewedReceiptUrl = url }
                )
            }
            if (onDelete == null || !modifiable) {
                row()
            } else {
                SwipeToDeleteRow(onDelete = { onDelete(expense) }, content = row)
            }
        }
    }

    viewedReceiptUrl?.let { url ->
        // A receipt is a document, not a snapshot: it is read, not glanced at. The shared
        // viewer pinches, pans and double-taps, and — unlike the fit-to-width dialog it
        // replaces — does not close on the first exploratory tap.
        FullScreenImageDialog(
            model = url,
            contentDescription = stringResource(R.string.expenses_receipt_photo),
            onDismiss = { viewedReceiptUrl = null }
        )
    }
}

/**
 * A single expense row: who paid, what for, and how it splits.
 *
 * The whole row opens the editor via [onClick] when one is provided; the receipt thumbnail keeps
 * its own tap target for the full-screen viewer, so tapping the photo never leaks through to the
 * editor.
 *
 * The amount alone cannot answer "is this settled?", which is the question co-parents actually
 * have, so the row states the payer and the split explicitly.
 *
 * @param expense Expense to render
 * @param parentNames Resolves a slot to that parent's name
 * @param payerRole The payer's slot, or null when the payer is not a known parent
 * @param splitKnown Whether both parent slots are known. Required rather than defaulted: false is
 *   not silence, it makes the row assert "split 50/50", and a caller that has not worked the
 *   answer out would be publishing a claim about money by omission.
 * @param onClick Opens the expense editor; null leaves the row inert
 * @param onReceiptClick Opens the full-screen receipt viewer
 */
@Composable
fun ExpenseItem(
    expense: Expense,
    parentNames: ParentNames,
    payerRole: String? = null,
    splitKnown: Boolean,
    onClick: (() -> Unit)? = null,
    onReceiptClick: (String) -> Unit = {}
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }
    val format = remember(expense.currency) { currencyFormat(expense.currency) }

    val payerColor = if (payerRole != null) {
        ParentColors.fill(payerRole)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val payerName = payerRole?.let { parentNames.labelFor(it) }

    val subtitle = if (payerName != null) {
        stringResource(
            R.string.expenses_row_subtitle,
            stringResource(expense.category.labelRes),
            payerName,
            expense.date.format(dateFormatter)
        )
    } else {
        stringResource(
            R.string.expenses_row_subtitle_unknown_payer,
            stringResource(expense.category.labelRes),
            expense.date.format(dateFormatter)
        )
    }

    // The ratio the expense was recorded under, not the family's current agreement: an expense
    // is priced once and a later renegotiation must not re-label a settled month. Null — a row
    // from before the agreement existed — divides evenly, which is what it was.
    val ratio = remember(expense.splitBasisPoints, splitKnown, expense.splitBetween) {
        SplitRatio.fromStored(expense.splitBasisPoints)
            ?.takeIf { splitKnown && expense.isTwoWaySplit() }
    }
    val splitLabel = when {
        // Named, not two bare numbers. The subtitle immediately before this already names one
        // parent — the payer — so "split 70/30" beside it reads as *their* share, and the first
        // figure is always slot 1's whoever paid. Naming the half the first number belongs to is
        // the difference between a label and a wrong claim about money.
        ratio != null -> stringResource(
            R.string.expenses_split_ratio,
            parentNames.labelFor(MOM_SLOT),
            ratio.momPercent,
            ratio.dadPercent
        )
        expense.isTwoWaySplit() ->
            stringResource(R.string.expenses_split_even)
        expense.splitBetween.size > 2 ->
            stringResource(R.string.expenses_split_n_ways, expense.splitBetween.size)
        else -> stringResource(R.string.expenses_split_none)
    }
    // The split moved from its own right-hand column into the subtitle: it is a property of
    // the expense, not a second figure competing with the amount.
    val meta = if (expense.receiptUrl != null) {
        stringResource(R.string.expenses_row_meta_with_receipt, subtitle, splitLabel)
    } else {
        stringResource(R.string.expenses_row_meta, subtitle, splitLabel)
    }

    Surface(
        shape = RoundedCornerShape(ROW_CORNER),
        // Was surfaceContainerLow, a 1.2:1 separation from the background in dark — the rows
        // barely read as cards at all.
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // A receipt photo, when present, keeps its own tappable thumbnail here: the viewer
            // is a working feature and losing its entry point to match a mockup would be a
            // regression. Without a photo the slot shows a payer-tinted category mark instead.
            // 40dp, up from 30dp — the thumbnail was below the 44–48dp touch-target floor.
            val receiptUrl = expense.receiptUrl
            if (receiptUrl != null) {
                AsyncImage(
                    model = receiptUrl,
                    contentDescription = stringResource(R.string.expenses_receipt_photo),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(TILE_SIZE)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onReceiptClick(receiptUrl) }
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(TILE_SIZE)
                        .clip(RoundedCornerShape(12.dp))
                        .background(payerTint(payerColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        // Per-category, so a list of ten expenses says what the money went on.
                        imageVector = expense.category.iconVector,
                        contentDescription = null,
                        tint = payerColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = expense.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = format.format(expense.amount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Payer colour at the tint alpha used behind row icons. */
private fun payerTint(color: Color): Color = color.copy(alpha = PAYER_TINT_ALPHA)

/**
 * Wraps a row in a left-swipe delete gesture, same shape as `EventListScreen`'s.
 *
 * Until now there was no way to remove an expense at all — `ExpenseViewModel.deleteExpense`
 * existed but nothing called it — so a mistyped amount was permanent.
 *
 * @param onDelete Invoked once the row is swiped past the threshold
 * @param content The row itself
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
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
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(ROW_CORNER)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.expenses_delete),
                    tint = MaterialTheme.colorScheme.onError
                )
            }
        }
    ) {
        content()
    }
}
