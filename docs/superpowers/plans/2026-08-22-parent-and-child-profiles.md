# Parent and child profiles, and the sharing they need — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give both parents a medical and contact profile — for themselves and for the child — and make the co-parent able to see it, which today they cannot.

**Architecture:** One `MedicalProfile` domain type serves parent and child alike, stored as a JSON
column on both `users` and `child_info` in an additive Room migration that also drops four dead
tables. `SyncService.syncChildInfo` starts publishing the co-parent in `sharedWith`, and a
partner-keyed backfill re-uploads rows written before pairing so existing pairs are repaired too.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (Material 3), Room 2.7.2, Hilt, Firebase Firestore,
Gson, JUnit 4 + MockK, `@firebase/rules-unit-testing` (mocha) for the security rules.

**Spec:** `docs/superpowers/specs/2026-08-22-parent-and-child-profiles-design.md`

**Task order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11.** Task 1 defines the types and every
new string, so it comes first; Tasks 3 and 4 need Task 2's columns; Tasks 7 and 8 need Tasks 1, 3
and 4.

## Global Constraints

- **Jetpack Compose only.** Never add an XML layout.
- **Stateless composables** — state lives in ViewModels as `StateFlow`; UI takes values and callbacks.
- **Hilt** for all DI. New modules go in `app/src/main/java/com/coparently/app/di/`.
- **minSdk 26.** No `java.time` API added after 26 (`LocalDate.ofInstant` is API 34; use `Instant.atZone(...).toLocalDate()`).
- **detekt `MaxLineLength` is 120**, comments included. Config: `app/config/detekt/detekt.yml`.
- **detekt `TooGenericExceptionCaught` is active and lists `Exception`.** New code must catch specific types. Nothing may be added to `app/config/detekt/baseline.xml`.
- **KDoc on every public class and function.** Code and comments in **English**.
- **Every new user-facing string goes into all five locales in the same commit:** `values`, `values-cs`, `values-de`, `values-ru`, `values-uk`. `MissingTranslation` lint is disabled project-wide and will not catch an omission.
- **Never hardcode user-visible text in a composable.** Use `stringResource`.
- **Material 3 only**, theme tokens from `presentation/theme/`; colours from `MaterialTheme.colorScheme`, never literal hex.
- **Room schema changes** require: entity change → version bump in `CoPlanlyDatabase` → migration in `DatabaseMigrations` (auto-registered via `ALL_MIGRATIONS`). Exported schemas live in `app/schemas/`.
- **Private events never reach Firestore**, and no receipt text or photo may be sent to any remote service. Neither is touched here, but do not weaken either.
- **Conventional Commits** (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`).
- Build with the JDK at `C:\Program Files\Android\Android Studio1\jbr` — the machine's `JAVA_HOME` points at a broken install:
  `JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew ...`
- `./gradlew detekt` **exits non-zero on this repository** from 17 findings that pre-date this branch, in files you are not touching. Judge your work by whether your files appear in `app/build/reports/detekt/detekt.xml`, never by the exit code.

---

### Task 1: `MedicalProfile`, and every string this package needs

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/model/MedicalProfile.kt`
- Create: `app/src/main/java/com/coparently/app/presentation/common/BloodTypeLabel.kt`
- Create: `app/src/main/res/values/medical_strings.xml`
- Create: `app/src/main/res/values-cs/medical_strings.xml`
- Create: `app/src/main/res/values-de/medical_strings.xml`
- Create: `app/src/main/res/values-ru/medical_strings.xml`
- Create: `app/src/main/res/values-uk/medical_strings.xml`
- Test: `app/src/test/java/com/coparently/app/presentation/common/BloodTypeLabelTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, for every later task:
  - `data class MedicalProfile(bloodType: BloodType?, intolerances: List<String>, hereditaryConditions: List<String>, vaccinations: List<Vaccination>)` — all four default to null/empty.
  - `data class Vaccination(val name: String, val date: LocalDate?)`
  - `enum class BloodType { A_POSITIVE, A_NEGATIVE, B_POSITIVE, B_NEGATIVE, AB_POSITIVE, AB_NEGATIVE, O_POSITIVE, O_NEGATIVE }`
  - `@StringRes fun BloodType.labelRes(): Int`
  - The string keys listed in Step 3.

`allergies` is deliberately **not** in `MedicalProfile` — see the spec §4. `ChildInfo` already has
it as a first-class field with a live editor, and `User` gains one beside `medicalProfile` in
Task 4 so both store it the same way.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/presentation/common/BloodTypeLabelTest.kt`:

```kotlin
package com.coparently.app.presentation.common

import com.coparently.app.domain.model.BloodType
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Every blood type must have its own label, and the labels must all differ.
 *
 * The notation is not universal: English writes the null group `O`, while Russian and German
 * write it `0` (zero). That is the whole reason these are string resources rather than a `code`
 * property on the enum — and it is also why a copy-paste slip that points two variants at the
 * same resource would be invisible on screen until someone was told the wrong blood type.
 */
class BloodTypeLabelTest {

    @Test
    fun `every blood type maps to a distinct string resource`() {
        val resources = BloodType.entries.map { it.labelRes() }

        assertEquals(8, resources.size)
        assertEquals(
            "two blood types share a label resource",
            resources.size,
            resources.distinct().size
        )
    }

    @Test
    fun `there are exactly the eight real blood types`() {
        assertEquals(
            listOf(
                "A_POSITIVE", "A_NEGATIVE", "B_POSITIVE", "B_NEGATIVE",
                "AB_POSITIVE", "AB_NEGATIVE", "O_POSITIVE", "O_NEGATIVE"
            ),
            BloodType.entries.map { it.name }
        )
    }

    @Test
    fun `an empty medical profile is the default, so an untouched parent stores nothing`() {
        val empty = com.coparently.app.domain.model.MedicalProfile()

        assertEquals(null, empty.bloodType)
        assertEquals(emptyList(), empty.intolerances)
        assertEquals(emptyList(), empty.hereditaryConditions)
        assertEquals(emptyList(), empty.vaccinations)
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.common.BloodTypeLabelTest"
```

Expected: compilation failure — `Unresolved reference: BloodType`.

- [ ] **Step 3: Add the English strings**

Create `app/src/main/res/values/medical_strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Medical profile, shared by the parent's own profile and the child's. -->
<resources>
    <string name="medical_section_title">Medical details</string>
    <string name="medical_blood_type_label">Blood type</string>
    <string name="medical_blood_type_not_set">Not recorded</string>
    <string name="medical_blood_type_a_positive">A+</string>
    <string name="medical_blood_type_a_negative">A−</string>
    <string name="medical_blood_type_b_positive">B+</string>
    <string name="medical_blood_type_b_negative">B−</string>
    <string name="medical_blood_type_ab_positive">AB+</string>
    <string name="medical_blood_type_ab_negative">AB−</string>
    <string name="medical_blood_type_o_positive">O+</string>
    <string name="medical_blood_type_o_negative">O−</string>
    <string name="medical_intolerances_label">Intolerances</string>
    <string name="medical_intolerances_hint">Lactose, gluten, a medicine…</string>
    <string name="medical_hereditary_label">Hereditary conditions</string>
    <string name="medical_hereditary_hint">Conditions that run in the family</string>
    <string name="medical_vaccinations_label">Vaccinations</string>
    <string name="medical_vaccination_name_label">Vaccine</string>
    <string name="medical_vaccination_date_label">Date (optional)</string>
    <string name="medical_vaccination_no_date">Date not recorded</string>
    <string name="medical_vaccination_add">Add vaccination</string>
    <string name="medical_vaccination_remove">Remove vaccination</string>
    <string name="medical_item_add">Add</string>
    <string name="medical_item_remove">Remove</string>
    <string name="medical_empty">Nothing recorded yet</string>
</resources>
```

- [ ] **Step 4: Add the Czech strings**

Create `app/src/main/res/values-cs/medical_strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="medical_section_title">Zdravotní údaje</string>
    <string name="medical_blood_type_label">Krevní skupina</string>
    <string name="medical_blood_type_not_set">Neuvedeno</string>
    <string name="medical_blood_type_a_positive">A+</string>
    <string name="medical_blood_type_a_negative">A−</string>
    <string name="medical_blood_type_b_positive">B+</string>
    <string name="medical_blood_type_b_negative">B−</string>
    <string name="medical_blood_type_ab_positive">AB+</string>
    <string name="medical_blood_type_ab_negative">AB−</string>
    <string name="medical_blood_type_o_positive">0+</string>
    <string name="medical_blood_type_o_negative">0−</string>
    <string name="medical_intolerances_label">Intolerance</string>
    <string name="medical_intolerances_hint">Laktóza, lepek, lék…</string>
    <string name="medical_hereditary_label">Dědičná onemocnění</string>
    <string name="medical_hereditary_hint">Onemocnění, která se v rodině opakují</string>
    <string name="medical_vaccinations_label">Očkování</string>
    <string name="medical_vaccination_name_label">Vakcína</string>
    <string name="medical_vaccination_date_label">Datum (nepovinné)</string>
    <string name="medical_vaccination_no_date">Datum neuvedeno</string>
    <string name="medical_vaccination_add">Přidat očkování</string>
    <string name="medical_vaccination_remove">Odebrat očkování</string>
    <string name="medical_item_add">Přidat</string>
    <string name="medical_item_remove">Odebrat</string>
    <string name="medical_empty">Zatím nic neuvedeno</string>
</resources>
```

- [ ] **Step 5: Add the German strings**

Create `app/src/main/res/values-de/medical_strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="medical_section_title">Medizinische Angaben</string>
    <string name="medical_blood_type_label">Blutgruppe</string>
    <string name="medical_blood_type_not_set">Nicht erfasst</string>
    <string name="medical_blood_type_a_positive">A+</string>
    <string name="medical_blood_type_a_negative">A−</string>
    <string name="medical_blood_type_b_positive">B+</string>
    <string name="medical_blood_type_b_negative">B−</string>
    <string name="medical_blood_type_ab_positive">AB+</string>
    <string name="medical_blood_type_ab_negative">AB−</string>
    <string name="medical_blood_type_o_positive">0+</string>
    <string name="medical_blood_type_o_negative">0−</string>
    <string name="medical_intolerances_label">Unverträglichkeiten</string>
    <string name="medical_intolerances_hint">Laktose, Gluten, ein Medikament…</string>
    <string name="medical_hereditary_label">Erbkrankheiten</string>
    <string name="medical_hereditary_hint">Krankheiten, die in der Familie vorkommen</string>
    <string name="medical_vaccinations_label">Impfungen</string>
    <string name="medical_vaccination_name_label">Impfstoff</string>
    <string name="medical_vaccination_date_label">Datum (optional)</string>
    <string name="medical_vaccination_no_date">Datum nicht erfasst</string>
    <string name="medical_vaccination_add">Impfung hinzufügen</string>
    <string name="medical_vaccination_remove">Impfung entfernen</string>
    <string name="medical_item_add">Hinzufügen</string>
    <string name="medical_item_remove">Entfernen</string>
    <string name="medical_empty">Noch nichts erfasst</string>
</resources>
```

- [ ] **Step 6: Add the Russian strings**

Create `app/src/main/res/values-ru/medical_strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="medical_section_title">Медицинские данные</string>
    <string name="medical_blood_type_label">Группа крови</string>
    <string name="medical_blood_type_not_set">Не указана</string>
    <string name="medical_blood_type_a_positive">A(II) Rh+</string>
    <string name="medical_blood_type_a_negative">A(II) Rh−</string>
    <string name="medical_blood_type_b_positive">B(III) Rh+</string>
    <string name="medical_blood_type_b_negative">B(III) Rh−</string>
    <string name="medical_blood_type_ab_positive">AB(IV) Rh+</string>
    <string name="medical_blood_type_ab_negative">AB(IV) Rh−</string>
    <string name="medical_blood_type_o_positive">0(I) Rh+</string>
    <string name="medical_blood_type_o_negative">0(I) Rh−</string>
    <string name="medical_intolerances_label">Непереносимости</string>
    <string name="medical_intolerances_hint">Лактоза, глютен, лекарство…</string>
    <string name="medical_hereditary_label">Наследственные заболевания</string>
    <string name="medical_hereditary_hint">Заболевания, которые есть в семье</string>
    <string name="medical_vaccinations_label">Прививки</string>
    <string name="medical_vaccination_name_label">Вакцина</string>
    <string name="medical_vaccination_date_label">Дата (необязательно)</string>
    <string name="medical_vaccination_no_date">Дата не указана</string>
    <string name="medical_vaccination_add">Добавить прививку</string>
    <string name="medical_vaccination_remove">Удалить прививку</string>
    <string name="medical_item_add">Добавить</string>
    <string name="medical_item_remove">Удалить</string>
    <string name="medical_empty">Пока ничего не указано</string>
</resources>
```

- [ ] **Step 7: Add the Ukrainian strings**

Create `app/src/main/res/values-uk/medical_strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="medical_section_title">Медичні дані</string>
    <string name="medical_blood_type_label">Група крові</string>
    <string name="medical_blood_type_not_set">Не вказана</string>
    <string name="medical_blood_type_a_positive">A(II) Rh+</string>
    <string name="medical_blood_type_a_negative">A(II) Rh−</string>
    <string name="medical_blood_type_b_positive">B(III) Rh+</string>
    <string name="medical_blood_type_b_negative">B(III) Rh−</string>
    <string name="medical_blood_type_ab_positive">AB(IV) Rh+</string>
    <string name="medical_blood_type_ab_negative">AB(IV) Rh−</string>
    <string name="medical_blood_type_o_positive">0(I) Rh+</string>
    <string name="medical_blood_type_o_negative">0(I) Rh−</string>
    <string name="medical_intolerances_label">Непереносимості</string>
    <string name="medical_intolerances_hint">Лактоза, глютен, ліки…</string>
    <string name="medical_hereditary_label">Спадкові захворювання</string>
    <string name="medical_hereditary_hint">Захворювання, які є в родині</string>
    <string name="medical_vaccinations_label">Щеплення</string>
    <string name="medical_vaccination_name_label">Вакцина</string>
    <string name="medical_vaccination_date_label">Дата (необовʼязково)</string>
    <string name="medical_vaccination_no_date">Дата не вказана</string>
    <string name="medical_vaccination_add">Додати щеплення</string>
    <string name="medical_vaccination_remove">Видалити щеплення</string>
    <string name="medical_item_add">Додати</string>
    <string name="medical_item_remove">Видалити</string>
    <string name="medical_empty">Поки нічого не вказано</string>
</resources>
```

- [ ] **Step 8: Write the domain types**

Create `app/src/main/java/com/coparently/app/domain/model/MedicalProfile.kt`:

```kotlin
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
```

- [ ] **Step 9: Write the label mapping**

Create `app/src/main/java/com/coparently/app/presentation/common/BloodTypeLabel.kt`:

```kotlin
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
```

- [ ] **Step 10: Run the test and confirm it passes**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.common.BloodTypeLabelTest"
```

Expected: 3 tests PASS.

- [ ] **Step 11: Verify every key exists in all five locales**

```bash
for k in $(grep -o 'name="[a-z_]*"' app/src/main/res/values/medical_strings.xml | sed 's/name="//;s/"//'); do printf "%-38s %s\n" "$k" "$(git grep -l "name=\"$k\"" -- 'app/src/main/res/values*/*.xml' | wc -l)"; done
```

Expected: every key prints `5`. Any other number is a missing or duplicated translation, which
lint will not report.

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/coparently/app/domain/model/MedicalProfile.kt app/src/main/java/com/coparently/app/presentation/common/BloodTypeLabel.kt app/src/main/res/values*/medical_strings.xml app/src/test/java/com/coparently/app/presentation/common/BloodTypeLabelTest.kt
git commit -m "feat(profiles): one medical shape for a parent and a child, in five languages"
```

