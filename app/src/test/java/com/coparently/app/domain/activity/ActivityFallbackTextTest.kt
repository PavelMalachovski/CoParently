package com.coparently.app.domain.activity

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sentence an un-upgraded phone sees.
 *
 * It is in the base locale on purpose and must stay predictable: it is the only thing a co-parent
 * whose build predates `MessageType.ACTIVITY` is shown, and an empty `content` there is an empty
 * bubble.
 */
class ActivityFallbackTextTest {

    private fun announcement(kind: ActivityKind, title: String) = ActivityAnnouncement(
        kind = kind,
        entityType = ActivityEntityType.EVENT,
        entityId = "e1",
        title = title
    )

    @Test
    fun `the fallback names what happened and to what`() {
        assertEquals(
            "New event: Football",
            ActivityFallbackText.of(announcement(ActivityKind.EVENT_CREATED, "Football"))
        )
    }

    @Test
    fun `an untitled entity still produces a sentence rather than a dangling colon`() {
        assertEquals(
            "Expense removed",
            ActivityFallbackText.of(announcement(ActivityKind.EXPENSE_DELETED, ""))
        )
    }

    @Test
    fun `every kind produces something, so no bubble is ever empty`() {
        ActivityKind.entries.forEach { kind ->
            val text = ActivityFallbackText.of(announcement(kind, "Thing"))
            assertTrue(text.isNotBlank(), "$kind produced nothing")
        }
    }

    @Test
    fun `no two kinds read the same, so the fallback is as specific as the card`() {
        val texts = ActivityKind.entries.map { ActivityFallbackText.of(announcement(it, "Thing")) }

        assertEquals(ActivityKind.entries.size, texts.toSet().size)
    }
}
