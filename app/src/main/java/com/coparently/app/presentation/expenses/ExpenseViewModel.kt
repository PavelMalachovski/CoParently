package com.coparently.app.presentation.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.expenses.ExpenseBalance
import com.coparently.app.domain.expenses.calculateExpenseBalance
import com.coparently.app.domain.model.Expense
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.domain.model.ExpenseSummary
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.receipts.ReceiptParser
import com.coparently.app.domain.receipts.ReceiptScan
import com.coparently.app.domain.receipts.ReceiptTextRecognizer
import com.coparently.app.domain.repository.ExpenseRepository
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.domain.repository.ReceiptStorage
import com.coparently.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

/** Keeps derived flows warm across brief unsubscriptions (config changes). */
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * State of the "save expense" operation, driving the Add Expense screen.
 */
sealed interface ExpenseSaveState {
    data object Idle : ExpenseSaveState
    data object Saving : ExpenseSaveState

    /**
     * Expense stored locally (and synced when online).
     * [warning] is non-null when the receipt photo upload failed —
     * the expense itself was still saved, just without the receipt.
     */
    data class Saved(val warning: String? = null) : ExpenseSaveState

    /** Save could not proceed (e.g. no signed-in user); [message] is user-facing. */
    data class Error(val message: String) : ExpenseSaveState
}

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