---

### Task 2: Migration 13 to 14 — new columns, and four dead tables gone

**Files:**
- Modify: `app/src/main/java/com/coparently/app/data/local/entity/ChildInfoEntity.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/entity/UserEntity.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/CoPlanlyDatabase.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/DatabaseMigrations.kt`
- Delete: the eighteen files listed in Step 4
- Test: `app/src/androidTest/java/com/coparently/app/data/local/CoPlanlyDatabaseMigrationTest.kt` (add a case)

**Interfaces:**
- Consumes: nothing from Task 1 — the entity stores JSON, not the type.
- Produces, for Tasks 3, 4 and 5:
  - `ChildInfoEntity.medicalProfileJson: String` (non-null, `"{}"` for an untouched row)
  - `UserEntity.dateOfBirth: String?` — an **ISO `LocalDate` string**, e.g. `"1988-04-17"`, not a `LocalDateTime`
  - `UserEntity.phone: String?`
  - `UserEntity.allergiesJson: String` (non-null, `"[]"`)
  - `UserEntity.medicalProfileJson: String` (non-null, `"{}"`)
  - Database version **14**; `MedicalRecordEntity`, `AllergyEntity`, `GradeEntity` and `SchoolEventEntity` are no longer registered.

`UserEntity.dateOfBirth` is a `String?` holding an ISO date, not a `LocalDateTime` with a
`TypeConverter`. A birth date has no time of day, and `Converters` has no `LocalDate` converter to
borrow — adding one for a single column would change how every future date is stored. Task 4 parses
it with `LocalDate.parse`.

- [ ] **Step 1: Add the columns to the entities**

In `app/src/main/java/com/coparently/app/data/local/entity/ChildInfoEntity.kt`, add the property
after `schoolInfoJson` and document it in the class KDoc:

```kotlin
    val schoolInfoJson: String?, // JSON object or null
    /** JSON object of [com.coparently.app.domain.model.MedicalProfile]; `{}` when never filled. */
    val medicalProfileJson: String = "{}",
```

In `app/src/main/java/com/coparently/app/data/local/entity/UserEntity.kt`, add after `fcmToken`:

```kotlin
    val fcmToken: String? = null,
    /** ISO `LocalDate` string, e.g. `1988-04-17`. Null until the parent records it. */
    val dateOfBirth: String? = null,
    /** Free-text phone number as the parent typed it; no format is imposed. */
    val phone: String? = null,
    /** JSON array of allergy strings; `[]` when none. Mirrors `ChildInfoEntity.allergiesJson`. */
    val allergiesJson: String = "[]",
    /** JSON object of [com.coparently.app.domain.model.MedicalProfile]; `{}` when never filled. */
    val medicalProfileJson: String = "{}"
```

Update both classes' KDoc `@property` lists to cover the new fields.

- [ ] **Step 2: Write the migration**

In `app/src/main/java/com/coparently/app/data/local/DatabaseMigrations.kt`, add after
`MIGRATION_12_13`:

