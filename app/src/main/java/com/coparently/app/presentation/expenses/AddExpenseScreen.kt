package com.coparently.app.presentation.expenses

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.coparently.app.R
import com.coparently.app.domain.expenses.SplitRatio
import com.coparently.app.domain.expenses.WHOLE_PERCENT
import com.coparently.app.domain.family.FamilyMemberRef
import com.coparently.app.domain.model.Expense
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.receipts.ReceiptScan
import com.coparently.app.presentation.common.FamilyMemberChips
import com.coparently.app.presentation.common.FullScreenImageDialog
import com.coparently.app.presentation.common.toggling
import com.coparently.app.presentation.theme.CoPlanlyShapes
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    onBack: () -> Unit,
    expenseId: String? = null,
    viewModel: ExpenseViewModel = hiltViewModel()
) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(ExpenseCategory.OTHER) }
    var notes by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var receiptUri by remember { mutableStateOf<Uri?>(null) }

    // The expense being edited (null in add mode). Kept whole so save can copy() it and preserve
    // fields the form does not touch — id, payer, createdAt, split — instead of rebuilding it.
    var editedExpense by remember { mutableStateOf<Expense?>(null) }

    val defaultCurrency by viewModel.defaultCurrency.collectAsState()
    var currency by remember { mutableStateOf<SupportedCurrency?>(null) }
    val effectiveCurrency = currency ?: defaultCurrency

    val familyMembers by viewModel.familyMembers.collectAsState()
    // Who the money was for. Empty is "the family", which is what every expense recorded before
    // this picker existed is, and what a family with fewer than two members keeps being: the
    // chips do not render at all below two, so nothing can be picked and nothing is claimed.
    var forMembers by remember { mutableStateOf(emptyList<FamilyMemberRef>()) }

    var date by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showOtherMonthWarning by remember { mutableStateOf(false) }
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    // In edit mode, load the expense once and prefill every field from it.
    LaunchedEffect(expenseId) {
        if (expenseId != null) {
            viewModel.getExpense(expenseId)?.let { expense ->
                editedExpense = expense
                title = expense.title
                amount = expense.amount.toString()
                category = expense.category
                notes = expense.notes.orEmpty()
                date = expense.date
                currency = SupportedCurrency.fromCode(expense.currency)
                forMembers = expense.forMembers
                expense.receiptUrl?.let { receiptUri = Uri.parse(it) }
            }
        }
    }

    val saveState by viewModel.saveState.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val isSaving = saveState is ExpenseSaveState.Saving
    val agreedRatio by viewModel.agreedRatio.collectAsState()
    // A new expense is shared by default: that is what a co-parenting expense tracker is for, and
    // the alternative — every row unshared unless ticked — is how the balance came to read zero.
    var shared by rememberSaveable { mutableStateOf(true) }
    // Null means "follow whatever the family agreed", which is the case that must not need a tap.
    var overrideMomPercent by rememberSaveable { mutableStateOf<Int?>(null) }
    val amountValue = amount.toDoubleOrNull()
    val isFormValid = title.isNotBlank() && amountValue != null
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) receiptUri = uri }

    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success) receiptUri = pendingCaptureUri }

    val takePhoto = {
        val uri = createReceiptCaptureUri(context)
        pendingCaptureUri = uri
        try {
            cameraLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.receipt_camera_unavailable, Toast.LENGTH_LONG).show()
        }
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

    ExpenseSaveEffect(
        saveState = saveState,
        onSaved = onBack,
        onConsumed = viewModel::resetSaveState
    )

    // A fresh photo (camera or gallery) is the single trigger for OCR — both capture paths
    // above end by setting receiptUri. The existing receipt pre-loaded in edit mode is not a
    // fresh photo (and is a remote URL OCR can't read), so it is deliberately skipped.
    LaunchedEffect(receiptUri) {
        val uri = receiptUri ?: return@LaunchedEffect
        if (uri.toString() != editedExpense?.receiptUrl) {
            viewModel.scanReceipt(uri.toString())
        }
    }

    ReceiptScanEffect(
        scanState = scanState,
        formState = ReceiptScanFormState(title, amount, category, date, currency),
        callbacks = ReceiptScanCallbacks(
            onTitleChange = { title = it },
            onAmountChange = { amount = it },
            onCategoryChange = { category = it },
            onDateChange = { date = it },
            onCurrencyChange = { currency = it },
            onScanConsumed = viewModel::resetScanState
        ),
        snackbarHostState = snackbarHostState
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (expenseId == null) R.string.expense_add_title else R.string.expense_edit_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.budgets_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.expense_field_title)) },
                modifier = Modifier.fillMaxWidth()
            )

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

            ExpenseCategoryDropdown(
                category = category,
                expanded = expanded,
                onExpandedChange = { expanded = it },
                onCategorySelected = {
                    category = it
                    expanded = false
                }
            )

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

            FamilyMemberChips(
                members = familyMembers,
                selected = forMembers,
                onToggle = { forMembers = forMembers.toggling(it) },
                label = R.string.expense_for_members
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.expense_field_notes)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            ReceiptSection(
                pickerState = ReceiptPickerState(
                    receiptUri = receiptUri,
                    enabled = !isSaving,
                    hasCamera = hasCamera
                ),
                scanState = scanState,
                onTakePhoto = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) takePhoto() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onPickPhoto = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemovePhoto = { receiptUri = null }
            )

            // Only on a new expense: an edit keeps the split it was recorded under, which is the
            // whole point of snapshotting it — re-opening a settled month must not re-price it.
            if (editedExpense == null) {
                SplitSection(
                    agreedRatio = agreedRatio,
                    shared = shared,
                    onSharedChange = { shared = it },
                    overrideMomPercent = overrideMomPercent,
                    onOverrideChange = { overrideMomPercent = it },
                    enabled = !isSaving
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val performSave = {
                val original = editedExpense
                if (original == null) {
                    viewModel.addExpense(
                        title = title,
                        amount = requireNotNull(amountValue),
                        category = category,
                        currency = effectiveCurrency.code,
                        forMembers = forMembers,
                        date = date,
                        notes = notes.takeIf { it.isNotBlank() },
                        receiptImageUri = receiptUri?.toString(),
                        shared = shared,
                        splitOverride = overrideMomPercent?.let { SplitRatio.ofMomPercent(it) }
                    )
                } else {
                    viewModel.updateExpense(
                        original = original,
                        title = title,
                        amount = requireNotNull(amountValue),
                        category = category,
                        currency = effectiveCurrency.code,
                        date = date,
                        notes = notes.takeIf { it.isNotBlank() },
                        receiptImageUri = receiptUri?.toString(),
                        forMembers = forMembers
                    )
                }
            }

            Button(
                onClick = {
                    if (isFormValid) {
                        // Saving an expense dated in another month files it there — and the list
                        // only shows one month at a time — so confirm first instead of it
                        // silently vanishing from the current month (a real receipt-scan bug).
                        if (YearMonth.from(date) == YearMonth.now()) performSave() else showOtherMonthWarning = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving && isFormValid
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.expense_save))
                }
            }

            ExpenseDatePickerDialog(
                visible = showDatePicker,
                date = date,
                onConfirm = { date = it },
                onDismiss = { showDatePicker = false }
            )

            OtherMonthWarningDialog(
                visible = showOtherMonthWarning,
                date = date,
                onConfirm = {
                    showOtherMonthWarning = false
                    performSave()
                },
                onDismiss = { showOtherMonthWarning = false }
            )
        }
    }
}

