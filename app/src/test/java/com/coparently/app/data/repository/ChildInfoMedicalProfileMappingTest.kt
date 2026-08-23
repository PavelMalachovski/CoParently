package com.coparently.app.data.repository

import com.coparently.app.domain.model.BloodType
import com.coparently.app.domain.model.MedicalProfile
import com.coparently.app.domain.model.Vaccination
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * A medical profile must survive the trip through Gson unchanged.
 *
 * `ChildInfoRepositoryImpl` stores this type as a JSON string in Room and sends it to Firestore
 * as a nested map, so a `LocalDate` inside `Vaccination` has to serialise to something Gson can
 * read back. Gson has no built-in `LocalDate` adapter: without one it writes the field's internal
 * structure (`{"year":2024,"month":3,"day":12}`) on some JVMs and throws on others under Android's
 * stricter reflection rules. This pins the representation before either can happen in the field.
 */
class ChildInfoMedicalProfileMappingTest {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, LocalDateJsonAdapter())
        .create()

    @Test
    fun `a full profile round-trips through JSON`() {
        val profile = MedicalProfile(
            bloodType = BloodType.O_NEGATIVE,
            intolerances = listOf("lactose"),
            hereditaryConditions = listOf("asthma"),
            vaccinations = listOf(
                Vaccination("MMR", LocalDate.of(2024, 3, 12)),
                Vaccination("Tetanus", null)
            )
        )

        val restored = gson.fromJson(gson.toJson(profile), MedicalProfile::class.java)

        assertEquals(profile, restored)
    }

    @Test
    fun `a vaccination date is written as an ISO string, not as an object`() {
        val json = gson.toJson(Vaccination("MMR", LocalDate.of(2024, 3, 12)))

        assertEquals("""{"name":"MMR","date":"2024-03-12"}""", json)
    }

    @Test
    fun `an untouched profile is the empty default rather than null`() {
        val restored = gson.fromJson("{}", MedicalProfile::class.java)

        assertEquals(null, restored.bloodType)
        assertEquals(emptyList(), restored.vaccinations)
    }
}
