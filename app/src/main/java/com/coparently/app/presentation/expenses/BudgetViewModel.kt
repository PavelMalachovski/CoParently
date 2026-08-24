package com.coparently.app.presentation.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.model.Budget
import com.coparently.app.domain.model.BudgetAlert
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.repository.BudgetRepository
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val userRepository: UserRepository,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val _currentUserId = MutableStateFlow<String>("")

    /** App-wide default currency, written onto new budgets — mirrors [ExpenseViewModel]. */
    private val defaultCurrency: StateFlow<SupportedCurrency> =
        preferencesRepository.getDefaultCurrencyFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SupportedCurrency.DEFAULT)

    init {
        viewModelScope.launch {
            userRepository.getCurrentUser()?.let { user ->
                _currentUserId.value = user.id
                budgetRepository.syncWithFirestore()
            }
        }
    }

    val budgets: StateFlow<List<Budget>> = budgetRepository.getActiveBudgets()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _activeAlerts = MutableStateFlow<List<BudgetAlert>>(emptyList())
    val activeAlerts: StateFlow<List<BudgetAlert>> = _activeAlerts.asStateFlow()

    init {
        refreshAlerts()
    }

    fun refreshAlerts() {
        viewModelScope.launch {
            _activeAlerts.value = budgetRepository.getBudgetAlerts()
        }
    }

    suspend fun getSpentForBudget(budgetId: String): Double {
        return budgetRepository.getSpentForBudget(budgetId)
    }

    fun addBudget(
        category: ExpenseCategory,
        monthlyLimit: Double,
        childId: String? = null
    ) {
        viewModelScope.launch {
            val budget = Budget(
                id = UUID.randomUUID().toString(),
                childId = childId,
                category = category,
                monthlyLimit = monthlyLimit,
                currency = defaultCurrency.value.code,
                createdAt = LocalDateTime.now()
            )
            budgetRepository.addBudget(budget)
            refreshAlerts()
        }
    }

    /**
     * Changes an existing budget's monthly limit.
     *
     * A `copy()` of the loaded budget, never a rebuilt one — the same rule event editing follows.
     * `createdAt`, `createdByFirebaseUid`, `currency` and the alert threshold are not this
     * screen's to reset, and rebuilding from the two fields the sheet shows would do exactly that.
     */
    fun updateBudget(budget: Budget, monthlyLimit: Double) {
        viewModelScope.launch {
            budgetRepository.updateBudget(budget.copy(monthlyLimit = monthlyLimit))
            refreshAlerts()
        }
    }

    fun deleteBudget(budgetId: String) {
        viewModelScope.launch {
            budgetRepository.deleteBudget(budgetId)
            refreshAlerts()
        }
    }
}
