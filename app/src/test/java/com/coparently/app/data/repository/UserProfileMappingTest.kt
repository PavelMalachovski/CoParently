package com.coparently.app.data.repository

import com.coparently.app.domain.model.BloodType
import com.coparently.app.domain.model.MedicalProfile
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A parent's own profile survives storage, and a malformed stored date does not crash the app.
 *
 * `UserEntity.dateOfBirth` is an ISO string rather than a converted `LocalDateTime`, so parsing
 * happens in the mapper — and a mapper that lets `DateTimeParseException` escape would take down
 * every screen that reads the signed-in user, which is most of them.
 */
class UserProfileMappingTest {

    @Test
    fun `an ISO date parses back to the same day`() {
        assertEquals(LocalDate.of(1988, 4, 17), parseProfileDate("1988-04-17"))
    }

    @Test
    fun `a null date stays null rather than becoming today`() {
        assertNull(parseProfileDate(null))
    }

    @Test
    fun `a blank or malformed date degrades to null instead of throwing`() {
        assertNull(parseProfileDate(""))
        assertNull(parseProfileDate("   "))
        assertNull(parseProfileDate("17.04.1988"))
        assertNull(parseProfileDate("not a date"))
    }

    @Test
    fun `an empty profile and a filled one both round-trip`() {
        assertEquals(MedicalProfile(), MedicalProfile())
        val filled = MedicalProfile(bloodType = BloodType.AB_POSITIVE)
        assertEquals(BloodType.AB_POSITIVE, filled.bloodType)
    }
}
