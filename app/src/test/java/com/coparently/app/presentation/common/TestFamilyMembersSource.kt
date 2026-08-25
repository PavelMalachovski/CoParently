package com.coparently.app.presentation.common

import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.model.Pet
import com.coparently.app.domain.repository.ChildInfoRepository
import com.coparently.app.domain.repository.PetRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDateTime

/**
 * A real [FamilyMembersSource] over stubbed repositories.
 *
 * The real class rather than a mock of it, for the reason [testParentsSource] gives: the thing
 * under test is the combining, and a mock would let the two halves describe different families.
 *
 * @param childNames The children, in list order. Ids are derived from the names.
 * @param petNames The pets, in list order.
 */
fun testFamilyMembersSource(
    childNames: List<String> = emptyList(),
    petNames: List<String> = emptyList()
): FamilyMembersSource {
    val now = LocalDateTime.parse("2026-08-01T09:00:00")
    val childInfoRepository = mockk<ChildInfoRepository> {
        every { getAllChildInfo() } returns flowOf(
            childNames.map { name ->
                ChildInfo(id = "c-$name", childName = name, dateOfBirth = null, createdAt = now, updatedAt = now)
            }
        )
    }
    val petRepository = mockk<PetRepository> {
        every { getAllPets() } returns flowOf(
            petNames.map { name -> Pet(id = "p-$name", name = name, createdAt = now, updatedAt = now) }
        )
    }
    return FamilyMembersSource(childInfoRepository, petRepository)
}
