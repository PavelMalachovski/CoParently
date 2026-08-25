package com.coparently.app.presentation.expenses

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.expenses.CategorySlice
import com.coparently.app.domain.expenses.CurrencyBreakdown
import com.coparently.app.domain.model.Expense
import com.coparently.app.presentation.common.AccountAvatar
import com.coparently.app.presentation.common.ParentNames
import com.coparently.app.presentation.common.PillChip
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.theme.ParentColors
import java.text.NumberFormat
import java.util.Locale

/** Width of the amount column, wide enough for a five-figure sum with its symbol. */
private val AMOUNT_COLUMN_WIDTH = 96.dp

/** Width of the share column, wide enough for "100%". */
private val SHARE_COLUMN_WIDTH = 52.dp

/** The colour swatch that ties a table row to its arc. */
private val SWATCH_SIZE = 12.dp

/** The dot that ties a ledger row to its category's arc — smaller than [SWATCH_SIZE], it is a
 *  marker on a text row, not a legend entry. */
private val LEDGER_DOT_SIZE = 8.dp

/** How much of the column the pie takes. Half the screen, per the owner's walkthrough. */
private const val PIE_WIDTH_FRACTION = 0.55f

/**
 * Where the month's money went: a pie by category, and beneath it the same figures as a table
 * sorted with the largest first.
 *
 * **The table is not a second view of the chart, it is the primary one.** Nine categorical hues
 * cannot be made distinguishable in every pairing — see [CategoryPalette], which measured it —
 * so this screen never asks anyone to tell two slices apart by colour. Every row carries the
 * category's name as text and its figures as numbers; the swatch is a pointer back to the arc,
 * not the encoding. That also makes the table the chart's legend, which is why there is no
 * separate one: two lists of the same nine categories, side by side, would be the "two answers
 * to one question" this design language removes elsewhere.
 *
 * **One currency at a time, never a mixed pie.** [ExpenseViewModel.selectedBreakdown] hands out
 * exactly one currency's figures; the chip row above only chooses between them.
 *
 * @param breakdown The currency being shown, or null when the month has nothing in it.
 * @param currencies Every currency the month holds, for the chip row. A single-currency month —
 *   the usual case — gets no chips: it should not pay for the unusual one.
 * @param payers The two uids the payer filter offers, or empty when the filter must be hidden.
 * @param selectedPayer The uid currently filtered to, or null for everyone.
 * @param parentNames Resolves a uid to that parent's own name.
 * @param expenses The whole selected month, unfiltered — this view narrows to the drawn currency
 *   and the payer filter itself, so the ledger can never disagree with the chart above it.
 * @param roleByUid Resolves a payer uid to its slot, for the parent-column order and colours.
 * @param onSelectCurrency Picks a currency to draw.
 * @param onSelectPayer Filters to one parent's spending, or to everyone's with null.
 * @param modifier Modifier applied to the view.
 */
@Composable
@Suppress("LongParameterList") // stateless view: its inputs and callbacks are its whole API
fun ExpenseAnalytics(
    breakdown: CurrencyBreakdown?,
    currencies: List<String>,
    payers: List<String>,
    selectedPayer: String?,
    parentNames: ParentNames,
    expenses: List<Expense>,
    roleByUid: Map<String, String>,
    onSelectCurrency: (String) -> Unit,
    onSelectPayer: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    // **This view does not scroll itself.** It used to, and that was the bug: the caller gave it
    // `weight(1f)` of whatever was left under the summary cards and the switcher, so on a month
    // with two currencies the chart got a couple of hundred dp to scroll a pie, a table and a
    // ledger inside — the figures below the arc were simply unreachable. Scrolling belongs to
    // the page, so that the cards scroll away and the whole breakdown gets the screen.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (payers.isNotEmpty()) {
            PayerFilter(
                payers = payers,
                selectedPayer = selectedPayer,
                parentNames = parentNames,
                onSelectPayer = onSelectPayer
            )
        }

        // Only when the month actually mixes currencies. A pie of two currencies is the one
        // thing this feature must never draw, so the chips are how a parent sees the second
        // one — not a control they have to meet on every ordinary month.
        if (currencies.size > 1) {
            CurrencyChips(
                currencies = currencies,
                selected = breakdown?.currency,
                onSelectCurrency = onSelectCurrency
            )
        }

        if (breakdown == null) {
            Text(
                text = if (selectedPayer == null) {
                    stringResource(R.string.expense_analytics_empty)
                } else {
                    // A different sentence, because it has a different remedy: the month is not
                    // empty, the filter is. Saying "nothing spent this month" under a filter the
                    // user just set would read as a bug.
                    stringResource(R.string.expense_analytics_empty_filtered)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
            return@Column
        }

        // Half the column, centred — full-width the pie pushed its own table off screen, and a
        // pie encodes shares in angles, which survive shrinking; the figures live in the rows.
        CategoryPieChart(
            slices = breakdown.slices,
            modifier = Modifier
                .fillMaxWidth(PIE_WIDTH_FRACTION)
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 8.dp)
        )

        BreakdownTable(breakdown = breakdown)

        ParentLedger(
            expenses = expenses.filter { it.currency == breakdown.currency },
            payers = payers,
            selectedPayer = selectedPayer,
            roleByUid = roleByUid,
            parentNames = parentNames,
            currency = breakdown.currency
        )
    }
}