/**
 * Confirmation shown when the expense's date falls outside the current month: saving is still
 * allowed, but the user is told the expense will land under [date]'s month rather than silently
 * disappearing from the current-month list.
 */
@Composable
private fun OtherMonthWarningDialog(
    visible: Boolean,
    date: LocalDate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val monthLabel = remember(date) {
        date.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expense_date_other_month_title)) },
        text = { Text(stringResource(R.string.expense_date_other_month_message, monthLabel)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.expense_date_other_month_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.expense_date_cancel))
            }
        }
    )
}

/**
 * Reacts to [ExpenseSaveState] changes: shows a warning toast on a partial save, an error as a
 * toast, and calls [onSaved] once the expense is actually saved. Extracted out of
 * [AddExpenseScreen] to keep that composable's cyclomatic complexity down.
 */
@Composable
private fun ExpenseSaveEffect(
    saveState: ExpenseSaveState,
    onSaved: () -> Unit,
    onConsumed: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(saveState) {
        when (val state = saveState) {
            is ExpenseSaveState.Saved -> {
                state.warning?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                onConsumed()
                onSaved()
            }
            is ExpenseSaveState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                onConsumed()
            }
            else -> Unit
        }
    }
}

/**
 * Category dropdown field. Extracted out of [AddExpenseScreen] to keep that composable's
 * cyclomatic complexity down — the category list never needs the rest of the form's state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseCategoryDropdown(
    category: ExpenseCategory,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCategorySelected: (ExpenseCategory) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { onExpandedChange(!expanded) },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = stringResource(category.labelRes),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.expense_field_category)) },
            // The selected category's dot, in the same colour its slice wears on the analytics
            // chart — one palette, every surface (owner ask, Aug 2026 walkthrough).
            leadingIcon = { CategoryDot(category) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            ExpenseCategory.values().forEach { cat ->
                DropdownMenuItem(
                    text = { Text(stringResource(cat.labelRes)) },
                    leadingIcon = { CategoryDot(cat) },
                    onClick = { onCategorySelected(cat) }
                )
            }
        }
    }
}

/** The category's colour, as the chart's own slice hue — a dot, not an icon tint. */
@Composable
private fun CategoryDot(category: ExpenseCategory) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(category.sliceColor())
    )
}

