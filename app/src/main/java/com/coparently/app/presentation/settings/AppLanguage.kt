package com.coparently.app.presentation.settings

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.coparently.app.R

/**
 * Languages offered by the in-app language picker in Settings.
 *
 * [SYSTEM] means "no per-app override": the UI follows the device locale, falling back
 * to English (the base resources) for unsupported device languages. The list must stay
 * in sync with `res/xml/locales_config.xml` and the `values-*` resource folders.
 *
 * @property tag BCP-47 language tag persisted by AppCompat (empty = follow the system).
 * @property labelRes Display label; language endonyms are intentionally not translated.
 */
enum class AppLanguage(val tag: String, @StringRes val labelRes: Int) {
    SYSTEM("", R.string.settings_language_system),
    ENGLISH("en", R.string.settings_language_english),
    CZECH("cs", R.string.settings_language_czech),
    GERMAN("de", R.string.settings_language_german),
    RUSSIAN("ru", R.string.settings_language_russian),
    UKRAINIAN("uk", R.string.settings_language_ukrainian);

    companion object {

        /** The language currently selected in the picker (persisted by AppCompat). */
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return SYSTEM
            val language = locales.get(0)?.language ?: return SYSTEM
            return entries.firstOrNull { it.tag == language } ?: SYSTEM
        }

        /**
         * Applies [language] as the per-app locale. AppCompat recreates started activities
         * and (thanks to the `autoStoreLocales` manifest service) persists the choice across
         * process restarts; on Android 13+ it is also mirrored to the system per-app
         * language setting.
         */
        fun apply(language: AppLanguage) {
            val locales = if (language == SYSTEM) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language.tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
