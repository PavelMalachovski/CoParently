package com.coparently.app.data.repository

import com.coparently.app.domain.model.MedicalProfile

/**
 * Repairs a [MedicalProfile] Gson just parsed, for the one field its constructor fallback
 * cannot protect.
 *
 * [MedicalProfile] itself is safe: every constructor parameter has a default, so Kotlin emits
 * a public no-arg constructor, Gson's `ConstructorConstructor` finds it via reflection, and
 * the three collection fields are genuinely initialised — even when the stored JSON is `{}` —
 * rather than left at a JVM-level null. This is pinned empirically by
 * `UserProfileMappingTest`'s `an empty stored profile deserialises to real empty lists, not to
 * nulls`, not assumed.
 *
 * `Vaccination`'s first parameter (`name`) has no default, so it gets no such constructor:
 * Gson falls back to `UnsafeAllocator`, which skips every constructor and leaves any field
 * missing from the stored JSON at its raw allocation value — `null` for `name`, despite its
 * non-null declared type. Also pinned empirically, by
 * `a vaccination JSON missing its name field exposes what Gson actually leaves there`. A later
 * `.isBlank()`, `.length` or string comparison on that field would throw where the type system
 * promises it cannot.
 *
 * Applied at every site that reads a [MedicalProfile] back out of Gson —
 * `UserRepositoryImpl.toDomain`/`toUser` and `ChildInfoRepositoryImpl.toDomain`/`toChildInfo` —
 * rather than by adding a default to `Vaccination` or otherwise changing either class's
 * declaration: other tasks depend on their exact shape.
 *
 * @receiver The profile Gson just returned, which may carry vaccinations with a JVM-level
 *   null `name`
 * @return The same profile, with every vaccination's `name` guaranteed non-null
 */
fun MedicalProfile.withSanitizedVaccinationNames(): MedicalProfile =
    copy(vaccinations = vaccinations.map { it.copy(name = it.name ?: "") })
