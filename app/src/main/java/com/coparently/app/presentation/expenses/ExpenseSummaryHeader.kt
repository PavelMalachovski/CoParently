package com.coparently.app.presentation.expenses

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.expenses.ExpenseBalance
import com.coparently.app.presentation.common.NamedParent
import com.coparently.app.presentation.common.ParentNames
import com.coparently.app.presentation.common.Parents
import com.coparently.app.presentation.common.PillChip
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.presentation.theme.ParentColors
import com.coparently.app.utils.LightDarkPreviews
import com.coparently.app.utils.PreviewWrapper
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/** Height of the who-paid split bar. */
private val SPLIT_BAR_HEIGHT = 8.dp

/** Below this the balance is treated as settled — sub-cent drift is not a debt. */
private const val SETTLED_EPSILON = 0.01

/** Tint strength of the strip behind the settle-up row. */
private const val BALANCE_STRIP_ALPHA = 0.12f

/**
 * Month header for the Expenses screen: which month, total spend, who paid what, and who owes
 * whom — in one card.
 *
 * Replaces a horizontal row of category cards that carried no per-parent semantics at all — in
 * a two-household product the money screen never answered the question co-parents actually have,
 * which is "are we square?". The pink/blue split bar reuses the calendar's colour language
 * rather than inventing a second one.
 *
 * The August 2026 refresh folded the standalone month navigator into this card: the screen used
 * to stack an app bar, a month navigator and this summary — three headers before the first
 * expense row, leaving the list about 40% of the screen. [monthNavigation] supplies the
 * switcher; pass null on the second and subsequent cards of a mixed-currency month, so one
 * switcher governs them all.
 *
 * While the two parents cannot be told apart the split bar and balance row are hidden: with one
 * parent on record a 100%-pink bar and a zero balance would be decoration pretending to be data.
 * That covers being unpaired *and* a pair whose two parents still hold the same slot.
 *
 * The two amounts under the bar are labelled by name — "Olya: $154.10" — rather than by a role
 * word. The name is the label: the row's two halves are already positioned and coloured per
 * parent, so a repeated "Paid by" on both sides would distinguish nothing. Its meaning rests on
 * sitting directly under the [SplitBar]; keep it there.
 *
 * @param balance The selected month's paid/owed figures for one currency
 * @param currency ISO currency code for formatting
 * @param parentNames Resolves a slot to that parent's name
 * @param onSettleUp Invoked with a ready-to-send message when the user taps Settle up
 * @param monthLabel Name of the month being shown, used in the header and settle-up draft
 * @param modifier Modifier for the card
 * @param monthNavigation Month switcher to render at the top of the card, or null for none
 */
