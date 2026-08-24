package com.coparently.app.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Today's date, as a value that actually changes when today does.
 *
 * `remember { LocalDate.now() }` is the obvious thing to write and it is wrong here: it captures
 * the date at first composition and never revisits it, so an app left open overnight goes on
 * highlighting yesterday. For a product whose entire question is *whose day is it today*, that is
 * an answer that quietly becomes wrong while the screen still looks right.
 *
 * Reading `LocalDate.now()` inline is no better on its own. It is correct whenever it runs, but
 * nothing makes it run: midnight is not a recomposition trigger. This is — the state changes, and
 * everything reading it recomposes.
 *
 * The wait is recomputed each round from the real clock rather than assuming 24 hours, so a
 * daylight-saving change, a manual clock adjustment or a delay that fires late all land on the
 * right date: whatever wakes the loop, the new value comes from `LocalDate.now()`, not from
 * counting.
 *
 * Known limitation: while the process is in Doze the timer is throttled, so the change can arrive
 * late — but it arrives with the correct date, and any screen the user actually returns to
 * recomposes with it.
 */
@Composable
fun rememberToday(): State<LocalDate> {
    val today = remember { mutableStateOf(LocalDate.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(millisUntilNextMidnight(LocalDateTime.now()))
            today.value = LocalDate.now()
        }
    }

    return today
}

/**
 * How long from [now] until the start of the next day, in milliseconds.
 *
 * Never zero or negative. A timer that fires marginally early would otherwise be asked to wait no
 * time at all, and the loop would spin against the clock instead of waiting for it — the one way
 * a date that updates itself can cost more than a date that never does.
 */
internal fun millisUntilNextMidnight(now: LocalDateTime): Long =
    Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay())
        .toMillis()
        .coerceAtLeast(1L)
