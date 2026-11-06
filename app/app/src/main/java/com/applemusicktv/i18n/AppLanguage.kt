package com.applemusicktv.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Stable preference values; language changes never recreate the activity or player. */
enum class LanguageChoice { System, Chinese, English }

object AppLanguage {
    fun quantity(id: Int, count: Int): String {
        val language = code
        return localizedResources(language)?.getQuantityString(id, count, count) ?: count.toString()
    }

    private var context: Context? = null
    private var systemLocale by mutableStateOf(Locale.getDefault())
    var choice by mutableStateOf(LanguageChoice.System)
        private set
    val code: String get() = resolve(choice, systemLocale)
    val locale: Locale get() = if (code == "zh") Locale.SIMPLIFIED_CHINESE else Locale.US
    private val _changes = MutableStateFlow(code)
    val changes = _changes.asStateFlow()
    private val resources = mutableMapOf<String, android.content.res.Resources>()

    fun resolve(choice: LanguageChoice, system: Locale): String = when (choice) {
        LanguageChoice.Chinese -> "zh"
        LanguageChoice.English -> "en"
        LanguageChoice.System -> if (system.language.equals("zh", true)) "zh" else "en"
    }

    @Synchronized fun initialize(appContext: Context) {
        context = appContext.applicationContext
        systemLocale = systemLocaleOf(appContext.resources.configuration)
        val saved = appContext.getSharedPreferences("app_language", Context.MODE_PRIVATE).getString("choice", null)
        choice = LanguageChoice.entries.firstOrNull { it.name == saved } ?: LanguageChoice.System
        resources.clear()
        _changes.value = code
    }

    fun select(value: LanguageChoice) {
        context?.getSharedPreferences("app_language", Context.MODE_PRIVATE)?.edit()?.putString("choice", value.name)?.apply()
        choice = value
        _changes.value = code
    }

    fun configurationChanged(configuration: Configuration) {
        systemLocale = systemLocaleOf(configuration)
        synchronized(this) { resources.clear() }
        _changes.value = code
    }

    @Suppress("DEPRECATION")
    private fun systemLocaleOf(configuration: Configuration): Locale = if (android.os.Build.VERSION.SDK_INT >= 24)
        configuration.locales[0] else configuration.locale ?: Locale.getDefault()

    @Synchronized private fun localizedResources(language: String): android.content.res.Resources? {
        val ctx = context ?: return null
        return resources.getOrPut(language) {
            val configuration = Configuration(ctx.resources.configuration)
            configuration.setLocale(if (language == "zh") Locale.SIMPLIFIED_CHINESE else Locale.US)
            ctx.createConfigurationContext(configuration).resources
        }
    }

    fun text(@StringRes id: Int, vararg args: Any?): String = textFor(code, id, *args)

    fun textFor(language: String, @StringRes id: Int, vararg args: Any?): String {
        val pair = MessageCatalog.messages[id]
        val template = localizedResources(language)?.getString(id)
            ?: (if (language == "zh") pair?.first else pair?.second)
            ?: error("Missing localized text: $id")
        return if (args.isEmpty()) template else String.format(if (language == "zh") Locale.SIMPLIFIED_CHINESE else Locale.US, template, *args)
    }

    fun outputLabel(value: String): String {
        for (prefix in listOf("蓝牙", "Bluetooth")) {
            if (value.startsWith(prefix + " · ")) return text(com.applemusicktv.R.string.msg_017) + value.removePrefix(prefix)
        }
        return message(value).orEmpty()
    }

    fun heading(value: String): String {
        val id = when (value) {
            "For You" -> com.applemusicktv.R.string.msg_315
            "Find Your Mood" -> com.applemusicktv.R.string.msg_316
            "New" -> com.applemusicktv.R.string.msg_317
            "New Releases" -> com.applemusicktv.R.string.msg_318
            "Top Playlists" -> com.applemusicktv.R.string.msg_319
            "Top Albums" -> com.applemusicktv.R.string.msg_320
            "Recently Played" -> com.applemusicktv.R.string.msg_321
            "Playlists Made for You" -> com.applemusicktv.R.string.msg_322
            "Recently Added" -> com.applemusicktv.R.string.msg_323
            else -> null
        }
        return id?.let { text(it) } ?: value
    }

    /** Only use for app-generated status/errors, never for song names, lyrics or user input. */
    fun message(value: String?): String? {
        value ?: return null
        val target = code
        for ((id, pair) in MessageCatalog.messages) {
            if (value == pair.first || value == pair.second) return textFor(target, id)
        }
        for ((id, pair) in MessageCatalog.messages) {
            for (template in listOf(pair.first, pair.second)) {
                val tokens = Regex("%([0-9]+)\\\$s").findAll(template).toList()
                if (tokens.isEmpty()) continue
                var offset = 0
                val pattern = buildString {
                    append("^")
                    for (token in tokens) {
                        append(Regex.escape(template.substring(offset, token.range.first)))
                        append("(.*?)")
                        offset = token.range.last + 1
                    }
                    append(Regex.escape(template.substring(offset).replace("%%", "%")))
                    append("$")
                }
                val match = Regex(pattern, RegexOption.DOT_MATCHES_ALL).matchEntire(value) ?: continue
                val args = arrayOfNulls<Any>(tokens.maxOf { it.groupValues[1].toInt() })
                tokens.forEachIndexed { index, token -> args[token.groupValues[1].toInt()-1] = match.groupValues[index+1] }
                return textFor(target, id, *args)
            }
        }
        return value
    }
}

fun tr(@StringRes id: Int, vararg args: Any?): String = AppLanguage.text(id, *args)
