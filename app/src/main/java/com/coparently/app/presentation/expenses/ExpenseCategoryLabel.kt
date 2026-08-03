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
import androidx.compose.ui.graphics.vector.ImageVector
import com.coparently.app.R
import com.coparently.app.domain.model.ExpenseCategory

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
