# Receipt OCR & App Currency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a parent photograph a receipt and have the Add Expense form pre-filled with the total, currency, merchant, date and category, and give the app a real currency concept instead of hardcoded USD.

**Architecture:** ML Kit's bundled Latin text recognizer runs on-device behind a domain interface (`ReceiptTextRecognizer`), so no photo leaves the phone and the domain layer stays free of Android types. A pure-Kotlin `ReceiptParser` turns recognised lines into a `ReceiptScan`; all fragile heuristics live there and are covered by JVM unit tests. Currency becomes a first-class value: a region-seeded default persisted through `PreferencesRepository`, overridable per expense.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (Material 3 / BOM 2025.10), Hilt 2.56.2, ML Kit `text-recognition:16.0.1` (bundled Latin model), JUnit 4 + `kotlin.test` + MockK for unit tests.

**Spec:** `docs/superpowers/specs/2026-07-30-receipt-ocr-design.md`

## Global Constraints

- **Build commands must set JAVA_HOME first** — the system one is broken on this machine:
  `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug`
- **Jetpack Compose only** — never add XML layouts. (`res/xml/file_paths.xml` is a
  FileProvider config file, not a layout, and is allowed.)
- **Stateless composables** — state lives in ViewModels as `StateFlow`; UI receives values
  and callbacks.
- **minSdk = 26** — no `java.time` APIs added after API 26 (`LocalDate.ofInstant` is API 34+;
  use `Instant.atZone(...).toLocalDate()`).
- **KDoc on public classes/functions; all code and comments in English.**
- **User-facing strings go in tracked, feature-named `res/values/*.xml` files** —
  never in `res/values/strings.xml`, which is gitignored in this repo. This plan adds
  `currency_strings.xml` and `receipt_ocr_strings.xml`. (Spec §8 named a single file;
  two feature-named files match the repo convention better and keep the currency work
  usable on its own.)
- **Conventional Commits** — `feat:`, `fix:`, `test:`, `refactor:`, `docs:`.
- **Material 3 components and theme tokens** from `presentation/theme/`.
- No Room entity, schema or migration changes anywhere in this plan.

---

## File Structure

**Created**

| File | Responsibility |
| --- | --- |
| `app/src/main/java/com/coparently/app/domain/money/SupportedCurrency.kt` | Currency enum + region→currency mapping (pure) |
| `app/src/test/java/com/coparently/app/domain/money/SupportedCurrencyTest.kt` | Tests for the region mapping |
| `app/src/main/java/com/coparently/app/domain/receipts/ReceiptScan.kt` | Result model + `ReceiptTextRecognizer` interface |
| `app/src/main/java/com/coparently/app/domain/receipts/ReceiptParser.kt` | All parsing heuristics (pure) |
| `app/src/test/java/com/coparently/app/domain/receipts/ReceiptParserMoneyTest.kt` | Money parsing and total detection tests |
| `app/src/test/java/com/coparently/app/domain/receipts/ReceiptParserDateCurrencyTest.kt` | Currency and date detection tests |
| `app/src/test/java/com/coparently/app/domain/receipts/ReceiptParserFullTest.kt` | End-to-end parse tests on realistic receipts |
| `app/src/main/java/com/coparently/app/data/mlkit/MlKitReceiptTextRecognizer.kt` | Thin ML Kit wrapper |
| `app/src/main/java/com/coparently/app/di/ReceiptModule.kt` | Binds the recognizer |
| `app/src/main/java/com/coparently/app/presentation/expenses/CurrencySelector.kt` | Compact currency dropdown used by the expense form |
| `app/src/main/res/values/currency_strings.xml` | Currency strings |
| `app/src/main/res/values/receipt_ocr_strings.xml` | Receipt-scanning strings |
| `app/src/main/res/xml/file_paths.xml` | FileProvider paths for camera captures |
| `app/src/test/java/com/coparently/app/data/repository/PreferencesRepositoryCurrencyTest.kt` | Default-currency resolution tests |

**Modified**

| File | Change |
| --- | --- |
| `app/build.gradle.kts:193` | Add the ML Kit text-recognition dependency |
| `app/src/main/AndroidManifest.xml` | Add the FileProvider |
| `app/src/main/java/com/coparently/app/data/local/preferences/EncryptedPreferences.kt` | Store/read the default currency code |
| `app/src/main/java/com/coparently/app/domain/repository/PreferencesRepository.kt` | Default-currency flow + setter |
| `app/src/main/java/com/coparently/app/data/repository/PreferencesRepositoryImpl.kt` | Region-seeded resolution |
| `app/src/main/java/com/coparently/app/presentation/settings/SettingsViewModel.kt` | Expose and set the default currency |
| `app/src/main/java/com/coparently/app/presentation/settings/SettingsScreen.kt` | Default-currency card + picker dialog |
| `app/src/main/java/com/coparently/app/presentation/expenses/ExpenseViewModel.kt` | `currency` param, default-currency flow, receipt scanning |
| `app/src/main/java/com/coparently/app/presentation/expenses/AddExpenseScreen.kt` | Currency selector, date field, camera capture, auto-fill + Undo |
| `app/src/main/java/com/coparently/app/presentation/expenses/BudgetItem.kt:38-39` | Use the tolerant `currencyFormat()` helper |

---

### Task 1: Currency domain model

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/money/SupportedCurrency.kt`
- Test: `app/src/test/java/com/coparently/app/domain/money/SupportedCurrencyTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class SupportedCurrency(val code: String, val symbol: String)` with entries
  `CZK, EUR, PLN, USD, GBP, CHF, HUF, SEK, DKK, NOK`; `SupportedCurrency.DEFAULT: SupportedCurrency`;
  `SupportedCurrency.fromCode(code: String?): SupportedCurrency?`;
  top-level `fun defaultCurrencyForRegion(countryCode: String): SupportedCurrency`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/domain/money/SupportedCurrencyTest.kt`:

```kotlin
package com.coparently.app.domain.money

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests for the region -> currency mapping behind the app's default currency. */
class SupportedCurrencyTest {

    @Test
    fun `maps the czech region to crowns`() {
        assertEquals(SupportedCurrency.CZK, defaultCurrencyForRegion("CZ"))
    }

    @Test
    fun `maps poland to zloty`() {
        assertEquals(SupportedCurrency.PLN, defaultCurrencyForRegion("PL"))
    }

    @Test
    fun `maps eurozone countries to euro`() {
        assertEquals(SupportedCurrency.EUR, defaultCurrencyForRegion("DE"))
        assertEquals(SupportedCurrency.EUR, defaultCurrencyForRegion("ES"))
        assertEquals(SupportedCurrency.EUR, defaultCurrencyForRegion("SK"))
    }

    @Test
    fun `accepts a lowercase country code`() {
        assertEquals(SupportedCurrency.CZK, defaultCurrencyForRegion("cz"))
    }

    @Test
    fun `falls back to crowns for an unknown or empty region`() {
        assertEquals(SupportedCurrency.CZK, defaultCurrencyForRegion("ZZ"))
        assertEquals(SupportedCurrency.CZK, defaultCurrencyForRegion(""))
    }

    @Test
    fun `resolves a stored code back to an entry`() {
        assertEquals(SupportedCurrency.EUR, SupportedCurrency.fromCode("EUR"))
        assertNull(SupportedCurrency.fromCode("XYZ"))
        assertNull(SupportedCurrency.fromCode(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.money.SupportedCurrencyTest"
```

Expected: FAIL — compilation error, `SupportedCurrency` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/coparently/app/domain/money/SupportedCurrency.kt`:

```kotlin
package com.coparently.app.domain.money

/**
 * Currencies the app offers when logging an expense or a budget.
 *
 * @property code ISO 4217 code stored on [com.coparently.app.domain.model.Expense]
 * @property symbol Short symbol for compact UI where a full code would not fit
 */
enum class SupportedCurrency(val code: String, val symbol: String) {
    CZK("CZK", "Kč"),
    EUR("EUR", "€"),
    PLN("PLN", "zł"),
    USD("USD", "$"),
    GBP("GBP", "£"),
    CHF("CHF", "CHF"),
    HUF("HUF", "Ft"),
    SEK("SEK", "kr"),
    DKK("DKK", "kr"),
    NOK("NOK", "kr");

