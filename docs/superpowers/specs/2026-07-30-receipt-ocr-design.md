# Receipt OCR & app currency — design

Date: 2026-07-30
Status: approved for planning

## Goal

Let a parent add an expense by photographing the receipt instead of typing. ML Kit
recognises the text on-device; a pure-Kotlin parser turns those lines into a total,
a currency, a merchant name, a date and a category, which pre-fill the Add Expense form.

The feature also introduces a real currency concept in the app: today every `Expense` is
hardcoded to `"USD"`, which is wrong for a Czech-market product and makes a recognised
`Kč` total unrepresentable.

## Non-goals

Live camera preview / real-time scanning, line-item extraction, splitting an expense by
line items, sending receipt text or photos to Gemini, image cropping or perspective
correction, receipt languages beyond Czech and English, and currency conversion between
different currencies inside one balance.

## Decisions taken during brainstorming

| Question | Decision |
| --- | --- |
| What to extract | Total, merchant, date and category |
| Parsing engine | ML Kit OCR + our own parser (fully offline, no photo leaves the device) |
| Capture | Gallery (existing) plus a new camera capture |
| Applying results | Auto-fill empty fields, with an "Undo" snackbar |
| Receipt locales | Czech and English |
| Currency of an expense | Written from the receipt; mixed-currency balances tracked separately |
| Currency selection | Global default in Settings (seeded from region) + per-expense override |

## Architecture

Clean-architecture split as elsewhere in the app. The domain layer stays free of Android
types — the image is referenced by a URI **string**, the same trick `ReceiptStorage`
already uses.

```
domain/receipts/   ReceiptScan, ReceiptTextRecognizer (interface), ReceiptParser (pure)
domain/money/      SupportedCurrency, defaultCurrencyForRegion (pure)
data/mlkit/        MlKitReceiptTextRecognizer (implements the interface)
di/                ReceiptModule (binds the interface)
presentation/expenses/  camera capture, currency selector, auto-fill + Undo
presentation/settings/  default-currency row
```

Flow: `AddExpenseScreen` (pick or shoot) → `ExpenseViewModel.scanReceipt(uri)` →
`ReceiptTextRecognizer.recognize(uri)` → `ReceiptParser.parse(lines)` → `ReceiptScan` →
form fields.

## 1. Dependency

```kotlin
implementation("com.google.mlkit:text-recognition:16.0.1")   // Latin script, bundled model
```

The **bundled** artifact rather than `play-services-mlkit-text-recognition`: the model
ships inside the APK, so recognition works offline and on first launch, at the cost of
roughly 4 MB. Latin script covers Czech diacritics. `Task.await()` is already available
through the Firebase KTX dependencies (used by every Firestore data source).

## 2. Domain — receipts

```kotlin
/** What the parser managed to read off a receipt. Every field is best-effort. */
data class ReceiptScan(
    val total: Double? = null,
    val currency: String? = null,       // ISO 4217, e.g. "CZK"
    val merchant: String? = null,
    val date: LocalDate? = null,
    val category: ExpenseCategory? = null
) {
    /** True when nothing usable was found — the UI shows "couldn't read the receipt". */
    val isEmpty: Boolean get() = total == null && merchant == null && date == null
}

/** On-device OCR. [imageUri] is a content/file URI string; the domain stays Android-free. */
interface ReceiptTextRecognizer {
    suspend fun recognize(imageUri: String): List<String>   // lines, top to bottom
}

/** Turns recognised lines into structured fields. Pure and deterministic. */
object ReceiptParser {
    fun parse(lines: List<String>): ReceiptScan
}
```

All the fragile logic lives in `ReceiptParser`, which has no dependencies and is covered
by JVM unit tests.

### Parser heuristics

**Total.** Search for a line whose normalised text (lowercase, diacritics stripped)
contains one of `celkem`, `k uhrade`, `suma`, `total`, `amount due`. Take the last
money-shaped number on that line; if the line has none, take the first number on the
following line. Lines containing `dph`, `vat`, `zaklad`, `zaokr` (VAT breakdown, rounding)
are **excluded from the search** — without this the VAT table routinely wins over the
actual total. If no keyword line is found at all, fall back to the largest money-shaped
number on the receipt.