```kotlin
    /**
     * Adds the parent and child medical profiles, and removes a subsystem that never ran.
     *
     * Purely additive on the two live tables: `ALTER TABLE ... ADD COLUMN` with defaults, so no
     * table is rebuilt and no stored value is read or rewritten. That is the whole reason
     * `MedicalProfile` keeps `allergies` outside it — folding it into the JSON blob would have
     * meant moving `child_info.allergiesJson` into a new column, and SQLite cannot drop the old
     * one without recreating the table.
     *
     * The four `DROP TABLE`s remove `medical_records`, `allergies`, `grades` and `school_events`.
     * `MedicalRepositoryImpl` and `EducationRepositoryImpl` were never bound in `RepositoryModule`
     * and no ViewModel or use case ever referenced either interface, so these tables have only
     * ever been empty — nothing has been able to write to them. `IF EXISTS` covers an install
     * where a partially-applied earlier migration left one missing.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE child_info ADD COLUMN medicalProfileJson TEXT NOT NULL DEFAULT '{}'"
            )
            database.execSQL("ALTER TABLE users ADD COLUMN dateOfBirth TEXT")
            database.execSQL("ALTER TABLE users ADD COLUMN phone TEXT")
            database.execSQL(
                "ALTER TABLE users ADD COLUMN allergiesJson TEXT NOT NULL DEFAULT '[]'"
            )
            database.execSQL(
                "ALTER TABLE users ADD COLUMN medicalProfileJson TEXT NOT NULL DEFAULT '{}'"
            )

            database.execSQL("DROP TABLE IF EXISTS medical_records")
            database.execSQL("DROP TABLE IF EXISTS allergies")
            database.execSQL("DROP TABLE IF EXISTS grades")
            database.execSQL("DROP TABLE IF EXISTS school_events")
        }
    }
```

Add `MIGRATION_13_14` as the last entry of `ALL_MIGRATIONS`.

- [ ] **Step 3: Bump the database**

In `app/src/main/java/com/coparently/app/data/local/CoPlanlyDatabase.kt`: remove
`MedicalRecordEntity::class`, `AllergyEntity::class`, `GradeEntity::class` and
`SchoolEventEntity::class` from the `entities` list, remove their imports, remove the four
abstract DAO accessor functions (`medicalRecordDao()`, `allergyDao()`, `gradeDao()`,
`schoolEventDao()` — read the file for their exact names), and change `version = 13` to
`version = 14`.

Leave `fallbackToDestructiveMigrationFrom(1, 2, 3, 4)` in `DatabaseModule` exactly as it is.

- [ ] **Step 4: Delete the dead subsystem**

```bash
git rm app/src/main/java/com/coparently/app/domain/model/MedicalRecord.kt app/src/main/java/com/coparently/app/domain/model/Allergy.kt app/src/main/java/com/coparently/app/domain/model/Grade.kt app/src/main/java/com/coparently/app/domain/model/SchoolEvent.kt app/src/main/java/com/coparently/app/domain/repository/MedicalRepository.kt app/src/main/java/com/coparently/app/domain/repository/EducationRepository.kt app/src/main/java/com/coparently/app/data/repository/MedicalRepositoryImpl.kt app/src/main/java/com/coparently/app/data/repository/EducationRepositoryImpl.kt app/src/main/java/com/coparently/app/data/remote/firebase/FirestoreMedicalDataSource.kt app/src/main/java/com/coparently/app/data/remote/firebase/FirestoreEducationDataSource.kt app/src/main/java/com/coparently/app/data/local/dao/MedicalRecordDao.kt app/src/main/java/com/coparently/app/data/local/dao/AllergyDao.kt app/src/main/java/com/coparently/app/data/local/dao/GradeDao.kt app/src/main/java/com/coparently/app/data/local/dao/SchoolEventDao.kt app/src/main/java/com/coparently/app/data/local/entity/MedicalRecordEntity.kt app/src/main/java/com/coparently/app/data/local/entity/AllergyEntity.kt app/src/main/java/com/coparently/app/data/local/entity/GradeEntity.kt app/src/main/java/com/coparently/app/data/local/entity/SchoolEventEntity.kt
```

**Do not delete** `app/src/main/java/com/coparently/app/presentation/childinfo/components/AllergyEditor.kt`.
Despite the name it edits `ChildInfo.allergies: List<String>` and has nothing to do with the
deleted `Allergy` model. It is live and Task 8 gives it a second caller.

**Do not delete** `SensitiveMedicalData.kt` or `EncryptionManager.kt` — encryption is a recorded
follow-up (spec §9), not part of this island.

- [ ] **Step 5: Confirm nothing referenced the deleted code**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. An unresolved reference here means something did depend on the island
after all — stop and report it rather than resurrecting the file.

Then confirm the exported schema was regenerated:

```bash
ls app/schemas/com.coparently.app.data.local.CoPlanlyDatabase/14.json
```

Expected: the file exists. Task 6's migration test validates against it.

- [ ] **Step 6: Add the migration test case**

Append to `app/src/androidTest/java/com/coparently/app/data/local/CoPlanlyDatabaseMigrationTest.kt`,
following the file's existing style (read it first — it uses `helper.createDatabase`,
`helper.runMigrationsAndValidate` and raw `execSQL` inserts):

```kotlin
    @Test
    fun migration13To14_keepsChildInfoAndDefaultsTheNewColumns() {
        val db = helper.createDatabase(TEST_DB, 13)
        db.execSQL(
            """
            INSERT INTO child_info
                (id, childName, dateOfBirth, medicationsJson, activitiesJson, allergiesJson,
                 medicalNotes, emergencyContactsJson, schoolInfoJson, createdAt, updatedAt,
                 createdByFirebaseUid, lastModifiedBy, syncedToFirestore)
            VALUES ('c1', 'Anya', NULL, '[]', '[]', '["peanuts"]', NULL, '[]', NULL,
                    '2026-08-01T09:00:00', '2026-08-01T09:00:00', 'uid-1', 'uid-1', 1)
            """.trimIndent()
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 14, true, DatabaseMigrations.MIGRATION_13_14
        )

        migrated.query("SELECT childName, allergiesJson, medicalProfileJson FROM child_info").use {
            assertTrue(it.moveToFirst())
            assertEquals("Anya", it.getString(0))
            // The pre-existing allergy survives: MedicalProfile deliberately does not absorb it,
            // so this column is never read or rewritten by the migration.
            assertEquals("[\"peanuts\"]", it.getString(1))
            assertEquals("{}", it.getString(2))
        }
    }

    @Test
    fun migration13To14_dropsTheSubsystemThatNeverRan() {
        val db = helper.createDatabase(TEST_DB, 13)
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 14, true, DatabaseMigrations.MIGRATION_13_14
        )

        for (table in listOf("medical_records", "allergies", "grades", "school_events")) {
            migrated.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)
            ).use {
                assertFalse("$table survived the migration", it.moveToFirst())
            }
        }
    }
```

If the file defines its test-database name under a different constant than `TEST_DB`, use that one.

- [ ] **Step 7: Note honestly that this test needs hardware**

`MigrationTestHelper` is instrumented. Run it if a device or emulator is attached:

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew connectedDebugAndroidTest --tests "com.coparently.app.data.local.CoPlanlyDatabaseMigrationTest"
```

If `adb devices` lists none, **say so in your report** — do not claim the migration is verified.
There is no Robolectric in this project to fall back on. `assembleDebug` passing proves the schema
compiles, not that the migration runs.

- [ ] **Step 8: Commit**

```bash
git add -A app/src/main/java/com/coparently/app/data app/src/androidTest app/schemas
git commit -m "feat(db): carry medical profiles, and drop the subsystem that never ran"
```

---

### Task 3: `ChildInfo` carries its medical profile

**Files:**
- Modify: `app/src/main/java/com/coparently/app/domain/model/ChildInfo.kt`
- Modify: `app/src/main/java/com/coparently/app/data/repository/ChildInfoRepositoryImpl.kt`
- Test: `app/src/test/java/com/coparently/app/data/repository/ChildInfoMedicalProfileMappingTest.kt`

**Interfaces:**
- Consumes: `MedicalProfile`, `Vaccination`, `BloodType` (Task 1); `ChildInfoEntity.medicalProfileJson` (Task 2).
- Produces, for Tasks 5, 7 and 10: `ChildInfo.medicalProfile: MedicalProfile` (defaults to `MedicalProfile()`), round-tripping through Room and through the Firestore map under the key `"medicalProfile"`.

`ChildInfoRepositoryImpl` holds four private mappers — `toDomain`, `toEntity`, `toFirestoreMap`
and `toChildInfo` — and **all four** must carry the new field. Missing one is silent: the value
survives locally and vanishes on the co-parent's device, or the reverse.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/data/repository/ChildInfoMedicalProfileMappingTest.kt`:

