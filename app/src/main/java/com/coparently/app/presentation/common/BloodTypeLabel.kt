package com.coparently.app.presentation.common

import androidx.annotation.StringRes
import com.coparently.app.R
import com.coparently.app.domain.model.BloodType

/**
 * The string resource naming a blood group in the reader's language.
 *
 * Deliberately not a property on the enum and deliberately not `@Composable`: the enum stays free
 * of Android, and returning the id rather than a resolved string lets a call site inside a
 * conditional resolve it only when it has a value to show.
 *
 * @return the id of the localized notation for this blood group
 */
@StringRes
fun BloodType.labelRes(): Int = when (this) {
    BloodType.A_POSITIVE -> R.string.medical_blood_type_a_positive
    BloodType.A_NEGATIVE -> R.string.medical_blood_type_a_negative
    BloodType.B_POSITIVE -> R.string.medical_blood_type_b_positive
    BloodType.B_NEGATIVE -> R.string.medical_blood_type_b_negative
    BloodType.AB_POSITIVE -> R.string.medical_blood_type_ab_positive
    BloodType.AB_NEGATIVE -> R.string.medical_blood_type_ab_negative
    BloodType.O_POSITIVE -> R.string.medical_blood_type_o_positive
    BloodType.O_NEGATIVE -> R.string.medical_blood_type_o_negative
}