    companion object {
        /** Used when nothing is stored and the region is unknown; the app's primary market. */
        val DEFAULT: SupportedCurrency = CZK

        /**
         * Resolves a stored ISO code back to an entry.
         *
         * @param code ISO 4217 code, or null when nothing has been stored yet
         * @return The matching entry, or null when the code is unknown
         */
        fun fromCode(code: String?): SupportedCurrency? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Picks a sensible currency for a device region, e.g. Czechia gets crowns and Poland zloty.
 *
 * @param countryCode ISO 3166-1 alpha-2 country code; case-insensitive, may be empty
 * @return The region's currency, or [SupportedCurrency.DEFAULT] when it is not mapped
 */
fun defaultCurrencyForRegion(countryCode: String): SupportedCurrency =
    when (countryCode.uppercase()) {
        "CZ" -> SupportedCurrency.CZK
        "PL" -> SupportedCurrency.PLN
        "DE", "ES", "AT", "SK", "FR", "IT", "NL", "BE", "PT", "IE", "FI", "GR" ->
            SupportedCurrency.EUR
        "US" -> SupportedCurrency.USD
        "GB" -> SupportedCurrency.GBP
        "CH" -> SupportedCurrency.CHF
        "HU" -> SupportedCurrency.HUF
        "SE" -> SupportedCurrency.SEK
        "DK" -> SupportedCurrency.DKK
        "NO" -> SupportedCurrency.NOK
        else -> SupportedCurrency.DEFAULT
    }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.money.SupportedCurrencyTest"
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/domain/money app/src/test/java/com/coparently/app/domain/money && git commit -m "feat(money): add supported currencies and region defaults"
```

---

### Task 2: Persist the default currency

**Files:**
- Modify: `app/src/main/java/com/coparently/app/data/local/preferences/EncryptedPreferences.kt`
- Modify: `app/src/main/java/com/coparently/app/domain/repository/PreferencesRepository.kt`
- Modify: `app/src/main/java/com/coparently/app/data/repository/PreferencesRepositoryImpl.kt`
- Test: `app/src/test/java/com/coparently/app/data/repository/PreferencesRepositoryCurrencyTest.kt`

**Interfaces:**
- Consumes: `SupportedCurrency`, `SupportedCurrency.fromCode`, `defaultCurrencyForRegion` (Task 1).
- Produces: `PreferencesRepository.getDefaultCurrencyFlow(): Flow<SupportedCurrency>` and
  `suspend fun setDefaultCurrency(currency: SupportedCurrency)`;
  `EncryptedPreferences.putDefaultCurrency(code: String)` / `getDefaultCurrency(): String?`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/data/repository/PreferencesRepositoryCurrencyTest.kt`:

```kotlin
package com.coparently.app.data.repository

import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.domain.money.SupportedCurrency
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

/** Tests for the region-seeded default currency preference. */
class PreferencesRepositoryCurrencyTest {

    private fun prefs(storedCurrency: String?): EncryptedPreferences = mockk(relaxed = true) {
        every { getDarkTheme() } returns null
        every { getDefaultCurrency() } returns storedCurrency
    }

    private fun withLocale(language: String, country: String, block: () -> Unit) {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.Builder().setLanguage(language).setRegion(country).build())
        try {
            block()
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `seeds the currency from the device region when nothing is stored`() {
        withLocale("cs", "CZ") {
            val preferences = prefs(storedCurrency = null)
            val repository = PreferencesRepositoryImpl(preferences)

            runBlocking {
                assertEquals(SupportedCurrency.CZK, repository.getDefaultCurrencyFlow().first())
            }
            verify { preferences.putDefaultCurrency("CZK") }
        }
    }

    @Test
    fun `prefers the stored currency over the device region`() {
        withLocale("cs", "CZ") {
            val repository = PreferencesRepositoryImpl(prefs(storedCurrency = "EUR"))

            runBlocking {
                assertEquals(SupportedCurrency.EUR, repository.getDefaultCurrencyFlow().first())
            }
        }
    }

    @Test
    fun `ignores an unrecognised stored code and reseeds from the region`() {
        withLocale("pl", "PL") {
            val repository = PreferencesRepositoryImpl(prefs(storedCurrency = "XYZ"))

            runBlocking {
                assertEquals(SupportedCurrency.PLN, repository.getDefaultCurrencyFlow().first())
            }
        }
    }

    @Test
    fun `setting the currency persists it and updates the flow`() {
        withLocale("cs", "CZ") {
            val preferences = prefs(storedCurrency = null)
            val repository = PreferencesRepositoryImpl(preferences)

            runBlocking {
                repository.setDefaultCurrency(SupportedCurrency.EUR)
                assertEquals(SupportedCurrency.EUR, repository.getDefaultCurrencyFlow().first())
            }
            verify { preferences.putDefaultCurrency("EUR") }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.data.repository.PreferencesRepositoryCurrencyTest"
```

Expected: FAIL — `getDefaultCurrency`, `putDefaultCurrency`, `getDefaultCurrencyFlow` unresolved.

- [ ] **Step 3: Add the preference accessors**

In `EncryptedPreferences.kt`, add these methods next to the dark-theme ones (before `clear()`):

```kotlin
    /**
     * Stores the app-wide default currency.
     *
     * @param code ISO 4217 currency code, e.g. "CZK"
     */
    fun putDefaultCurrency(code: String) {
        encryptedPreferences.edit()
            .putString(KEY_DEFAULT_CURRENCY, code)
            .apply()
    }

    /**
     * Retrieves the stored default currency code.
     *
     * @return The ISO 4217 code, or null when the user has never had one resolved
     */
    fun getDefaultCurrency(): String? {
        return encryptedPreferences.getString(KEY_DEFAULT_CURRENCY, null)
    }
```

And add to the `companion object`:

```kotlin
        private const val KEY_DEFAULT_CURRENCY = "default_currency"
```

- [ ] **Step 4: Extend the repository interface**

In `PreferencesRepository.kt`, add the import `com.coparently.app.domain.money.SupportedCurrency`
and these members inside the interface:

```kotlin
    /**
     * Gets the app-wide default currency as a Flow. Never emits null — when nothing has been
     * stored the region's currency is resolved and persisted on first read.
     *
     * @return Flow emitting the current default currency
     */
    fun getDefaultCurrencyFlow(): Flow<SupportedCurrency>

    /**
     * Sets the app-wide default currency used to pre-fill new expenses.
     *
     * @param currency Currency to store as the default
     */
    suspend fun setDefaultCurrency(currency: SupportedCurrency)
```

- [ ] **Step 5: Implement the resolution**

In `PreferencesRepositoryImpl.kt`, add imports:

```kotlin
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.money.defaultCurrencyForRegion
import java.util.Locale
```

Add the backing flow next to `_darkThemeFlow` and the two overrides:

```kotlin
    private val _defaultCurrencyFlow = MutableStateFlow(resolveInitialCurrency())

    /**
     * Reads the stored currency, or resolves one from the device region and persists it so the
     * choice stays stable even if the device locale later changes.
     */
    private fun resolveInitialCurrency(): SupportedCurrency {
        SupportedCurrency.fromCode(encryptedPreferences.getDefaultCurrency())?.let { return it }
        val resolved = defaultCurrencyForRegion(Locale.getDefault().country)
        encryptedPreferences.putDefaultCurrency(resolved.code)
        return resolved
    }

    override fun getDefaultCurrencyFlow(): Flow<SupportedCurrency> =
        _defaultCurrencyFlow.asStateFlow()

    override suspend fun setDefaultCurrency(currency: SupportedCurrency) {
        encryptedPreferences.putDefaultCurrency(currency.code)
        _defaultCurrencyFlow.value = currency
    }
```

Note: `_defaultCurrencyFlow` is a property initialiser, so it runs before the existing `init`
block — that is fine, the two preferences are independent.

- [ ] **Step 6: Run tests to verify they pass**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.data.repository.PreferencesRepositoryCurrencyTest"
```

Expected: PASS, 4 tests.

- [ ] **Step 7: Build to catch any other implementer of the interface**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. If a fake/test double implements `PreferencesRepository`, add the
two new members there too.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/coparently/app/data/local/preferences/EncryptedPreferences.kt app/src/main/java/com/coparently/app/domain/repository/PreferencesRepository.kt app/src/main/java/com/coparently/app/data/repository/PreferencesRepositoryImpl.kt app/src/test/java/com/coparently/app/data/repository/PreferencesRepositoryCurrencyTest.kt && git commit -m "feat(money): persist a region-seeded default currency"
```

---

### Task 3: Default currency in Settings

**Files:**
- Create: `app/src/main/res/values/currency_strings.xml`
- Modify: `app/src/main/java/com/coparently/app/presentation/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `PreferencesRepository.getDefaultCurrencyFlow()` / `setDefaultCurrency` (Task 2),
  `SupportedCurrency` (Task 1).
- Produces: `SettingsViewModel.defaultCurrency: StateFlow<SupportedCurrency>` and
  `SettingsViewModel.setDefaultCurrency(currency: SupportedCurrency)`.

This task has no unit test: it is Compose wiring over an already-tested repository, and the
project has no Compose test harness for Settings beyond the existing instrumented test.
Verification is the debug build plus a manual check.

- [ ] **Step 1: Add the strings**

Create `app/src/main/res/values/currency_strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Currency selection strings. Kept out of strings.xml because strings.xml is
     gitignored in this project (it holds local OAuth values). -->
<resources>
    <string name="currency_settings_title">Currency</string>
    <string name="currency_settings_default">Default currency</string>
    <string name="currency_settings_description">Used for new expenses. You can change it on a single expense.</string>
    <string name="currency_picker_title">Choose default currency</string>
    <string name="currency_picker_cancel">Cancel</string>
    <string name="currency_selector_label">Currency</string>
</resources>
```

- [ ] **Step 2: Expose the currency from the ViewModel**

In `SettingsViewModel.kt`, add imports:

```kotlin
import com.coparently.app.domain.money.SupportedCurrency
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
```

Add after `darkThemeFlow`:

```kotlin
    /** App-wide default currency for new expenses. */
    val defaultCurrency: StateFlow<SupportedCurrency> =
        preferencesRepository.getDefaultCurrencyFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SupportedCurrency.DEFAULT)