/**
 * Everyone, or one parent — named with that parent's own name, never "Mom" or "Dad".
 *
 * Shown only when both parents are known. The caller decides that; this composable is not
 * reached otherwise.
 */
@Composable
private fun PayerFilter(
    payers: List<String>,
    selectedPayer: String?,
    parentNames: ParentNames,
    onSelectPayer: (String?) -> Unit
) {
    val label = stringResource(R.string.expense_analytics_payer_label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            label = stringResource(R.string.expense_analytics_payer_everyone),
            selected = selectedPayer == null,
            onClick = { onSelectPayer(null) }
        )
        payers.forEach { uid ->
            FilterChip(
                label = parentNames.labelForUid(uid),
                selected = selectedPayer == uid,
                onClick = { onSelectPayer(uid) }
            )
        }
    }
}

/** One chip per currency the month holds. Absent entirely when there is only one. */
@Composable
private fun CurrencyChips(
    currencies: List<String>,
    selected: String?,
    onSelectCurrency: (String) -> Unit
) {
    val label = stringResource(R.string.expense_analytics_currency_label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        currencies.forEach { currency ->
            FilterChip(
                label = currency,
                selected = currency == selected,
                onClick = { onSelectCurrency(currency) }
            )
        }
    }
}

/**
 * A selected/unselected chip.
 *
 * Uses the theme's neutral secondary container for the selected state, never a parent hue and
 * never a category's slice colour: a control is not a series, and borrowing a series colour for
 * one is how a reader starts believing the filter is part of the data.
 */
@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    PillChip(
        label = label,
        container = if (selected) MaterialTheme.colorScheme.secondaryContainer else null,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        onClick = onClick
    )
}

/**
 * The figures, largest first — which is the whole point: the question is "what is eating the
 * money", so the answer is the first row.
 *
 * Amount and share are text in text colours, never in the series colour. Only the swatch wears
 * the category's hue.
 */
@Composable
private fun BreakdownTable(breakdown: CurrencyBreakdown) {
    val total = remember(breakdown) { currencyFormat(breakdown.currency).format(breakdown.total) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp + SWATCH_SIZE + 12.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ColumnHeader(stringResource(R.string.expense_analytics_column_category), Modifier.weight(1f))
            ColumnHeader(
                stringResource(R.string.expense_analytics_column_amount),
                Modifier.width(AMOUNT_COLUMN_WIDTH),
                TextAlign.End
            )
            ColumnHeader(
                stringResource(R.string.expense_analytics_column_share),
                Modifier.width(SHARE_COLUMN_WIDTH),
                TextAlign.End
            )
        }
        SectionGroup {
            breakdown.slices.forEachIndexed { index, slice ->
                BreakdownRow(slice = slice, currency = breakdown.currency)
                if (index != breakdown.slices.lastIndex) Divider()
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.expense_analytics_total),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = total,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** One column heading, in the muted text token every other header on this screen uses. */
@Composable
private fun ColumnHeader(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        modifier = modifier
    )
}

/** One category: its swatch, its name, its amount and its share. */
@Composable
private fun BreakdownRow(slice: CategorySlice, currency: String) {
    val name = stringResource(slice.category.labelRes)
    val amount = currencyFormat(currency).format(slice.amount)
    val share = formatShare(slice.share)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            // One description for the whole row: a screen reader reading "swatch, Food, 25.50,
            // 34%" as four separate nodes is the table read sideways.
            .semantics(mergeDescendants = true) { contentDescription = "$name, $amount, $share" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(SWATCH_SIZE)
                .clip(CircleShape)
                .background(slice.category.sliceColor())
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = amount,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(AMOUNT_COLUMN_WIDTH)
        )
        Text(
            text = share,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(SHARE_COLUMN_WIDTH)
        )
    }
}

