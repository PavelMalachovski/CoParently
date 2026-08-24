package com.coparently.app.presentation.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A value that may not have arrived yet — and a screen that can tell the difference.
 *
 * Every list screen in this app used to expose `StateFlow<List<T>>` starting at `emptyList()`, so
 * "nothing yet" and "nothing at all" were the same value. The screens then branched on
 * `items.isEmpty()`, which means that for the first frames after a cold start each of them
 * **asserted a fact it did not have**: no conversations, no expenses, no emergency contacts. On
 * the emergency surface that is the worst version of it — a parent opening Contacts in a hurry is
 * told there are none, and the list appears a moment later.
 *
 * This is deliberately not an error case. A failed read is a different question with a different
 * answer (see the calendar's Retry snackbar); this type only separates *not known yet* from
 * *known to be empty*.
 */
sealed interface Loadable<out T> {

    /** No answer yet. Screens show a skeleton — never an empty state, and never a fact. */
    data object Loading : Loadable<Nothing>

    /** The answer, which may itself be empty. */
    data class Loaded<out T>(val value: T) : Loadable<T>
}

/** The value once it has arrived, or null while it has not. */
val <T> Loadable<T>.valueOrNull: T?
    get() = (this as? Loadable.Loaded)?.value

/**
 * Shares this flow as a [StateFlow] that starts at [Loadable.Loading] rather than at a fabricated
 * empty value.
 *
 * The stop timeout matches the one used across the ViewModels here: long enough that a
 * configuration change does not tear down and re-establish the upstream, short enough that
 * leaving the screen releases it.
 */
fun <T> Flow<T>.stateInLoadable(
    scope: CoroutineScope,
    stopTimeoutMillis: Long = LOADABLE_STOP_TIMEOUT_MS
): StateFlow<Loadable<T>> =
    map<T, Loadable<T>> { Loadable.Loaded(it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis), Loadable.Loading)

private const val LOADABLE_STOP_TIMEOUT_MS = 5_000L
