package com.coparently.app.presentation.custody

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.coparently.app.R
import com.coparently.app.domain.custody.CustodyPatternDiff
import com.coparently.app.presentation.common.ParentNames

/**
 * Turns a [CustodyPatternDiff] into the two lines a parent reads before answering a proposal.
 *
 * In composable scope rather than in the ViewModel for the usual reason: the diff is slots and
 * counts, and only here is there a `Context` to resolve five locales and a [ParentNames] to turn
 * `"mom"`/`"dad"` into the people they are.
 *
 * @param diff What the proposal would change, or null while nothing has resolved.
 * @param parentNames Resolves a slot to that parent's name.
 * @return The description, or null when there is nothing honest to say — no diff yet, or a
 *   pattern the app could not read. Callers fall back to the plain "a new schedule was
 *   proposed" wording rather than printing a confident "nothing changes".
 */
@Composable
fun custodyDiffDescription(diff: CustodyPatternDiff?, parentNames: ParentNames): String? {
    if (diff == null || !diff.comparable) return null
    if (diff.identical) return stringResource(R.string.custody_diff_none)

    val context = LocalContext.current
    val summary = context.resources.getQuantityString(
        R.plurals.custody_diff_days_move,
        diff.movedDayCount,
        diff.movedDayCount
    )

    // Net per slot, in the schema's slot order so the sentence reads the same on both phones.
    val net = listOf("mom", "dad").mapNotNull { slot ->
        diff.netDaysBySlot[slot]?.let { slot to it }
    }
    if (net.isEmpty()) return summary

    val netLine = net.joinToString(" · ") { (slot, days) ->
        // A signed integer with no unit word, deliberately: the plural above already said
        // "days", and "+3"/"-3" needs no grammatical agreement in any of the five locales.
        val signed = if (days > 0) "+$days" else days.toString()
        "${parentNames.labelFor(slot)} $signed"
    }
    return "$summary\n$netLine"
}
