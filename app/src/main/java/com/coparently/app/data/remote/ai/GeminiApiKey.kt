package com.coparently.app.data.remote.ai

import javax.inject.Qualifier

/**
 * Marks the Gemini API key binding.
 *
 * Without a qualifier the key was provided as a bare `String` in the `SingletonComponent`, which
 * makes it *the* binding for `String` across the whole graph: any `@Inject constructor(x: String)`
 * anywhere in the app would have been handed the API key, silently and with no error to notice.
 * A qualifier makes the binding addressable only by whoever asks for it by name, and makes an
 * unqualified `String` injection a compile-time failure instead of a surprise.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GeminiApiKey
