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
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.coparently.app.R
import com.coparently.app.domain.model.Expense
import com.coparently.app.presentation.theme.CoPlanlyColors
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Alpha of the payer-tinted circle behind a row's leading icon. */
private const val PAYER_TINT_ALPHA = 0.18f

/**
 * List of expenses for the period.
 *
 * @param expenses Expenses to show, already ordered
 * @param roleByUid Map of payer uid to "mom"/"dad"; a missing entry just omits the payer
 * @param modifier Modifier for the list
 */
@Composable
fun ExpenseList(
    expenses: List<Expense>,
    roleByUid: Map<String, String>,
    modifier: Modifier = Modifier
) {
    // Receipt being viewed full-screen; transient UI state, deliberately local.
    var viewedReceiptUrl by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items(expenses, key = { it.id }) { expense ->
            ExpenseItem(
                expense = expense,
                payerRole = roleByUid[expense.paidBy],
                onReceiptClick = { url -> viewedReceiptUrl = url }
            )
        }
    }

    viewedReceiptUrl?.let { url ->
        ReceiptViewerDialog(
            receiptUrl = url,
            onDismiss = { viewedReceiptUrl = null }
        )
    }
}

/**
 * A single expense row: who paid, what for, and how it splits.
 *
 * Deliberately NOT clickable as a whole — there is no expense-detail screen yet, and a row that
 * ripples but does nothing reads as a broken app. The receipt thumbnail is the one real
 * affordance here and stays tappable.
 *
 * The amount alone cannot answer "is this settled?", which is the question co-parents actually
 * have, so the row states the payer and the split explicitly.
 *
 * @param expense Expense to render
 * @param payerRole "mom"/"dad", or null when the payer is not a known parent
 * @param onReceiptClick Opens the full-screen receipt viewer
 */
@Composable
fun ExpenseItem(
    expense: Expense,
    payerRole: String? = null,
    onReceiptClick: (String) -> Unit = {}
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }
    val format = remember(expense.currency) { currencyFormat(expense.currency) }

    val payerColor = when (payerRole) {
        "mom" -> CoPlanlyColors.MomPink
        "dad" -> CoPlanlyColors.DadBlue
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val payerName = when (payerRole) {
        "mom" -> stringResource(R.string.calendar_parent_mom)
        "dad" -> stringResource(R.string.calendar_parent_dad)
        else -> null
    }

    val subtitle = if (payerName != null) {
        stringResource(
            R.string.expenses_row_subtitle,
            expense.category.displayName,
            payerName,
            expense.date.format(dateFormatter)
        )
    } else {
        stringResource(
            R.string.expenses_row_subtitle_unknown_payer,
            expense.category.displayName,
            expense.date.format(dateFormatter)
        )
    }

    val splitLabel = when {
        expense.splitBetween.size >= 2 && expense.splitBetween.size == 2 ->
            stringResource(R.string.expenses_split_even)
        expense.splitBetween.size > 2 ->
            stringResource(R.string.expenses_split_n_ways, expense.splitBetween.size)
        else -> stringResource(R.string.expenses_split_none)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // A receipt photo, when present, keeps its own tappable thumbnail here: the viewer
            // is a working feature and losing its entry point to match a mockup would be a
            // regression. Without a photo the slot shows a payer-tinted category mark instead.
            val receiptUrl = expense.receiptUrl
            if (receiptUrl != null) {
                AsyncImage(
                    model = receiptUrl,
                    contentDescription = stringResource(R.string.expenses_receipt_photo),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onReceiptClick(receiptUrl) }
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(payerTint(payerColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ReceiptLong,
                        contentDescription = null,
                        tint = payerColor,
                        modifier = Modifier.size(16.dp)
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
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = format.format(expense.amount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = splitLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Payer colour at the tint alpha used behind row icons. */
private fun payerTint(color: Color): Color = color.copy(alpha = PAYER_TINT_ALPHA)

/**
 * Full-width receipt photo viewer; tap anywhere on the image or outside to close.
 */
@Composable
private fun ReceiptViewerDialog(
    receiptUrl: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AsyncImage(
            model = receiptUrl,
            contentDescription = stringResource(R.string.expenses_receipt_photo),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onDismiss)
        )
    }
}
