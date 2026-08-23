package com.coparently.app.domain.activity

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The wire shape of an announcement.
 *
 * The keys are pinned here on purpose. A co-parent on an older build has to keep reading the
 * `messages` collection, so renaming one later is not available — a test that fails on a rename is
 * cheaper than discovering it on somebody's phone.
 *
 * The dropping cases are the other half. An unrecognised `kind` from a newer build must not be
 * defaulted into a *different* kind: `Message.content` carries a plain-text fallback for exactly
 * this, so the reader sees something honest rather than a confident sentence about the wrong
 * thing.
 */
class ActivityAnnouncementTest {

    private val announcement = ActivityAnnouncement(
        kind = ActivityKind.EVENT_CREATED,
        entityType = ActivityEntityType.EVENT,
        entityId = "e1",
        title = "Football",
        whenIso = "2026-09-01T16:00:00"
    )

    @Test
    fun `the keys are the ones an older build will look for`() {
        assertEquals(
            setOf("kind", "entityType", "entityId", "title", "whenIso"),
            announcement.toMap().keys
        )
    }

    @Test
    fun `an absent optional is omitted, not written as an empty string`() {
        val map = announcement.copy(whenIso = null).toMap()

        assertEquals(setOf("kind", "entityType", "entityId", "title"), map.keys)
    }

    @Test
    fun `a payload survives the round trip`() {
        val full = announcement.copy(amount = "250 Kč", currency = "CZK")

        assertEquals(full, ActivityAnnouncement.fromMap(full.toMap()))
    }

    @Test
    fun `an unrecognised kind is dropped rather than defaulted`() {
        val map = announcement.toMap().toMutableMap().apply { put("kind", "SOMETHING_NEWER") }

        assertNull(ActivityAnnouncement.fromMap(map))
    }

    @Test
    fun `an unrecognised entity type is dropped too`() {
        val map = announcement.toMap().toMutableMap().apply { put("entityType", "BUDGET") }

        assertNull(ActivityAnnouncement.fromMap(map))
    }

    @Test
    fun `a payload with nothing to open is dropped`() {
        val map = announcement.toMap().toMutableMap().apply { put("entityId", "") }

        assertNull(ActivityAnnouncement.fromMap(map))
    }

    @Test
    fun `a missing payload is simply absent`() {
        assertNull(ActivityAnnouncement.fromMap(null))
    }

    @Test
    fun `a blank title is tolerated, because an untitled entity is still worth announcing`() {
        val map = announcement.toMap().toMutableMap().apply { put("title", "") }

        assertEquals("", ActivityAnnouncement.fromMap(map)?.title)
    }
}