```kotlin
package com.coparently.app.data.repository

import com.coparently.app.domain.model.BloodType
import com.coparently.app.domain.model.MedicalProfile
import com.coparently.app.domain.model.Vaccination
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * A medical profile must survive the trip through Gson unchanged.
 *
 * `ChildInfoRepositoryImpl` stores this type as a JSON string in Room and sends it to Firestore
 * as a nested map, so a `LocalDate` inside `Vaccination` has to serialise to something Gson can
 * read back. Gson has no built-in `LocalDate` adapter: without one it writes the field's internal
 * structure (`{"year":2024,"month":3,"day":12}`) on some JVMs and throws on others under Android's
 * stricter reflection rules. This pins the representation before either can happen in the field.
 */
class ChildInfoMedicalProfileMappingTest {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, LocalDateJsonAdapter())
        .create()

    @Test
    fun `a full profile round-trips through JSON`() {
        val profile = MedicalProfile(
            bloodType = BloodType.O_NEGATIVE,
            intolerances = listOf("lactose"),
            hereditaryConditions = listOf("asthma"),
            vaccinations = listOf(
                Vaccination("MMR", LocalDate.of(2024, 3, 12)),
                Vaccination("Tetanus", null)
            )
        )

        val restored = gson.fromJson(gson.toJson(profile), MedicalProfile::class.java)

        assertEquals(profile, restored)
    }

    @Test
    fun `a vaccination date is written as an ISO string, not as an object`() {
        val json = gson.toJson(Vaccination("MMR", LocalDate.of(2024, 3, 12)))

        assertEquals("""{"name":"MMR","date":"2024-03-12"}""", json)
    }

    @Test
    fun `an untouched profile is the empty default rather than null`() {
        val restored = gson.fromJson("{}", MedicalProfile::class.java)

        assertEquals(null, restored.bloodType)
        assertEquals(emptyList(), restored.vaccinations)
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew testDebugUnitTest --tests "com.coparently.app.data.repository.ChildInfoMedicalProfileMappingTest"
```

Expected: compilation failure — `Unresolved reference: LocalDateJsonAdapter`.

- [ ] **Step 3: Write the Gson adapter**

Create `app/src/main/java/com/coparently/app/data/repository/LocalDateJsonAdapter.kt`:

```kotlin
package com.coparently.app.data.repository

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type
import java.time.LocalDate

/**
 * Reads and writes a [LocalDate] as an ISO string.
 *
 * Gson ships no adapter for `java.time`, and its reflective fallback serialises a `LocalDate`'s
 * private fields — a shape that is not a date to anything else and that Android's stricter
 * reflection rules can refuse outright. Every date this project sends to Firestore is already an
 * ISO string, so this keeps one representation rather than adding a second.
 */
class LocalDateJsonAdapter : JsonSerializer<LocalDate>, JsonDeserializer<LocalDate> {

    override fun serialize(
        src: LocalDate?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement = JsonPrimitive(src?.toString())

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): LocalDate? = json?.asString?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew testDebugUnitTest --tests "com.coparently.app.data.repository.ChildInfoMedicalProfileMappingTest"
```

Expected: 3 tests PASS.

- [ ] **Step 5: Add the field to the domain model**

In `app/src/main/java/com/coparently/app/domain/model/ChildInfo.kt`, add after `schoolInfo` and
document it in the class KDoc:

```kotlin
    val schoolInfo: SchoolInfo? = null,
    val medicalProfile: MedicalProfile = MedicalProfile(),
```

- [ ] **Step 6: Carry it through all four mappers**

In `app/src/main/java/com/coparently/app/data/repository/ChildInfoRepositoryImpl.kt`:

Register the adapter on the `gson` instance this class uses. If it is injected, register it where
it is provided; if it is a local `Gson()`, replace it with:

```kotlin
    private val gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, LocalDateJsonAdapter())
        .create()
```

`toDomain()` — add:

```kotlin
            medicalProfile = gson.fromJson(medicalProfileJson, MedicalProfile::class.java)
                ?: MedicalProfile(),
```

`toEntity()` — add:

```kotlin
            medicalProfileJson = gson.toJson(medicalProfile),
```

`toFirestoreMap()` — add, next to the other nested objects:

```kotlin
            "medicalProfile" to gson.fromJson(gson.toJson(medicalProfile), Map::class.java),
```

`toChildInfo()` — add, mirroring how `schoolInfo` is read back:

```kotlin
            medicalProfile = (this["medicalProfile"] as? Map<*, *>)?.let {
                gson.fromJson(gson.toJson(it), MedicalProfile::class.java)
            } ?: MedicalProfile(),
```

