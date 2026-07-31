package com.coparently.app.presentation.expenses

import androidx.annotation.StringRes
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
