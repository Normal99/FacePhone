package com.family.grandmaphone.ui

import android.content.Context
import android.content.res.Configuration
import com.family.grandmaphone.data.Repo
import java.util.Locale

/**
 * Her screens and speech follow the language in the family settings, whatever language the phone
 * itself is set to. The family's own settings screen keeps the phone's language.
 */
object AppLocale {
    fun wrap(context: Context): Context {
        val tag = Repo.settings.value.speechLanguage
        if (tag.isBlank()) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(configuration)
    }
}