- [ ] **Step 7: Build and run the whole suite**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/coparently/app/domain/model/ChildInfo.kt app/src/main/java/com/coparently/app/data/repository/ChildInfoRepositoryImpl.kt app/src/main/java/com/coparently/app/data/repository/LocalDateJsonAdapter.kt app/src/test/java/com/coparently/app/data/repository/ChildInfoMedicalProfileMappingTest.kt
git commit -m "feat(childinfo): carry a medical profile through Room and Firestore"
```

---

### Task 4: `User` carries a date of birth, a phone and a medical profile

**Files:**
- Modify: `app/src/main/java/com/coparently/app/domain/model/User.kt`
- Modify: `app/src/main/java/com/coparently/app/data/repository/UserRepositoryImpl.kt`
- Test: `app/src/test/java/com/coparently/app/data/repository/UserProfileMappingTest.kt`

**Interfaces:**
- Consumes: `MedicalProfile` (Task 1); `UserEntity.dateOfBirth` / `phone` / `allergiesJson` / `medicalProfileJson` (Task 2); `LocalDateJsonAdapter` (Task 3).
- Produces, for Task 8: `User.dateOfBirth: LocalDate?`, `User.phone: String?`, `User.allergies: List<String>`, `User.medicalProfile: MedicalProfile`.

**One thing here is easy to get catastrophically wrong.** `UserRepositoryImpl` pushes the profile
to Firestore with `set(..., merge)`, and the map it sends **deliberately omits `role`**. Read the
long comment above `val userData = mapOf(` before touching it: echoing a locally-stale `role` back
to the server once left a pair with both parents in slot 1, permanently and silently. The new
fields are safe to send because this user is their only writer — but do **not** take that as
licence to "tidy up" by adding `role` back.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/data/repository/UserProfileMappingTest.kt`:

```kotlin
package com.coparently.app.data.repository

import com.coparently.app.domain.model.BloodType
import com.coparently.app.domain.model.MedicalProfile
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A parent's own profile survives storage, and a malformed stored date does not crash the app.
 *
 * `UserEntity.dateOfBirth` is an ISO string rather than a converted `LocalDateTime`, so parsing
 * happens in the mapper — and a mapper that lets `DateTimeParseException` escape would take down
 * every screen that reads the signed-in user, which is most of them.
 */
class UserProfileMappingTest {

    @Test
    fun `an ISO date parses back to the same day`() {
        assertEquals(LocalDate.of(1988, 4, 17), parseProfileDate("1988-04-17"))
    }

    @Test
    fun `a null date stays null rather than becoming today`() {
        assertNull(parseProfileDate(null))
    }

    @Test
    fun `a blank or malformed date degrades to null instead of throwing`() {
        assertNull(parseProfileDate(""))
        assertNull(parseProfileDate("   "))
        assertNull(parseProfileDate("17.04.1988"))
        assertNull(parseProfileDate("not a date"))
    }

    @Test
    fun `an empty profile and a filled one both round-trip`() {
        assertEquals(MedicalProfile(), MedicalProfile())
        val filled = MedicalProfile(bloodType = BloodType.AB_POSITIVE)
        assertEquals(BloodType.AB_POSITIVE, filled.bloodType)
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew testDebugUnitTest --tests "com.coparently.app.data.repository.UserProfileMappingTest"
```

Expected: compilation failure — `Unresolved reference: parseProfileDate`.

- [ ] **Step 3: Write the parser**

Create `app/src/main/java/com/coparently/app/data/repository/ProfileDate.kt`:

```kotlin
package com.coparently.app.data.repository

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Reads a stored profile date.
 *
 * `UserEntity.dateOfBirth` is an ISO string, not a converted `LocalDateTime`: a birth date has no
 * time of day, and `Converters` carries no `LocalDate` converter to borrow.
 *
 * A value that will not parse degrades to null rather than throwing. Every screen that shows the
 * signed-in user reads this row, so an exception here would not spoil a date field — it would
 * empty the app.
 *
 * @param stored The raw column value, or null
 * @return The date, or null when absent, blank or unparseable
 */
fun parseProfileDate(stored: String?): LocalDate? {
    val trimmed = stored?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return try {
        LocalDate.parse(trimmed)
    } catch (e: DateTimeParseException) {
        null
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew testDebugUnitTest --tests "com.coparently.app.data.repository.UserProfileMappingTest"
```

Expected: 4 tests PASS.

- [ ] **Step 5: Add the fields to the domain model**

In `app/src/main/java/com/coparently/app/domain/model/User.kt`, add after `fcmToken` and document
each in the class KDoc:

```kotlin
    val fcmToken: String? = null,
    val dateOfBirth: LocalDate? = null,
    val phone: String? = null,
    val allergies: List<String> = emptyList(),
    val medicalProfile: MedicalProfile = MedicalProfile()
```

- [ ] **Step 6: Carry them through the mappers**

In `app/src/main/java/com/coparently/app/data/repository/UserRepositoryImpl.kt`, using the same
`gson` instance the class already has, registered with `LocalDateJsonAdapter` as in Task 3:

`toDomain()` — add:

```kotlin
            dateOfBirth = parseProfileDate(dateOfBirth),
            phone = phone,
            allergies = gson.fromJson(allergiesJson, Array<String>::class.java)?.toList().orEmpty(),
            medicalProfile = gson.fromJson(medicalProfileJson, MedicalProfile::class.java)
                ?: MedicalProfile(),
```

`toEntity()` — add:

```kotlin
            dateOfBirth = dateOfBirth?.toString(),
            phone = phone,
            allergiesJson = gson.toJson(allergies),
            medicalProfileJson = gson.toJson(medicalProfile),
```

`toUser()` (the Firestore reader) — add, with the same defensive defaults the surrounding lines use:

```kotlin
            dateOfBirth = parseProfileDate(this["dateOfBirth"] as? String),
            phone = (this["phone"] as? String)?.takeIf { it.isNotBlank() },
            allergies = (this["allergies"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            medicalProfile = (this["medicalProfile"] as? Map<*, *>)?.let {
                gson.fromJson(gson.toJson(it), MedicalProfile::class.java)
            } ?: MedicalProfile(),
```

The `userData` map sent to Firestore — add four entries, and leave every existing entry, and the
absence of `role`, exactly as they are:

```kotlin
                    "dateOfBirth" to (user.dateOfBirth?.toString() ?: ""),
                    "phone" to (user.phone ?: ""),
                    "allergies" to user.allergies,
                    "medicalProfile" to gson.fromJson(
                        gson.toJson(user.medicalProfile), Map::class.java
                    )
```

- [ ] **Step 7: Build and run the whole suite**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Confirm `role` is still absent from the Firestore map**

```bash
grep -n "\"role\" to" app/src/main/java/com/coparently/app/data/repository/UserRepositoryImpl.kt
```

Expected: **no output**. If this prints anything you have re-introduced the slot bug described
above.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/coparently/app/domain/model/User.kt app/src/main/java/com/coparently/app/data/repository/UserRepositoryImpl.kt app/src/main/java/com/coparently/app/data/repository/ProfileDate.kt app/src/test/java/com/coparently/app/data/repository/UserProfileMappingTest.kt
git commit -m "feat(profiles): give a parent a birth date, a phone and a medical profile"
```

---

### Task 5: The co-parent finally gets read access

**Files:**
- Modify: `app/src/main/java/com/coparently/app/data/local/dao/ChildInfoDao.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/preferences/PreferenceKeys.kt`
- Modify: `app/src/main/java/com/coparently/app/data/sync/SyncService.kt`
- Create: `app/src/main/java/com/coparently/app/data/sync/ChildInfoAudience.kt`
- Test: `app/src/test/java/com/coparently/app/data/sync/ChildInfoAudienceTest.kt`

**Interfaces:**
- Consumes: nothing from Tasks 1–4.
- Produces: `ChildInfoAudience.entitled(userId: String, creatorUid: String?, partnerId: String?): List<String>`; `ChildInfoDao.markOwnChildInfoUnsynced(myUid: String): Int`; `PreferenceKeys.CHILD_INFO_AUDIENCE_BACKFILL_PREFIX`.

This is the task that makes item 5 work. Read the spec's §3 and §6 before starting.

**Why this does not reuse `SyncService.shareTargets`.** That function intersects the entitled set
with the audience stored in `EventEntity.sharedWithJson`, because the server's unpair sweep narrows
the remote document but never the local Room copy. `ChildInfoEntity` **has no `sharedWith` column
at all** — the audience has always been derived fresh at upload from live fields — so there is no
stored list to carry a stale uid forward and nothing for an intersection to remove. Do **not** add
such a column: it would create exactly the staleness the events path has to defend against.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/data/sync/ChildInfoAudienceTest.kt`:

```kotlin
package com.coparently.app.data.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who may read a child-info document.
 *
 * Until this existed, `SyncService.syncChildInfo` published only the creator and the last
 * modifier, and said so in a comment: the co-parent was never added, so a paired parent could not
 * see child information the other had entered. That was a feature never built rather than a bug,
 * and this is the policy it needed.
 *
 * The property that matters most is the last one. The audience is derived from live state, so the
 * moment `partnerId` is null — which is what an unpair leaves behind — an ex-partner is simply
 * absent from the next upload. There is no stored list they could linger in.
 */
class ChildInfoAudienceTest {

    private val me = "uid-me"
    private val partner = "uid-partner"

    @Test
    fun `a paired parent publishes to both of them`() {
        val audience = ChildInfoAudience.entitled(me, creatorUid = me, partnerId = partner)

        assertEquals(setOf(me, partner), audience.toSet())
    }

    @Test
    fun `the creator is kept even when someone else uploads the row`() {
        val audience = ChildInfoAudience.entitled(me, creatorUid = "uid-other", partnerId = partner)

        assertTrue(audience.contains("uid-other"), "the creator must not lose their own document")
        assertTrue(audience.contains(me))
        assertTrue(audience.contains(partner))
    }

    @Test
    fun `an unpaired parent publishes only to themselves`() {
        val audience = ChildInfoAudience.entitled(me, creatorUid = me, partnerId = null)

        assertEquals(listOf(me), audience)
    }

    @Test
    fun `an ex-partner cannot come back through a stale value`() {
        // partnerId null is exactly what an unpair leaves. Nothing else feeds this function,
        // so there is no path by which the ex-partner reappears.
        val audience = ChildInfoAudience.entitled(me, creatorUid = me, partnerId = null)

        assertFalse(audience.contains(partner))
    }

    @Test
    fun `blank and duplicate uids are dropped`() {
        val audience = ChildInfoAudience.entitled(me, creatorUid = me, partnerId = "")

        assertEquals(listOf(me), audience)
    }

    @Test
    fun `a never-synced row with no creator still reaches both parents`() {
        val audience = ChildInfoAudience.entitled(me, creatorUid = null, partnerId = partner)

        assertEquals(setOf(me, partner), audience.toSet())
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew testDebugUnitTest --tests "com.coparently.app.data.sync.ChildInfoAudienceTest"
```

Expected: compilation failure — `Unresolved reference: ChildInfoAudience`.

- [ ] **Step 3: Write the policy**

Create `app/src/main/java/com/coparently/app/data/sync/ChildInfoAudience.kt`:

```kotlin
package com.coparently.app.data.sync

/**
 * Who may read a child-info document.
 *
 * `child_info` is gated on `sharedWith` in `firestore.rules`, and until this existed the uploader
 * published only the creator and the last modifier — so a paired parent could never see child
 * information the other had entered. The Cloud Function that revokes this access on unpair
 * (`SHARED_AUDIENCE_COLLECTIONS`) has always covered `child_info`: the revocation was written for
 * a grant that was never made.
 */
object ChildInfoAudience {

    /**
     * Derives the audience for an upload, from live state only.
     *
     * Deliberately **not** intersected with a stored list the way `SyncService.shareTargets` is
     * for events. That intersection exists because `EventEntity` keeps its own copy of the
     * audience, which the server's unpair sweep never narrows; `ChildInfoEntity` keeps no such
     * copy, so deriving from live state gives the same protection for free. An ex-partner is
     * absent from the very next upload simply because [partnerId] is null.
     *
     * @param userId The uploading user's Firebase UID
     * @param creatorUid The document's `createdByFirebaseUid`, or null if it never synced
     * @param partnerId The uploader's **current** co-parent, or null when unpaired
     * @return The UIDs to publish, de-duplicated, uploader first
     */
    fun entitled(userId: String, creatorUid: String?, partnerId: String?): List<String> =
        (listOf(userId) + listOfNotNull(creatorUid, partnerId))
            .filter { it.isNotBlank() }
            .distinct()
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew testDebugUnitTest --tests "com.coparently.app.data.sync.ChildInfoAudienceTest"
```

Expected: 6 tests PASS.

- [ ] **Step 5: Add the re-queue query**

In `app/src/main/java/com/coparently/app/data/local/dao/ChildInfoDao.kt`, add:

```kotlin
    /**
     * Re-queues this user's own child-info rows for upload, so their audience is recomputed.
     *
     * Mirrors `EventDao.markOwnEventsUnsynced`. Rows whose `createdByFirebaseUid` is null are
     * deliberately not matched: nothing distinguishes this user's un-stamped row from anybody
     * else's, and re-publishing a stranger's row under this user's audience would be worse than
     * leaving it alone.
     *
     * @param myUid Firebase UID of the signed-in user.
     * @return How many rows were re-queued.
     */
    @Query("UPDATE child_info SET syncedToFirestore = 0 WHERE createdByFirebaseUid = :myUid")
    suspend fun markOwnChildInfoUnsynced(myUid: String): Int
```

- [ ] **Step 6: Add the preference key**

In `app/src/main/java/com/coparently/app/data/local/preferences/PreferenceKeys.kt`, add beside
`EVENT_AUDIENCE_BACKFILL_PREFIX`:

```kotlin
    /**
     * Prefix for the per-user key recording which co-parent this device has already re-published
     * its own **child info** for — the actual key is this prefix plus the Firebase UID, and the
     * value is the partner's UID.
     *
     * Separate from [EVENT_AUDIENCE_BACKFILL_PREFIX] rather than shared with it: the two backfills
     * were introduced at different times, so on an install that already ran the events one a
     * shared key would read as "child info is done too" and skip it forever.
     *
     * The value is the partner's UID and never a boolean, for the reason spelled out on
     * [EVENT_AUDIENCE_BACKFILL_PREFIX]: a boolean never re-arms when the same two people pair
     * again.
     */
    const val CHILD_INFO_AUDIENCE_BACKFILL_PREFIX = "child_info_audience_backfill_"
```

- [ ] **Step 7: Publish the audience and arm the backfill**

In `app/src/main/java/com/coparently/app/data/sync/SyncService.kt`:

At the top of `syncChildInfo`, before the upload loop, resolve the partner once and run the
backfill — the partner is currently read *after* each upload, only for the notification:

```kotlin
    private suspend fun syncChildInfo(userId: String) {
        val partnerId = userDao.getUserById(userId)?.partnerId?.takeIf { it.isNotBlank() }
        backfillChildInfoAudienceForPartner(userId, partnerId)

        // Upload unsynced local child info
        val unsyncedChildInfo = childInfoDao.getUnsyncedChildInfo()
```

Replace the `"sharedWith"` entry in `childInfoData` — and delete the `SEPARATE CONCERN` comment
above it, which this change resolves:

```kotlin
                "sharedWith" to ChildInfoAudience.entitled(
                    userId = userId,
                    creatorUid = entity.createdByFirebaseUid,
                    partnerId = partnerId
                )
```

Then simplify the notification block below, which no longer needs its own lookup:

```kotlin
                if (partnerId != null && partnerId != userId) {
                    notifyChildInfoUpdate(partnerId, entity.id, entity.childName)
                }
```

Add the backfill beside `backfillAudienceForPartner`:

```kotlin
    /**
     * Re-publishes this user's own child info once per co-parent, so rows written before pairing
     * become readable by that co-parent.
     *
     * Without this, item 5 fails silently in the one case that matters most: a parent fills in
     * everything about their child, *then* invites the other parent, and the other parent sees an
     * empty screen with no error.
     *
     * Two rules are copied deliberately from [backfillAudienceForPartner] rather than simplified:
     *
     * - The marker stores the **partner's UID**, not a flag. A flag never re-arms when the same
     *   two people pair again after an unpair, and the pair then looks correctly linked while
     *   everything from before stays invisible to one of them.
     * - When unpaired the marker is **blanked**, not left alone. Leaving it naming an ex-partner
     *   means re-pairing with that same person finds it already equal and skips the backfill.
     *   `EncryptedPreferences` has no generic remove, and a blank value can never equal a real
     *   UID, so it re-arms exactly as an absent marker does.
     */
    private suspend fun backfillChildInfoAudienceForPartner(userId: String, partnerId: String?) {
        val key = "${PreferenceKeys.CHILD_INFO_AUDIENCE_BACKFILL_PREFIX}$userId"

        if (partnerId == null) {
            if (!encryptedPreferences.getString(key).isNullOrBlank()) {
                encryptedPreferences.putString(key, "")
            }
            return
        }
        if (encryptedPreferences.getString(key) == partnerId) return

        val requeued = childInfoDao.markOwnChildInfoUnsynced(userId)
        encryptedPreferences.putString(key, partnerId)
        Log.i(
            TAG,
            "Child-info audience backfill for $userId with partner $partnerId: " +
                "re-queued $requeued row(s)"
        )
    }
```

- [ ] **Step 8: Build and run the whole suite**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 9: Confirm the stale comment is gone**

```bash
grep -n "SEPARATE CONCERN" app/src/main/java/com/coparently/app/data/sync/SyncService.kt
```

Expected: **no output**. That comment described the gap this task closes; leaving it would tell the
next reader the opposite of the truth.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/coparently/app/data/sync/ app/src/main/java/com/coparently/app/data/local/dao/ChildInfoDao.kt app/src/main/java/com/coparently/app/data/local/preferences/PreferenceKeys.kt app/src/test/java/com/coparently/app/data/sync/ChildInfoAudienceTest.kt
git commit -m "feat(sync): let a co-parent read the child information the other entered"
```

---

### Task 6: Prove the rules agree, on the emulator

**Files:**
- Modify: `firestore-tests/rules/child-info.test.js`
- Modify: `firestore-tests/rules/users-profile.test.js`

**Interfaces:**
- Consumes: the document shapes Tasks 3, 4 and 5 write.
- Produces: nothing consumed by later tasks.

`firestore.rules` itself needs **no change**: `users/{userId}` already allows
`isOwner(userId) || isPartnerOf(userId)` to read and only the owner to write, and `child_info`
already gates on `sharedWith`. What is missing is proof, and CLAUDE.md is explicit that rules are
never debugged by deploying to production and watching a phone.

Read both files first and follow their existing harness style (`firestore-tests/harness.js`,
`fixtures/`). Do not invent a new setup helper.

- [ ] **Step 1: Add the child-info cases**

Append to `firestore-tests/rules/child-info.test.js`, inside its existing top-level `describe`:

```javascript
  it('lets a co-parent read a child_info document once they are in sharedWith', async () => {
    // The state the audience backfill produces: a row created before pairing, re-uploaded
    // afterwards with the co-parent included.
    await seedChildInfo('c1', {
      childName: 'Anya',
      createdByFirebaseUid: 'parent-a',
      sharedWith: ['parent-a', 'parent-b'],
    });

    await assertSucceeds(readChildInfoAs('parent-b', 'c1'));
  });

  it('keeps a child_info document private while sharedWith names only its creator', async () => {
    // The state every document is in today, before this branch: the pre-pairing upload.
    await seedChildInfo('c2', {
      childName: 'Anya',
      createdByFirebaseUid: 'parent-a',
      sharedWith: ['parent-a'],
    });

    await assertFails(readChildInfoAs('parent-b', 'c2'));
  });

  it('lets a co-parent in sharedWith add an emergency contact', async () => {
    // Item 5 says the second parent may add to the contacts. This is the document where that is
    // allowed - the parent's own users/{uid} is not, and must not become so.
    await seedChildInfo('c3', {
      childName: 'Anya',
      createdByFirebaseUid: 'parent-a',
      sharedWith: ['parent-a', 'parent-b'],
    });

    await assertSucceeds(
        updateChildInfoAs('parent-b', 'c3', {
          emergencyContacts: [{name: 'Grandma', relationship: 'grandmother', phone: '+420...'}],
        }),
    );
  });

  it('refuses to let a co-parent rewrite who created the document', async () => {
    await seedChildInfo('c4', {
      childName: 'Anya',
      createdByFirebaseUid: 'parent-a',
      sharedWith: ['parent-a', 'parent-b'],
    });

    await assertFails(
        updateChildInfoAs('parent-b', 'c4', {createdByFirebaseUid: 'parent-b'}),
    );
  });
```

Use whatever seed and read helpers the file already defines; the names above are illustrative and
must be replaced with the real ones.

- [ ] **Step 2: Add the users-profile cases**

Append to `firestore-tests/rules/users-profile.test.js`, inside its existing top-level `describe`:

```javascript
  it('lets a co-parent read the other parent\'s medical profile', async () => {
    // Deliberate: if something happens to one parent, the other can tell a paramedic the blood
    // group. The questionnaire exists for exactly this.
    await seedPairedUsers('parent-a', 'parent-b');
    await seedUserFields('parent-a', {
      phone: '+420123456789',
      medicalProfile: {bloodType: 'O_NEGATIVE', intolerances: ['lactose']},
    });

    await assertSucceeds(readUserAs('parent-b', 'parent-a'));
  });

  it('refuses to let a co-parent write the other parent\'s medical profile', async () => {
    // Load-bearing. Client writes to another user's document are what forced the permissive
    // firestore.rules.simple to be deployed once already.
    await seedPairedUsers('parent-a', 'parent-b');
    await seedUserFields('parent-a', {medicalProfile: {bloodType: 'O_NEGATIVE'}});

    await assertFails(
        updateUserAs('parent-b', 'parent-a', {medicalProfile: {bloodType: 'A_POSITIVE'}}),
    );
  });

  it('refuses a stranger who is nobody\'s co-parent', async () => {
    await seedUserFields('parent-a', {medicalProfile: {bloodType: 'O_NEGATIVE'}});

    await assertFails(readUserAs('stranger', 'parent-a'));
  });
```

- [ ] **Step 3: Run the rules suite**

```bash
cd firestore-tests && npm test
```

Expected: all tests pass, new ones included. This needs a **JDK 21+ on `PATH`**, not merely in
`JAVA_HOME` — see `firestore-tests/README.md`. If the emulator will not start, report that rather
than skipping the task: these cases are the only evidence the sharing works, and CLAUDE.md forbids
checking it by deploying to production.

- [ ] **Step 4: Commit**

```bash
git add firestore-tests/rules/child-info.test.js firestore-tests/rules/users-profile.test.js
git commit -m "test(rules): pin who can read a profile and who can only look"
```

---

### Task 7: The medical editor, and the child's form

**Files:**
- Create: `app/src/main/java/com/coparently/app/presentation/common/MedicalProfileEditor.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/childinfo/AddEditChildInfoScreen.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/childinfo/ChildInfoViewModel.kt`

**Interfaces:**
- Consumes: `MedicalProfile`, `Vaccination`, `BloodType`, `BloodType.labelRes()` and the `medical_*` strings (Task 1); `ChildInfo.medicalProfile` (Task 3).
- Produces, for Task 8: `@Composable fun MedicalProfileEditor(profile: MedicalProfile, onChange: (MedicalProfile) -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier)`.

One composable, three callers: the child's form here, the parent's profile in Task 8, and B2's
wizard later. It is stateless — it takes a `MedicalProfile` and emits a new one.

- [ ] **Step 1: Write the editor**

Create `app/src/main/java/com/coparently/app/presentation/common/MedicalProfileEditor.kt`.

Read `app/src/main/java/com/coparently/app/presentation/childinfo/components/AllergyEditor.kt`
first and follow its shape exactly — a labelled section, chips for existing entries with a remove
affordance, and a text field plus an add button. Reuse that structure for `intolerances` and
`hereditaryConditions` rather than inventing a second list idiom.

The signature is fixed; three callers depend on it (this task, Task 8, and B2's wizard later):

```kotlin
/**
 * Edits a [MedicalProfile], or renders one read-only.
 *
 * Stateless: it takes a profile and emits a new one, so the owning ViewModel stays the single
 * source of truth. One composable rather than a parent version and a child version, because a
 * paramedic asks the same questions of both.
 *
 * @param profile Current values
 * @param onChange Called with the whole updated profile on every edit
 * @param enabled False renders values with **no** editing affordance at all — used for the
 *   co-parent's profile, where `firestore.rules` refuses the write anyway
 * @param modifier Modifier for the container
 */
@Composable
fun MedicalProfileEditor(
    profile: MedicalProfile,
    onChange: (MedicalProfile) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 1. blood type   2. intolerances   3. hereditary conditions   4. vaccinations
    }
}
```

Each edit emits the whole profile through `copy()`, never a partially rebuilt one — for example
`onChange(profile.copy(intolerances = profile.intolerances + entry))`. The four blocks:

1. **Blood type** — a `FilterChip` row or an `ExposedDropdownMenuBox` over `BloodType.entries`,
   each rendered with `stringResource(it.labelRes())`, plus a "not recorded" option that sets
   `bloodType = null`. Never render `BloodType.name`.
2. **Intolerances** — string list, `AllergyEditor`'s shape, labelled
   `R.string.medical_intolerances_label` with `R.string.medical_intolerances_hint` as the field hint.
3. **Hereditary conditions** — the same, `R.string.medical_hereditary_label` /
   `R.string.medical_hereditary_hint`.
4. **Vaccinations** — a row per `Vaccination`: name, the date formatted with a `java.time`
   formatter in the default locale, or `R.string.medical_vaccination_no_date` when null, and a
   remove button. Below, a name field, an optional date picker
   (`presentation/childinfo/components/DatePickerDialog.kt` already exists — reuse it) and
   `R.string.medical_vaccination_add`.

Constraints for this file: every string through `stringResource`; colours from
`MaterialTheme.colorScheme`; `enabled = false` renders values without any editing affordance —
that is what Task 8's read-only co-parent view uses, and it must not merely disable buttons while
leaving text fields present.

- [ ] **Step 2: Wire it into the child's form**

In `AddEditChildInfoScreen.kt`, add a `MedicalProfileEditor` section below the existing allergies
section, bound to the screen's edit state.

**The event-editing rule applies here too.** CLAUDE.md records that rebuilding a model from scratch
on save once wiped `sharedWith`, `permissions` and `createdByFirebaseUid`. Read how this screen
saves: if it keeps a snapshot of the loaded `ChildInfo` and uses `copy()`, add `medicalProfile` to
that `copy()`. If it constructs a fresh `ChildInfo`, **do not follow that pattern** — convert it to
a snapshot-and-`copy()` save and say so in your report.

- [ ] **Step 3: Build and check the form saves the field**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Confirm no hardcoded text entered the new file**

```bash
grep -nE 'Text\(|label = |placeholder' app/src/main/java/com/coparently/app/presentation/common/MedicalProfileEditor.kt | grep '"'
```

Expected: **no output**.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/common/MedicalProfileEditor.kt app/src/main/java/com/coparently/app/presentation/childinfo/
git commit -m "feat(childinfo): edit the child's medical details"
```