    /**
     * Stores a new app-wide default currency.
     *
     * @param currency Currency to use for expenses created from now on
     */
    fun setDefaultCurrency(currency: SupportedCurrency) {
        viewModelScope.launch { preferencesRepository.setDefaultCurrency(currency) }
    }
```

- [ ] **Step 3: Add the Settings card**

In `SettingsScreen.kt`, add imports:

```kotlin
import com.coparently.app.domain.money.SupportedCurrency
```

Add state next to the other `collectAsState` calls:

```kotlin
    val defaultCurrency by settingsViewModel.defaultCurrency.collectAsState()
    var showCurrencyPicker by remember { mutableStateOf(false) }
```

Add this card inside the scrolling `Column`, directly after the theme card:

```kotlin
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Payments,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.currency_settings_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.currency_settings_default))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.currency_settings_description))
                        },
                        trailingContent = {
                            TextButton(onClick = { showCurrencyPicker = true }) {
                                Text("${defaultCurrency.code} ${defaultCurrency.symbol}")
                            }
                        }
                    )
                }
            }
```

Add the dialog at the end of the `Column`'s content (still inside the `Scaffold` body):

```kotlin
            if (showCurrencyPicker) {
                AlertDialog(
                    onDismissRequest = { showCurrencyPicker = false },
                    title = { Text(stringResource(R.string.currency_picker_title)) },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            SupportedCurrency.entries.forEach { currency ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            settingsViewModel.setDefaultCurrency(currency)
                                            showCurrencyPicker = false
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    RadioButton(
                                        selected = currency == defaultCurrency,
                                        onClick = {
                                            settingsViewModel.setDefaultCurrency(currency)
                                            showCurrencyPicker = false
                                        }
                                    )
                                    Text(
                                        text = "${currency.code}  ${currency.symbol}",
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showCurrencyPicker = false }) {
                            Text(stringResource(R.string.currency_picker_cancel))
                        }
                    }
                )
            }
```

`clickable` needs `import androidx.compose.foundation.clickable`. The wildcard imports already
in this file cover `Row`, `Card`, `ListItem`, `AlertDialog`, `RadioButton`, `TextButton` and
`Icons.Default.Payments`.

- [ ] **Step 4: Build and verify**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. Install and open Settings (gear icon in a top-level screen's top
bar): a Currency card shows the region-appropriate code, and tapping it opens the picker.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/currency_strings.xml app/src/main/java/com/coparently/app/presentation/settings && git commit -m "feat(settings): add a default currency preference"
```

---

### Task 4: Currency on an expense

**Files:**
- Create: `app/src/main/java/com/coparently/app/presentation/expenses/CurrencySelector.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/expenses/ExpenseViewModel.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/expenses/AddExpenseScreen.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/expenses/BudgetItem.kt:38-39`

**Interfaces:**
- Consumes: `SupportedCurrency` (Task 1), `PreferencesRepository.getDefaultCurrencyFlow()` (Task 2),
  `currencyFormat(currency: String): NumberFormat` (existing, `ExpenseSummaryHeader.kt:238`).
- Produces: `@Composable fun CurrencySelector(selected: SupportedCurrency, enabled: Boolean, onSelect: (SupportedCurrency) -> Unit, modifier: Modifier)`;
  `ExpenseViewModel.defaultCurrency: StateFlow<SupportedCurrency>`;
  `ExpenseViewModel.addExpense(title, amount, category, currency: String, childId, date, notes, receiptImageUri)`.

- [ ] **Step 1: Create the selector composable**

Create `app/src/main/java/com/coparently/app/presentation/expenses/CurrencySelector.kt`:

```kotlin
package com.coparently.app.presentation.expenses

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.coparently.app.domain.money.SupportedCurrency

/**
 * Compact currency picker shown next to an amount field.
 *
 * @param selected Currently selected currency
 * @param enabled Whether the control accepts input
 * @param onSelect Called with the newly picked currency
 * @param modifier Modifier applied to the button
 */
@Composable
fun CurrencySelector(
    selected: SupportedCurrency,
    enabled: Boolean,
    onSelect: (SupportedCurrency) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { expanded = true },
        enabled = enabled,
        modifier = modifier
    ) {
        Text(selected.code)
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        SupportedCurrency.entries.forEach { currency ->
            DropdownMenuItem(
                text = { Text("${currency.code}  ${currency.symbol}") },
                onClick = {
                    onSelect(currency)
                    expanded = false
                }
            )
        }
    }
}
```

- [ ] **Step 2: Add the currency to the ViewModel**

In `ExpenseViewModel.kt`, add imports:

```kotlin
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.repository.PreferencesRepository
```

Add `private val preferencesRepository: PreferencesRepository` to the constructor, and expose:

```kotlin
    /** App-wide default currency, used to pre-fill the expense form. */
    val defaultCurrency: StateFlow<SupportedCurrency> =
        preferencesRepository.getDefaultCurrencyFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SupportedCurrency.DEFAULT)
```

Change `addExpense` to take a currency and pass it through. Add the parameter after `category`:

```kotlin
        currency: String,
```

and set it on the constructed `Expense`:

```kotlin
                currency = currency,
```

- [ ] **Step 3: Wire the selector into the form**

In `AddExpenseScreen.kt`, add imports:

```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.coparently.app.domain.money.SupportedCurrency
```

Add state next to the other `remember` calls:

```kotlin
    val defaultCurrency by viewModel.defaultCurrency.collectAsState()
    var currency by remember { mutableStateOf<SupportedCurrency?>(null) }
    val effectiveCurrency = currency ?: defaultCurrency
```

Replace the standalone amount `OutlinedTextField` with a row:

```kotlin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.expense_field_amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                CurrencySelector(
                    selected = effectiveCurrency,
                    enabled = !isSaving,
                    onSelect = { currency = it }
                )
            }
```

Pass the currency in the Save `onClick`:

```kotlin
                            currency = effectiveCurrency.code,
```

`currency` is deliberately nullable: `null` means "the user has not touched it", which Task 11
uses to decide whether a recognised currency may overwrite it.

- [ ] **Step 4: Fix the budget formatter**

In `BudgetItem.kt`, replace lines 38-39:

```kotlin
    val format = NumberFormat.getCurrencyInstance(Locale.US)
    format.currency = java.util.Currency.getInstance(budget.currency)
```

with:

```kotlin
    val format = remember(budget.currency) { currencyFormat(budget.currency) }
```

