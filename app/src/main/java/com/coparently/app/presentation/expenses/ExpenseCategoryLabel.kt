package com.coparently.app.presentation.expenses

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import com.coparently.app.R
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.presentation.theme.CoPlanlyColors

/**
 * Localized label resource for an [ExpenseCategory].
 *
 * Presentation-layer mapping: the enum names remain the persisted wire values, while the
 * user-facing label is resolved through string resources so it follows the app locale.
 */
@get:StringRes
internal val ExpenseCategory.labelRes: Int
    get() = when (this) {
        ExpenseCategory.EDUCATION -> R.string.expenses_category_education
        ExpenseCategory.MEDICAL -> R.string.expenses_category_medical
        ExpenseCategory.CLOTHING -> R.string.expenses_category_clothing
        ExpenseCategory.FOOD -> R.string.expenses_category_food
        ExpenseCategory.ACTIVITIES -> R.string.expenses_category_activities
        ExpenseCategory.TRANSPORTATION -> R.string.expenses_category_transportation
        ExpenseCategory.TOYS -> R.string.expenses_category_toys
        ExpenseCategory.HOUSEHOLD -> R.string.expenses_category_household
        ExpenseCategory.OTHER -> R.string.expenses_category_other
    }

/**
 * Compose icon for an [ExpenseCategory].
 *
 * The domain model already carries an `icon` string, but those are Material Symbols *names*
 * meant for a web renderer — Compose needs an [ImageVector]. This is the presentation-layer
 * counterpart, kept next to [labelRes] so a new category is obviously missing both.
 *
 * Introduced by the August 2026 refresh: every expense row used the same generic receipt glyph,
 * so a list of ten expenses gave no signal about what the money went on.
 */
internal val ExpenseCategory.iconVector: ImageVector
    get() = when (this) {
        ExpenseCategory.EDUCATION -> Icons.Default.School
        ExpenseCategory.MEDICAL -> Icons.Default.MedicalServices
        ExpenseCategory.CLOTHING -> Icons.Default.Checkroom
        ExpenseCategory.FOOD -> Icons.Default.Restaurant
        ExpenseCategory.ACTIVITIES -> Icons.Default.SportsSoccer
        ExpenseCategory.TRANSPORTATION -> Icons.Default.DirectionsBus
        ExpenseCategory.TOYS -> Icons.Default.Toys
        ExpenseCategory.HOUSEHOLD -> Icons.Default.Home
        ExpenseCategory.OTHER -> Icons.Default.ReceiptLong
    }

/**
 * The colour this category is drawn in on the spending chart, its legend and its table.
 *
 * One line thick on purpose: the derivation, and the reasoning behind every number in it, lives
 * in [CategoryPalette], which is kept free of Compose so it can be unit tested. Kept here beside
 * [labelRes] and [iconVector] so a new category is obviously missing all three.
 *
 * Reads the rendered theme rather than `isSystemInDarkTheme()` — the app can force light while
 * the system is dark, and the palette must follow what is actually painted. The dark steps are
 * separately chosen, not a flip of the light ones.
 */
@Composable
internal fun ExpenseCategory.sliceColor(): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < CoPlanlyColors.DARK_LUMINANCE_THRESHOLD
    return Color(CategoryPalette.sliceArgb(this, scheme.primary.toArgb(), dark))
}
