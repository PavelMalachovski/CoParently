package com.coparently.app.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The one injectable [Gson].
 *
 * It lived in `AIModule` until the AI subsystem was deleted (MON-7), which is a poor place for a
 * general serialization binding and cost a CI round to discover: nothing in the AI packages was
 * referenced from outside them, but the module in that package was providing something that was.
 * A module named after a feature should only provide that feature's bindings.
 *
 * `setLenient()` is preserved from that provider rather than reconsidered. It changes how
 * malformed JSON is read, and the only consumer —
 * [com.coparently.app.presentation.event.EventViewModel] — parses values that have been through
 * Room and Firestore; tightening it is a behaviour change that belongs in its own commit, with
 * whatever it breaks in front of the person making it.
 *
 * Note that most classes here do **not** inject Gson: repositories construct their own
 * `Gson()` (a strict one, and in `ChildInfoRepositoryImpl` one with a `LocalDate` adapter
 * registered). That inconsistency is pre-existing and is not resolved by this module — it is
 * simply where the injectable instance now lives.
 */
@Module
@InstallIn(SingletonComponent::class)
object SerializationModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder()
        .setLenient()
        .create()
}