/**
 * Date picker dialog for the expense date field, shown only while [visible].
 *
 * Extracted out of [AddExpenseScreen] to keep that composable's cyclomatic complexity down —
 * the dialog owns no state of its own, it just reports the confirmed date back up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseDatePickerDialog(
    visible: Boolean,
    date: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = date
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { millis ->
                    // The picker reports UTC midnight; converting through the system
                    // zone here would shift the date by a day in negative offsets.
                    onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                }
                onDismiss()
            }) {
                Text(stringResource(R.string.expense_date_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.expense_date_cancel))
            }
        }
    ) {
        DatePicker(state = pickerState)
    }
}

/**
 * Current values of the fields a receipt scan may pre-fill, read by [ReceiptScanEffect] to
 * decide whether each one is still untouched.
 *
 * Internal rather than private so the timing behaviour of [ReceiptScanEffect] can be driven
 * directly from a Compose UI test (`ReceiptScanEffectTest`) without standing up the whole form.
 */
internal data class ReceiptScanFormState(
    val title: String,
    val amount: String,
    val category: ExpenseCategory,
    val date: LocalDate,
    val currency: SupportedCurrency?
)

/**
 * Setters back into the Add Expense form, used by [ReceiptScanEffect] to apply or undo a scan.
 * Internal for the same reason as [ReceiptScanFormState].
 */
internal data class ReceiptScanCallbacks(
    val onTitleChange: (String) -> Unit,
    val onAmountChange: (String) -> Unit,
    val onCategoryChange: (ExpenseCategory) -> Unit,
    val onDateChange: (LocalDate) -> Unit,
    val onCurrencyChange: (SupportedCurrency?) -> Unit,
    val onScanConsumed: () -> Unit
)

/** Form values captured before a receipt scan is applied, so Undo can put them back. */
private data class ReceiptUndoSnapshot(
    val title: String,
    val amount: String,
    val category: ExpenseCategory,
    val date: LocalDate,
    val currency: SupportedCurrency?
)

/**
 * Which fields a receipt scan would still change, given the form state at the time it
 * completed. A null field means that field is untouched by the scan — either the scan found
 * nothing for it, or the user had already filled it in.
 *
 * Internal, not private, because it is [buildReceiptScanUpdate]'s return type and that function
 * is internal for testability — see [buildReceiptScanUpdate].
 */
internal data class ReceiptScanUpdate(
    val title: String? = null,
    val amount: String? = null,
    val category: ExpenseCategory? = null,
    val date: LocalDate? = null,
    val currency: SupportedCurrency? = null
) {
    val hasChanges: Boolean
        get() = listOfNotNull(title, amount, category, date, currency).isNotEmpty()
}

/**
 * Works out which of [formState]'s still-untouched fields [scan] can fill in.
 *
 * Each field keeps its own untouched test: title/amount only when blank, date only while it is
 * still [today], category only while it is still [ExpenseCategory.OTHER], and currency only
 * while the selector has not been touched ([ReceiptScanFormState.currency] is still null).
 *
 * Internal (not private) — and takes [today] rather than reading `LocalDate.now()` itself — so
 * `BuildReceiptScanUpdateTest` can exercise the non-overwrite rule as a plain JVM unit test,
 * without a wall-clock dependency or a Compose UI test.
 *
 * @param scan Fields read off the receipt photo
 * @param formState Current form values, used to decide which fields are still untouched
 * @param today Reference date the "date field untouched" check compares against
 */
