/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.content.Context
import android.content.res.Configuration
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.forgetTheTestsLayouts
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.ui.main.MainFragment
import org.fcitx.fcitx5.android.ui.main.settings.AddressSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeysSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SoundSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings
import org.fcitx.fcitx5.android.ui.main.settings.im.flat
import org.fcitx.fcitx5.android.ui.search.SettingsSearch
import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Entry
import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
// the real application initialises the AppPrefs the nine keys' and English pages read
@Config(application = FcitxApplication::class)
class SettingsIndexTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun context(): Context = PlaceholderStrings(RuntimeEnvironment.getApplication())

    private fun entries(ctx: Context) = buildList<Entry<SettingsIndex.Target>> { SettingsIndex.inputMethods(ctx, this) }

    private val Entry<*>.isRow get() = kind == Kind.PAGE || kind == Kind.SETTING

    private fun pageOf(ctx: Context, uniqueName: String): PreferenceScreen =
        InputMethodSettings.create(PreferenceManager(ctx), InputMethodSettings.of(uniqueName)!!, mutableMapOf(), save = {}, open = {})

    private fun keysOn(ctx: Context, uniqueName: String): Set<String> {
        fun PreferenceGroup.keys(): List<String> = (0 until preferenceCount).flatMap {
            val pref = getPreference(it)
            if (pref is PreferenceGroup) pref.keys() else listOfNotNull(pref.key)
        }
        return pageOf(ctx, uniqueName).keys().toSet()
    }

    @Test
    fun everyOptionFoundOpensAnInputMethodsPageThatHasIt() {
        val ctx = context()
        val options = entries(ctx).filter { it.target.key != null && it.isRow }
        assertTrue(options.isNotEmpty())
        val pages = mutableMapOf<String, PreferenceScreen>()
        for (entry in options) {
            val route = entry.target.route as? SettingsRoute.InputMethodConfig
            assertNotNull("$entry", route)
            val screen = pages.getOrPut(route!!.uniqueName) { pageOf(ctx, route.uniqueName) }
            // the setting SearchHighlight finds by the key is the one the result is titled after
            assertEquals("$entry", entry.title, screen.findPreference<Preference>(entry.target.key!!)?.title?.toString())
        }
    }

    // moved off the input methods' pages: found where they are now
    @Test
    fun everySharedOptionFoundOpensTheSharedPageThatHasIt() {
        val ctx = context()
        val found = buildList<Entry<SettingsIndex.Target>> { SettingsIndex.sharedInput(ctx, this) }
        val screen = InputMethodSettings.create(PreferenceManager(ctx), InputMethodSettings.SHARED, mutableMapOf(), save = {}, open = {})
        val options = found.filter { it.target.key != null && it.isRow }
        // each row of the shared page, sections aside, once
        assertEquals(InputMethodSettings.SHARED.flat().size, options.size)
        for (entry in options) {
            assertEquals("$entry", SettingsRoute.SharedInput, entry.target.route)
            assertEquals("$entry", entry.title, screen.findPreference<Preference>(entry.target.key!!)?.title?.toString())
        }
    }

    @Test
    fun everyPageOfTheHomePageIsFoundAndWhatMovedOnIt() {
        val all = SettingsIndex.build(context())
        val pages = all.filter { it.target.key == null }.map { it.target.route }.toSet()
        MainFragment.HOME.forEach { assertTrue("${it.route}", it.route in pages) }
        fun on(route: SettingsRoute) = all.filter { it.target.route == route }.mapNotNull { it.target.key }.toSet()
        assertTrue(EngineSettings.PAGE_SIZE in on(SettingsRoute.HardwareKeyboard))
        assertTrue(AppPrefs.getInstance().advanced.disableAnimation.key in on(SettingsRoute.Theme))
        assertTrue(AppPrefs.getInstance().symbols.defaultEmojiSkinTone.key in on(SettingsRoute.Clipboard))
        assertTrue(AppPrefs.getInstance().keyboard.soundOnKeyPress.key in on(SettingsRoute.SoundAndVibration))
        assertFalse(AppPrefs.getInstance().keyboard.soundOnKeyPress.key in on(SettingsRoute.Keys))
        assertTrue(AppPrefs.getInstance().keyboard.popupOnKeyPress.key in on(SettingsRoute.Keys))
        assertFalse(AppPrefs.AUTO_PAIRS in on(SettingsRoute.Keys))
        assertEquals(
            setOf(AddressSettingsFragment.EMAIL_KEY, AddressSettingsFragment.URL_KEY, AddressSettingsFragment.REMEMBER_KEY, AddressSettingsFragment.REMEMBERED_KEY),
            on(SettingsRoute.Addresses),
        )
        // the page itself found once, by 通用输入's row that opens it
        assertEquals(1, all.count { it.title == context().getString(R.string.address_suggestions) })
    }

    @Test
    fun theKeyboardChoicesAreFoundOnTheirInputMethodsPage() {
        val all = entries(context())
        assertRows(
            listOf(
                Triple(AppPrefs.PINYIN_KEYBOARD, InputMethodCatalog.PINYIN, "全拼's keyboard, on pinyin's page"),
                Triple(AppPrefs.SHUANGPIN_KEYBOARD, InputMethodCatalog.SHUANGPIN, "双拼's, under its scheme"),
            ),
            { it.third },
        ) { (key, im, _) ->
            val found = all.filter { it.target.key == key }
            assertEquals(listOf(im), found.map { (it.target.route as SettingsRoute.InputMethodConfig).uniqueName })
        }
    }

    @Test
    fun everyBuiltInTableHasItsOwnPageAndOptionsFound() {
        val ctx = context()
        val all = entries(ctx)
        // the engine's own list: a table added there is held to this too
        for (table in InputMethodCatalog.TABLES.keys) {
            val on = all.filter { (it.target.route as? SettingsRoute.InputMethodConfig)?.uniqueName == table }
            assertEquals(table, 1, on.count { it.target.key == null })
            // the tables' keyboard hidden unless a user layout has the tables' keys: else five results opened a page without it
            assertEquals(table, keysOn(ctx, table) - InputMethodSettings.SHARED_LINK - AppPrefs.TABLE_KEYBOARD_LAYOUT, on.mapNotNull { it.target.key }.toSet())
        }
    }

    @Test
    @Uat("ST-194")
    fun theTablesKeyboardIsFoundWhileALayoutOfTheUsersHasItsKeys() {
        val ctx = context()
        val dirWas = CustomKeyboards.dir
        val dir = File(folder.root, "layouts").apply { mkdirs() }
        CustomKeyboards.dir = { dir }
        try {
            fun found() = entries(ctx).filter { it.target.key == AppPrefs.TABLE_KEYBOARD_LAYOUT }
                .map { (it.target.route as SettingsRoute.InputMethodConfig).uniqueName }.sorted()
            assertEquals("none of the user's", emptyList<String>(), found())
            val qwerty = BuiltInLayouts(ctx).text("qwerty")!!
            File(dir, "my-qwerty.xml").writeText(qwerty.replaceFirst("id=\"qwerty\"", "id=\"my-qwerty\" based-on=\"qwerty\""))
            CustomKeyboards.forget("my-qwerty")
            assertTrue(CustomKeyboards.choice("my-qwerty")!!.tableExtras)
            assertEquals("the tables' pages", InputMethodCatalog.TABLES.keys.sorted(), found())
            for (table in InputMethodCatalog.TABLES.keys) {
                assertTrue(table, AppPrefs.TABLE_KEYBOARD_LAYOUT in keysOn(ctx, table))
                assertTrue(table, pageOf(ctx, table).findPreference<Preference>(AppPrefs.TABLE_KEYBOARD_LAYOUT)!!.isVisible)
            }
            File(dir, "my-qwerty.xml").delete()
            CustomKeyboards.forget("my-qwerty")
            assertEquals("gone", emptyList<String>(), found())
        } finally {
            forgetTheTestsLayouts(dirWas)
        }
    }

    /** A setting with no key, or sharing one on its page, would point at nothing or at the other. */
    @Test
    fun eachPageIsFoundOnceAndEachSettingOnItByAKeyOfItsOwn() {
        for ((route, on) in SettingsIndex.build(context()).filter { it.isRow }.groupBy { it.target.route }) {
            // the page itself: neither a row's, a control's (view:) nor one said where it is (↶, ⋮'s)
            val page = on.count { it.target.key == null && it.target.view == null && it.target.where == 0 }
            assertEquals("$route", if (route == SettingsRoute.Addresses || route == SettingsRoute.Index) 0 else 1, page)
            val keys = on.mapNotNull { it.target.key }
            assertEquals("$route: $keys", keys.size, keys.toSet().size)
        }
    }

    /** The shared-page link on every input method's page was found ten times over. */
    @Test
    fun noTwoResultsOpenTheSamePageUnderTheSameTitle() {
        val all = SettingsIndex.build(context())
        assertTrue(all.none { it.target.key == InputMethodSettings.SHARED_LINK })
        val twice = all.filter { it.isRow }.groupBy { it.target.route to it.title }.filterValues { it.size > 1 }
        assertTrue("$twice", twice.isEmpty())
    }

    // one section each: none on neither, none on both
    @Test
    fun theKeysAndTheSoundPagesTogetherHaveTheWholeKeyboardCategory() {
        val ctx = context()
        fun keysOf(build: (PreferenceScreen) -> Unit): List<String> {
            fun PreferenceGroup.keys(): List<String> = (0 until preferenceCount).flatMap {
                val pref = getPreference(it)
                if (pref is PreferenceGroup) pref.keys() else listOfNotNull(pref.key)
            }
            return PreferenceManager(ctx).createPreferenceScreen(ctx).also(build).keys()
        }
        val whole = keysOf { AppPrefs.getInstance().keyboard.createUi(it) }
        val pages = keysOf { KeysSettingsFragment.build(it) } + keysOf { SoundSettingsFragment.build(it) }
        assertEquals(whole.sorted(), pages.sorted())
    }

    /** A preference that saved its default as it is made to be read would pin every untouched default, at every search. */
    @Test
    fun buildingTheIndexSavesNothing() {
        val stores = listOf(
            PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication()),
            AppPrefs.getInstance().keyboard.keyboardHeightPercent.sharedPreferences,
        )
        val before = stores.map { it.all.toMap() }
        assertTrue(SettingsIndex.build(context()).isNotEmpty())
        assertEquals(before, stores.map { it.all.toMap() })
    }

    // nine-key settings are hidden unless the keyboard is nine keys: each result also names the always-shown keyboard row
    @Test
    fun aNineKeysSettingFoundAlsoNamesTheKeyboardRowThatShowsIt() {
        val ctx = context()
        val nine = entries(ctx).filter { it.target.key == AppPrefs.T9_MULTI_TAP }
        assertTrue(nine.isNotEmpty())
        for (entry in nine) {
            val route = entry.target.route as SettingsRoute.InputMethodConfig
            val via = entry.target.via
            assertNotNull("$entry", via)
            val row = pageOf(ctx, route.uniqueName).findPreference<Preference>(via!!)
            assertTrue("$entry", row != null && generateSequence(row) { it.parent }.all { it.isVisible })
        }
    }

    // hidden unless strokes are drawn: found, they name 在键上划, always shown, to point at instead
    @Test
    fun theStrokeSettingsFoundNameTheKeyDrawingRowThatShowsThem() {
        val ctx = context()
        val shared = buildList<Entry<SettingsIndex.Target>> { SettingsIndex.sharedInput(ctx, this) }
        val screen = InputMethodSettings.create(PreferenceManager(ctx), InputMethodSettings.SHARED, mutableMapOf(), save = {}, open = {})
        val row = screen.findPreference<Preference>(AppPrefs.KEY_DRAWING)
        assertTrue(row != null && generateSequence(row) { it.parent }.all { it.isVisible })
        for (key in listOf(AppPrefs.STROKE_LIFT, EngineSettings.STROKE_SINGLES)) {
            val entry = shared.single { it.target.key == key && it.isRow }
            assertEquals("$entry", SettingsRoute.SharedInput, entry.target.route)
            assertEquals("$entry", AppPrefs.KEY_DRAWING, entry.target.via)
            assertFalse("$entry", screen.findPreference<Preference>(key)!!.isVisible)
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun theRareCharactersPageIsFound() {
        val search = SettingsSearch(SettingsIndex.build(RuntimeEnvironment.getApplication()))
        for (term in listOf("生僻字屏蔽", "生僻字")) {
            assertTrue(term, SettingsIndex.Target(SettingsRoute.RareChars, null) in search.find(term).map { it.target })
        }
    }

    // found on each input method page, not again as a page of its own
    @Test
    @Config(qualifiers = "zh-rCN")
    fun theNineKeysLettersAreFoundOnTheInputMethodsPagesOnly() {
        val ctx = RuntimeEnvironment.getApplication()
        val found = SettingsSearch(SettingsIndex.build(ctx)).find(ctx.getString(R.string.t9_letters)).map { it.entry }
        assertTrue(found.toString(), found.isNotEmpty() && found.none { it.id == "page:T9Letters" || it.target.route == SettingsRoute.T9Letters && it.target.key == null })
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun theShortcutsAndTheSwipeAreFoundByTheirWords() {
        val ctx = RuntimeEnvironment.getApplication()
        val search = SettingsSearch(SettingsIndex.build(ctx))
        fun found(term: String) = search.find(term).map { it.target }
        for (term in listOf("快捷键", "全选", "复制", "粘贴")) {
            assertTrue("$term: ${found(term)}", SettingsIndex.Target(SettingsRoute.PopupOverrides, null) in found(term))
        }
        assertTrue(found("上滑").toString(), found("上滑").any { it.route == SettingsRoute.Keys && it.key == AppPrefs.SWIPE_UP })
        assertTrue(found("下滑").toString(), found("下滑").any { it.route == SettingsRoute.Keys && it.key == AppPrefs.SWIPE_DOWN })
        // 编码行显示双拼按键 by the words people say for it
        for (term in listOf("拼音行", "按键显示")) {
            assertTrue("$term: ${found(term)}", found(term).any { it.key == EngineSettings.SHUANGPIN_KEYS_SHOWN })
        }
    }

    @Test
    fun everyEntryHasAnIdOfItsOwn() {
        val all = SettingsIndex.build(context())
        assertTrue(all.none { it.id.isEmpty() })
        val twice = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue("$twice", twice.isEmpty())
        assertTrue(all.any { it.id == "pref:${AppPrefs.T9_MULTI_TAP}@${InputMethodCatalog.PINYIN}" })
        assertEquals("page:InputMethodConfig/${InputMethodCatalog.PINYIN}", SettingsIndex.pageId(SettingsRoute.InputMethodConfig("x", InputMethodCatalog.PINYIN)))
        // by uri: its page's, whatever it was opened at
        assertEquals("page:PinyinDict", SettingsIndex.pageId(SettingsRoute.PinyinDict("content://x")))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun aChoiceFoundOpensItsSettingsPageAtItsRow() {
        val all = SettingsIndex.build(RuntimeEnvironment.getApplication())
        val nine = all.single { it.id == "value:${AppPrefs.T9_MULTI_TAP}=Fast@${InputMethodCatalog.PINYIN}" }
        assertEquals(Kind.VALUE, nine.kind)
        assertEquals(SettingsIndex.Target(SettingsRoute.InputMethodConfig("拼音", InputMethodCatalog.PINYIN), AppPrefs.T9_MULTI_TAP, AppPrefs.PINYIN_KEYBOARD), nine.target)
        assertTrue(nine.path.last() == RuntimeEnvironment.getApplication().getString(R.string.t9_multi_tap) && nine.keywords.isEmpty())
        assertTrue(all.any { it.id == "value:${AppPrefs.PINYIN_KEYBOARD}=Keys18@${InputMethodCatalog.PINYIN}" })
        assertTrue(all.any { it.id == "value:keyboard_height_level=1" && it.target == SettingsIndex.Target(SettingsRoute.Theme, "keyboard_height_level") })
        // kw_split hangs on "1", which must stay 分开
        val split = RuntimeEnvironment.getApplication().getString(R.string.keyboard_style_split)
        assertEquals(split, all.single { it.id == "value:keyboard_style_portrait_list=1" }.title)
        assertEquals(split, all.single { it.id == "value:keyboard_style_landscape_list=1" }.title)
        // no choice worth a result
        assertTrue(all.none { it.kind == Kind.VALUE && it.target.key in setOf(AppPrefs.STROKE_LIFT, EngineSettings.STROKE_SINGLES, EngineSettings.PAGE_SIZE) })
    }

    // 未添加 only once fcitx has said which input methods are added
    @Test
    @Config(qualifiers = "zh-rCN")
    fun aResultSaysWhatItIsAndWhetherItsInputMethodIsAdded() {
        val ctx = RuntimeEnvironment.getApplication()
        val all = SettingsIndex.build(ctx)
        fun notes(id: String, added: Set<String>?, alias: String? = null) =
            SettingsSearchFragment.notes(ctx, SettingsSearch.Found(all.single { it.id == id }, alias), added)
        val choice = "value:${AppPrefs.T9_MULTI_TAP}=Fast@${InputMethodCatalog.PINYIN}"
        assertEquals(listOf("选项"), notes(choice, null))
        assertEquals(listOf("选项"), notes(choice, setOf(InputMethodCatalog.PINYIN)))
        assertEquals(listOf("选项", "未添加"), notes(choice, emptySet()))
        assertEquals(listOf("未添加"), notes("pref:${AppPrefs.T9_MULTI_TAP}@${InputMethodCatalog.PINYIN}", setOf("other")))
        assertEquals(emptyList<String>(), notes("page:Theme", emptySet()))
        assertEquals(listOf(ctx.getString(R.string.search_is_command), ctx.getString(R.string.search_also_called, "笔画")), notes(SettingsIndex.STROKES, emptySet(), "笔画"))
    }

    @Test
    fun everyCommandIsFoundOnThePageOfTheKeysSwipes() {
        val commands = SettingsIndex.build(context()).filter { it.kind == Kind.COMMAND }
        // a stroke or group added counts as it should
        assertEquals(KeyCommand.entries.count { it.group != KeyCommand.Group.Stroke } + 1, commands.size)
        assertTrue(commands.all { it.target == SettingsIndex.Target(SettingsRoute.PopupOverrides, null) })
        assertEquals(1, commands.count { it.id == SettingsIndex.STROKES })
        assertTrue(commands.none { it.id == "cmd:stroke_h" })
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun theHomePagesWordsAreItsPagesAndItsLanguageRowIsFound() {
        val ctx = RuntimeEnvironment.getApplication()
        val all = SettingsIndex.build(ctx)
        for (row in MainFragment.HOME) {
            assertEquals("${row.route}", ctx.getString(row.summary), all.single { it.id == SettingsIndex.pageId(row.route) }.summary)
        }
        val search = SettingsSearch(all)
        assertEquals(SettingsRoute.SharedInput, search.find("表情 模糊音").first().target.route)
        assertEquals(SettingsIndex.Target(SettingsRoute.Index, MainFragment.LANGUAGE_KEY), search.find("界面语言").first().target)
    }

    // a renamed or removed setting leaves no other names hanging on nothing; every language has each group
    @Test
    fun everyIdWithOtherNamesIsIndexed() {
        val app = RuntimeEnvironment.getApplication()
        for (tag in listOf("zh-CN", "zh-TW", "en")) {
            val ctx = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            val ids = SettingsIndex.build(ctx).map { it.id }.flatMap { listOf(it, it.substringBefore('@')) }.toSet()
            val missing = SearchKeywords.TABLE.flatMap { it.first }.filter { it !in ids }
            assertTrue("$tag: $missing", missing.isEmpty())
        }
        val arrays = listOf("values", "values-zh-rCN", "values-zh-rTW").associateWith { dir ->
            Regex("""<string-array name="(kw_\w+)">""").findAll(File("src/main/res/$dir/search_keywords.xml").readText()).map { it.groupValues[1] }.toSet()
        }
        val named = SearchKeywords.TABLE.map { RuntimeEnvironment.getApplication().resources.getResourceEntryName(it.second) }.toSet()
        for ((dir, there) in arrays) assertEquals(dir, named, there)
    }

    // the English keywords name the level's default, so "auto" finds the setting
    @Test
    fun autoFindsTheModelLevelInEnglish() {
        val app = RuntimeEnvironment.getApplication()
        val ctx = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag("en")) })
        val found = SettingsSearch(SettingsIndex.build(ctx)).find("auto")
        assertTrue(found.map { it.target }.toString(), found.any { it.target.key == EngineSettings.MODEL_LEVEL })
    }

    private fun strokeFilterFindsFour(ctx: Context, query: String) {
        val found = SettingsSearch(SettingsIndex.build(ctx)).find(query)
        assertEquals(
            found.map { it.entry.id }.toString(),
            listOf("value:${AppPrefs.KEY_DRAWING}=StrokeFilter", "pref:${AppPrefs.STROKE_LIFT}", "pref:${EngineSettings.STROKE_SINGLES}", SettingsIndex.STROKES),
            found.map { it.entry.id },
        )
        assertEquals(listOf(null, null, query, query), found.map { it.alias?.lowercase() })
    }

    @Test
    @Uat("ST-156", "ST-159")
    fun strokeFilterFindsFourInEachLanguage() {
        val app = RuntimeEnvironment.getApplication()
        fun inLanguage(tag: String) = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
        assertRows(
            "zh-CN: 笔画筛选" to { strokeFilterFindsFour(inLanguage("zh-CN"), "笔画筛选") },
            "zh-TW: 筆畫篩選" to { strokeFilterFindsFour(inLanguage("zh-TW"), "筆畫篩選") },
            "en: stroke filter" to { strokeFilterFindsFour(inLanguage("en"), "stroke filter") },
        )
    }
}