`currencyFormat` is `internal` in the same `presentation.expenses` package, so no import is
needed. Add `import androidx.compose.runtime.remember` if absent, and delete the now-unused
`java.text.NumberFormat` / `java.util.Locale` imports **only if nothing else in the file uses
them** — check before deleting.

- [ ] **Step 5: Build**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. If any other caller of `addExpense` exists, the compiler will
point at it — add the currency argument there.

- [ ] **Step 6: Run the existing expense tests**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.expenses.*"
```

Expected: PASS — the balance maths is untouched.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/expenses && git commit -m "feat(expenses): pick a currency per expense"
```

---

### Task 5: Date field on the expense form

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/expenses/AddExpenseScreen.kt`
- Modify: `app/src/main/res/values/expenses_strings.xml`

**Interfaces:**
- Consumes: `ExpenseViewModel.addExpense(..., date: LocalDate, ...)` (already exists).
- Produces: a `date` form field whose value is passed to `addExpense`; Task 11 pre-fills it.

The form currently always saves `LocalDate.now()`. A recognised receipt date would otherwise be
applied invisibly, so the field has to exist before auto-fill lands.

- [ ] **Step 1: Add the strings**

Append to `app/src/main/res/values/expenses_strings.xml`, before `</resources>`:

```xml
    <string name="expense_field_date">Date</string>
    <string name="expense_date_confirm">OK</string>
    <string name="expense_date_cancel">Cancel</string>
```

- [ ] **Step 2: Add the date state and picker**

In `AddExpenseScreen.kt`, add imports:

```kotlin
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
```

Add state:

```kotlin
    var date by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
```

Add the field to the form, directly after the category dropdown:

```kotlin
            OutlinedTextField(
                value = date.format(dateFormatter),
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text(stringResource(R.string.expense_field_date)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isSaving) { showDatePicker = true }
            )
```

`clickable` needs `import androidx.compose.foundation.clickable`. The field is `enabled = false`
so the keyboard never opens; the click is handled by the modifier on the wrapper.

- [ ] **Step 3: Add the dialog**

Add at the end of the `Column`:

```kotlin
            if (showDatePicker) {
                val pickerState = rememberDatePickerState(
                    initialSelectedDateMillis = date
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli()
                )
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            pickerState.selectedDateMillis?.let { millis ->
                                // The picker reports UTC midnight; converting through the system
                                // zone here would shift the date by a day in negative offsets.
                                date = Instant.ofEpochMilli(millis)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                            }
                            showDatePicker = false
                        }) {
                            Text(stringResource(R.string.expense_date_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) {
                            Text(stringResource(R.string.expense_date_cancel))
                        }
                    }
                ) {
                    DatePicker(state = pickerState)
                }
            }
```

- [ ] **Step 4: Pass the date when saving**

In the Save `onClick`, add to the `viewModel.addExpense(...)` call:

```kotlin
                            date = date,
```

- [ ] **Step 5: Build and verify**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. Manually: open Add expense, tap the date field, pick yesterday,
save, and confirm the expense appears under yesterday's date in the list.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/expenses/AddExpenseScreen.kt app/src/main/res/values/expenses_strings.xml && git commit -m "feat(expenses): let the user set the expense date"
```

---

### Task 6: Receipt model and money parsing

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/receipts/ReceiptScan.kt`
- Create: `app/src/main/java/com/coparently/app/domain/receipts/ReceiptParser.kt`
- Test: `app/src/test/java/com/coparently/app/domain/receipts/ReceiptParserMoneyTest.kt`

**Interfaces:**
- Consumes: `ExpenseCategory` (existing, `domain/model/Expense.kt`).
- Produces: `data class ReceiptScan(total: Double?, currency: String?, merchant: String?, date: LocalDate?, category: ExpenseCategory?)`
  with `val isEmpty: Boolean`; `interface ReceiptTextRecognizer { suspend fun recognize(imageUri: String): List<String> }`;
  `object ReceiptParser` with `fun parse(lines: List<String>, today: LocalDate = LocalDate.now()): ReceiptScan`,
  `internal fun parseMoney(raw: String): Double?`, `internal fun findTotal(lines: List<String>): Double?`,
  and `internal fun normalise(text: String): String`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/domain/receipts/ReceiptParserMoneyTest.kt`:

```kotlin
package com.coparently.app.domain.receipts

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests for the money parsing and total detection in [ReceiptParser]. */
class ReceiptParserMoneyTest {

    @Test
    fun `parses a plain decimal with a dot`() {
        assertEquals(12.99, ReceiptParser.parseMoney("12.99"))
    }

    @Test
    fun `parses a decimal comma`() {
        assertEquals(349.5, ReceiptParser.parseMoney("349,50"))
    }

    @Test
    fun `parses a space grouped amount`() {
        assertEquals(1234.5, ReceiptParser.parseMoney("1 234,50"))
    }

    @Test
    fun `parses a dot grouped amount with a decimal comma`() {
        assertEquals(1234.5, ReceiptParser.parseMoney("1.234,50"))
    }

    @Test
    fun `treats a three digit tail as thousands grouping`() {
        assertEquals(1234.0, ReceiptParser.parseMoney("1.234"))
    }

    @Test
    fun `rejects something that is not money`() {
        assertNull(ReceiptParser.parseMoney("09.07.2026"))
        assertNull(ReceiptParser.parseMoney("abc"))
    }

    @Test
    fun `finds the total on a czech keyword line`() {
        val lines = listOf(
            "BILLA CESKA REPUBLIKA",
            "Mleko            24,90",
            "Chleb            39,90",
            "CELKEM          349,50 Kc"
        )
        assertEquals(349.5, ReceiptParser.findTotal(lines))
    }

    @Test
    fun `ignores the VAT breakdown when looking for the total`() {
        val lines = listOf(
            "Rossmann",
            "ZAKLAD DPH 21%     999,00",
            "DPH 21%            209,79",
            "CELKEM K UHRADE    288,79"
        )
        assertEquals(288.79, ReceiptParser.findTotal(lines))
    }

    @Test
    fun `reads the total from the line below the keyword`() {
        val lines = listOf("Lekarna", "CELKEM", "512,00 Kc")
        assertEquals(512.0, ReceiptParser.findTotal(lines))
    }

    @Test
    fun `finds an english total`() {
        val lines = listOf("BOOKSHOP", "Item        5.00", "TOTAL      12.99")
        assertEquals(12.99, ReceiptParser.findTotal(lines))
    }

    @Test
    fun `falls back to the largest amount when no keyword is present`() {
        val lines = listOf("Kiosk", "2,50", "18,00", "7,20")
        assertEquals(18.0, ReceiptParser.findTotal(lines))
    }

    @Test
    fun `returns null when there is no money at all`() {
        assertNull(ReceiptParser.findTotal(listOf("hello", "world")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.receipts.ReceiptParserMoneyTest"
```

Expected: FAIL — `ReceiptParser` unresolved.

- [ ] **Step 3: Create the model and the recognizer interface**

Create `app/src/main/java/com/coparently/app/domain/receipts/ReceiptScan.kt`:

```kotlin
package com.coparently.app.domain.receipts

import com.coparently.app.domain.model.ExpenseCategory
import java.time.LocalDate

/**
 * What could be read off a receipt photo. Every field is best-effort and may be null.
 *
 * @property total Amount to charge, as printed on the total line
 * @property currency ISO 4217 code detected on the receipt, e.g. "CZK"
 * @property merchant Shop name taken from the receipt header
 * @property date Purchase date
 * @property category Category guessed from keywords on the receipt
 */
data class ReceiptScan(
    val total: Double? = null,
    val currency: String? = null,
    val merchant: String? = null,
    val date: LocalDate? = null,
    val category: ExpenseCategory? = null
) {
    /** True when nothing usable was read, so the UI can say the receipt was unreadable. */
    val isEmpty: Boolean
        get() = total == null && merchant == null && date == null
}

/**
 * On-device optical character recognition over a receipt photo.
 *
 * The image is referenced by a URI string rather than an Android `Uri` so the domain layer
 * stays free of Android types, the same way [com.coparently.app.domain.repository.ReceiptStorage]
 * does.
 */
interface ReceiptTextRecognizer {

    /**
     * Recognises the text on the image behind [imageUri].
     *
     * @param imageUri Content or file URI string of a local image
     * @return Recognised lines, roughly top to bottom
     */
    suspend fun recognize(imageUri: String): List<String>
}
```

