package com.coparently.app.presentation.common

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coparently.app.domain.family.FamilyMemberRef
import com.coparently.app.domain.family.names

/**
 * "Who is this about" — one chip per child and per pet, multi-select.
 *
 * **Renders nothing for a family with fewer than two members**, and the gate lives here rather
 * than at each call site so it cannot be forgotten by the next screen that grows one. A picker
 * for a set of one asks a question with a single answer, which is design item 8 in miniature:
 * an affordance that promises a choice the family does not have. A family with one child and no
 * pets must see the screen they saw before this existed.
 *
 * Children and pets share one strip because a visit to the vet and a visit to the dentist are
 * the same shape of thing — see [FamilyMemberRef] for why they also share one stored reference.
 *
 * **Nothing selected means "everyone".** On a form that is an untagged record; on a filter it is
 * the unfiltered list. Deselecting the last chip is therefore always a way back out, and no
 * "All" chip is needed to provide one.
 *
 * Deliberately not colour-coded. Pink and blue identify the two parents, teal a calendar friend
 * and neutral grey the weekend; a fifth colour channel would break what
 * `presentation/calendar/DayCellFills.kt` exists to protect. A member is their name.
 *
 * @param members Everyone this family cares for, from [FamilyMembersSource].
 * @param selected The references currently chosen.
 * @param onToggle Called with the chip's reference; the caller adds or removes it.
 * @param label Sits above the strip, so the chips are not an unexplained row of names.
 */
@Composable
fun FamilyMemberChips(
    members: List<FamilyMember>,
    selected: List<FamilyMemberRef>,
    onToggle: (FamilyMemberRef) -> Unit,
    @StringRes label: Int,
    modifier: Modifier = Modifier
) {
    if (members.size < 2) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(label), style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            members.forEach { member ->
                FilterChip(
                    selected = selected.names(member.ref),
                    onClick = { onToggle(member.ref) },
                    label = { Text(member.name) }
                )
            }
        }
    }
}

/**
 * [ref] added to this list, or removed when it is already there.
 *
 * The toggle every caller of [FamilyMemberChips] needs, in one place so none of them reaches for
 * a `MutableList` and mutates state Compose is holding.
 */
fun List<FamilyMemberRef>.toggling(ref: FamilyMemberRef): List<FamilyMemberRef> =
    if (contains(ref)) filterNot { it == ref } else this + ref