internal fun buildReceiptScanUpdate(
    scan: ReceiptScan,
    formState: ReceiptScanFormState,
    today: LocalDate = LocalDate.now()
): ReceiptScanUpdate =
    ReceiptScanUpdate(
        title = scan.merchant?.takeIf { formState.title.isBlank() },
        amount = scan.total?.takeIf { formState.amount.isBlank() }?.toString(),
        category = scan.category?.takeIf { formState.category == ExpenseCategory.OTHER },
        date = scan.date?.takeIf { formState.date == today },
        currency = SupportedCurrency.fromCode(scan.currency)?.takeIf { formState.currency == null }
    )

/** Writes whichever fields [update] carries back into the form via [callbacks]. */
private fun applyReceiptScanUpdate(update: ReceiptScanUpdate, callbacks: ReceiptScanCallbacks) {
    update.title?.let(callbacks.onTitleChange)
    update.amount?.let(callbacks.onAmountChange)
    update.category?.let(callbacks.onCategoryChange)
    update.date?.let(callbacks.onDateChange)
    update.currency?.let(callbacks.onCurrencyChange)
}

/** Restores the fields a scan changed, back to what the user had before it was applied. */
private fun restoreReceiptScanSnapshot(snapshot: ReceiptUndoSnapshot, callbacks: ReceiptScanCallbacks) {
    callbacks.onTitleChange(snapshot.title)
    callbacks.onAmountChange(snapshot.amount)
    callbacks.onCategoryChange(snapshot.category)
    callbacks.onDateChange(snapshot.date)
    callbacks.onCurrencyChange(snapshot.currency)
}

/**
 * Applies a completed receipt scan to whichever fields are still untouched, and offers an
 * Undo snackbar when anything changed. A failed scan changes nothing but still surfaces a
 * snackbar; the photo stays attached either way.
 *
 * Extracted out of [AddExpenseScreen] so the apply/undo branching does not count toward that
 * composable's cyclomatic complexity.
 *
 * Internal (not private) so `ReceiptScanEffectTest` can drive it directly from a fake
 * `MutableStateFlow<ReceiptScanState>` and assert the Undo snackbar survives the recomposition
 * that follows [ReceiptScanCallbacks.onScanConsumed].
 */
@Composable
internal fun ReceiptScanEffect(
    scanState: ReceiptScanState,
    formState: ReceiptScanFormState,
    callbacks: ReceiptScanCallbacks,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current

    LaunchedEffect(scanState) {
        when (val state = scanState) {
            is ReceiptScanState.Applied -> {
                val update = buildReceiptScanUpdate(state.scan, formState)
                // onScanConsumed() resets the ViewModel's scanState back to Idle, and this
                // effect is keyed on scanState — resetting it early would change the key while
                // showSnackbar is still suspended, cancelling this coroutine and, with it, the
                // snackbar (SnackbarHostState clears currentSnackbarData on cancellation). It
                // must run last, and in a finally so navigating away mid-snackbar still clears
                // the ViewModel state instead of leaving a stale Applied/Failed to re-apply.
                try {
                    if (update.hasChanges) {
                        val snapshot = ReceiptUndoSnapshot(
                            title = formState.title,
                            amount = formState.amount,
                            category = formState.category,
                            date = formState.date,
                            currency = formState.currency
                        )
                        applyReceiptScanUpdate(update, callbacks)
                        val result = snackbarHostState.showSnackbar(
                            message = context.getString(R.string.receipt_scan_applied),
                            actionLabel = context.getString(R.string.receipt_scan_undo),
                            duration = SnackbarDuration.Short
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            restoreReceiptScanSnapshot(snapshot, callbacks)
                        }
                    }
                } finally {
                    callbacks.onScanConsumed()
                }
            }

            ReceiptScanState.Failed -> {
                try {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.receipt_scan_failed),
                        duration = SnackbarDuration.Short
                    )
                } finally {
                    callbacks.onScanConsumed()
                }
            }

            else -> Unit
        }
    }
}

/**
 * Display state for [ReceiptPicker]: the current photo (if any), whether its controls
 * are enabled, and whether the device has a camera to photograph with. Bundled into one
 * class so the composable does not take an ever-growing list of parameters.
 */
