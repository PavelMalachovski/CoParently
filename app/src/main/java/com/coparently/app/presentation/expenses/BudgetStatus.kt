package com.coparently.app.presentation.expenses

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.coparently.app.R
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.presentation.theme.ParentColors

/**
 * How a budget is doing, as a value rather than as a colour (UX-10).
 *
 * The chip strip and the budgets screen each decided this for themselves, in colour alone and in
 * two different palettes — `error`/`0xFFF5C05B`/`tertiary` on one, `Color.Red`/`0xFFFFC107`/
 * `0xFF4CAF50` on the other. So the same budget could read amber on one screen and a different
 * amber on the next, and a parent who cannot distinguish those hues got no status at all: a
 * screen reader was handed "School 800 / 1000" and nothing else, and a colour-blind reader saw a
 * 6dp dot they could not name. The numbers do not answer it either — "near" is measured against
 * each budget's own `alertThreshold`, which is not on screen.
 *
 * Colour is now the third channel behind a **word** and a **shape**, which is what WCAG 1.4.1
 * asks for and what makes the status survivable without any colour at all.
 */
enum class BudgetStatus {
    /** Comfortably inside the limit. Carries no word and no icon — a calm state should be quiet. */
    UNDER,

    /** Past the budget's own alert threshold, not yet past the limit. */
    NEAR,

    /** Past the limit. */
    OVER
}

/** Where this budget stands. Over the limit wins over near it, which is the order they overlap in. */
fun BudgetProgress.status(): BudgetStatus = when {
    isOverLimit -> BudgetStatus.OVER
    isNearLimit -> BudgetStatus.NEAR
    else -> BudgetStatus.UNDER
}

/**
 * The status colour for the current theme.
 *
 * [BudgetStatus.NEAR] resolves through the theme-aware pair rather than a fixed amber: the one it
 * replaces was 1.67:1 on a light background, which is below the 3:1 WCAG 1.4.11 asks of a
 * graphical indicator — and it was the only thing saying the budget was in trouble.
 */
@Composable
@ReadOnlyComposable
fun BudgetStatus.color(): Color = when (this) {
    BudgetStatus.OVER -> MaterialTheme.colorScheme.error
    BudgetStatus.NEAR ->
        if (ParentColors.isDarkTheme) CoPlanlyColors.BudgetWarningLight else CoPlanlyColors.BudgetWarningDark
    BudgetStatus.UNDER -> MaterialTheme.colorScheme.tertiary
}

/**
 * The status as a shape — the channel that survives when colour does not.
 *
 * Null for [BudgetStatus.UNDER], which is the absence of a warning: marking every budget in hand
 * would make the two that matter harder to pick out, not easier.
 */
val BudgetStatus.icon: ImageVector?
    get() = when (this) {
        // A circle and a triangle, not two tints of one shape: the point is to be
        // distinguishable with the colour discarded entirely.
        BudgetStatus.OVER -> Icons.Default.Error
        BudgetStatus.NEAR -> Icons.Default.Warning
        BudgetStatus.UNDER -> null
    }

/** The status in words, or null for [BudgetStatus.UNDER]. Also what a screen reader is given. */
@Composable
fun BudgetStatus.label(): String? = when (this) {
    BudgetStatus.OVER -> stringResource(R.string.budget_status_over)
    BudgetStatus.NEAR -> stringResource(R.string.budget_status_near)
    BudgetStatus.UNDER -> null
}
