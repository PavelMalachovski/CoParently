package com.coparently.app.data.sync

import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.data.remote.google.CredentialProvider
import com.coparently.app.data.remote.google.GoogleCalendarApi
import com.coparently.app.domain.model.User
import com.coparently.app.domain.repository.UserRepository
import com.google.api.client.auth.oauth2.Credential
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.google.api.services.calendar.model.Event as GoogleEvent

/**
 * [CalendarSyncRepository] used to read the owner slot from `ParentsSource`, in
 * `presentation/`, the tree's only data -> presentation edge. It now reads the same two calls -
 * [UserRepository.getCurrentUserId] and [UserRepository.getUserById] - directly, since
 * `UserRepository` is a domain interface this data-layer class may depend on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarSyncRepositoryTest {

    private val eventDao: EventDao = mockk(relaxed = true)
    private val googleCalendarApi: GoogleCalendarApi = mockk()
    private val credentialProvider: CredentialProvider = mockk()
    private val userRepository: UserRepository = mockk()

    private fun repository() =
        CalendarSyncRepository(eventDao, googleCalendarApi, credentialProvider, userRepository)

    @Test
    fun `an imported event is stamped with the signed-in user's slot, read through UserRepository`() =
        runTest {
            val credential = mockk<Credential>()
            coEvery { credentialProvider.getCredential() } returns credential
            coEvery { userRepository.getCurrentUserId() } returns "u1"
            coEvery { userRepository.getUserById("u1") } returns User(
                id = "u1",
                email = "pavel@example.com",
                name = "Pavel",
                role = "dad",
                colorCode = "#2196F3"
            )
            val googleEvent = GoogleEvent().apply {
                id = "e1"
                summary = "Pickup"
            }
            every {
                googleCalendarApi.listEvents(
                    credential = any(),
                    calendarId = any(),
                    timeMin = any(),
                    timeMax = any(),
                    maxResults = any()
                )
            } returns listOf(googleEvent)
            val inserted = slot<List<EventEntity>>()
            coEvery { eventDao.insertEvents(capture(inserted)) } returns Unit

            val results = repository().syncFromGoogle().toList()

            assertTrue(results.last() is SyncResult.Success)
            assertEquals("dad", inserted.captured.single().parentOwner)
        }

    @Test
    fun `syncFromGoogle throws rather than stamping an event when nobody is signed in`() =
        runTest {
            val credential = mockk<Credential>()
            coEvery { credentialProvider.getCredential() } returns credential
            coEvery { userRepository.getCurrentUserId() } returns null

            val results = repository().syncFromGoogle().toList()

            val error = results.last()
            assertTrue(error is SyncResult.Error)
            assertEquals(
                "Not signed in. Please sign in to CoPlanly.",
                (error as SyncResult.Error).message
            )
            verify(exactly = 0) {
                googleCalendarApi.listEvents(
                    credential = any(),
                    calendarId = any(),
                    timeMin = any(),
                    timeMax = any(),
                    maxResults = any()
                )
            }
            coVerify(exactly = 0) { eventDao.insertEvents(any()) }
        }
}