private data class ReceiptPickerState(
    val receiptUri: Uri?,
    val enabled: Boolean,
    val hasCamera: Boolean
)

/**
 * Receipt area of the form: a progress row while on-device OCR is running, then the photo
 * picker/preview. Bundled into one composable so [AddExpenseScreen] does not need its own
 * branch to decide whether the scanning row shows.
 */
@Composable
private fun ReceiptSection(
    pickerState: ReceiptPickerState,
    scanState: ReceiptScanState,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit
) {
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
    ReceiptPicker(
        state = pickerState,
        onTakePhoto = onTakePhoto,
        onPickPhoto = onPickPhoto,
        onRemovePhoto = onRemovePhoto
    )
}

/**
 * Receipt photo section of the form: a button to attach a photo, or a preview
 * of the picked image with a remove control.
 */
@Composable
private fun ReceiptPicker(
    state: ReceiptPickerState,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit
) {
    val (receiptUri, enabled, hasCamera) = state
    if (receiptUri == null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasCamera) {
                OutlinedButton(
                    onClick = onTakePhoto,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.receipt_take_photo))
                }
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
        // Tapping a row opens this editor, so this 180dp crop is the receipt most parents
        // actually reach — and a crop of a portrait receipt hides most of it. It is now the
        // entry point to the full-screen zoomable viewer rather than an inert decoration.
        var viewingFullScreen by rememberSaveable { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = receiptUri,
                contentDescription = stringResource(R.string.image_viewer_open),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(CoPlanlyShapes.medium)
                    .clickable { viewingFullScreen = true }
            )
            FilledTonalIconButton(
                onClick = onRemovePhoto,
                enabled = enabled,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.expense_remove_receipt))
            }
        }
        if (viewingFullScreen) {
            FullScreenImageDialog(
                model = receiptUri,
                contentDescription = stringResource(R.string.expenses_receipt_photo),
                onDismiss = { viewingFullScreen = false }
            )
        }
    }
}

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

/**
 * How this one expense divides.
 *
 * Two decisions, in the order they matter. **Is it shared at all** — a parent buying something
 * purely for themselves is not a claim on the other, and before this the app had no way to say so
 * because `splitBetween` was empty on every row it ever wrote, which made every expense look
 * unshared to the maths and fully owed on the screen. Then, **does it follow the family's agreed
 * split** — normally yes, and the row says what that is, but "you cover this school trip" is a
 * real thing two parents agree about one expense and not about all of them.
 *
 * A one-off override changes nothing about the agreement: it is stamped onto this expense and
 * never proposed to the co-parent, because there is nothing here for them to agree to that they
 * have not already agreed by recording the expense together.
 *
 * @param agreedRatio The family's agreed split, shown as the default.
 * @param shared Whether this expense is split at all.
 * @param onSharedChange Toggles that.
 * @param overrideMomPercent Slot 1's share for this expense alone, or null to follow the agreement.
 * @param onOverrideChange Sets or clears the override.
 * @param enabled False while a save is in flight.
 */
@Composable
private fun SplitSection(
    agreedRatio: SplitRatio,
    shared: Boolean,
    onSharedChange: (Boolean) -> Unit,
    overrideMomPercent: Int?,
    onOverrideChange: (Int?) -> Unit,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.expense_split_shared),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = shared, onCheckedChange = onSharedChange, enabled = enabled)
        }

        if (!shared) {
            Text(
                text = stringResource(R.string.expense_split_not_shared_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        val effective = overrideMomPercent ?: agreedRatio.momPercent
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(
                    R.string.expense_split_ratio,
                    effective,
                    WHOLE_PERCENT - effective
                ),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = overrideMomPercent != null,
                onCheckedChange = { on ->
                    onOverrideChange(if (on) agreedRatio.momPercent else null)
                },
                enabled = enabled
            )
        }
        Text(
            text = stringResource(R.string.expense_split_override_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (overrideMomPercent != null) {
            Slider(
                value = overrideMomPercent.toFloat(),
                onValueChange = { onOverrideChange(it.toInt()) },
                valueRange = 0f..WHOLE_PERCENT.toFloat(),
                steps = SPLIT_SLIDER_STEPS,
                enabled = enabled
            )
        }
    }
}

/** Stops on the slider: every 5 %, which is nineteen stops between the two ends. */
private const val SPLIT_SLIDER_STEPS = 19