- [ ] **Step 4: Implement money parsing and total detection**

Create `app/src/main/java/com/coparently/app/domain/receipts/ReceiptParser.kt`:

```kotlin
package com.coparently.app.domain.receipts

import java.text.Normalizer

/**
 * Turns the raw lines recognised on a receipt into structured fields.
 *
 * Pure and deterministic: every heuristic here is covered by unit tests, because OCR output
 * varies wildly between shops and this is where the feature is most likely to be wrong.
 */
object ReceiptParser {

    /** Lines whose text marks the amount actually charged. */
    private val TOTAL_KEYWORDS = listOf("celkem", "k uhrade", "suma", "total", "amount due")

    /**
     * Lines that hold money but never the total: the VAT breakdown, the taxable base,
     * rounding lines and subtotals. Without excluding these, the VAT table routinely
     * beats the real total.
     */
    private val EXCLUDED_KEYWORDS =
        listOf("dph", "vat", "zaklad", "zaokr", "subtotal", "mezisoucet")

    /** A run of digits possibly grouped by spaces, dots or commas. */
    private val MONEY_REGEX = Regex("""\d[\d \u00A0.,]*""")

    /** Lowercases and strips diacritics so "K ÚHRADĚ" and "k uhrade" compare equal. */
    internal fun normalise(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")

    /**
     * Parses one money-shaped token.
     *
     * Handles `12.99`, `349,50`, `1 234,50`, `1.234,50` and `1.234`. When both separators are
     * present the last one is the decimal separator. A three-digit tail is treated as thousands
     * grouping; anything longer is not money (dates and item codes land here).
     *
     * @param raw Token as printed on the receipt
     * @return The amount, or null when the token is not money
     */
    internal fun parseMoney(raw: String): Double? {
        val cleaned = raw.replace("\u00A0", "").replace(" ", "").trim('.', ',')
        if (cleaned.isEmpty() || !cleaned.all { it.isDigit() || it == '.' || it == ',' }) return null

        val separatorIndex = maxOf(cleaned.lastIndexOf('.'), cleaned.lastIndexOf(','))
        if (separatorIndex < 0) return cleaned.toDoubleOrNull()

        val head = cleaned.substring(0, separatorIndex)
        val tail = cleaned.substring(separatorIndex + 1)
        val headDigits = head.filter { it.isDigit() }

        return when {
            tail.length in 1..2 -> "$headDigits.$tail".toDoubleOrNull()
            tail.length == 3 && headDigits.length in 1..3 -> (headDigits + tail).toDoubleOrNull()
            else -> null
        }
    }

    /** All money-shaped amounts on one line, in reading order. */
    private fun moneyOnLine(line: String): List<Double> =
        MONEY_REGEX.findAll(line).mapNotNull { parseMoney(it.value) }.toList()

    /**
     * Finds the amount charged.
     *
     * Prefers the last amount on a line carrying a total keyword, falling back to the first
     * amount on the following line (some receipts print the label and the number separately),
     * and finally to the largest amount anywhere outside the VAT breakdown.
     *
     * @param lines Recognised receipt lines
     * @return The total, or null when the receipt holds no money at all
     */
    internal fun findTotal(lines: List<String>): Double? {
        val keywordLines = lines.withIndex().filter { (_, line) ->
            val text = normalise(line)
            TOTAL_KEYWORDS.any { it in text } && EXCLUDED_KEYWORDS.none { it in text }
        }

        for ((index, line) in keywordLines) {
            moneyOnLine(line).lastOrNull()?.let { return it }
            lines.getOrNull(index + 1)
                ?.let { moneyOnLine(it).firstOrNull() }
                ?.let { return it }
        }

        return lines
            .filter { line -> EXCLUDED_KEYWORDS.none { it in normalise(line) } }
            .flatMap { moneyOnLine(it) }
            .maxOrNull()
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.receipts.ReceiptParserMoneyTest"
```

Expected: PASS, 12 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/coparently/app/domain/receipts app/src/test/java/com/coparently/app/domain/receipts && git commit -m "feat(receipts): parse the charged total off receipt text"
```

---

### Task 7: Currency and date detection

**Files:**
- Modify: `app/src/main/java/com/coparently/app/domain/receipts/ReceiptParser.kt`
- Test: `app/src/test/java/com/coparently/app/domain/receipts/ReceiptParserDateCurrencyTest.kt`

**Interfaces:**
- Consumes: `ReceiptParser.normalise`, `ReceiptParser.parseMoney` (Task 6).
- Produces: `internal fun findCurrency(lines: List<String>): String?` and
  `internal fun findDate(lines: List<String>, today: LocalDate): LocalDate?`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/domain/receipts/ReceiptParserDateCurrencyTest.kt`:

```kotlin
package com.coparently.app.domain.receipts

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests for currency and purchase-date detection in [ReceiptParser]. */
class ReceiptParserDateCurrencyTest {

    private val today = LocalDate.of(2026, 7, 30)

    @Test
    fun `detects czech crowns`() {
        assertEquals("CZK", ReceiptParser.findCurrency(listOf("CELKEM 349,50 Kč")))
    }

    @Test
    fun `detects crowns written without diacritics by the recognizer`() {
        assertEquals("CZK", ReceiptParser.findCurrency(listOf("CELKEM 349,50 Kc")))
    }

    @Test
    fun `detects euro from the symbol`() {
        assertEquals("EUR", ReceiptParser.findCurrency(listOf("TOTAL 12,99 €")))
    }

    @Test
    fun `detects zloty`() {
        assertEquals("PLN", ReceiptParser.findCurrency(listOf("SUMA 45,00 zł")))
    }

    @Test
    fun `returns null when no currency is printed`() {
        assertNull(ReceiptParser.findCurrency(listOf("CELKEM 349,50")))
    }

    @Test
    fun `reads a czech style date`() {
        assertEquals(
            LocalDate.of(2026, 7, 12),
            ReceiptParser.findDate(listOf("Datum: 12.07.2026 14:32"), today)
        )
    }

    @Test
    fun `reads a slash separated date`() {
        assertEquals(
            LocalDate.of(2026, 6, 3),
            ReceiptParser.findDate(listOf("03/06/2026"), today)
        )
    }

    @Test
    fun `reads an iso date`() {
        assertEquals(
            LocalDate.of(2026, 5, 21),
            ReceiptParser.findDate(listOf("2026-05-21"), today)
        )
    }

    @Test
    fun `reads a two digit year`() {
        assertEquals(
            LocalDate.of(2026, 7, 12),
            ReceiptParser.findDate(listOf("12.07.26"), today)
        )
    }

    @Test
    fun `rejects a future date such as a card expiry`() {
        assertNull(ReceiptParser.findDate(listOf("Platnost do 01.01.2030"), today))
    }

    @Test
    fun `rejects a date older than two years`() {
        assertNull(ReceiptParser.findDate(listOf("01.01.2020"), today))
    }

    @Test
    fun `skips a card expiry and takes the real purchase date`() {
        val lines = listOf("VISA exp 09/2031", "Datum 12.07.2026")
        assertEquals(LocalDate.of(2026, 7, 12), ReceiptParser.findDate(lines, today))
    }

    @Test
    fun `rejects an impossible date`() {
        assertNull(ReceiptParser.findDate(listOf("32.13.2026"), today))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.receipts.ReceiptParserDateCurrencyTest"
```

Expected: FAIL — `findCurrency` and `findDate` unresolved.

- [ ] **Step 3: Implement detection**

Add to `ReceiptParser.kt` — imports first:

```kotlin
import java.time.LocalDate
```

Then the constants and functions inside the object:

