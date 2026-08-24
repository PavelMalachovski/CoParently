package com.coparently.app.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coparently.app.presentation.theme.LayoutConstants
import java.util.Locale

/**
 * Shared building blocks introduced by the August 2026 design refresh.
 *
 * The refresh replaced six screens' worth of bespoke `Card { ListItem { … } }` stacks with a
 * small set of repeated shapes: a grouped section of rows, a pill-shaped chip, and a labelled
 * group header. They live here rather than in each feature package because the whole point of
 * the pass was that Home, Settings, Expenses and Chat stop inventing their own row anatomy —
 * one definition is what keeps them from drifting apart again.
 *
 * Everything below takes its colours from `MaterialTheme.colorScheme`, never from literal hex,
 * so the same composables render correctly in light and dark.
 */

/** Corner radius of a grouped section container. */
private val GROUP_CORNER = 16.dp

/** Corner radius of a pill chip; large enough to always read as fully rounded. */
private val PILL_CORNER = 16.dp

/**
 * An uppercase label above a [SectionGroup], e.g. "FAMILY".
 *
 * Uppercasing happens here, not in the string resource: a resource is sometimes legitimately
 * shared with a screen that needs it in sentence case (`childinfo_section_*` is also a form
 * header in `AddEditChildInfoScreen`), so baking the casing into the value would leak into that
 * other caller. Centralizing it here means every caller can pass its string as authored and get
 * the same label styling regardless of how that string is cased or reused elsewhere.
 *
 * @param text Label text, in whatever case it is authored; uppercased for display
 * @param modifier Modifier applied to the label
 */
@Composable
fun GroupLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp)
    )
}

/**
 * A single tonal container holding a run of rows, with hairline dividers between them.
 *
 * This is the refresh's answer to "every list row is a full Card wrapping a ListItem": one
 * surface for the whole group instead of one per row.
 *
 * @param modifier Modifier applied to the container
 * @param content Rows to render; dividers are inserted automatically between them
 */
@Composable
fun SectionGroup(
    modifier: Modifier = Modifier,
    content: @Composable SectionGroupScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GROUP_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column {
            SectionGroupScopeImpl().content()
        }
    }
}

/**
 * Receiver for [SectionGroup] content, used to separate rows with dividers without making
 * every caller remember to add them.
 */
interface SectionGroupScope {
    /**
     * Emits a divider between rows. Call between [SectionRow]s; the last row must not be
     * followed by one.
     */
    @Composable
    fun Divider()
}

private class SectionGroupScopeImpl : SectionGroupScope {
    @Composable
    override fun Divider() {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    }
}

/**
 * One row inside a [SectionGroup]: leading icon, title, optional supporting line, and at most
 * one trailing control.
 *
 * The "at most one trailing control" is the rule the settings audit called for — a row that
 * carries a toggle *and* a chevron *and* two buttons is three interaction models pretending to
 * be one.
 *
 * @param title Primary line
 * @param modifier Modifier applied to the row
 * @param icon Leading icon, or null for a text-only row
 * @param iconTint Tint for [icon]; defaults to the primary colour
 * @param leading A leading slot drawn instead of [icon] — for a row whose subject is a *person*
 *   and whose leading mark is therefore their avatar, not a glyph. Exactly one of the two is
 *   ever drawn: an avatar beside an icon would be the double leading mark this anatomy exists
 *   to prevent.
 * @param supporting Secondary line under [title], or null
 * @param supportingColor Colour of [supporting]; defaults to the muted on-surface variant
 * @param supportingIcon Small status dot colour shown before [supporting], or null for none
 * @param titleColor Colour of [title]; override for destructive rows
 * @param onClick Row tap handler, or null to make the row inert
 * @param trailing The single trailing control (value text, switch, chevron, …)
 */
@Composable
@Suppress("LongParameterList") // one row anatomy, expressed as one parameter list
fun SectionRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    supporting: String? = null,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    supportingIcon: Color? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor
            )
            if (supporting != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (supportingIcon != null) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(supportingIcon)
                        )
                    }
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = supportingColor
                    )
                }
            }
        }
        trailing?.invoke()
    }
}

/**
 * A small pill-shaped chip — the refresh's replacement for unlabelled icon actions.
 *
 * Two variants, chosen by [container]: filled when a container colour is given, outlined when
 * it is null. Both keep their label on one line, because a chip that wraps stops reading as a
 * button.
 *
 * @param label Chip text
 * @param modifier Modifier applied to the chip
 * @param icon Optional leading icon
 * @param container Fill colour, or null for an outlined chip
 * @param contentColor Text and icon colour
 * @param leadingDot Colour of a small status dot before the label, or null for none
 * @param onClick Tap handler, or null for a display-only chip
 */
@Composable
@Suppress("LongParameterList") // one chip anatomy, expressed as one parameter list
fun PillChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color? = null,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    leadingDot: Color? = null,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(PILL_CORNER)
    Row(
        modifier = modifier
            .clip(shape)
            .then(if (container != null) Modifier.background(container) else Modifier)
            .then(
                if (container == null) {
                    Modifier.border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape
                    )
                } else {
                    Modifier
                }
            )
            // A chip that does something is a control, and a control has to be reachable and
            // announced as one. The padding below puts a pill at roughly 28dp tall — well
            // under the 48dp minimum — and nine call sites are interactive, including the
            // "Review" action on Home's handover card. `Role.Button` is what makes TalkBack
            // say "button" instead of reading the label as ordinary text.
            //
            // Only the interactive branch grows: a decorative chip (a status pill, a category
            // marker) is not a target and padding it to 48dp would wreck the chip strips it
            // sits in.
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(role = Role.Button, onClick = onClick)
                        .defaultMinSize(minHeight = LayoutConstants.MIN_TOUCH_TARGET)
                } else {
                    Modifier
                }
            )
            .padding(PaddingValues(horizontal = 12.dp, vertical = 6.dp)),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingDot != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(leadingDot)
            )
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1
        )
    }
}
