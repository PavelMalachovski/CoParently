package com.coparently.app.presentation.calendar

import com.coparently.app.domain.repository.FriendRepository
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
