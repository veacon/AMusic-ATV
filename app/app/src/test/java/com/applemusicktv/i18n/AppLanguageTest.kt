package com.applemusicktv.i18n

import android.app.Application
import android.content.res.Configuration
import com.applemusicktv.R
import com.applemusicktv.ui.navigation.TopNavTab
import com.applemusicktv.ui.background.FluidQuality
import com.applemusicktv.ui.viewmodel.PlaybackOrder
import java.util.Locale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,28], application=Application::class)
class AppLanguageTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun init() {
        context.getSharedPreferences("app_language",0).edit().clear().commit()
        AppLanguage.initialize(context)
    }
    @Test fun systemDefaultUsesChineseForAllChineseVariantsAndEnglishOtherwise() {
        assertEquals(LanguageChoice.System, AppLanguage.choice)
        for (tag in listOf("zh-CN","zh-TW","zh-HK")) assertEquals("zh",AppLanguage.resolve(LanguageChoice.System,Locale.forLanguageTag(tag)))
        for (tag in listOf("en-US","en-GB","fr-FR","ja-JP")) assertEquals("en",AppLanguage.resolve(LanguageChoice.System,Locale.forLanguageTag(tag)))
        assertEquals("zh",AppLanguage.resolve(LanguageChoice.Chinese,Locale.US))
        assertEquals("en",AppLanguage.resolve(LanguageChoice.English,Locale.CHINA))
    }
    @Test fun manualSelectionSurvivesInitializationAndFollowSystemRespondsToConfiguration() {
        AppLanguage.select(LanguageChoice.English)
        AppLanguage.initialize(context)
        assertEquals(LanguageChoice.English,AppLanguage.choice)
        val zh=Configuration(context.resources.configuration).apply {setLocale(Locale.CHINA)}
        AppLanguage.configurationChanged(zh)
        assertEquals("en",AppLanguage.code)
        AppLanguage.select(LanguageChoice.System)
        assertEquals("zh",AppLanguage.code)
        AppLanguage.configurationChanged(Configuration(zh).apply {setLocale(Locale.FRANCE)})
        assertEquals("en",AppLanguage.code)
        assertEquals("en",AppLanguage.changes.value)
    }
    @Test fun bothResourceSetsHaveEveryMessageAndMatchingFormatArguments() {
        val declaredMessages = R.string::class.java.fields
            .filter { it.name.startsWith("msg_") }.map { it.getInt(null) }.toSet()
        assertTrue("Localized message catalog must not be empty", declaredMessages.isNotEmpty())
        assertTrue("Every numbered message must have both translations", MessageCatalog.messages.keys.containsAll(declaredMessages))
        for ((id,pair) in MessageCatalog.messages) {
            val zh=AppLanguage.textFor("zh",id)
            val en=AppLanguage.textFor("en",id)
            assertEquals("Chinese resource $id",pair.first,zh)
            assertEquals("English resource $id",pair.second,en)
            assertEquals(Regex("%[0-9]+\\\$s").findAll(zh).map {it.value}.toList(),Regex("%[0-9]+\\\$s").findAll(en).map {it.value}.toList())
            if(id!=R.string.msg_312) assertFalse("English contains untranslated copy: $en",Regex("[\\u4e00-\\u9fff]").containsMatchIn(en))
        }
    }
    @Test fun enumLabelsResolveAtReadTimeInsteadOfStayingInTheStartupLanguage() {
        AppLanguage.select(LanguageChoice.Chinese)
        assertEquals("立即聆听",TopNavTab.ListenNow.label)
        assertEquals("标准",FluidQuality.STANDARD.label)
        AppLanguage.select(LanguageChoice.English)
        assertEquals("Listen Now",TopNavTab.ListenNow.label)
        assertEquals("Standard",FluidQuality.STANDARD.label)
        assertEquals("Shuffle",PlaybackOrder.Shuffle.label)
    }
    @Test fun appGeneratedMessagesSwitchWithoutTranslatingArgumentsOrUnrecognizedContent() {
        AppLanguage.select(LanguageChoice.Chinese)
        val message=tr(R.string.msg_028,"中文 song title")
        AppLanguage.select(LanguageChoice.English)
        assertEquals("Added “中文 song title” to your library",AppLanguage.message(message))
        assertEquals("Unknown user playlist 中文",AppLanguage.message("Unknown user playlist 中文"))
        assertEquals("Something went wrong: Could not add to your library",tr(R.string.msg_096,AppLanguage.message("无法添加到资料库")))
    }
    @Test fun percentPluralAndNewSmallestSizeLabelsAreFormattedCorrectly() {
        AppLanguage.select(LanguageChoice.English)
        assertEquals("Loading lyric fonts, 12%",tr(R.string.msg_301,12))
        assertEquals("1 song",AppLanguage.quantity(R.plurals.song_count,1))
        assertEquals("2 songs",AppLanguage.quantity(R.plurals.song_count,2))
        assertEquals("1 hour",AppLanguage.quantity(R.plurals.hour_count,1))
        assertEquals("Bluetooth · 设备",AppLanguage.outputLabel("蓝牙 · 设备"))
        assertEquals("Extra small",com.applemusicktv.ui.screens.lyricsScaleLabel(0.8f))
        AppLanguage.select(LanguageChoice.Chinese)
        assertEquals("1 首歌曲",AppLanguage.quantity(R.plurals.song_count,1))
        assertEquals("歌词字体加载中，12%",tr(R.string.msg_301,12))
        assertEquals("更小",com.applemusicktv.ui.screens.lyricsScaleLabel(0.8f))
    }
}
