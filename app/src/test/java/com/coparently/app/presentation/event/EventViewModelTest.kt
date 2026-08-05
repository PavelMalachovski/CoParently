package com.coparently.app.presentation.event

import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.domain.error.AppError
import com.coparently.app.domain.error.ErrorHandler
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.model.User
import com.coparently.app.domain.usecase.CreateEventUseCase
import com.coparently.app.domain.usecase.DeleteEventUseCase
import com.coparently.app.domain.usecase.EventUseCases
import com.coparently.app.domain.usecase.GetEventsUseCase
import com.coparently.app.domain.usecase.UpdateEventUseCase
import com.coparently.app.presentation.common.testParentsSource
import com.google.gson.Gson
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for EventViewModel.
 * Covers loading, deletion and pickup confirmation flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var createEvent: CreateEventUseCase
    private lateinit var updateEvent: UpdateEventUseCase
    private lateinit var deleteEvent: DeleteEventUseCase
    private lateinit var getEvents: GetEventsUseCase
    private lateinit var errorHandler: ErrorHandler
    private lateinit var encryptedPreferences: EncryptedPreferences
    private lateinit var viewModel: EventViewModel

    /** The signed-in parent, in slot 2. `confirmPickup` stamps their slot on the event. */
    private val signedInParent = User(
        id = "u1",
        email = "pavel@example.com",
        name = "Pavel",
        role = "dad",
        colorCode = "#2196F3"
    )

    private val sampleEvent = Event(
        id = "e1",
        title = "Soccer",
        startDateTime = LocalDateTime.of(2026, 7, 20, 16, 0),
        endDateTime = LocalDateTime.of(2026, 7, 20, 17, 0),
        eventType = "sports",
        parentOwner = "mom",
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now()
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        createEvent = mockk(relaxed = true)
        updateEvent = mockk(relaxed = true)
        deleteEvent = mockk(relaxed = true)
        getEvents = mockk {
            every { this@mockk.invoke() } returns flowOf(listOf(sampleEvent))
        }
        errorHandler = mockk(relaxed = true)
        encryptedPreferences = mockk(relaxed = true)
        viewModel = EventViewModel(
            EventUseCases(createEvent, updateEvent, deleteEvent, getEvents),
            errorHandler,
            encryptedPreferences,
            Gson(),
            eventImageStorage = mockk(relaxed = true),
            parentsSource = testParentsSource(me = signedInParent)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `loadEvents populates events state`() = runTest {
        advanceUntilIdle()
        assertEquals(listOf(sampleEvent), viewModel.events.value)
    }

    @Test
    fun `deleteEventById delegates to use case`() = runTest {
        coEvery { deleteEvent.deleteById("e1") } returns Result.success(Unit)

        viewModel.deleteEventById("e1")
        advanceUntilIdle()

        coVerify { deleteEvent.deleteById("e1") }
    }

    @Test
    fun `confirmPickup stamps the signed-in parent's slot`() = runTest {
        coEvery { getEvents.getById("e1") } returns sampleEvent
        val saved = slot<Event>()
        coEvery { updateEvent.invoke(capture(saved)) } answers { Result.success(saved.captured) }

        viewModel.confirmPickup("e1")
        advanceUntilIdle()

        assertEquals("dad", saved.captured.pickupConfirmedBy)
        assertNotNull(saved.captured.pickupConfirmedAt)
    }

    @Test
    fun `undoPickupConfirmation clears confirmation`() = runTest {
        coEvery { getEvents.getById("e1") } returns sampleEvent.copy(
            pickupConfirmedBy = "dad",
            pickupConfirmedAt = LocalDateTime.now()
        )
        val saved = slot<Event>()
        coEvery { updateEvent.invoke(capture(saved)) } answers { Result.success(saved.captured) }

        viewModel.undoPickupConfirmation("e1")
        advanceUntilIdle()

        assertNull(saved.captured.pickupConfirmedBy)
        assertNull(saved.captured.pickupConfirmedAt)
    }

    @Test
    fun `setting a new range cancels the previous collection`() = runTest {
        val firstStart = LocalDateTime.of(2026, 8, 1, 0, 0)
        val firstEnd = LocalDateTime.of(2026, 8, 31, 23, 59, 59)
        val secondStart = LocalDateTime.of(2026, 11, 1, 0, 0)
        val secondEnd = LocalDateTime.of(2026, 11, 30, 23, 59, 59)

        val firstRange = MutableSharedFlow<List<Event>>()
        val laterEvent = sampleEvent.copy(id = "e2", title = "Dentist")
        every { getEvents.getByDateRange(firstStart, firstEnd) } returns firstRange
        every { getEvents.getByDateRange(secondStart, secondEnd) } returns flowOf(listOf(laterEvent))

        viewModel.loadEventsForDateRange(firstStart, firstEnd)
        advanceUntilIdle()
        firstRange.emit(listOf(sampleEvent))
        advanceUntilIdle()
        assertEquals(listOf(sampleEvent), viewModel.events.value)

        viewModel.loadEventsForDateRange(secondStart, secondEnd)
        advanceUntilIdle()

        assertEquals(0, firstRange.subscriptionCount.value)
        assertEquals(listOf(laterEvent), viewModel.events.value)

        // A late emission from the abandoned range must not reach the UI.
        firstRange.emit(listOf(sampleEvent))
        advanceUntilIdle()
        assertEquals(listOf(laterEvent), viewModel.events.value)
    }

    @Test
    fun `re-requesting the same range does not restart the collection`() = runTest {
        val start = LocalDateTime.of(2026, 8, 1, 0, 0)
        val end = LocalDateTime.of(2026, 8, 31, 23, 59, 59)
        val range = MutableSharedFlow<List<Event>>()
        every { getEvents.getByDateRange(start, end) } returns range

        viewModel.loadEventsForDateRange(start, end)
        advanceUntilIdle()
        viewModel.loadEventsForDateRange(start, end)
        advanceUntilIdle()

        assertEquals(1, range.subscriptionCount.value)
    }

    @Test
    fun `a failed range does not block a later different range`() = runTest {
        val start = LocalDateTime.of(2026, 8, 1, 0, 0)
        val end = LocalDateTime.of(2026, 8, 31, 23, 59, 59)
        val secondStart = LocalDateTime.of(2026, 11, 1, 0, 0)
        val secondEnd = LocalDateTime.of(2026, 11, 30, 23, 59, 59)
        val laterEvent = sampleEvent.copy(id = "e2", title = "Dentist")
        val failure = RuntimeException("boom")

        every { getEvents.getByDateRange(start, end) } returns flow { throw failure }
        every { errorHandler.handleError(failure) } returns AppError.UnknownError(originalException = failure)
        every { getEvents.getByDateRange(secondStart, secondEnd) } returns flowOf(listOf(laterEvent))

        viewModel.loadEventsForDateRange(start, end)
        advanceUntilIdle()

        assertEquals(
            EventUiState.Error("Something went wrong. Please try again."),
            viewModel.uiState.value
        )

        // The failed range's .catch completed only the inner flow; a different query must still
        // be servable through the same flatMapLatest chain.
        viewModel.loadEventsForDateRange(secondStart, secondEnd)
        advanceUntilIdle()

        assertEquals(listOf(laterEvent), viewModel.events.value)
    }

    @Test
    fun `refresh restarts a failed range once it stops failing`() = runTest {
        val start = LocalDateTime.of(2026, 8, 1, 0, 0)
        val end = LocalDateTime.of(2026, 8, 31, 23, 59, 59)
        val failure = RuntimeException("boom")
        var shouldFail = true
        val recovered = MutableSharedFlow<List<Event>>()
        every { getEvents.getByDateRange(start, end) } answers {
            if (shouldFail) flow { throw failure } else recovered
        }
        every { errorHandler.handleError(failure) } returns AppError.UnknownError(originalException = failure)

        viewModel.loadEventsForDateRange(start, end)
        advanceUntilIdle()
        assertEquals(
            EventUiState.Error("Something went wrong. Please try again."),
            viewModel.uiState.value
        )

        // Re-requesting the identical range is conflated away and could never restart it -
        // refresh() is the only lever, and it works because the underlying source now succeeds.
        shouldFail = false
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(1, recovered.subscriptionCount.value)
        recovered.emit(listOf(sampleEvent))
        advanceUntilIdle()
        assertEquals(listOf(sampleEvent), viewModel.events.value)
    }
}