---

### Task 8: A profile screen, used twice

**Files:**
- Create: `app/src/main/java/com/coparently/app/presentation/profile/ProfileScreen.kt`
- Create: `app/src/main/java/com/coparently/app/presentation/profile/ProfileViewModel.kt`
- Create: `app/src/main/res/values/profile_strings.xml` (+ `values-cs`, `values-de`, `values-ru`, `values-uk`)
- Modify: `app/src/main/java/com/coparently/app/presentation/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `User.dateOfBirth` / `phone` / `allergies` / `medicalProfile` (Task 4); `MedicalProfileEditor` (Task 7); `AllergyEditor`.
- Produces: routes `Screen.MyProfile` (`"my_profile"`) and `Screen.CoParentProfile` (`"coparent_profile"`).

Spec §8.5: there is no parent profile screen today — Settings' Account group holds only
`SignedInAsRow` and sign-out. This adds one composable used in two modes.

The co-parent's view is **read-only**, and not as a courtesy: `firestore.rules` refuses the write
(Task 6 pins it). An editable co-parent screen would promise a feature the server rejects — the
defect the August 2026 refresh's rule 8 forbids.

- [ ] **Step 1: Add the strings in all five locales**

Create `app/src/main/res/values/profile_strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="profile_my_title">My details</string>
    <string name="profile_coparent_title">Co-parent</string>
    <string name="profile_name_label">Name</string>
    <string name="profile_dob_label">Date of birth</string>
    <string name="profile_phone_label">Phone</string>
    <string name="profile_allergies_label">Allergies</string>
    <string name="profile_save">Save</string>
    <string name="profile_saved">Saved</string>
    <string name="profile_readonly_note">Only %1$s can change these details</string>
    <string name="profile_coparent_empty">Your co-parent has not filled this in yet</string>
    <string name="profile_not_paired">Link a co-parent to see their details</string>
    <string name="settings_my_profile_title">My details</string>
    <string name="settings_my_profile_description">Your contact and medical details</string>
    <string name="settings_coparent_profile_title">Co-parent details</string>
    <string name="settings_coparent_profile_description">What your co-parent has shared</string>