```kotlin
    /**
     * Currency markers searched in normalised text. "Kč" normalises to "kc"; "zł" keeps its
     * stroked l because NFD does not decompose it, so both spellings are listed.
     */
    private val CURRENCY_MARKERS = listOf(
        "czk" to "CZK", "kc" to "CZK",
        "eur" to "EUR", "€" to "EUR",
        "pln" to "PLN", "zł" to "PLN", "zl" to "PLN",
        "usd" to "USD", "$" to "USD",
        "gbp" to "GBP", "£" to "GBP"
    )

    private val DATE_DMY = Regex("""\b(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{4})\b""")
    private val DATE_YMD = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val DATE_DMY_SHORT = Regex("""\b(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{2})\b""")

    /** A receipt older than this is almost certainly a misread, not a real purchase. */
    private const val MAX_RECEIPT_AGE_YEARS = 2L

    /**
     * Detects the currency printed on the receipt.
     *
     * @param lines Recognised receipt lines
     * @return ISO 4217 code, or null when no marker is present
     */
    internal fun findCurrency(lines: List<String>): String? {
        val text = normalise(lines.joinToString(" "))
        return CURRENCY_MARKERS.firstOrNull { (marker, _) -> marker in text }?.second
    }

    /**
     * Detects the purchase date.
     *
     * Dates in the future or more than [MAX_RECEIPT_AGE_YEARS] old are rejected — that guard is
     * what stops a card expiry or a "platnost do" line being read as the purchase date.
     *
     * @param lines Recognised receipt lines
     * @param today Reference date; injected so the tests are deterministic
     * @return The purchase date, or null when nothing plausible was found
     */
    internal fun findDate(lines: List<String>, today: LocalDate): LocalDate? {
        val oldest = today.minusYears(MAX_RECEIPT_AGE_YEARS)

        for (line in lines) {
            val candidates = buildList {
                DATE_DMY.findAll(line).forEach { match ->
                    val (day, month, year) = match.destructured
                    add(Triple(year.toInt(), month.toInt(), day.toInt()))
                }
                DATE_YMD.findAll(line).forEach { match ->
                    val (year, month, day) = match.destructured
                    add(Triple(year.toInt(), month.toInt(), day.toInt()))
                }
                DATE_DMY_SHORT.findAll(line).forEach { match ->
                    val (day, month, year) = match.destructured
                    add(Triple(2000 + year.toInt(), month.toInt(), day.toInt()))
                }
            }

            candidates.forEach { (year, month, day) ->
                val date = runCatching { LocalDate.of(year, month, day) }.getOrNull()
                if (date != null && !date.isAfter(today) && !date.isBefore(oldest)) return date
            }
        }
        return null
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.receipts.*"
```

Expected: PASS — 12 money tests plus 13 date/currency tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/domain/receipts/ReceiptParser.kt app/src/test/java/com/coparently/app/domain/receipts/ReceiptParserDateCurrencyTest.kt && git commit -m "feat(receipts): detect currency and purchase date"
```

---

### Task 8: Merchant, category and the full parse

**Files:**
- Modify: `app/src/main/java/com/coparently/app/domain/receipts/ReceiptParser.kt`
- Test: `app/src/test/java/com/coparently/app/domain/receipts/ReceiptParserFullTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 6 and 7.
- Produces: `internal fun findMerchant(lines: List<String>): String?`,
  `internal fun findCategory(lines: List<String>): ExpenseCategory?`, and the public entry point
  `fun parse(lines: List<String>, today: LocalDate = LocalDate.now()): ReceiptScan`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/domain/receipts/ReceiptParserFullTest.kt`:

```kotlin
package com.coparently.app.domain.receipts

import com.coparently.app.domain.model.ExpenseCategory
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** End-to-end tests for [ReceiptParser.parse] over realistic receipt layouts. */
class ReceiptParserFullTest {

    private val today = LocalDate.of(2026, 7, 30)

    @Test
    fun `reads a czech pharmacy receipt`() {
        val lines = listOf(
            "LEKARNA DR.MAX",
            "Namesti Miru 12, Praha 2",
            "ICO 12345678",
            "Paralen 500mg        89,00",
            "Vitamin D            210,00",
            "CELKEM K UHRADE      299,00 Kc",
            "Datum: 12.07.2026 16:04"
        )

        val scan = ReceiptParser.parse(lines, today)

        assertEquals(299.0, scan.total)
        assertEquals("CZK", scan.currency)
        assertEquals("LEKARNA DR.MAX", scan.merchant)
        assertEquals(LocalDate.of(2026, 7, 12), scan.date)
        assertEquals(ExpenseCategory.MEDICAL, scan.category)
    }

    @Test
    fun `reads an english receipt in euro`() {
        val lines = listOf(
            "CITY BOOKSHOP",
            "Notebook          4.50",
            "Pencils           3.49",
            "TOTAL            12.99 EUR",
            "2026-05-21"
        )

        val scan = ReceiptParser.parse(lines, today)

        assertEquals(12.99, scan.total)
        assertEquals("EUR", scan.currency)
        assertEquals("CITY BOOKSHOP", scan.merchant)
        assertEquals(LocalDate.of(2026, 5, 21), scan.date)
    }

    @Test
    fun `skips address and registration lines when naming the merchant`() {
        val lines = listOf(
            "SKOLNI JIDELNA",
            "Ulice Dlouha 5",
            "DIC CZ12345678",
            "tel. 777 123 456",
            "CELKEM 640,00 Kc"
        )

        assertEquals("SKOLNI JIDELNA", ReceiptParser.findMerchant(lines))
    }

    @Test
    fun `guesses education from school keywords`() {
        assertEquals(
            ExpenseCategory.EDUCATION,
            ReceiptParser.findCategory(listOf("MATERSKA SKOLKA", "Skolne za cerven"))
        )
    }

    @Test
    fun `guesses transportation from a fuel receipt`() {
        assertEquals(
            ExpenseCategory.TRANSPORTATION,
            ReceiptParser.findCategory(listOf("ORLEN", "Benzin Natural 95"))
        )
    }

    @Test
    fun `leaves the category unset when nothing matches`() {
        assertNull(ReceiptParser.findCategory(listOf("NEJAKY OBCHOD", "Zbozi 100,00")))
    }

