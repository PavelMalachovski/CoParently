package com.coparently.app.presentation.common

import com.coparently.app.domain.model.BloodType
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Every blood type must have its own label, and the labels must all differ.
 *
 * The notation is not universal: English writes the null group `O`, while Russian and German
 * write it `0` (zero). That is the whole reason these are string resources rather than a `code`
 * property on the enum — and it is also why a copy-paste slip that points two variants at the
 * same resource would be invisible on screen until someone was told the wrong blood type.
 */
class BloodTypeLabelTest {

    @Test
    fun `every blood type maps to a distinct string resource`() {
        val resources = BloodType.entries.map { it.labelRes() }

        assertEquals(8, resources.size)
        assertEquals(
            resources.size,
            resources.distinct().size,
            "two blood types share a label resource"
        )
    }

    @Test
    fun `there are exactly the eight real blood types`() {
        assertEquals(
            listOf(
                "A_POSITIVE",
                "A_NEGATIVE",
                "B_POSITIVE",
                "B_NEGATIVE",
                "AB_POSITIVE",
                "AB_NEGATIVE",
                "O_POSITIVE",
                "O_NEGATIVE"
            ),
            BloodType.entries.map { it.name }
        )
    }

    @Test
    fun `an empty medical profile is the default, so an untouched parent stores nothing`() {
        val empty = com.coparently.app.domain.model.MedicalProfile()

        assertEquals(null, empty.bloodType)
        assertEquals(emptyList(), empty.intolerances)
        assertEquals(emptyList(), empty.hereditaryConditions)
        assertEquals(emptyList(), empty.vaccinations)
    }
}
