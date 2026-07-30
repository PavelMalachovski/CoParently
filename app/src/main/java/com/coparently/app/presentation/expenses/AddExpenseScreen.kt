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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.receipts.ReceiptScan
import com.coparently.app.presentation.theme.CoPlanlyShapes
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    onBack: () -> Unit,
    viewModel: ExpenseViewModel = hiltViewModel()
) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(ExpenseCategory.OTHER) }
    var notes by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var receiptUri by remember { mutableStateOf<Uri?>(null) }

    val defaultCurrency by viewModel.defaultCurrency.collectAsState()
    var currency by remember { mutableStateOf<SupportedCurrency?>(null) }
    val effectiveCurrency = currency ?: defaultCurrency

    var date by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    val saveState by viewModel.saveState.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val isSaving = saveState is ExpenseSaveState.Saving
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
    // above end by setting receiptUri.
    LaunchedEffect(receiptUri) {
        receiptUri?.let { viewModel.scanReceipt(it.toString()) }
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
                title = { Text(stringResource(R.string.expense_add_title)) },
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

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (isFormValid) {
                        viewModel.addExpense(
                            title = title,
                            amount = requireNotNull(amountValue),
                            category = category,
                            currency = effectiveCurrency.code,
                            date = date,
                            notes = notes.takeIf { it.isNotBlank() },
                            receiptImageUri = receiptUri?.toString()
                        )
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
        }
    }
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
            value = category.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.expense_field_category)) },
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
                    text = { Text(cat.displayName) },
                    onClick = { onCategorySelected(cat) }
                )
            }
        }
    }
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
 */
private data class ReceiptScanUpdate(
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
 * still today, category only while it is still [ExpenseCategory.OTHER], and currency only
 * while the selector has not been touched ([ReceiptScanFormState.currency] is still null).
 */
private fun buildReceiptScanUpdate(scan: ReceiptScan, formState: ReceiptScanFormState): ReceiptScanUpdate =
    ReceiptScanUpdate(
        title = scan.merchant?.takeIf { formState.title.isBlank() },
        amount = scan.total?.takeIf { formState.amount.isBlank() }?.toString(),
        category = scan.category?.takeIf { formState.category == ExpenseCategory.OTHER },
        date = scan.date?.takeIf { formState.date == LocalDate.now() },
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
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.expense_remove_receipt))
            }
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
