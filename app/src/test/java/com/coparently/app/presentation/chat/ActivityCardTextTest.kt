package com.coparently.app.presentation.chat

import com.coparently.app.domain.activity.ActivityKind
import org.junit.Test
import kotlin.test.assertEquals

/**
 * That every announced change has its own sentence.
 *
 * Exhaustiveness itself is enforced by the compiler — the mapping is a `when` over an enum with no
 * `else`, so a new [ActivityKind] does not build until it has been given one. What a test can add
 * is **distinctness**: a copy-paste that pointed two kinds at the same resource would compile
 * happily and quietly tell a parent their expense was deleted when it was edited.
 */
class ActivityCardTextTest {

    @Test
    fun `every kind has its own string, with none shared by two`() {
        val resources = ActivityKind.entries.map { ActivityCardText.resourceFor(it) }

        assertEquals(ActivityKind.entries.size, resources.toSet().size)
    }

    @Test
    fun `every kind resolves to a real resource`() {
        ActivityKind.entries.forEach { kind ->
            assertEquals(true, ActivityCardText.resourceFor(kind) != 0, "$kind has no string")
        }
    }
}
