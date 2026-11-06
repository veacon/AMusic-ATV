package com.applemusicktv.i18n

import android.content.Context
import org.json.JSONObject
import org.json.JSONTokener

/** Static page copy only. Token endpoints, bookmark actions and raw logs retain their behavior. */
internal object PhonePage {
    fun resolveLanguage(query: String?, cookie: String?, acceptLanguage: String?): String {
        if (query == "zh" || query == "en") return query
        cookie?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("amtv_lang=") }
            ?.substringAfter('=')?.takeIf { it == "zh" || it == "en" }?.let { return it }
        val preferred = acceptLanguage.orEmpty().split(',').mapIndexedNotNull { index, part ->
            val bits = part.trim().split(';')
            val quality = bits.drop(1).firstOrNull { it.trim().startsWith("q=") }
                ?.trim()?.substringAfter('=')?.toDoubleOrNull() ?: 1.0
            if (bits[0].isBlank() || quality <= 0) null else Triple(bits[0], quality, index)
        }.sortedWith(compareByDescending<Triple<String, Double, Int>> { it.second }.thenBy { it.third })
        return if (preferred.firstOrNull()?.first?.substringBefore('-').equals("zh", true)) "zh" else "en"
    }

    private val dictionaries = mutableMapOf<String, List<Pair<String, String>>>()
    @Synchronized private fun dictionary(context: Context): List<Pair<String, String>> = dictionaries.getOrPut("en") {
        val obj = JSONObject(context.assets.open("i18n/phone-en.json").bufferedReader().use { it.readText() })
        obj.keys().asSequence().map { it to obj.getString(it) }.sortedByDescending { it.first.length }.toList()
    }

    fun render(context: Context, html: String, language: String): String {
        if (language == "zh") return html
        var value = html
        // Replace longer phrases first so short labels cannot split a sentence.
        for ((zh, en) in dictionary(context)) value = value.replace(zh, en)
        // Language names stay in their native script in the language selector.
        return value.replace("</span>。", "</span>.")
    }

    fun bookmarkJson(context: Context, language: String): String {
        val source = context.assets.open("i18n/bookmark.json").bufferedReader().use { it.readText() }
        if (language == "zh") return source
        val raw = JSONTokener(source).nextValue() as String
        // This one label is Close in a dialog, rather than Off in a settings toggle.
        val closeLocalized = raw.replace("关闭", "Close")
        return JSONObject.quote(render(context, closeLocalized, language)).replace("<", "\\u003c")
    }

    fun bookmarkHtml(context: Context, language: String): String {
        val code = JSONTokener(bookmarkJson(context, language)).nextValue() as String
        return code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#x27;")
    }
}
