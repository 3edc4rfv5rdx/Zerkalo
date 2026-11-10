package xx.zerkalo.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.os.Build
import android.os.LocaleList
import xx.zerkalo.R
import java.util.Locale

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/** One entry of the language picker; [tag] empty means "follow the device". */
data class LanguageOption(val tag: String, val label: String)

/**
 * The languages this build ships, read from res/xml/locales_config.xml, each
 * named in itself. Read from the XML directly: LocaleConfig, which would do it,
 * needs API 34.
 */
fun supportedLanguages(context: Context, systemLabel: String): List<LanguageOption> {
    val tags = mutableListOf<String>()
    context.resources.getXml(R.xml.locales_config).use { parser ->
        while (parser.next() != XmlResourceParser.END_DOCUMENT) {
            if (parser.eventType == XmlResourceParser.START_TAG && parser.name == "locale") {
                parser.getAttributeValue(ANDROID_NS, "name")?.let(tags::add)
            }
        }
    }
    val languages = tags.map { tag ->
        val locale = Locale.forLanguageTag(tag)
        LanguageOption(
            tag = tag,
            label = locale.getDisplayLanguage(locale)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() },
        )
    }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    return listOf(LanguageOption("", systemLabel)) + languages
}

/**
 * The per-app language; empty for "follow the device". The platform holds it
 * from API 33, [AppSettings] below that.
 */
fun currentLanguageTag(context: Context): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java)
            .applicationLocales
            .takeUnless { it.isEmpty }
            ?.get(0)
            ?.toLanguageTag()
            .orEmpty()
    } else {
        AppSettings.languageTag(context)
    }

/**
 * From API 33 the system persists the choice and recreates the activity;
 * below that the app does both, and [localizedContext] applies it.
 */
fun setLanguageTag(activity: Activity, tag: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activity.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    } else {
        AppSettings.setLanguageTag(activity, tag)
        activity.recreate()
    }
}

/**
 * The activity's base context in the chosen language, below API 33; from 33
 * the platform does this itself and [base] comes back as it is.
 */
fun localizedContext(base: Context): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
    val tag = AppSettings.languageTag(base)
    val locale = if (tag.isEmpty()) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    if (tag.isEmpty()) return base
    // Only the locale is set: an empty Configuration overrides nothing else, so
    // orientation and screen size still follow the device.
    val override = Configuration().apply { setLocale(locale) }
    return base.createConfigurationContext(override)
}
