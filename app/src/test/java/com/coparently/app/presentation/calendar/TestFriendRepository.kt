package com.coparently.app.presentation.calendar

import com.coparently.app.domain.repository.FriendRepository
import com.coparently.app.domain.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

/**
 * A [FriendRepository] for a family that has admitted no calendar friend — which is the state
 * every one of these tests is in, none of them being about friends.
 *
 * Only [FriendRepository.observeFamilyFriends] is stubbed on purpose: the mock is strict, so if
 * [CalendarViewModel] ever starts calling something else on this collaborator the test fails
 * loudly instead of silently observing a relaxed default.
 */
fun noCalendarFriends(): FriendRepository = mockk {
    every { observeFamilyFriends() } returns flowOf(emptyList())
}

/**
 * A [UserRepository] with nobody signed in, which is all `CalendarViewModel.holidayCountry` needs.
 *
 * The calendar reads which country's public holidays to draw from the signed-in parent's profile
 * (MON-13). None of these tests is about holidays, but the constructor builds that flow either
 * way — and a strict mock is the point: a relaxed one would hand `stateIn` a mock `Flow` to
 * collect, and a collaborator that grew a second call would go unnoticed.
 */
fun noSignedInUser(): UserRepository = mockk {
    every { observeCurrentUserId() } returns flowOf(null)
}
