package com.coparently.app.presentation.expenses

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.expenses.ExpenseBalance
import com.coparently.app.presentation.theme.CoPlanlyColors
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

/**
 * Month header for the Expenses screen: total spend, who paid what, and who owes whom.
 *
 * Replaces a horizontal row of category cards that carried no Mom/Dad semantics at all — in a
 * two-household product the money screen never answered the question co-parents actually have,
 * which is "are we square?". The pink/blue split bar reuses the calendar's colour language
 * rather than inventing a second one.
 *
 * While unpaired the split bar and balance row are hidden: with one parent on record a
 * 100%-pink bar and a zero balance would be decoration pretending to be data.
 *
 * @param balance This month's paid/owed figures
 * @param currency ISO currency code for formatting
 * @param onSettleUp Invoked with a ready-to-send message when the user taps Settle up
 * @param modifier Modifier for the card
 */
@Composable
fun ExpenseSummaryHeader(
    balance: ExpenseBalance,
    currency: String,
    onSettleUp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val format = remember(currency) { currencyFormat(currency) }
    val monthLabel = LocalDate.now().month
        .getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
        .replaceFirstChar { it.uppercase() }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.expenses_month_shared_spend, monthLabel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = format.format(balance.total),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (balance.splitKnown) {
                SplitBar(
                    momShare = balance.momShareOfPaid,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(
                            R.string.expenses_mom_paid,
                            format.format(balance.momPaid)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = CoPlanlyColors.MomPink
                    )
                    Text(
                        text = stringResource(
                            R.string.expenses_dad_paid,
                            format.format(balance.dadPaid)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = CoPlanlyColors.DadBlue
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
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
 * "Dad owes you $29.85" plus the Settle up action.
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
    val dotColor = when {
        settled -> MaterialTheme.colorScheme.outline
        net > 0 -> CoPlanlyColors.BrandAccent
        else -> CoPlanlyColors.MomPink
    }
    val draft = if (net > 0) {
        stringResource(R.string.expenses_settle_up_message_owed, amount, monthLabel)
    } else {
        stringResource(R.string.expenses_settle_up_message_owing, amount, monthLabel)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!settled) {
            OutlinedButton(
                onClick = { onSettleUp(draft) },
                shape = RoundedCornerShape(11.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 10.dp,
                    vertical = 2.dp
                )
            ) {
                Text(
                    text = stringResource(R.string.expenses_settle_up),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** Currency formatter that tolerates an unknown code rather than crashing on it. */
internal fun currencyFormat(currency: String): NumberFormat =
    NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
        runCatching { this.currency = Currency.getInstance(currency) }
    }

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
            onSettleUp = {}
        )
    }
}
