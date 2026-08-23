package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreUserDataSource
import com.coparently.app.domain.model.BloodType
import com.coparently.app.domain.model.MedicalProfile
import com.coparently.app.domain.model.User
import com.coparently.app.domain.model.Vaccination
import com.google.gson.GsonBuilder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A parent's own profile survives storage, and a malformed stored date does not crash the app.
 *
 * `UserEntity.dateOfBirth` is an ISO string rather than a converted `LocalDateTime`, so parsing
 * happens in the mapper — and a mapper that lets `DateTimeParseException` escape would take down
 * every screen that reads the signed-in user, which is most of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `an empty stored profile deserialises to real empty lists, not to nulls`() {
        val gson = GsonBuilder()
            .registerTypeAdapter(LocalDate::class.java, LocalDateJsonAdapter())
            .create()

        val parsed = gson.fromJson("{}", MedicalProfile::class.java)

        // Route through a nullable reference so the check is a real null test rather than
        // something the compiler optimises away on a type it believes cannot be null.
        val intolerances: List<String>? = parsed.intolerances
        val hereditary: List<String>? = parsed.hereditaryConditions
        val vaccinations: List<Vaccination>? = parsed.vaccinations

        assertNotNull(intolerances, "intolerances came back null from an empty stored profile")
        assertNotNull(hereditary, "hereditaryConditions came back null from an empty stored profile")
        assertNotNull(vaccinations, "vaccinations came back null from an empty stored profile")
        assertEquals(0, intolerances.size)
    }

    @Test
    fun `a vaccination JSON missing its name field leaves the raw JVM field null`() {
        // Documents the landmine `withSanitizedVaccinationNames` exists to repair.
        // `Vaccination`'s first parameter (`name`) has no default, so — unlike `MedicalProfile`
        // above — Kotlin emits no no-arg constructor for it. Gson falls back to
        // `UnsafeAllocator`, which skips every constructor, so a field missing from the stored
        // JSON keeps its raw allocation value: null, despite the non-null declared type. This
        // is the actual Gson behaviour, not the desired one — see the next test for the fix
        // every `MedicalProfile` parse site applies.
        val gson = GsonBuilder()
            .registerTypeAdapter(LocalDate::class.java, LocalDateJsonAdapter())
            .create()

        val parsed = gson.fromJson("""{"date":"2024-03-12"}""", Vaccination::class.java)

        val name: String? = parsed.name
        assertNull(name, "expected Gson's raw UnsafeAllocator gap to still be null here")
    }

    @Test
    fun `withSanitizedVaccinationNames repairs a vaccination missing its name`() {
        val gson = GsonBuilder()
            .registerTypeAdapter(LocalDate::class.java, LocalDateJsonAdapter())
            .create()

        val parsed = gson.fromJson(
            """{"vaccinations":[{"date":"2024-03-12"}]}""",
            MedicalProfile::class.java
        ).withSanitizedVaccinationNames()

        val name: String? = parsed.vaccinations.single().name
        assertNotNull(name, "withSanitizedVaccinationNames left a null name in place")
        assertEquals("", name)
    }

    @Test
    fun `a filled profile round-trips through the repository's real entity mapping`() = runTest {
        val userDao = mockk<UserDao>(relaxed = true)
        val authService = mockk<FirebaseAuthService>(relaxed = true)
        val firestoreUserDataSource = mockk<FirestoreUserDataSource>(relaxed = true)
        val fcmService = mockk<FcmService>(relaxed = true)
        // No signed-in user: isolates the Room round trip (toEntity/toDomain) from the
        // Firestore push, which is covered separately by the "role" grep and the tests in
        // UserRepositoryEnsureProfileTest.
        every { authService.getCurrentUser() } returns null
        val repository = UserRepositoryImpl(userDao, authService, firestoreUserDataSource, fcmService)

        val filled = User(
            id = "user-a",
            email = "alice@example.com",
            name = "Alice Novak",
            role = "mom",
            colorCode = "#FF4081",
            dateOfBirth = LocalDate.of(1988, 4, 17),
            phone = "+420123456789",
            allergies = listOf("peanuts"),
            medicalProfile = MedicalProfile(
                bloodType = BloodType.AB_POSITIVE,
                intolerances = listOf("lactose"),
                hereditaryConditions = listOf("asthma"),
                vaccinations = listOf(Vaccination("MMR", LocalDate.of(2020, 1, 1)))
            )
        )

        repository.updateUser(filled)

        val savedEntity = slot<UserEntity>()
        coVerify { userDao.updateUser(capture(savedEntity)) }
        assertEquals("1988-04-17", savedEntity.captured.dateOfBirth)

        coEvery { userDao.getUserById("user-a") } returns savedEntity.captured
        val restored = repository.getUserById("user-a")

        assertEquals(filled.dateOfBirth, restored?.dateOfBirth)
        assertEquals(filled.phone, restored?.phone)
        assertEquals(filled.allergies, restored?.allergies)
        assertEquals(filled.medicalProfile, restored?.medicalProfile)
    }
}