**Number format.** Accept `1 234,50`, `1.234,50`, `1234.50`, `1 234.50`. Normalisation:
drop spaces and non-breaking spaces; if both `.` and `,` are present, the *last* separator
is the decimal one and the other is a grouping separator; if only `,` is present it is the
decimal separator. Reject numbers with more than two decimals (those are quantities or
item codes, not money).

**Currency.** `Kč`/`CZK`/`Kc` → `CZK`, `€`/`EUR` → `EUR`, `zł`/`PLN` → `PLN`, `$`/`USD` →
`USD`, `£`/`GBP` → `GBP`. Searched on the total line first, then anywhere in the text.
No match → `null`, and the form keeps whatever currency it already had.

**Merchant.** The first line from the top that is 3–40 characters, contains letters, and
does not look like an address, `IČO`/`DIČ`, phone number, or receipt number. Returned
trimmed and collapsed to single spaces, otherwise verbatim — no title-casing, since
receipt headers are frequently already correct and shouting them differently looks worse.

**Date.** Match `dd.MM.yyyy`, `d.M.yyyy`, `dd/MM/yyyy`, `yyyy-MM-dd`, `dd.MM.yy`. Take the
first that parses **and** is not in the future and not older than two years — this guard
is what stops the parser latching onto a card expiry date or a "platnost do" line.
Everything else → `null`, and the form keeps today's date.

**Category.** A keyword map applied to the whole normalised text, first match wins:
`lekarna`/`pharmacy`/`apotheke` → `MEDICAL`, `skola`/`skolka`/`krouzek`/`tuition` →
`EDUCATION`, `potraviny`/`restaurace`/`bistro`/`cafe` → `FOOD`, `obleceni`/`obuv`/
`clothing` → `CLOTHING`, `hracky`/`toys` → `TOYS`, `jizdenka`/`ticket`/`benzin`/`fuel` →
`TRANSPORTATION`, `drogerie`/`household` → `HOUSEHOLD`. No match → `null`, and the form
stays on whatever the user had (`OTHER` by default).

## 3. Data — `data/mlkit/MlKitReceiptTextRecognizer.kt`

```kotlin
class MlKitReceiptTextRecognizer @Inject constructor(
    @ApplicationContext private val context: Context
) : ReceiptTextRecognizer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(imageUri: String): List<String> {
        val image = InputImage.fromFilePath(context, Uri.parse(imageUri))
        return recognizer.process(image).await()
            .textBlocks.flatMap { it.lines }.map { it.text }
    }
}
```

Bound in a new `di/ReceiptModule.kt`. The recognizer is a `@Singleton` so the model is
loaded once.

## 4. Domain — currency

```kotlin
/** Currencies the app offers. Code is ISO 4217; symbol is for compact UI. */
enum class SupportedCurrency(val code: String, val symbol: String) {
    CZK("CZK", "Kč"), EUR("EUR", "€"), PLN("PLN", "zł"), USD("USD", "$"),
    GBP("GBP", "£"), CHF("CHF", "CHF"), HUF("HUF", "Ft"),
    SEK("SEK", "kr"), DKK("DKK", "kr"), NOK("NOK", "kr")
}

/** Region-appropriate default, e.g. "CZ" -> CZK, "PL" -> PLN, "DE" -> EUR. */
fun defaultCurrencyForRegion(countryCode: String): SupportedCurrency
```