@Composable
@Suppress("LongParameterList") // one card, one parameter per thing it displays
fun ExpenseSummaryHeader(
    balance: ExpenseBalance,
    currency: String,
    parentNames: ParentNames,
    onSettleUp: (String) -> Unit,
    monthLabel: String = defaultMonthLabel(),
    modifier: Modifier = Modifier,
    monthNavigation: MonthNavigation? = null
) {
    val format = remember(currency) { currencyFormat(currency) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (monthNavigation != null) {
                MonthSwitcherBar(
                    navigation = monthNavigation,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = format.format(balance.total),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.expenses_shared_spend),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, bottom = 4.dp)
                )
            }

            if (balance.splitKnown) {
                SplitBar(
                    momShare = balance.momShareOfPaid,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Each half gets an equal share of the row and ellipsises inside it. The
                    // name is arbitrary length now, and without this a long one on the start
                    // side would push the other parent's figure off the end entirely.
                    Text(
                        text = stringResource(
                            R.string.expenses_paid_by,
                            parentNames.labelFor("mom"),
                            format.format(balance.momPaid)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = ParentColors.text("mom"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(
                            R.string.expenses_paid_by,
                            parentNames.labelFor("dad"),
                            format.format(balance.dadPaid)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = ParentColors.text("dad"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f)
                    )
                }

                BalanceRow(
                    balance = balance,
                    format = format,
                    monthLabel = monthLabel,
                    onSettleUp = onSettleUp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

/**
 * Which month the summary is showing and how to page it.
 *
 * @property label Month name and year, already formatted and capitalised
 * @property expenseCount Number of expenses in that month
 * @property onPrevious Pages one month back
 * @property onNext Pages one month forward
 */
data class MonthNavigation(
    val label: String,
    val expenseCount: Int,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit
)

/**
 * The month switcher: back/forward around "August 2026 · 5 expenses".
 *
 * Normally rendered inside the summary card. `ExpenseScreen` also uses it standalone for a
 * month with no expenses, which has no summary card to sit in but still has to be pageable.
 *
 * @param navigation Month being shown and how to page it
 * @param modifier Modifier for the row
 */
@Composable
internal fun MonthSwitcherBar(navigation: MonthNavigation, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = navigation.onPrevious, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.expenses_prev_month),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(
                R.string.expenses_month_and_count,
                navigation.label,
                pluralStringResource(
                    R.plurals.expenses_count,
                    navigation.expenseCount,
                    navigation.expenseCount
                )
            ),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = navigation.onNext, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.expenses_next_month),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Travel required before a drag over the month header commits to a month change. */
private val MONTH_SWIPE_THRESHOLD = 56.dp

/**
 * Horizontal swipe over the month header, resolved on release.
 *
 * Deliberately not a pager: the month's content is a summary card plus a list that owns its own
 * horizontal gesture, so this reads the drag and calls the same two callbacks the chevrons do.
 *
 * @param navigation The same [MonthNavigation] the chevrons use, so the two can never disagree
 */
@Composable
internal fun Modifier.monthSwipe(navigation: MonthNavigation): Modifier {
    val thresholdPx = with(LocalDensity.current) { MONTH_SWIPE_THRESHOLD.toPx() }
    return this.pointerInput(navigation.label) {
        var drag = 0f
        detectHorizontalDragGestures(
            onDragStart = { drag = 0f },
            onDragEnd = {
                when (MonthSwipe.resolve(drag, thresholdPx)) {
                    MonthStep.PREVIOUS -> navigation.onPrevious()
                    MonthStep.NEXT -> navigation.onNext()
                    MonthStep.NONE -> Unit
                }
            },
            onDragCancel = { drag = 0f },
            onHorizontalDrag = { change, amount ->
                drag += amount
                change.consume()
            }
        )
    }
}

/** Proportional pink/blue bar showing what share of the month each parent fronted. */
@Composable
private fun SplitBar(momShare: Float, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SPLIT_BAR_HEIGHT)
            .clip(RoundedCornerShape(4.dp))
    ) {
        if (momShare > 0f) {
            Box(
                modifier = Modifier
                    .weight(momShare)
                    .fillMaxHeight()
                    .background(CoPlanlyColors.MomPink)
            )
        }
        if (momShare < 1f) {
            Box(
                modifier = Modifier
                    .weight(1f - momShare)
                    .fillMaxHeight()
                    .background(CoPlanlyColors.DadBlue)
            )
        }
    }
}

/**
 * "Your co-parent owes you $29.85" plus the Settle up action, on its own tinted strip.
 *
 * This doc said "Dad owes you" until parent labels stopped being role words. The line
 * itself never named anyone: `expenses_balance_owed_to_you` says "your co-parent", which
 * is already agreement-free in all five locales. Naming the person here would need the
 * co-parent's *slot*, and this row is derived from a net figure that has no slot on it —
 * so it is deliberately left as the generic phrase rather than resolved to a name.
 *
 * The strip and the filled chip are the August 2026 refresh: this line answers the question the
 * screen exists for, and it used to sit below a divider as plain text next to an outlined
 * button — quieter than the row of numbers above it.
 *
 * Settle up only drafts a message — see [ExpenseScreen]. Sending is left to the user, because
 * a message to the other parent is theirs to send.
 */
@Composable
private fun BalanceRow(
    balance: ExpenseBalance,
    format: NumberFormat,
    monthLabel: String,
    onSettleUp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val net = balance.netForCurrentUser
    val amount = format.format(abs(net))
    val settled = abs(net) < SETTLED_EPSILON

    val label = when {
        settled -> stringResource(R.string.expenses_balance_settled)
        net > 0 -> stringResource(R.string.expenses_balance_owed_to_you, amount)
        else -> stringResource(R.string.expenses_balance_you_owe, amount)
    }
    // Settled is a neutral fact, being owed is good news, owing is a nudge — but never an
    // error: owing your co-parent for half the school shoes is not a failure state.
    val accent = when {
        settled -> MaterialTheme.colorScheme.outline
        net > 0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val draft = if (net > 0) {
        stringResource(R.string.expenses_settle_up_message_owed, amount, monthLabel)
    } else {
        stringResource(R.string.expenses_settle_up_message_owing, amount, monthLabel)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = BALANCE_STRIP_ALPHA))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(
            imageVector = Icons.Default.AccountBalanceWallet,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!settled) {
            PillChip(
                label = stringResource(R.string.expenses_settle_up),
                container = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = { onSettleUp(draft) }
            )
        }
    }
}

/** The current month's standalone name, capitalised — the header's fallback when none is passed. */
private fun defaultMonthLabel(): String =
    LocalDate.now().month
        .getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
        .replaceFirstChar { it.uppercase() }

/** Currency formatter that tolerates an unknown code rather than crashing on it. */
internal fun currencyFormat(currency: String): NumberFormat =
    NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
        runCatching { this.currency = Currency.getInstance(currency) }
    }

/** Two named parents, so the previews render the same shape a paired device does. */
private val previewParentNames = ParentNames(
    parents = Parents(
        me = NamedParent(uid = "u1", slot = "mom", name = "Olya"),
        coParent = NamedParent(uid = "u2", slot = "dad", name = "Pavel"),
        loaded = true
    ),
    youFallback = "You",
    coParentFallback = "Co-parent",
    unknownFallback = "Parent"
)

@LightDarkPreviews
@Composable
private fun ExpenseSummaryHeaderPairedPreview() {
    PreviewWrapper {
        ExpenseSummaryHeader(
            balance = ExpenseBalance(
                momPaid = 154.10,
                dadPaid = 94.40,
                total = 248.50,
                netForCurrentUser = 29.85,
                splitKnown = true
            ),
            currency = "USD",
            parentNames = previewParentNames,
            onSettleUp = {}
        )
    }
}

@LightDarkPreviews
@Composable
private fun ExpenseSummaryHeaderUnpairedPreview() {
    PreviewWrapper {
        ExpenseSummaryHeader(
            balance = ExpenseBalance(
                momPaid = 248.50,
                dadPaid = 0.0,
                total = 248.50,
                netForCurrentUser = 0.0,
                splitKnown = false
            ),
            currency = "USD",
            parentNames = previewParentNames,
            onSettleUp = {}
        )
    }
}