@HiltViewModel
class ExpenseViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val userRepository: UserRepository,
    private val receiptStorage: ReceiptStorage,
    private val preferencesRepository: PreferencesRepository,
    private val receiptTextRecognizer: ReceiptTextRecognizer
) : ViewModel() {

    private val _currentUserId = MutableStateFlow<String>("")
    val currentUserId: StateFlow<String> = _currentUserId.asStateFlow()

    /** App-wide default currency, used to pre-fill the expense form. */
    val defaultCurrency: StateFlow<SupportedCurrency> =
        preferencesRepository.getDefaultCurrencyFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SupportedCurrency.DEFAULT)

    init {
        viewModelScope.launch {
            // Use the auth uid, which is available immediately after sign-in — the local
            // profile row (getCurrentUser) only exists after pairing, so relying on it
            // here left unpaired accounts with an empty id and a silently-failing Save.
            userRepository.getCurrentUserId()?.let { uid ->
                _currentUserId.value = uid
                expenseRepository.syncWithFirestore()
            }
        }
    }

    val expenses: StateFlow<List<Expense>> = expenseRepository.getAllExpenses()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _expenseSummary = MutableStateFlow<ExpenseSummary?>(null)
    val expenseSummary: StateFlow<ExpenseSummary?> = _expenseSummary.asStateFlow()

    /** uid -> "mom"/"dad", so a payer can be named and coloured. Empty until users sync. */
    val roleByUid: StateFlow<Map<String, String>> = userRepository.getAllUsers()
        .map { users -> users.associate { it.id to it.role } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /** Expenses dated within the current calendar month, newest first. */
    val expensesThisMonth: StateFlow<List<Expense>> = expenses
        .map { all ->
            val today = LocalDate.now()
            all.filter {
                it.date.year == today.year && it.date.month == today.month
            }.sortedByDescending { it.date }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * This month's who-paid-what split and settle-up figure.
     *
     * Derived rather than stored: [calculateExpenseBalance] is pure, so the split bar cannot
     * drift out of sync with the list it summarises.
     */
    val balance: StateFlow<ExpenseBalance> = combine(
        expensesThisMonth,
        _currentUserId,
        roleByUid
    ) { monthExpenses, userId, roles ->
        calculateExpenseBalance(monthExpenses, userId, roles)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        ExpenseBalance(0.0, 0.0, 0.0, 0.0, splitKnown = false)
    )

    private val _saveState = MutableStateFlow<ExpenseSaveState>(ExpenseSaveState.Idle)
    val saveState: StateFlow<ExpenseSaveState> = _saveState.asStateFlow()

    init {
        // Load initial summary for current month
        loadSummaryForMonth(LocalDate.now())
    }

    fun loadSummaryForMonth(date: LocalDate) {
        val start = date.withDayOfMonth(1)
        val end = date.withDayOfMonth(date.lengthOfMonth())

        viewModelScope.launch {
            _expenseSummary.value = expenseRepository.getExpenseSummary(start, end)
        }
    }

    /**
     * Saves a new expense. When [receiptImageUri] is provided, the photo is uploaded
     * to remote storage first and its download URL stored on the expense, so the
     * other parent sees the receipt too. An upload failure does not lose the expense —
     * it is saved without the receipt and a warning is surfaced via [saveState].
     */
    @Suppress("LongParameterList") // mirrors the Expense domain model fields
    fun addExpense(
        title: String,
        amount: Double,
        category: ExpenseCategory,
        currency: String,
        childId: String? = null,
        date: LocalDate = LocalDate.now(),
        notes: String? = null,
        receiptImageUri: String? = null
    ) {
        if (_saveState.value is ExpenseSaveState.Saving) return

        viewModelScope.launch {
            _saveState.value = ExpenseSaveState.Saving

            // Resolve the payer id now: init may not have completed yet, and there may
            // be no local profile row, so fall back to the auth uid directly.
            val userId = _currentUserId.value.ifEmpty { userRepository.getCurrentUserId() ?: "" }
            if (userId.isEmpty()) {
                _saveState.value = ExpenseSaveState.Error("You must be signed in to add an expense")
                return@launch
            }
            _currentUserId.value = userId

            val expenseId = UUID.randomUUID().toString()
            var receiptUrl: String? = null
            var warning: String? = null
            if (receiptImageUri != null) {
                receiptUrl = try {
                    receiptStorage.uploadReceipt(expenseId, receiptImageUri)
                } catch (
                    // Any upload failure (IO, storage, decode) must not lose the expense
                    @Suppress("TooGenericExceptionCaught") e: Exception
                ) {
                    android.util.Log.e("CoPlanlyUpload", "Receipt upload failed", e)
                    warning = "Receipt upload failed — expense saved without receipt"
                    null
                }
            }

            val expense = Expense(
                id = expenseId,
                childId = childId,
                title = title,
                amount = amount,
                category = category,
                currency = currency,
                paidBy = userId,
                date = date,
                receiptUrl = receiptUrl,
                notes = notes,
                createdAt = LocalDateTime.now()
            )
            expenseRepository.addExpense(expense)

            // Refresh summary
            loadSummaryForMonth(date)
            _saveState.value = ExpenseSaveState.Saved(warning)
        }
    }

    /** Resets [saveState] back to [ExpenseSaveState.Idle] after the UI consumed a result. */
    fun resetSaveState() {
        _saveState.value = ExpenseSaveState.Idle
    }

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

    /**
     * Removes the expense row so it leaves the list immediately.
     *
     * The receipt photo is deliberately left in storage: an Undo has to be able to bring the
     * expense back intact, and a deleted photo cannot be un-deleted. Call [purgeReceipt] once
     * the undo window has closed.
     *
     * @param expenseId Expense to remove
     */
    fun deleteExpense(expenseId: String) {
        viewModelScope.launch {
            expenseRepository.deleteExpense(expenseId)
            loadSummaryForMonth(LocalDate.now())
        }
    }

    /**
     * Puts back an expense removed by [deleteExpense], keeping its original id so the receipt
     * it points at is still the right one.
     *
     * @param expense The captured expense to restore
     */
    fun restoreExpense(expense: Expense) {
        viewModelScope.launch {
            expenseRepository.addExpense(expense)
            loadSummaryForMonth(expense.date)
        }
    }

    /**
     * Deletes the stored receipt photo for an expense the user did not undo.
     *
     * Best effort: an orphaned photo is a smaller problem than a failed delete, and the row is
     * already gone either way.
     *
     * @param expenseId Expense whose receipt should be removed
     */
    fun purgeReceipt(expenseId: String) {
        viewModelScope.launch {
            runCatching { receiptStorage.deleteReceipt(expenseId) }
        }
    }
}
