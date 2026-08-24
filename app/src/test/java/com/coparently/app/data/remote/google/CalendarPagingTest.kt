package com.coparently.app.data.remote.google

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The paging behind a Google Calendar import.
 *
 * The defect these pin is not that an import can stop early — it can, and should — but that a
 * truncated import used to be indistinguishable from a complete one. `listEvents` asked for a
 * single page of 50 and returned it, its caller reported "Found 50 events", and a user with a
 * full calendar was told their import had finished.
 *
 * So every case here is really the same question asked from a different angle: **does the caller
 * end up able to tell whether there is more?**
 */
class CalendarPagingTest {

    private val noLimitInSight = 1_000
    private val plentyOfPages = 64

    /** A source of [pages], handing out tokens `"1"`, `"2"`, … and recording what it was asked. */
    private class FakePages(private val pages: List<List<String>>) {
        val requested = mutableListOf<String?>()

        fun fetch(pageToken: String?): PageOf<String> {
            requested += pageToken
            val index = pageToken?.toInt() ?: 0
            val isLast = index == pages.lastIndex
            return PageOf(pages[index], if (isLast) null else (index + 1).toString())
        }
    }

    @Test
    fun `a single page with no token is the whole answer`() {
        val source = FakePages(listOf(listOf("a", "b", "c")))

        val result = collectPages(noLimitInSight, plentyOfPages, source::fetch)

        assertEquals(listOf("a", "b", "c"), result.items)
        assertFalse(result.truncated, "a page without a next token is the end of the window")
        assertEquals(listOf<String?>(null), source.requested)
    }

    @Test
    fun `every page is followed, in order`() {
        val source = FakePages(listOf(listOf("a", "b"), listOf("c", "d"), listOf("e")))

        val result = collectPages(noLimitInSight, plentyOfPages, source::fetch)

        assertEquals(listOf("a", "b", "c", "d", "e"), result.items)
        assertFalse(result.truncated)
        assertEquals(listOf(null, "1", "2"), source.requested)
    }

    @Test
    fun `a limit landing mid-page keeps exactly the limit and says there is more`() {
        val source = FakePages(listOf(listOf("a", "b"), listOf("c", "d"), listOf("e")))

        val result = collectPages(limit = 3, maxPages = plentyOfPages, fetch = source::fetch)

        assertEquals(listOf("a", "b", "c"), result.items)
        assertTrue(result.truncated)
    }

    @Test
    fun `a limit landing on a page boundary still says there is more when a token follows`() {
        // The case a naive `if (collected.size > limit)` misses: the page fitted exactly, so
        // nothing was dropped here — but the token proves the window holds more.
        val source = FakePages(listOf(listOf("a", "b"), listOf("c", "d")))

        val result = collectPages(limit = 2, maxPages = plentyOfPages, fetch = source::fetch)

        assertEquals(listOf("a", "b"), result.items)
        assertTrue(result.truncated)
    }

    @Test
    fun `a limit landing on the last page is not truncation`() {
        val source = FakePages(listOf(listOf("a", "b")))

        val result = collectPages(limit = 2, maxPages = plentyOfPages, fetch = source::fetch)

        assertEquals(listOf("a", "b"), result.items)
        assertFalse(result.truncated, "there was no next token, so nothing was left behind")
    }

    @Test
    fun `a blank token is treated as no token`() {
        val result = collectPages(noLimitInSight, plentyOfPages) { PageOf(listOf("a"), "") }

        assertEquals(listOf("a"), result.items)
        assertFalse(result.truncated)
    }

    @Test
    fun `a token that never advances stops at the page cap instead of hanging`() {
        var calls = 0

        val result = collectPages(noLimitInSight, maxPages = 5) {
            calls++
            PageOf(listOf("a"), "always-more")
        }

        assertEquals(5, calls, "the cap is what ends this, not the server")
        assertEquals(5, result.items.size)
        assertTrue(result.truncated, "stopping early for any reason means the caller holds less")
    }

    @Test
    fun `an empty first page is a complete, empty answer`() {
        val result = collectPages(noLimitInSight, plentyOfPages) { PageOf(emptyList(), null) }

        assertTrue(result.items.isEmpty())
        assertFalse(result.truncated)
    }

    @Test
    fun `a zero limit fetches once and reports that everything was left behind`() {
        val result = collectPages(limit = 0, maxPages = plentyOfPages) { PageOf(listOf("a"), null) }

        assertTrue(result.items.isEmpty())
        assertTrue(result.truncated)
    }
}
