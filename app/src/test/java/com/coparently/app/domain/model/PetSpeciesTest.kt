package com.coparently.app.domain.model

import org.junit.Test
import kotlin.test.assertEquals

/**
 * [PetSpecies.fromStored] must round-trip every known constant and degrade unknown or absent
 * values to [PetSpecies.OTHER] rather than throwing — a record written by a newer build that
 * knows a species this one does not must still load.
 */
class PetSpeciesTest {

    @Test
    fun `every constant round-trips through its stored name`() {
        PetSpecies.entries.forEach { species ->
            assertEquals(species, PetSpecies.fromStored(species.name))
        }
    }

    @Test
    fun `an unknown value degrades to OTHER`() {
        assertEquals(PetSpecies.OTHER, PetSpecies.fromStored("HAMSTER_DELUXE"))
    }

    @Test
    fun `a null value degrades to OTHER`() {
        assertEquals(PetSpecies.OTHER, PetSpecies.fromStored(null))
    }
}
