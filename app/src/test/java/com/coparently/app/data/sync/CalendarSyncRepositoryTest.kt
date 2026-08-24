package com.coparently.app.data.sync

import com.coparently.app.data.local.dao.EventDao
import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.data.remote.google.CalendarEvents
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
import java.time.LocalDateTime
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

    private fun repository() = CalendarSyncRepository(
        eventDao,
        googleCalendarApi,
        credentialProvider,
        userRepository,
        mockk(relaxed = true)
    )

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
                    limit = any()
                )
            } returns imported(googleEvent)
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
                    limit = any()
                )
            }
            coVerify(exactly = 0) { eventDao.insertEvents(any()) }
        }

    @Test
    fun `a truncated import says so, instead of reading like a finished one`() = runTest {
        // The whole point of CQ-7: the old import stopped at the 50th event and reported the same
        // "Synced N events" a complete one did, so a user with a full calendar was told it had
        // finished. Whatever the wording, the two outcomes must not read alike.
        val credential = mockk<Credential>()
        coEvery { credentialProvider.getCredential() } returns credential
        coEvery { userRepository.getCurrentUserId() } returns "u1"
        coEvery { userRepository.getUserById("u1") } returns User(
            id = "u1", email = "p@example.com", name = "Pavel", role = "dad", colorCode = "#2196F3"
        )
        coEvery { eventDao.insertEvents(any()) } returns Unit
        val event = GoogleEvent().apply {
            id = "e1"
            summary = "Pickup"
        }

        every {
            googleCalendarApi.listEvents(any(), any(), any(), any(), any())
        } returns imported(event, truncated = true)
        val cutShort = repository().syncFromGoogle().toList().last()

        every {
            googleCalendarApi.listEvents(any(), any(), any(), any(), any())
        } returns imported(event, truncated = false)
        val complete = repository().syncFromGoogle().toList().last()

        assertTrue(cutShort is SyncResult.Success)
        assertTrue(complete is SyncResult.Success)
        assertTrue(
            (cutShort as SyncResult.Success).message != (complete as SyncResult.Success).message,
            "a cut-short import must not report what a complete one reports"
        )
    }

    @Test
    fun `the window the import actually read is reported, not left to be guessed`() = runTest {
        val credential = mockk<Credential>()
        coEvery { credentialProvider.getCredential() } returns credential
        coEvery { userRepository.getCurrentUserId() } returns "u1"
        coEvery { userRepository.getUserById("u1") } returns User(
            id = "u1", email = "p@example.com", name = "Pavel", role = "dad", colorCode = "#2196F3"
        )
        coEvery { eventDao.insertEvents(any()) } returns Unit
        every {
            googleCalendarApi.listEvents(any(), any(), any(), any(), any())
        } returns CalendarEvents(
            events = listOf(GoogleEvent().apply {
                id = "e1"
                summary = "Pickup"
            }),
            truncated = false,
            from = LocalDateTime.of(2026, 8, 24, 0, 0),
            until = LocalDateTime.of(2027, 8, 24, 0, 0)
        )

        val message = (repository().syncFromGoogle().toList().last() as SyncResult.Success).message

        assertTrue(message.contains("2026-08-24"), "start of the window: $message")
        assertTrue(message.contains("2027-08-24"), "end of the window: $message")
    }

    private fun imported(vararg events: GoogleEvent, truncated: Boolean = false) = CalendarEvents(
        events = events.toList(),
        truncated = truncated,
        from = LocalDateTime.of(2026, 8, 24, 0, 0),
        until = LocalDateTime.of(2027, 8, 24, 0, 0)
    )
}
