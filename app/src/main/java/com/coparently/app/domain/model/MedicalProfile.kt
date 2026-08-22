package com.coparently.app.domain.model

import java.time.LocalDate

/**
 * The medical facts an emergency needs, for one person.
 *
 * Used unchanged for a parent and for a child: the questions a paramedic asks do not differ by
 * age, and one type means one editor rather than two that drift apart.
 *
 * Allergies are deliberately **not** here. `ChildInfo.allergies` is already a first-class field
 * with a live editor (`AllergyEditor`), and folding it in would mean moving values between
 * columns — SQLite cannot drop a column without recreating the table. `User` carries `allergies`
 * beside this instead, so both store it the same way and every schema change stays additive.
 *
 * @property bloodType Blood group, or null when not recorded
 * @property intolerances Substances tolerated poorly but not allergically — lactose, gluten
 * @property hereditaryConditions Conditions that run in the family
 * @property vaccinations Vaccines given, newest first is not enforced
 */
data class MedicalProfile(
    val bloodType: BloodType? = null,
    val intolerances: List<String> = emptyList(),
    val hereditaryConditions: List<String> = emptyList(),
    val vaccinations: List<Vaccination> = emptyList()
)

/**
 * One vaccination.
 *
 * @property name Vaccine name as the parent knows it, not a code
 * @property date When it was given, or null. Nullable on purpose: a parent who remembers the
 *   vaccine but not the month should still be able to record it, and "recorded without a date"
 *   is more useful than "not recorded".
 */
data class Vaccination(
    val name: String,
    val date: LocalDate? = null
)

/**
 * The eight blood groups.
 *
 * An enum rather than free text so the eight real answers are the only answers, and so the stored
 * value survives a language change. The *displayed* notation is locale-dependent — English writes
 * the null group `O`, Russian and German write it `0` — which is why rendering goes through
 * `BloodType.labelRes()` and never through the constant name.
 */
enum class BloodType {
    A_POSITIVE,
    A_NEGATIVE,
    B_POSITIVE,
    B_NEGATIVE,
    AB_POSITIVE,
    AB_NEGATIVE,
    O_POSITIVE,
    O_NEGATIVE
}
