package com.coparently.app.presentation.expenses

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coparently.app.R
import com.coparently.app.domain.model.ExpenseCategory
import com.coparently.app.domain.receipts.ReceiptScan
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Regression test for a timing bug found in code review: [ReceiptScanEffect] must not reset
 * [ReceiptScanCallbacks.onScanConsumed] until *after* the Undo/failure snackbar's
 * `showSnackbar` call returns.
 *
 * The effect is keyed on `scanState`. If `onScanConsumed()` (which flips the ViewModel's
 * `scanState` back to `Idle`) ran *before* `showSnackbar` suspended, the resulting
 * recomposition would change the `LaunchedEffect` key while the snackbar coroutine was still
 * live, cancelling it — and `SnackbarHostState` clears the snackbar it is showing when its
 * coroutine is cancelled. That bug was deterministic (same frame/dispatcher), not a race, so a
 * few `mainClock` frame advances after emitting `Applied` are enough to reproduce it.
 *
 * This test drives [ReceiptScanEffect] directly from a fake `MutableStateFlow`, the same way
 * `AddExpenseScreen` drives it from `ExpenseViewModel.scanState` — no Activity, Hilt or
 * ViewModel involved, per the reviewer's request.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptScanEffectTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val scanState = MutableStateFlow<ReceiptScanState>(ReceiptScanState.Idle)

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun setContentWithEffect(snackbarHostState: SnackbarHostState) {
        composeTestRule.setContent {
            val state by scanState.collectAsState()
            Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                    ReceiptScanEffect(
                        scanState = state,
                        formState = ReceiptScanFormState(
                            title = "",
                            amount = "",
                            category = ExpenseCategory.OTHER,
                            date = LocalDate.now(),
                            currency = null
                        ),
                        callbacks = ReceiptScanCallbacks(
                            onTitleChange = {},
                            onAmountChange = {},
                            onCategoryChange = {},
                            onDateChange = {},
                            onCurrencyChange = {},
                            onScanConsumed = { scanState.value = ReceiptScanState.Idle }
                        ),
                        snackbarHostState = snackbarHostState
                    )
                }
            }
        }
    }

    @Test
    fun appliedScan_undoSnackbar_survivesSeveralFramesAfterApplied() {
        val snackbarHostState = SnackbarHostState()
        setContentWithEffect(snackbarHostState)
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            scanState.value = ReceiptScanState.Applied(
                ReceiptScan(total = 150.0, merchant = "Lekarna", currency = "CZK")
            )
        }
        composeTestRule.waitForIdle()

        // Give the recomposition triggered by a (buggy) early onScanConsumed() every chance to
        // land and cancel the snackbar's coroutine before we assert.
        repeat(5) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }

        val undoLabel = context().getString(R.string.receipt_scan_undo)
        composeTestRule.onNodeWithText(undoLabel).assertIsDisplayed()
    }

    @Test
    fun failedScan_failureSnackbar_survivesSeveralFramesAfterFailed() {
        val snackbarHostState = SnackbarHostState()
        setContentWithEffect(snackbarHostState)
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            scanState.value = ReceiptScanState.Failed
        }
        composeTestRule.waitForIdle()

        repeat(5) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }

        val failedMessage = context().getString(R.string.receipt_scan_failed)
        composeTestRule.onNodeWithText(failedMessage).assertIsDisplayed()
    }
}
