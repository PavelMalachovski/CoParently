package com.coparently.app.presentation.common

import androidx.annotation.StringRes
import com.coparently.app.R
import com.coparently.app.domain.model.PetSpecies

/**
 * The string resource naming a species in the reader's language.
 *
 * Same shape as `BloodType.labelRes()` — not a property on the enum, so the domain model
 * stays free of Android, and the stored constant name never reaches the screen.
 *
 * @return the id of the localized name for this species
 */
@StringRes
fun PetSpecies.labelRes(): Int = when (this) {
    PetSpecies.DOG -> R.string.pet_species_dog
    PetSpecies.CAT -> R.string.pet_species_cat
    PetSpecies.BIRD -> R.string.pet_species_bird
    PetSpecies.RODENT -> R.string.pet_species_rodent
    PetSpecies.FISH -> R.string.pet_species_fish
    PetSpecies.REPTILE -> R.string.pet_species_reptile
    PetSpecies.OTHER -> R.string.pet_species_other
}