/** "34%" — rounded, because a pie slice is not a figure anyone reads to a decimal place. */
private fun formatShare(share: Double): String {
    val format = NumberFormat.getPercentInstance(Locale.getDefault())
    format.maximumFractionDigits = 0
    return format.format(share)
}

/**
 * The month's expenses as one column per parent — who spent what, side by side, each row marked
 * with its category's colour (owner ask, Aug 2026 walkthrough).
 *
 * Column order follows the slots — slot 1 ("mom", pink) left, slot 2 right — so the columns sit
 * where the calendar's colours already taught the eye to look. With the payer filter on, only
 * that parent's column renders: the chart above shows one parent's shares, and a second column
 * proven empty by construction would just say "the filter works". Unpaired (or before both
 * profiles resolve) the ledger is a single unheaded column. Expenses whose payer matches neither
 * uid — legacy rows — keep a full-width row at the bottom rather than being silently dropped.
 *
 * Read-only on purpose: editing and swipe-to-delete live on the List tab, which is one switch
 * away and built for it.
 */
@Composable
@Suppress("LongParameterList") // stateless view: its inputs are its whole API
private fun ParentLedger(
    expenses: List<Expense>,
    payers: List<String>,
    selectedPayer: String?,
    roleByUid: Map<String, String>,
    parentNames: ParentNames,
    currency: String
) {
    if (expenses.isEmpty()) return
    val ordered = remember(expenses) { expenses.sortedByDescending { it.date } }

    val shownPayers = when {
        payers.size != 2 -> emptyList()
        selectedPayer != null -> listOf(selectedPayer)
        // Slot 1 left, slot 2 right; roleByUid can miss a legacy uid, so fall back to given order.
        else -> payers.sortedBy { if (roleByUid[it] == "mom") 0 else 1 }
    }

    if (shownPayers.isEmpty()) {
        SectionGroup {
            ordered.forEachIndexed { index, expense ->
                LedgerRow(expense = expense, currency = currency)
                if (index != ordered.lastIndex) Divider()
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        shownPayers.forEach { uid ->
            LedgerColumn(
                title = parentNames.labelForUid(uid),
                titleColor = ParentColors.text(roleByUid[uid].orEmpty()),
                photoUrl = parentNames.photoForUid(uid),
                expenses = ordered.filter { it.paidBy == uid },
                currency = currency,
                modifier = Modifier.weight(1f)
            )
        }
    }

    val unattributed = ordered.filter { it.paidBy !in shownPayers && selectedPayer == null }
    if (unattributed.isNotEmpty() && shownPayers.size == 2) {
        SectionGroup {
            unattributed.forEachIndexed { index, expense ->
                LedgerRow(expense = expense, currency = currency)
                if (index != unattributed.lastIndex) Divider()
            }
        }
    }
}

/** One parent's half of the ledger: their name in their colour, then their rows. */
@Composable
private fun LedgerColumn(
    title: String,
    titleColor: androidx.compose.ui.graphics.Color,
    expenses: List<Expense>,
    currency: String,
    modifier: Modifier = Modifier,
    photoUrl: String? = null
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The parent's own Google picture beside their name, so a glance at the two columns
        // reads as two people rather than two labels. Falls back to their initial.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp)
        ) {
            AccountAvatar(name = title, photoUrl = photoUrl, size = 20.dp)
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (expenses.isEmpty()) {
            Text(
                text = stringResource(R.string.expense_analytics_column_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        } else {
            SectionGroup {
                expenses.forEachIndexed { index, expense ->
                    LedgerRow(expense = expense, currency = currency)
                    if (index != expenses.lastIndex) Divider()
                }
            }
        }
    }
}

/** One expense: its category's dot, its title, its amount. */
@Composable
private fun LedgerRow(expense: Expense, currency: String) {
    val name = stringResource(expense.category.labelRes)
    val amount = currencyFormat(currency).format(expense.amount)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${expense.title}, $name, $amount"
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(LEDGER_DOT_SIZE)
                .clip(CircleShape)
                .background(expense.category.sliceColor())
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = expense.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = amount,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