Mapping: `CZ` → CZK; `PL` → PLN; `DE`, `ES`, `AT`, `SK`, `FR`, `IT`, `NL`, `BE`, `PT`,
`IE`, `FI`, `GR` → EUR; `US` → USD; `GB` → GBP; `CH` → CHF; `HU` → HUF; `SE` → SEK;
`DK` → DKK; `NO` → NOK; anything else → **CZK** (the app's primary market).

Pure function, unit-tested.

## 5. Storage and Settings

`EncryptedPreferences` gains `putDefaultCurrency(code: String)` / `getDefaultCurrency():
String`, following the existing `putDarkTheme`/`getDarkTheme` style. On first read, when
nothing is stored, resolve from `Locale.getDefault().country` via
`defaultCurrencyForRegion` and persist the result, so the choice is stable afterwards even
if the device locale changes.

`SettingsScreen` gets a "Default currency" row showing the current code, opening an
`AlertDialog` with the `SupportedCurrency` list. Settings is reached from the gear action
in each top-level screen's top bar — no navigation changes needed.

## 6. Presentation — Add Expense

**Capture.** The empty receipt slot offers two buttons: "Take photo" and "From gallery".
The gallery path is unchanged (`PickVisualMedia`). The camera path uses
`ActivityResultContracts.TakePicture` writing into `cacheDir` through a new `FileProvider`
(`res/xml/file_paths.xml`, `cache-path`, authority `${applicationId}.fileprovider`), with
a runtime CAMERA permission request — the permission is already declared in the manifest
for QR pairing. Either way the resulting URI flows into `ReceiptStorage` exactly as today.

**Date field.** The form currently has no date input and always saves `LocalDate.now()`.
Since the parser now supplies a date, the form gains a date row with a `DatePickerDialog`;
otherwise a recognised date would be applied invisibly. `ExpenseViewModel.addExpense`
already accepts `date`, so no API change there.

**Currency selector.** A compact selector next to the amount field, pre-filled from the
stored default. `ExpenseViewModel.addExpense` gains a `currency: String` parameter, passed
straight into the `Expense`.

**Scan state and auto-fill.** After a photo is picked or shot, the ViewModel exposes
`ReceiptScanState`: `Idle`, `Scanning`, `Applied(fieldsFilled)`, `Failed`. On success the
screen fills **only fields the user has not filled in**: title if blank, amount if blank,
date if still today, category if still `OTHER`, currency if the user has not changed the
selector in this form session. A snackbar "Filled from receipt · Undo" restores a snapshot
of all form fields taken immediately before applying.

`Failed` (or `ReceiptScan.isEmpty`) shows "Couldn't read the receipt" and changes nothing;
the photo stays attached, because an unreadable receipt is still a receipt worth storing.

## 7. Currency correctness — accepted limitations

Writing a real currency onto expenses exposes something that USD-everywhere was hiding:

- `calculateExpenseBalance` sums amounts without any FX conversion, and
  `ExpenseScreen` takes the header's currency from the first expense of the month
  (`ExpenseScreen.kt:139`). A month mixing CZK and EUR will therefore show a wrong total.
  Conversion is **out of scope**; a separate ticket covers mixed-currency months.
- Display formatting is safe: `currencyFormat()` (`ExpenseSummaryHeader.kt:238`) already
  tolerates an unknown code instead of crashing, and the expense list formats per expense.
- `BudgetItem.kt:38` formats with a hardcoded `Locale.US` and a bare
  `Currency.getInstance(...)`, which renders non-USD budgets poorly and can throw on an
  unexpected code. It is switched to the same tolerant `currencyFormat()` helper — in
  scope because this change is what makes non-USD codes reachable.
- **Existing expense rows keep `"USD"`.** No data migration is performed; historical rows
  simply display as they were entered.

## 8. Strings

All new user-facing strings go into a tracked, feature-named file
`res/values/receipt_ocr_strings.xml` (never `strings.xml`, which is gitignored). Keys are
prefixed `receipt_` and `currency_` to avoid clashing with keys that already exist in
local `strings.xml` copies.

## 9. Testing

JVM unit tests, no Android dependencies:

- `ReceiptParserTest` — a Czech supermarket receipt, a pharmacy receipt, a receipt with a
  VAT breakdown that must not beat the total, `1 234,50 Kč` grouping, an English
  `TOTAL 12.99` receipt, a EUR receipt, a receipt with no total keyword (largest-number
  fallback), a future date that must be rejected, a card-expiry line that must not be read
  as the purchase date, and pure garbage input returning an empty `ReceiptScan`.
- `SupportedCurrencyTest` — region mapping including the unknown-region fallback.

`MlKitReceiptTextRecognizer` is not unit-tested: it is a thin wrapper around a Google API
with no logic of its own.

## 10. Follow-up tickets (not this work)

1. Mixed-currency months: either convert via an FX rate or group the balance per currency.
2. App localisation (Czech / English / other UI languages) — currently the UI is
   English-only with partial translations and `MissingTranslation` disabled in lint.