    @Test
    fun `returns an empty scan for unreadable input`() {
        val scan = ReceiptParser.parse(listOf("~~~", "###"), today)

        assertTrue(scan.isEmpty)
        assertNull(scan.total)
        assertNull(scan.merchant)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.receipts.ReceiptParserFullTest"
```

Expected: FAIL — `findMerchant`, `findCategory` and `parse` unresolved.

- [ ] **Step 3: Implement merchant, category and parse**

Add to `ReceiptParser.kt` — import first:

```kotlin
import com.coparently.app.domain.model.ExpenseCategory
```

Then inside the object:

```kotlin
    /** Header lines that are never the shop's name. */
    private val MERCHANT_STOPWORDS = listOf(
        "ico", "dic", "tel", "www", "http", "ulice", "namesti", "psc",
        "danovy doklad", "uctenka", "receipt", "faktura", "pokladna"
    )

    private const val MERCHANT_MIN_LENGTH = 3
    private const val MERCHANT_MAX_LENGTH = 40

    /** Keyword to category, first match wins. */
    private val CATEGORY_KEYWORDS = listOf(
        "lekarna" to ExpenseCategory.MEDICAL,
        "pharmacy" to ExpenseCategory.MEDICAL,
        "apotheke" to ExpenseCategory.MEDICAL,
        "zubni" to ExpenseCategory.MEDICAL,
        "skolka" to ExpenseCategory.EDUCATION,
        "skolne" to ExpenseCategory.EDUCATION,
        "skola" to ExpenseCategory.EDUCATION,
        "krouzek" to ExpenseCategory.EDUCATION,
        "tuition" to ExpenseCategory.EDUCATION,
        "potraviny" to ExpenseCategory.FOOD,
        "restaurace" to ExpenseCategory.FOOD,
        "kavarna" to ExpenseCategory.FOOD,
        "bistro" to ExpenseCategory.FOOD,
        "cafe" to ExpenseCategory.FOOD,
        "obleceni" to ExpenseCategory.CLOTHING,
        "obuv" to ExpenseCategory.CLOTHING,
        "clothing" to ExpenseCategory.CLOTHING,
        "hracky" to ExpenseCategory.TOYS,
        "toys" to ExpenseCategory.TOYS,
        "jizdenka" to ExpenseCategory.TRANSPORTATION,
        "benzin" to ExpenseCategory.TRANSPORTATION,
        "nafta" to ExpenseCategory.TRANSPORTATION,
        "fuel" to ExpenseCategory.TRANSPORTATION,
        "drogerie" to ExpenseCategory.HOUSEHOLD,
        "household" to ExpenseCategory.HOUSEHOLD
    )

    /**
     * Picks the shop name: the first header line that reads like a name rather than an address,
     * a registration number or a phone number.
     *
     * Returned verbatim apart from whitespace collapsing — receipt headers are usually already
     * capitalised the way the shop writes its name.
     *
     * @param lines Recognised receipt lines
     * @return The merchant name, or null when no line qualifies
     */
    internal fun findMerchant(lines: List<String>): String? = lines.asSequence()
        .map { it.trim().replace(Regex("""\s+"""), " ") }
        .filter { it.length in MERCHANT_MIN_LENGTH..MERCHANT_MAX_LENGTH }
        .filter { line -> line.any { it.isLetter() } }
        .filter { line ->
            val text = normalise(line)
            MERCHANT_STOPWORDS.none { it in text }
        }
        .filterNot { line -> line.count { it.isDigit() } * 3 > line.length }
        .firstOrNull()

    /**
     * Guesses a category from keywords anywhere on the receipt.
     *
     * @param lines Recognised receipt lines
     * @return The matched category, or null when nothing matched
     */
    internal fun findCategory(lines: List<String>): ExpenseCategory? {
        val text = normalise(lines.joinToString(" "))
        return CATEGORY_KEYWORDS.firstOrNull { (keyword, _) -> keyword in text }?.second
    }

    /**
     * Parses recognised receipt lines into structured fields.
     *
     * @param lines Recognised receipt lines, roughly top to bottom
     * @param today Reference date used to sanity-check the purchase date
     * @return Best-effort fields; any of them may be null
     */
    fun parse(lines: List<String>, today: LocalDate = LocalDate.now()): ReceiptScan = ReceiptScan(
        total = findTotal(lines),
        currency = findCurrency(lines),
        merchant = findMerchant(lines),
        date = findDate(lines, today),
        category = findCategory(lines)
    )
```

- [ ] **Step 4: Run the whole parser suite**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.receipts.*"
```

Expected: PASS, 32 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/domain/receipts/ReceiptParser.kt app/src/test/java/com/coparently/app/domain/receipts/ReceiptParserFullTest.kt && git commit -m "feat(receipts): detect merchant and category, add the full parse entry point"
```

---

### Task 9: ML Kit recognizer and DI

**Files:**
- Modify: `app/build.gradle.kts:193`
- Create: `app/src/main/java/com/coparently/app/data/mlkit/MlKitReceiptTextRecognizer.kt`
- Create: `app/src/main/java/com/coparently/app/di/ReceiptModule.kt`

**Interfaces:**
- Consumes: `ReceiptTextRecognizer` (Task 6).
- Produces: an injectable `ReceiptTextRecognizer` binding for `ExpenseViewModel` (Task 11).

Not unit-tested: a thin wrapper over a Google API with no logic of its own. Verification is the
debug build plus the manual check in Task 11.

- [ ] **Step 1: Add the dependency**

In `app/build.gradle.kts`, directly under the existing barcode-scanning line:

```kotlin
    // ML Kit for QR code scanning
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ML Kit text recognition for receipts — bundled Latin model, so OCR works offline
    // and from first launch (~4 MB) instead of waiting on a Play Services download.
    implementation("com.google.mlkit:text-recognition:16.0.1")
```

- [ ] **Step 2: Write the recognizer**

Create `app/src/main/java/com/coparently/app/data/mlkit/MlKitReceiptTextRecognizer.kt`:

```kotlin
package com.coparently.app.data.mlkit

import android.content.Context
import android.net.Uri
import com.coparently.app.domain.receipts.ReceiptTextRecognizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device receipt OCR backed by ML Kit's bundled Latin text recognizer.
 *
 * The photo never leaves the device.
 */
@Singleton
class MlKitReceiptTextRecognizer @Inject constructor(
    @ApplicationContext private val context: Context
) : ReceiptTextRecognizer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(imageUri: String): List<String> {
        val image = InputImage.fromFilePath(context, Uri.parse(imageUri))
        return recognizer.process(image).await()
            .textBlocks
            .flatMap { block -> block.lines }
            .map { line -> line.text }
    }
}
```

- [ ] **Step 3: Bind it**

Create `app/src/main/java/com/coparently/app/di/ReceiptModule.kt`:

```kotlin
package com.coparently.app.di

import com.coparently.app.data.mlkit.MlKitReceiptTextRecognizer
import com.coparently.app.domain.receipts.ReceiptTextRecognizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dagger Hilt module providing receipt scanning implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReceiptModule {

    /**
     * Provides the on-device receipt text recognizer.
     */
    @Binds
    @Singleton
    abstract fun bindReceiptTextRecognizer(
        mlKitReceiptTextRecognizer: MlKitReceiptTextRecognizer
    ): ReceiptTextRecognizer
}
```

- [ ] **Step 4: Build**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. If Gradle cannot resolve `text-recognition:16.0.1`, check network
access to `google()` in the repositories block — do not silently downgrade the version.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/coparently/app/data/mlkit app/src/main/java/com/coparently/app/di/ReceiptModule.kt && git commit -m "feat(receipts): add on-device ML Kit text recognition"
```

---

### Task 10: Camera capture for receipts

**Files:**
- Create: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/coparently/app/presentation/expenses/AddExpenseScreen.kt`
- Create: `app/src/main/res/values/receipt_ocr_strings.xml`

**Interfaces:**
- Consumes: the existing `receiptUri` state in `AddExpenseScreen`.
- Produces: a camera path that sets `receiptUri` exactly like the gallery path, so Task 11 can
  hang scanning off a single state change.

- [ ] **Step 1: Add the strings**

Create `app/src/main/res/values/receipt_ocr_strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Receipt capture and scanning strings. Kept out of strings.xml because
     strings.xml is gitignored in this project (it holds local OAuth values). -->
<resources>
    <string name="receipt_take_photo">Take photo</string>
    <string name="receipt_pick_photo">From gallery</string>
    <string name="receipt_camera_denied">Camera permission is needed to photograph a receipt</string>
    <string name="receipt_scanning">Reading the receipt…</string>
    <string name="receipt_scan_failed">Couldn\'t read the receipt</string>
    <string name="receipt_scan_applied">Filled from receipt</string>
    <string name="receipt_scan_undo">Undo</string>
</resources>
```

- [ ] **Step 2: Declare the FileProvider**

Create `app/src/main/res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- Receipt photos taken in-app land in the cache; they are uploaded to
         Firebase Storage and the local copy is disposable. -->
    <cache-path name="receipts" path="receipts/" />
</paths>
```

In `AndroidManifest.xml`, add inside `<application>`, after the existing `<provider>` block:

```xml
        <!-- Shares camera output files with the camera app when photographing a receipt -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 3: Add the capture helper**

At the bottom of `AddExpenseScreen.kt`, add:

```kotlin
/**
 * Creates a shareable URI for a new receipt photo in the app cache.
 *
 * @param context Context used to resolve the cache directory and the FileProvider authority
 * @return URI the camera app may write to
 */
private fun createReceiptCaptureUri(context: Context): Uri {
    val directory = File(context.cacheDir, "receipts").apply { mkdirs() }
    val file = File(directory, "receipt_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
```

Imports to add:

```kotlin
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
```

- [ ] **Step 4: Wire the launchers**

In `AddExpenseScreen`, next to the existing `photoPicker`:

```kotlin
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success) receiptUri = pendingCaptureUri }

    val takePhoto = {
        val uri = createReceiptCaptureUri(context)
        pendingCaptureUri = uri
        cameraLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            takePhoto()
        } else {
            Toast.makeText(context, R.string.receipt_camera_denied, Toast.LENGTH_LONG).show()
        }
    }
```

- [ ] **Step 5: Offer both buttons**

Replace the `receiptUri == null` branch of `ReceiptPicker` with two buttons, and give the
composable the extra callback. New signature and empty state:

```kotlin
@Composable
private fun ReceiptPicker(
    receiptUri: Uri?,
    enabled: Boolean,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit
) {
    if (receiptUri == null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onTakePhoto,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.receipt_take_photo))
            }
            OutlinedButton(
                onClick = onPickPhoto,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.AddAPhoto, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.receipt_pick_photo))
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = receiptUri,
                contentDescription = stringResource(R.string.expenses_receipt_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(CoPlanlyShapes.medium)
            )
            FilledTonalIconButton(
                onClick = onRemovePhoto,
                enabled = enabled,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.expense_remove_receipt)
                )
            }
        }
    }
}
```

The `else` branch above is the existing preview code, unchanged — only the `if` branch and the
signature are new.

Add `import androidx.compose.material.icons.filled.PhotoCamera`.

At the call site, pass the new callback:

```kotlin
                onTakePhoto = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) takePhoto() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
```

- [ ] **Step 6: Build and verify on a device**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. Manually: Add expense → "Take photo" → grant the permission →
shoot → the preview shows the photo. Save and confirm the receipt thumbnail appears on the
expense row (upload path unchanged).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/xml/file_paths.xml app/src/main/res/values/receipt_ocr_strings.xml app/src/main/AndroidManifest.xml app/src/main/java/com/coparently/app/presentation/expenses/AddExpenseScreen.kt && git commit -m "feat(expenses): photograph a receipt from the expense form"
```

---

### Task 11: Scan the receipt and auto-fill the form

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/expenses/ExpenseViewModel.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/expenses/AddExpenseScreen.kt`

**Interfaces:**
- Consumes: `ReceiptTextRecognizer` (Task 9), `ReceiptParser.parse` and `ReceiptScan` (Tasks 6-8),
  the `currency` (Task 4) and `date` (Task 5) form fields, `receipt_ocr_strings.xml` (Task 10).
- Produces: `ExpenseViewModel.scanState: StateFlow<ReceiptScanState>`,
  `ExpenseViewModel.scanReceipt(imageUri: String)`, `ExpenseViewModel.resetScanState()`.

- [ ] **Step 1: Add scan state to the ViewModel**

In `ExpenseViewModel.kt`, add imports:

```kotlin
import com.coparently.app.domain.receipts.ReceiptParser
import com.coparently.app.domain.receipts.ReceiptScan
import com.coparently.app.domain.receipts.ReceiptTextRecognizer
```

Add `private val receiptTextRecognizer: ReceiptTextRecognizer` to the constructor.

Add the state type above the class, next to `ExpenseSaveState`:

```kotlin
/**
 * State of the on-device receipt scan that pre-fills the Add Expense form.
 */
sealed interface ReceiptScanState {
    data object Idle : ReceiptScanState
    data object Scanning : ReceiptScanState

    /** OCR produced usable fields; the form applies the ones the user has not filled in. */
    data class Applied(val scan: ReceiptScan) : ReceiptScanState

    /** Recognition failed, or produced nothing usable. The photo stays attached regardless. */
    data object Failed : ReceiptScanState
}
```

And inside the class:

```kotlin
    private val _scanState = MutableStateFlow<ReceiptScanState>(ReceiptScanState.Idle)
    val scanState: StateFlow<ReceiptScanState> = _scanState.asStateFlow()

    /**
     * Runs on-device OCR over a receipt photo and parses it into form fields.
     *
     * Failures are not fatal: the photo is still a valid receipt, so the expense can be saved
     * with it either way.
     *
     * @param imageUri Content or file URI string of the receipt photo
     */
    fun scanReceipt(imageUri: String) {
        if (_scanState.value is ReceiptScanState.Scanning) return

        viewModelScope.launch {
            _scanState.value = ReceiptScanState.Scanning
            _scanState.value = try {
                val scan = ReceiptParser.parse(receiptTextRecognizer.recognize(imageUri))
                if (scan.isEmpty) ReceiptScanState.Failed else ReceiptScanState.Applied(scan)
            } catch (
                // Any recognition failure (IO, decode, model load) must not break the form
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                android.util.Log.e("CoPlanlyReceiptScan", "Receipt OCR failed", e)
                ReceiptScanState.Failed
            }
        }
    }

    /** Resets [scanState] after the UI consumed a result. */
    fun resetScanState() {
        _scanState.value = ReceiptScanState.Idle
    }
```

- [ ] **Step 2: Trigger the scan when a photo arrives**

In `AddExpenseScreen`, add:

```kotlin
    val scanState by viewModel.scanState.collectAsState()

    LaunchedEffect(receiptUri) {
        receiptUri?.let { viewModel.scanReceipt(it.toString()) }
    }
```

- [ ] **Step 3: Add a snackbar host and the undo snapshot**

Add the snapshot type at the bottom of the file:

```kotlin
/** Form values captured before a receipt scan is applied, so Undo can put them back. */
private data class ReceiptUndoSnapshot(
    val title: String,
    val amount: String,
    val category: ExpenseCategory,
    val date: LocalDate,
    val currency: SupportedCurrency?
)
```

Add state and a host to the screen:

```kotlin
    val snackbarHostState = remember { SnackbarHostState() }
    var undoSnapshot by remember { mutableStateOf<ReceiptUndoSnapshot?>(null) }
```

and on the `Scaffold`:

```kotlin
        snackbarHost = { SnackbarHost(snackbarHostState) },
```

Imports: `androidx.compose.material3.SnackbarHost`, `androidx.compose.material3.SnackbarHostState`,
`androidx.compose.material3.SnackbarResult`.

- [ ] **Step 4: Apply the scan to empty fields only**

Add this effect after the existing `LaunchedEffect(saveState)`:

```kotlin
    LaunchedEffect(scanState) {
        when (val state = scanState) {
            is ReceiptScanState.Applied -> {
                val snapshot = ReceiptUndoSnapshot(title, amount, category, date, currency)
                var changed = false

                state.scan.merchant?.let { merchant ->
                    if (title.isBlank()) {
                        title = merchant
                        changed = true
                    }
                }
                state.scan.total?.let { total ->
                    if (amount.isBlank()) {
                        amount = total.toString()
                        changed = true
                    }
                }
                state.scan.date?.let { scanned ->
                    if (date == LocalDate.now()) {
                        date = scanned
                        changed = true
                    }
                }
                state.scan.category?.let { scanned ->
                    if (category == ExpenseCategory.OTHER) {
                        category = scanned
                        changed = true
                    }
                }
                SupportedCurrency.fromCode(state.scan.currency)?.let { scanned ->
                    if (currency == null) {
                        currency = scanned
                        changed = true
                    }
                }

                viewModel.resetScanState()
                if (changed) {
                    undoSnapshot = snapshot
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.receipt_scan_applied),
                        actionLabel = context.getString(R.string.receipt_scan_undo)
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        undoSnapshot?.let { previous ->
                            title = previous.title
                            amount = previous.amount
                            category = previous.category
                            date = previous.date
                            currency = previous.currency
                        }
                    }
                    undoSnapshot = null
                }
            }

            ReceiptScanState.Failed -> {
                viewModel.resetScanState()
                snackbarHostState.showSnackbar(context.getString(R.string.receipt_scan_failed))
            }

            else -> Unit
        }
    }
```

Note the rules encoded here: title and amount are filled only when blank, the date only when it
is still today, the category only when still `OTHER`, and the currency only when the user has
not touched the selector (`currency == null`, from Task 4).

- [ ] **Step 5: Show progress while scanning**

Above `ReceiptPicker`, add:

```kotlin
            if (scanState is ReceiptScanState.Scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.receipt_scanning))
                }
            }
```

- [ ] **Step 6: Build**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. `AddExpenseScreen` has grown — if detekt's `LongMethod` fires in
Step 8, add `@Suppress("LongMethod")` to the composable with a comment, matching how
`SettingsScreen.kt:43` handles the same situation.

- [ ] **Step 7: Manual verification on a device**

Photograph a real Czech receipt via Add expense → "Take photo". Expected: a brief
"Reading the receipt…" row, then title, amount, date, category and currency fill in, and a
"Filled from receipt · Undo" snackbar appears. Tapping Undo restores the empty form.
Then repeat with a blank sheet of paper — expected: "Couldn't read the receipt", the photo
stays attached, and the form is untouched.

- [ ] **Step 8: Run the full check**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest lint detekt
```

Expected: all tests pass; lint and detekt clean (or only pre-existing baseline entries).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/expenses && git commit -m "feat(expenses): pre-fill the expense form from a scanned receipt"
```

---

## Post-implementation

Update `CLAUDE.md`'s expenses notes with two invariants this work introduces:

1. Receipt OCR is on-device only — no receipt text or photo may be sent to Gemini or any other
   remote service without an explicit product decision.
2. `Expense.currency` is now real. Balance maths (`calculateExpenseBalance`) still does not
   convert between currencies; a month mixing currencies shows a wrong total. Tracked as a
   follow-up, do not "fix" it by silently normalising currencies.

Follow-ups deliberately left out of this plan (spec §10): mixed-currency months, and app
localisation.