</resources>
```

Create the same sixteen keys in `values-cs`, `values-de`, `values-ru` and `values-uk`. Russian, for
reference:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="profile_my_title">Мои данные</string>
    <string name="profile_coparent_title">Второй родитель</string>
    <string name="profile_name_label">Имя</string>
    <string name="profile_dob_label">Дата рождения</string>
    <string name="profile_phone_label">Телефон</string>
    <string name="profile_allergies_label">Аллергии</string>
    <string name="profile_save">Сохранить</string>
    <string name="profile_saved">Сохранено</string>
    <string name="profile_readonly_note">Эти данные может менять только %1$s</string>
    <string name="profile_coparent_empty">Второй родитель пока ничего не заполнил</string>
    <string name="profile_not_paired">Свяжитесь со вторым родителем, чтобы увидеть его данные</string>
    <string name="settings_my_profile_title">Мои данные</string>
    <string name="settings_my_profile_description">Ваши контакты и медицинские данные</string>
    <string name="settings_coparent_profile_title">Данные второго родителя</string>
    <string name="settings_coparent_profile_description">Что второй родитель указал о себе</string>
</resources>
```

For Czech, German and Ukrainian, follow each file's existing register — German already uses
informal "du", Czech and Ukrainian use polite plural imperatives. Check a neighbouring
`values-<locale>/settings_account_strings.xml` before writing.

- [ ] **Step 2: Write the ViewModel**

Create `app/src/main/java/com/coparently/app/presentation/profile/ProfileViewModel.kt`. The
contract, which Task 8's screen and B2's wizard both bind to:

```kotlin
/**
 * State of a profile screen — the signed-in parent's own, and the co-parent's.
 *
 * @property me The signed-in user, or null before the first load
 * @property coParent The co-parent's profile, or null when unpaired or not yet loaded
 * @property isSaving Whether a save is in flight
 * @property savedAt Epoch millis of the last successful save, for a transient confirmation
 */
data class ProfileUiState(
    val me: User? = null,
    val coParent: User? = null,
    val isSaving: Boolean = false,
    val savedAt: Long? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository
) : ViewModel() {
    val uiState: StateFlow<ProfileUiState>

    fun updateName(name: String)
    fun updateDateOfBirth(date: LocalDate?)
    fun updatePhone(phone: String)
    fun updateAllergies(allergies: List<String>)
    fun updateMedicalProfile(profile: MedicalProfile)
    fun save()
}
```

`savedAt` is epoch millis, not a `LocalDateTime`: it is an instant, and CLAUDE.md's chat-sync entry
records why that distinction is worth keeping even for a value that is only displayed.

Follow the project's `UiState` sealed-class pattern (see `presentation/common/UiState.kt`), inject
`UserRepository` and `PairingRepository` via Hilt, and use `ParentsSource` /
`PairingRepository.observePairingState` to resolve the co-parent — **not**
`userRepository.getAllUsers()`. CLAUDE.md is explicit: only the signed-in user has a Room `users`
row, so that call can never answer "who is the other parent", and on a device where two accounts
have signed in over time it returns rows for accounts that are not paired with anyone.

Read the co-parent's profile from their `users/{uid}` Firestore document, **not** by extending
`PartnerSummary` — that model exists to name a person in a chat header, and hanging seven medical
fields on it would load them on every screen that wants a name.

- [ ] **Step 3: Write the screen**

Create `app/src/main/java/com/coparently/app/presentation/profile/ProfileScreen.kt`:

```kotlin
@Composable
fun ProfileScreen(
    editable: Boolean,
    onNavigateUp: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
)
```

`editable = true` renders the signed-in user's own record with fields and a save button;
`editable = false` renders the co-parent's, read-only, with
`stringResource(R.string.profile_readonly_note, coParentName)` explaining why. Both use
`SectionGroup` / `SectionRow` from `presentation/common/DesignSystem.kt`, and both delegate the
medical block to `MedicalProfileEditor(profile, onChange, enabled = editable)`.

Empty states: `profile_coparent_empty` when the co-parent exists but has filled nothing;
`profile_not_paired` when there is no co-parent at all.

- [ ] **Step 4: Add the routes and the Settings rows**

In `NavGraph.kt`, add to the `Screen` sealed class:

```kotlin
    data object MyProfile : Screen("my_profile")
    data object CoParentProfile : Screen("coparent_profile")
```

and two `composable(...)` blocks calling `ProfileScreen(editable = true, …)` and
`ProfileScreen(editable = false, …)`, each with `onNavigateUp = navController::popBackStack`.
Both are detail screens: the bottom bar hides automatically because neither route is in
`BottomNavDestination.topLevelRoutes` — do not add them there.

In `SettingsScreen.kt`, add two `SectionRow`s to the **Family** group (Family, Sync, App, Account
is the fixed order; Family is first because it is the product), each with a `Chevron()` trailing
and the haptic call the neighbouring rows already make. Family is where the co-parent and the
child already live.

