package com.coparently.app.presentation.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.expenses.CurrencyBalance
import com.coparently.app.domain.expenses.CurrencyBreakdown
import com.coparently.app.domain.expenses.breakdownByCurrency
import com.coparently.app.domain.expenses.calculateExpenseBalancesByCurrency
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
import com.coparently.app.presentation.common.Loadable
import com.coparently.app.presentation.common.Parents
import com.coparently.app.presentation.common.ParentsSource
import com.coparently.app.presentation.common.stateInLoadable
import com.coparently.app.presentation.common.valueOrNull
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
import java.time.YearMonth
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
    private val receiptTextRecognizer: ReceiptTextRecognizer,
    parentsSource: ParentsSource
) : ViewModel() {

    /**
     * Signed-in parent and paired co-parent, for naming a payer and for [roleByUid].
     */
    val parents: StateFlow<Parents> = parentsSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Parents())

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

    val expenses: StateFlow<Loadable<List<Expense>>> = expenseRepository.getAllExpenses()
        .stateInLoadable(viewModelScope)

    /**
     * The same expenses, flattened to a plain list for everything derived from them.
     *
     * The derivations below — the month's slice, the per-currency balances, the pie chart — are
     * all "given these expenses, compute that", and none of them has anything different to say
     * while the list is still loading. Only the screen does, which is why [expenses] carries the
     * distinction and this does not.
     */
    private val loadedExpenses: StateFlow<List<Expense>> = expenses
        .map { it.valueOrNull.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _expenseSummary = MutableStateFlow<ExpenseSummary?>(null)
    val expenseSummary: StateFlow<ExpenseSummary?> = _expenseSummary.asStateFlow()

    /**
     * uid -> slot, so a payer can be named and coloured. Empty until the profiles load.
     *
     * Built from the two parents rather than from `userRepository.getAllUsers()`, which is what
     * this used to read. Room only ever stores a `users` row for the signed-in user — nothing
     * anywhere writes one for the co-parent — so that map could never hold more than one uid,
     * and `ExpenseBalance.splitKnown` (which requires both slots to be present) was therefore
     * false on every device, hiding the entire split block in `ExpenseSummaryHeader` from
     * everyone who has ever used the app. Reading the co-parent's slot from their own profile
     * document is what makes the second entry real.
     *
     * A pair whose two parents still share a slot yields two entries with the same value, which
     * keeps `splitKnown` false. That is correct and deliberate: the two people cannot be told
     * apart yet, and a split bar attributing one parent's spending to the other would be worse
     * than no split bar.
     */
    val roleByUid: StateFlow<Map<String, String>> = parents
        .map { it.roleByUid }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /**
     * The month the list and balance are showing. Starts on the current month; the Expenses
     * screen pages it back and forth so expenses dated in other months (e.g. an older receipt)
     * are reachable instead of silently missing from the current-month-only view.
     */
    private val _selectedMonth = MutableStateFlow(YearMonth.now())
    val selectedMonth: StateFlow<YearMonth> = _selectedMonth.asStateFlow()

    /** Shows the month before the one currently selected. */
    fun showPreviousMonth() {
        _selectedMonth.value = _selectedMonth.value.minusMonths(1)
    }

    /** Shows the month after the one currently selected. */
    fun showNextMonth() {
        _selectedMonth.value = _selectedMonth.value.plusMonths(1)
    }

    /**
     * The selected month and the figures that belong to **that** month, as one value.
     *
     * Computed in a single `combine` from the raw sources rather than chained one off another,
     * so the month can never be observed alongside another month's expenses. Two separate flows
     * derived from `_selectedMonth` do allow exactly that: `combine` re-emits per upstream
     * change, so paging emitted the new month beside the old list for an instant.
     *
     * Invisible while the swap is instant. Not invisible once the screen animates between
     * months — the outgoing half of a slide renders the state it is leaving, so a bundle is what
     * lets it show the month it is actually leaving rather than the one arriving.
     */
    val monthOfExpenses: StateFlow<MonthOfExpenses> = combine(
        loadedExpenses,
        _selectedMonth,
        _currentUserId,
        roleByUid
    ) { all, month, userId, roles ->
        val ofMonth = all.filter { YearMonth.from(it.date) == month }
            .sortedByDescending { it.date }
        MonthOfExpenses(
            month = month,
            expenses = ofMonth,
            balances = calculateExpenseBalancesByCurrency(ofMonth, userId, roles)
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        MonthOfExpenses(YearMonth.now(), emptyList(), emptyList())
    )

    /** Expenses dated within [selectedMonth], newest first. */
    val monthExpenses: StateFlow<List<Expense>> = monthOfExpenses
        .map { it.expenses }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * The selected month's who-paid-what split and settle-up figure, one entry per currency.
     *
     * Derived rather than stored: [calculateExpenseBalancesByCurrency] is pure, so the split bars
     * cannot drift out of sync with the list they summarise. Split by currency because the app
     * does no FX conversion — a month mixing currencies must not be added into one wrong total.
     */
    val balancesByCurrency: StateFlow<List<CurrencyBalance>> = monthOfExpenses
        .map { it.balances }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * Whose spending the analytics view is showing: a Firebase uid, or null for everyone.
     *
     * A uid rather than a slot, because [Expense.paidBy] is a uid and going via a slot would
     * attribute both parents' spending to one of them on a pair whose slots have not been
     * separated yet — the same trap [com.coparently.app.presentation.common.ParentNames.labelForUid]
     * exists to keep callers out of.
     */
    private val _analyticsPayer = MutableStateFlow<String?>(null)
    val analyticsPayer: StateFlow<String?> = _analyticsPayer.asStateFlow()

    /** The currency the user picked from the chip row, or null while they have picked none. */
    private val _pickedCurrency = MutableStateFlow<String?>(null)

    /**
     * The selected month's spending by category, one entry per currency.
     *
     * Derived from [monthExpenses] rather than from a second repository read, so the chart and
     * the list below it can never be looking at different months.
     */
    val breakdowns: StateFlow<List<CurrencyBreakdown>> = combine(
        monthExpenses,
        _analyticsPayer
    ) { expenses, payer ->
        breakdownByCurrency(expenses, payer)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * The one breakdown the chart and the table draw, or null when the month has nothing in it.
     *
     * **One currency at a time, always.** The app does no FX conversion, and a pie is a single
     * total cut into slices — so there is no honest way to draw two currencies in one chart, and
     * this deliberately hands out exactly one.
     *
     * The user's pick wins, but only while it is still on offer. Changing the payer filter, or
     * the month, can remove the currency they had selected; falling back then is what stops the
     * screen going blank while a chip row sits above it showing something else. The fallback is
     * the app's default currency when the month contains it, and otherwise the currency with the
     * largest total — [breakdownByCurrency] already orders them that way.
     */
    val selectedBreakdown: StateFlow<CurrencyBreakdown?> = combine(
        breakdowns,
        _pickedCurrency,
        defaultCurrency
    ) { available, picked, fallbackCurrency ->
        available.firstOrNull { it.currency == picked }
            ?: available.firstOrNull { it.currency == fallbackCurrency.code }
            ?: available.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * The two uids the payer filter offers, or empty when it must not be shown at all.
     *
     * Empty while unpaired, before the two profiles resolve, and on a pair whose parents still
     * share a slot — the same condition [com.coparently.app.domain.expenses.ExpenseBalance.splitKnown]
     * models, and for the same reason. A filter offering "Parent" and "Parent" is worse than no
     * filter: it invites a choice the app cannot honour.
     */
    val analyticsPayers: StateFlow<List<String>> = parents
        .map { known ->
            listOfNotNull(known.me?.uid, known.coParent?.uid)
                .filter { it.isNotBlank() }
                .distinct()
                .takeIf { it.size == 2 }
                .orEmpty()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * Shows only [uid]'s spending, or everyone's when null.
     *
     * The currency selection is deliberately left alone: [selectedBreakdown] already falls back
     * when the picked currency stops being available, and clearing it here would also throw away
     * a still-valid choice every time the filter moved.
     */
    fun selectAnalyticsPayer(uid: String?) {
        _analyticsPayer.value = uid
    }

    /** Draws [currency] instead of the default. */
    fun selectAnalyticsCurrency(currency: String) {
        _pickedCurrency.value = currency
    }

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

    /** Loads a single expense for the edit form, or null when it no longer exists. */
    suspend fun getExpense(expenseId: String): Expense? = expenseRepository.getExpenseById(expenseId)

    /**
     * Saves edits to an existing expense.
     *
     * Follows the same field-preserving rule as event editing: the loaded [original] is kept and
     * `copy()`-ed, so id, payer, createdAt, split and sync flags survive — rebuilding the expense
     * from scratch would wipe them.
     *
     * [receiptImageUri] may be the expense's existing remote URL (kept as-is), a new local photo
     * URI (uploaded, replacing the old one), or null (receipt removed). An upload failure keeps
     * the previous receipt and surfaces a warning rather than losing the edit.
     */
    @Suppress("LongParameterList") // mirrors the Expense domain model fields
    fun updateExpense(
        original: Expense,
        title: String,
        amount: Double,
        category: ExpenseCategory,
        currency: String,
        date: LocalDate = original.date,
        notes: String? = null,
        receiptImageUri: String? = null
    ) {
        if (_saveState.value is ExpenseSaveState.Saving) return

        viewModelScope.launch {
            _saveState.value = ExpenseSaveState.Saving

            var warning: String? = null
            val receiptUrl = when (receiptImageUri) {
                null -> null
                original.receiptUrl -> original.receiptUrl // unchanged remote photo, no re-upload
                else -> try {
                    receiptStorage.uploadReceipt(original.id, receiptImageUri)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception
                ) {
                    android.util.Log.e("CoPlanlyUpload", "Receipt upload failed", e)
                    warning = "Receipt upload failed — expense saved without the new receipt"
                    original.receiptUrl
                }
            }

            val updated = original.copy(
                title = title,
                amount = amount,
                category = category,
                currency = currency,
                date = date,
                receiptUrl = receiptUrl,
                notes = notes
            )
            expenseRepository.updateExpense(updated)

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

/**
 * One month of expenses, carried together with the month they belong to.
 *
 * The month is part of the value rather than a separate flow beside it, so nothing can render
 * September's heading over August's rows — not for a frame, and not for the 500 ms a slide
 * between months lasts.
 *
 * @property month The month these figures describe.
 * @property expenses Expenses dated within [month], newest first.
 * @property balances Who-paid-what and the settle-up figure, one entry per currency present.
 */
data class MonthOfExpenses(
    val month: YearMonth,
    val expenses: List<Expense>,
    val balances: List<CurrencyBalance>
)
