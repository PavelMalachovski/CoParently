package com.coparently.app.di

import com.coparently.app.BuildConfig
import com.coparently.app.data.remote.ai.AIService
import com.coparently.app.data.remote.ai.GeminiAIService
import com.coparently.app.data.remote.ai.GeminiApiKey
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Dagger Hilt module for AI-related dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object AIModule {

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            .setLenient()
            .create()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The Gemini API key, from `BuildConfig` (a gradle property or environment variable).
     *
     * Qualified rather than bound as a bare `String` — see [GeminiApiKey]. The old placeholder
     * fallback is gone too: `"YOUR_GEMINI_API_KEY_HERE"` is not a key, so every AI call made
     * with it failed at the network, and each failure path swallows the exception and returns
     * an empty result. An unconfigured build therefore looked exactly like a model that had
     * nothing to say. An empty string is the honest value for "not configured".
     */
    @Provides
    @Singleton
    @GeminiApiKey
    fun provideGeminiApiKey(): String = BuildConfig.GEMINI_API_KEY

    @Provides
    @Singleton
    fun provideAIService(
        @GeminiApiKey apiKey: String,
        gson: Gson
    ): AIService {
        return GeminiAIService(apiKey, gson)
    }
}