- [ ] **Step 5: Verify locale completeness**

```bash
for k in $(grep -o 'name="[a-z_]*"' app/src/main/res/values/profile_strings.xml | sed 's/name="//;s/"//'); do printf "%-38s %s\n" "$k" "$(git grep -l "name=\"$k\"" -- 'app/src/main/res/values*/*.xml' | wc -l)"; done
```

Expected: every key prints `5`.

- [ ] **Step 6: Build and run the whole suite**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/profile/ app/src/main/res/values*/profile_strings.xml app/src/main/java/com/coparently/app/presentation/navigation/NavGraph.kt app/src/main/java/com/coparently/app/presentation/settings/SettingsScreen.kt
git commit -m "feat(profiles): a screen for your own details, and a read-only one for your co-parent's"
```

---

### Task 9: The education section stops saying "school"

**Files:**
- Modify: `app/src/main/res/values/childinfo_strings.xml` (+ the four locale variants)
- Modify: `app/src/main/res/values/strings.xml` (+ the four locale variants)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. Values change; **key names do not**.

Item 18 asks for the broader term, because a child may be at a nursery or a college. The phrase the
item quotes does not exist in the app; the label today is "School Information".

**Key names stay exactly as they are.** `childinfo_section_school` keeps its name even though its
value no longer says "school" — renaming touches five files plus two call sites and buys nothing a
reader of the value cannot see. `SchoolInfo` and `schoolInfoJson` keep theirs for the stronger
reason: `schoolInfo` is a field in the `child_info` Firestore document and a co-parent on an older
build must keep reading it.

- [ ] **Step 1: Change the five values in `childinfo_strings.xml`**

| Key | `values` | `values-ru` |
|---|---|---|
| `childinfo_section_school` | Place of study | Учебное заведение |
| `childinfo_add_school_info` | Add place of study | Добавить учебное заведение |
| `childinfo_school_name_label` | Institution name | Название учебного заведения |
| `childinfo_school_phone_optional_label` | Institution phone (optional) | Телефон заведения (необязательно) |

Czech: `Místo studia`, `Přidat místo studia`, `Název instituce`, `Telefon instituce (nepovinné)`.
German: `Bildungseinrichtung`, `Bildungseinrichtung hinzufügen`, `Name der Einrichtung`,
`Telefon der Einrichtung (optional)`.
Ukrainian: `Навчальний заклад`, `Додати навчальний заклад`, `Назва навчального закладу`,
`Телефон закладу (необовʼязково)`.

Leave `childinfo_address_optional_label`, `childinfo_grade_optional_label`,
`childinfo_grade_placeholder`, `childinfo_teacher_name_optional_label` and
`childinfo_teacher_email_optional_label` alone — none of them says "school".

- [ ] **Step 2: Delete the dead duplicate**

`child_info_school` in `strings.xml` duplicates `childinfo_section_school` and has **zero**
references. Delete it from all five `strings.xml` files.

```bash
grep -rn "R.string.child_info_school\b" app/src/main/java --include=*.kt
```

Expected: **no output** — confirm before deleting.

- [ ] **Step 3: Confirm the live key still resolves in five locales**

```bash
for k in childinfo_section_school childinfo_add_school_info childinfo_school_name_label childinfo_school_phone_optional_label; do printf "%-46s %s\n" "$k" "$(git grep -l "name=\"$k\"" -- 'app/src/main/res/values*/*.xml' | wc -l)"; done
echo "--- the deleted one must now be 0 ---"
git grep -l 'name="child_info_school"' -- 'app/src/main/res/values*/*.xml' | wc -l
```

Expected: the first four print `5`; the last prints `0`.

- [ ] **Step 4: Build**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. A failure here means something did reference `child_info_school`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values*/childinfo_strings.xml app/src/main/res/values*/strings.xml
git commit -m "feat(childinfo): call it a place of study, not a school"
```

---

### Task 10: The child's information opens on tap

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/childinfo/ChildInfoScreen.kt`

**Interfaces:**
- Consumes: `ChildInfo.medicalProfile` (Task 3); the `medical_*` strings (Task 1).
- Produces: nothing.

Item 19. The screen renders non-clickable `Card { … }` blocks and hides editing behind a pencil in
the top bar. It is the last of the seven main screens still on the card-per-row shape the August
2026 refresh calls "double surfaces".

- [ ] **Step 1: Convert the content to the design system**

In `ChildInfoContent`, replace each `Card` + `SectionHeader` pair with `GroupLabel` +
`SectionGroup { SectionRow(...) }` from `presentation/common/DesignSystem.kt`. `SectionGroup`
inserts dividers itself — do not add any. `SectionRow` takes **at most one** trailing control.

Signatures, so you do not have to guess:

```kotlin
fun GroupLabel(text: String, modifier: Modifier = Modifier)
fun SectionGroup(modifier: Modifier = Modifier, content: @Composable SectionGroupScope.() -> Unit)
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
    trailing: @Composable (() -> Unit)? = null
)
```

Add a **medical details** group rendering `childInfo.medicalProfile`: blood type via
`stringResource(bloodType.labelRes())` or `R.string.medical_blood_type_not_set`, and the three
lists, each hidden when empty. Do not render `BloodType.name`.

Delete the local `SectionHeader`, `MedicationCard`, `ActivityCard`, `EmergencyContactCard` and
`SchoolInfoCard` composables once nothing calls them.

- [ ] **Step 2: Make the sections open the editor, and remove the pencil**

Give each `SectionRow` an `onClick` that calls `onEditClick(childInfo.id)`, with the haptic the
other screens use (`haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)` — the light
one for navigation, not `LongPress`).

Remove the `Edit` `IconButton` from the `TopAppBar` `actions`. **Keep** the `Refresh` one — it is
a different action and still the only manual sync trigger.

The `onEditClick` parameter and the screen's signature do not change, so `NavGraph` needs no edit.

- [ ] **Step 3: Confirm the old shape is gone**

```bash
grep -nE "Card\(|SectionHeader\(" app/src/main/java/com/coparently/app/presentation/childinfo/ChildInfoScreen.kt
```

Expected: **no output**.

```bash
grep -n "Icons.Default.Edit" app/src/main/java/com/coparently/app/presentation/childinfo/ChildInfoScreen.kt
```

Expected: **no output**.

- [ ] **Step 4: Build and run the whole suite**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/childinfo/ChildInfoScreen.kt
git commit -m "feat(childinfo): open a section by tapping it, not by hunting for a pencil"
```

---

### Task 11: Full verification

**Files:** none changed unless a check fails.

- [ ] **Step 1: Clean build, tests, lint and detekt**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew clean assembleDebug testDebugUnitTest lint detekt
```

Expected: BUILD SUCCESSFUL for assemble and tests. For `detekt` and `lint`, report whether any
finding names a file this branch changed (`git diff --name-only main..HEAD`) — the exit code is
red from 17 pre-existing findings either way. Do not add anything to
`app/config/detekt/baseline.xml`.

- [ ] **Step 2: Rules suite**

```bash
cd firestore-tests && npm test && npm run lint
```

Expected: all pass. Needs a JDK 21+ on `PATH`.

- [ ] **Step 3: Cloud Functions untouched**

```bash
git diff --name-only main..HEAD -- functions/
```

Expected: **no output**. The unpair sweep already covers `child_info`; this branch adds no
server-side change, and one appearing here means something drifted out of scope.

- [ ] **Step 4: Locale completeness across every new and renamed key**

```bash
for f in app/src/main/res/values/medical_strings.xml app/src/main/res/values/profile_strings.xml; do for k in $(grep -o 'name="[a-z_]*"' $f | sed 's/name="//;s/"//'); do n=$(git grep -l "name=\"$k\"" -- 'app/src/main/res/values*/*.xml' | wc -l); [ "$n" = "5" ] || echo "MISSING: $k -> $n"; done; done
echo "--- done; any line above is a gap ---"
```

Expected: only the `done` line.

- [ ] **Step 5: The migration, if hardware allows**

```bash
adb devices
```

If a device or emulator is listed:

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew connectedDebugAndroidTest --tests "com.coparently.app.data.local.CoPlanlyDatabaseMigrationTest"
```

If none is listed, **state plainly in your report that the migration is unverified.** There is no
Robolectric here; a green JVM suite says nothing about whether 13→14 runs. This is the one part of
B1 that a plain `./gradlew` run cannot sign off.

- [ ] **Step 6: Report**

Summarise: the build result, the JVM test total, the rules test total, whether detekt or lint
names any changed file, the locale check, and whether the migration test ran or was skipped for
lack of a device.

---

## Notes for the reviewer

**What this package does not do,** all recorded in spec §9: the first-run wizard and its
disclaimer (items 3 and 4, package B2); encryption at rest for the new medical fields, which
`EncryptionManager` and the unused `SensitiveMedicalData` model would support but which cannot be
queried or conflict-resolved once on; and any Cloud Function change.

**Two asymmetries are deliberate and will look like oversights.** `ChildInfo.dateOfBirth` stays a
`LocalDateTime` while `User.dateOfBirth` is a `LocalDate` — changing the former means converting
stored values, which the whole additive-migration design exists to avoid; it joins the four
naive-local-time fields CLAUDE.md already tracks. And `allergies` sits outside `MedicalProfile` on
both models, for the reason in spec §4.

**The audience derivation differs from the events one on purpose.** `ChildInfoAudience.entitled`
does not intersect with a stored list because `ChildInfoEntity` has no `sharedWith` column. Adding
one to "match events" would introduce the staleness that the events intersection exists to defend
against.
