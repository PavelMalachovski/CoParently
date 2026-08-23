package com.coparently.app.data.sync

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Reading the photograph list off a child document.
 *
 * Every case here is one a real document can be in, and every one of them is on the path that
 * renders a child's medical record — so the rule throughout is "drop it", never "throw".
 */
class ChildInfoPhotosTest {

    @Test
    fun `a document written before this field existed reads as an empty list, not null`() {
        assertEquals(emptyList(), ChildInfoPhotos.decode(null))
    }

    @Test
    fun `the urls survive in the order they were added`() {
        val urls = listOf("https://example.test/a.jpg", "https://example.test/b.jpg")

        assertEquals(urls, ChildInfoPhotos.decode(urls))
    }

    @Test
    fun `a value of the wrong shape is dropped rather than thrown on`() {
        // A hand-edited document, or a newer build writing a shape this one does not know.
        assertEquals(emptyList(), ChildInfoPhotos.decode("https://example.test/a.jpg"))
        assertEquals(emptyList(), ChildInfoPhotos.decode(42))
        assertEquals(emptyList(), ChildInfoPhotos.decode(mapOf("0" to "a.jpg")))
    }

    @Test
    fun `a non-string entry is dropped and its neighbours survive`() {
        assertEquals(
            listOf("https://example.test/a.jpg"),
            ChildInfoPhotos.decode(listOf("https://example.test/a.jpg", 7, null))
        )
    }

    @Test
    fun `a blank url is dropped`() {
        // A failed upload that stored an empty string would otherwise become a thumbnail that can
        // never load and can only be removed by guessing which one it is.
        assertEquals(
            listOf("https://example.test/a.jpg"),
            ChildInfoPhotos.decode(listOf("", "   ", "https://example.test/a.jpg"))
        )
    }
}
