/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import androidx.annotation.StringRes
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.ModelLevel
import org.fcitx.fcitx5.android.engine.runtime.TableSettings
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.host.ConfigTree
import org.fcitx.fcitx5.android.host.EngineOptions
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPause
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.keyboard.T9EnglishTyping
import org.fcitx.fcitx5.android.input.keyboard.T9MultiTap
import org.fcitx.fcitx5.android.ui.main.modified.hasDefault
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Choice
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Item
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Section
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Toggle
import org.fcitx.fcitx5.android.ui.main.settings.placeholder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The pages as the preferences [InputMethodSettings.create] makes of them, over fcitx's config. */
@RunWith(RobolectricTestRunner::class)
// the real application, which initialises the AppPrefs the nine keys' page reads
@Config(application = FcitxApplication::class)
class InputMethodSettingsScreenTest {
    // the app's own preferences the tests set: back to their defaults whether a test passed or not,
    // or a failure leaks its value into the tests after it in this JVM
    @After
    fun restorePrefs() {
        AppPrefs.getInstance().keyboard.run {
            t9MultiTap.setValue(t9MultiTap.defaultValue)
            handwritingPause.setValue(handwritingPause.defaultValue)
            englishAutoCaps.setValue(englishAutoCaps.defaultValue)
        }
    }


    private fun create(items: List<Item>, cfg: MutableMap<String, String> = mutableMapOf(), save: () -> Unit = {}): PreferenceScreen {
        val ctx = PlaceholderStrings(RuntimeEnvironment.getApplication())
        return InputMethodSettings.create(PreferenceManager(ctx), items, cfg, save, open = {})
    }

    private fun screen(page: String, cfg: MutableMap<String, String> = mutableMapOf(), save: () -> Unit = {}) =
        create(InputMethodSettings.of(page)!!, cfg, save)

    private fun PreferenceGroup.all(): List<Preference> = (0 until preferenceCount).flatMap {
        val pref = getPreference(it)
        if (pref is PreferenceGroup) pref.all() else listOf(pref)
    }

    private fun PreferenceGroup.lists() = all().filterIsInstance<ListPreference>()

    private fun PreferenceGroup.titled(@StringRes title: Int) = all().single { it.title == placeholder(title) }

    // a page's keys, a group's after its name and a slash, to see what a save holds
    private fun Map<String, String>.flattened(): String = entries.joinToString("") {
        "${it.key}=${it.value}\n"
    }

    // what the engine reads once [im]'s page saves [cfg]: the host's partial save, then its settings
    // (that the host keeps these keys, the options androidengine.h declares, is AndroidEngineHeaderTest's to hold)
    private fun engineReads(im: String, cfg: Map<String, String>) = EngineOptions().apply { setPage(im, ConfigTree.of(cfg)) }.toSettings()

    private val defaults = EngineOptions().toSettings()

    // the English page's word list, opened as the app's settings open a page
    @Test
    fun theEnglishPageOpensItsWordList() {
        val opened = ArrayList<SettingsRoute>()
        val ctx = PlaceholderStrings(RuntimeEnvironment.getApplication())
        val screen = InputMethodSettings.create(PreferenceManager(ctx), InputMethodSettings.of(InputMethodNames.KEYBOARD)!!, mutableMapOf(), {}) { opened += it }
        val words = screen.titled(R.string.english_words)
        assertSame(words, screen.findPreference<Preference>(InputMethodSettings.ENGLISH_WORDS))
        words.performClick()
        assertEquals(listOf<SettingsRoute>(SettingsRoute.UserWordList("LEARNED", english = true)), opened)
    }

    // 恢复码表顺序: asked first, then the page's input method's order reset on the fcitx thread
    @Test
    fun aTablesOrderIsResetOnceConfirmed() {
        val acted = ArrayList<InputMethodSettings.Action>()
        val told = ArrayList<(Boolean) -> Unit>()
        val asked = ArrayList<() -> Unit>()
        val confirm: ImConfirm = { _, item, then ->
            assertEquals(R.string.im_reset_table_order_confirm, item.confirm)
            asked += then
        }
        val wubi = "engine-wubi"
        val ctx = PlaceholderStrings(RuntimeEnvironment.getApplication())
        val screen = InputMethodSettings.create(
            PreferenceManager(ctx), InputMethodSettings.of(wubi)!!, mutableMapOf(), {}, act = { item, done -> acted += item; told += done }, confirm = confirm,
        ) {}
        screen.findPreference<Preference>(InputMethodSettings.RESET_TABLE_ORDER)!!.performClick()
        assertTrue(acted.isEmpty())
        asked.single()()
        val fcitx = FakeHostApi()
        assertTrue(runBlocking { acted.single().run(fcitx, wubi) })
        assertEquals(listOf("learning(ResetTableOrder(im=$wubi))"), fcitx.calls)
        // F2673: the engine not there, not done; what is shown says which
        fcitx.learned = { LearningResult.Unavailable }
        assertFalse(runBlocking { acted.single().run(fcitx, wubi) })
        told.single()(true)
        assertEquals(ctx.getText(R.string.im_reset_table_order_done), ShadowToast.getTextOfLatestToast())
        told.single()(false)
        assertEquals(ctx.getText(R.string.dict_change_failed), ShadowToast.getTextOfLatestToast())
    }

    // a list's dialog looks its preference up by key: one without a key crashed on a tap
    @Test
    fun everyChoiceOnAPageIsFoundByItsOwnKey() {
        appInputMethods.forEach { page ->
            val screen = screen(page)
            val lists = screen.lists()
            assertEquals(page, InputMethodSettings.of(page)!!.flat().count { it is Choice || it is InputMethodSettings.AppChoice<*> }, lists.size)
            lists.forEach {
                assertNotNull(page, it.key)
                assertSame("$page ${it.key}", it, screen.findPreference<ListPreference>(it.key))
            }
        }
    }

    // the keyboard's own, in the 九键 section of pinyin's and English's pages (11-01): in the app's preferences,
    // nothing of it in fcitx's config
    @Test
    fun multiTapOnTheNineKeysPageIsTheAppsPreference() {
        val pref = AppPrefs.getInstance().keyboard.t9MultiTap
        pref.setValue(T9MultiTap.Medium)
        val cfg = mutableMapOf<String, String>()
        var saved = 0
        val list = create(InputMethodSettings.NINE_KEYS, cfg) { saved++ }.findPreference<ListPreference>(AppPrefs.T9_MULTI_TAP)!!
        assertEquals(T9MultiTap.Medium.name, list.value)
        assertTrue(list.callChangeListener(T9MultiTap.Off.name))
        assertEquals(T9MultiTap.Off, pref.getValue())
        assertEquals(0, saved)
        assertTrue(AppPrefs.T9_MULTI_TAP !in cfg.flattened())
        // on pinyin's page, its section hidden while the keyboard is the 26 keys
        AppPrefs.getInstance().keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Full)
        val row = screen(InputMethodCatalog.PINYIN).findPreference<ListPreference>(AppPrefs.T9_MULTI_TAP)!!
        assertFalse(row.parent!!.isVisible)
    }

    // how 九键英文 types: the app's preference, as multi-tap, on English's page
    @Test
    fun nineKeyEnglishTypingIsTheAppsPreference() {
        val keyboard = AppPrefs.getInstance().keyboard
        val typing = screen(InputMethodCatalog.ENGLISH, mutableMapOf()).findPreference<ListPreference>(AppPrefs.T9_ENGLISH_TYPING)!!
        assertEquals(T9EnglishTyping.Predictive.name, typing.value)
        assertTrue(typing.callChangeListener(T9EnglishTyping.MultiTap.name))
        assertEquals(T9EnglishTyping.MultiTap, keyboard.t9EnglishTyping.getValue())
        keyboard.t9EnglishTyping.setValue(T9EnglishTyping.Predictive)
    }

    // as multi-tap: the pad's own, nothing of it in fcitx's config
    @Test
    fun thePauseOnTheHandwritingPageIsTheAppsPreference() {
        val pref = AppPrefs.getInstance().keyboard.handwritingPause
        pref.setValue(HandwritingPause.Medium)
        val cfg = mutableMapOf<String, String>()
        var saved = 0
        val list = screen(InputMethodCatalog.HANDWRITING, cfg) { saved++ }.findPreference<ListPreference>(AppPrefs.HANDWRITING_PAUSE)!!
        assertEquals(HandwritingPause.Medium.name, list.value)
        assertTrue(list.callChangeListener(HandwritingPause.Slow.name))
        assertEquals(HandwritingPause.Slow, pref.getValue())
        assertEquals(0, saved)
        assertTrue(AppPrefs.HANDWRITING_PAUSE !in cfg.flattened())
        assertNull(screen(InputMethodCatalog.PINYIN).findPreference<ListPreference>(AppPrefs.HANDWRITING_PAUSE))
    }

    // as multi-tap: the keyboard's own, nothing of it in fcitx's config
    @Test
    fun autoCapsOnTheEnglishPageIsTheAppsPreference() {
        val pref = AppPrefs.getInstance().keyboard.englishAutoCaps
        pref.setValue(true)
        val cfg = mutableMapOf<String, String>()
        var saved = 0
        val toggle = screen(InputMethodNames.KEYBOARD, cfg) { saved++ }.findPreference<SwitchPreferenceCompat>(AppPrefs.ENGLISH_AUTO_CAPS)!!
        assertTrue(toggle.isChecked)
        assertTrue(toggle.callChangeListener(false))
        assertFalse(pref.getValue())
        assertEquals(0, saved)
        assertTrue(AppPrefs.ENGLISH_AUTO_CAPS !in cfg.flattened())
    }

    // 在键上划: one list of 滑行输入, 笔画筛选 and off (键盘手写 not offered yet), its summary the choice alone;
    // a choice stored as it is picked
    @Test
    fun keyDrawingIsOneChoiceSummarizedByItself() {
        val keyboard = AppPrefs.getInstance().keyboard
        try {
            keyboard.keyDrawing.setValue(KeyDrawing.Glide)
            val drawing = create(InputMethodSettings.SHARED).findPreference<ListPreference>(AppPrefs.KEY_DRAWING)!!
            assertEquals(listOf(KeyDrawing.Glide, KeyDrawing.StrokeFilter, KeyDrawing.Off).map { it.name }, drawing.entryValues.map { it.toString() })
            assertEquals(KeyDrawing.Glide.name, drawing.value)
            assertEquals(drawing.entry.toString(), drawing.summary?.toString())
            assertTrue(drawing.callChangeListener(KeyDrawing.StrokeFilter.name))
            assertEquals(KeyDrawing.StrokeFilter, keyboard.keyDrawing.getValue())
            // one this version does not offer (a later version's backup) shows as off, as the keyboard reads it
            keyboard.keyDrawing.setValue(KeyDrawing.Handwriting)
            assertEquals(KeyDrawing.Off.name, create(InputMethodSettings.SHARED).findPreference<ListPreference>(AppPrefs.KEY_DRAWING)!!.value)
        } finally {
            keyboard.keyDrawing.setValue(KeyDrawing.Off)
        }
    }

    // a dialog message would leave the list's dialog with no choices in it
    @Test
    fun anExplanationIsUnderTheValueNotInTheDialog() {
        appInputMethods.forEach { page -> screen(page).lists().forEach { assertNull("$page ${it.key}", it.dialogMessage) } }
        val length = InputMethodSettings.SHARED.flat().first { it is Choice && it.key == EngineSettings.MODEL_LEVEL } as Choice
        val pref = create(InputMethodSettings.SHARED).findPreference<ListPreference>(length.key)!!
        assertEquals("${pref.entry}\n${placeholder(length.summary)}", pref.summary?.toString())
    }

    // as a list without an explanation shows it, not as "null"
    @Test
    fun aValueNotAmongTheChoicesIsNotSetAboveTheExplanation() {
        val length = InputMethodSettings.SHARED.flat().first { it is Choice && it.key == EngineSettings.MODEL_LEVEL } as Choice
        val pref = create(InputMethodSettings.SHARED).findPreference<ListPreference>(length.key)!!
        pref.value = "7"
        assertEquals("${placeholder(androidx.preference.R.string.not_set)}\n${placeholder(length.summary)}", pref.summary?.toString())
    }

    // MyListPreferenceDialogFragment offers Default only when hasDefault() is true: these lists have no default to go back to
    @Test
    fun aChoiceHasNoDefaultToGoBackTo() {
        appInputMethods.forEach { page -> screen(page).lists().forEach { assertFalse("$page ${it.key}", it.hasDefault()) } }
        val given = create(InputMethodSettings.SHARED).lists().first().apply { setDefaultValue("5") }
        assertTrue(given.hasDefault())
    }

    // a key the engine does not read is a switch that does nothing; the shared page and the physical
    // keyboard's save into pinyin's config, whose shared keys are the addon's own, read by every
    // session (EngineSettings, one for all: pinyin's, a table's, handwriting's)
    @Test
    fun aChangeIsSavedWhereTheEngineReadsIt() {
        val cfg = mutableMapOf<String, String>()
        var saves = 0
        val page = create(InputMethodSettings.SHARED, cfg) { saves++ }
        page.titled(R.string.im_prediction).callChangeListener(false)
        page.titled(R.string.im_typos).callChangeListener(false)
        page.findPreference<ListPreference>(EngineSettings.MODEL_LEVEL)!!.callChangeListener("Low")
        create(InputMethodSettings.HARDWARE_KEYBOARD, cfg) { saves++ }.findPreference<ListPreference>(EngineSettings.PAGE_SIZE)!!.callChangeListener("5")
        assertEquals(4, saves)
        assertEquals("False", cfg["${EngineSettings.FUZZY}/${EngineSettings.TYPOS}"])
        assertEquals(defaults.copy(prediction = false, typos = false, pageSize = 5, modelLevel = ModelLevel.LOW), engineReads(InputMethodCatalog.PINYIN, cfg))
    }

    // a page saves what it shows alone: the addon keeps the rest (a partial load), so a table's page
    // opened before the shared one does not write back the prediction it read then
    @Test
    fun aPageSavesOnlyWhatItShows() {
        val cfg = mutableMapOf<String, String>().apply {
            this[EngineSettings.PREDICTION] = "True"
            this[TableSettings.HINT] = "True"
        }
        screen("engine-wubi", cfg).titled(R.string.im_hint).callChangeListener(false)
        val saved = InputMethodSettings.only(InputMethodSettings.of("engine-wubi")!!, cfg).flattened()
        assertTrue(saved, "${TableSettings.HINT}=False\n" in saved)
        assertFalse(saved, EngineSettings.PREDICTION in saved)
        val shared = mutableMapOf<String, String>().apply {
            this[EngineSettings.CHAIZI] = "False"
            this["${EngineSettings.FUZZY}/${Fuzzy.L_N.name}"] = "True"
            this[EngineSettings.EMOJI] = "False"
        }
        // pinyin's own (拆字) stays on its page; a pair of the fuzzy ones, not the group's name alone
        assertEquals("${EngineSettings.EMOJI}=False\n${EngineSettings.FUZZY}/${Fuzzy.L_N.name}=True\n", InputMethodSettings.only(InputMethodSettings.SHARED, shared).flattened())
    }

    // the shared page says under each what it is no use to; an input method's page, its own alone, says nothing of the kind
    @Test
    fun aSharedOptionSaysUnderItWhatItIsNoUseTo() {
        val shared = create(InputMethodSettings.SHARED)
        // in the Chinese input methods' section, whose title says English: only the exception within it
        assertEquals(placeholder(R.string.im_prediction_summary), shared.titled(R.string.im_prediction).summary?.toString())
        assertEquals("${placeholder(R.string.im_emoji_summary)}\n${placeholder(R.string.im_not_for)}", shared.titled(R.string.im_emoji).summary?.toString())
        assertNull(shared.titled(R.string.auto_pairs).summary)
        // 在键上划: the choice alone, which input methods each reaches is the docs'
        val drawing = shared.findPreference<ListPreference>(AppPrefs.KEY_DRAWING)!!
        assertEquals(drawing.entry.toString(), drawing.summary?.toString())
        val level = shared.findPreference<ListPreference>(EngineSettings.MODEL_LEVEL)!!
        assertEquals("${level.entry}\n${placeholder(R.string.im_model_level_summary)}", level.summary?.toString())
        // pinyin's own: not for the 14 keys (the hardware keyboard still types full pinyin)
        assertEquals("${placeholder(R.string.im_chaizi_summary)}\n${placeholder(R.string.im_not_for)}", screen(InputMethodCatalog.PINYIN).titled(R.string.im_chaizi).summary?.toString())
    }

    // every input method's page opens the shared one from its first row
    @Test
    fun eachPageOpensTheSharedOneFromItsFirstRow() {
        val ctx = PlaceholderStrings(RuntimeEnvironment.getApplication())
        appInputMethods.forEach { page ->
            val opened = ArrayList<SettingsRoute>()
            val screen = InputMethodSettings.create(PreferenceManager(ctx), InputMethodSettings.of(page)!!, mutableMapOf(), {}) { opened += it }
            val link = screen.getPreference(0)
            assertEquals(page, InputMethodSettings.SHARED_LINK, link.key)
            link.performClick()
            assertEquals(page, listOf<SettingsRoute>(SettingsRoute.SharedInput), opened)
        }
    }

    @Test
    fun aSchemeAndATablesOptionsAreSavedWhereTheEngineReadsThem() {
        val shuangpin = mutableMapOf<String, String>()
        screen(InputMethodCatalog.SHUANGPIN, shuangpin).findPreference<ListPreference>(EngineSettings.SHUANGPIN_PROFILE)!!.callChangeListener("Xiaohe")
        assertEquals("Xiaohe", engineReads(InputMethodCatalog.SHUANGPIN, shuangpin).shuangpin)
        val wubi = mutableMapOf<String, String>()
        val page = screen("engine-wubi", wubi)
        page.titled(R.string.im_auto_select).callChangeListener(false)
        page.titled(R.string.im_order_by_use).callChangeListener(false)
        // a table's page is its group of the addon's config
        val group = InputMethodCatalog.TABLES.getValue("engine-wubi").group
        assertEquals(
            defaults.tables.getValue(group).copy(autoSelect = false, orderByUse = false),
            engineReads("engine-wubi", wubi).tables[group],
        )
    }

    @Test
    fun theFuzzyPairsStoredShowOnTheirRow() {
        val cfg = mutableMapOf<String, String>().apply { this["${EngineSettings.FUZZY}/${Fuzzy.L_N.name}"] = "True" }
        assertEquals("l ↔ n", create(InputMethodSettings.SHARED, cfg).titled(R.string.im_fuzzy).summary?.toString())
    }

    @Test
    fun aSectionInAnotherStaysInIt() {
        val inner = Section(R.string.im_section_words, listOf(Toggle("Inner", { "inner" })))
        val screen = create(listOf(Section(R.string.im_section_typing, listOf(inner))))
        assertEquals(1, screen.preferenceCount)
        val outer = screen.getPreference(0) as PreferenceGroup
        assertEquals(placeholder(R.string.im_section_words), outer.getPreference(0).title?.toString())
    }

    // 双拼's 14 keys, then 微软 chosen on the page: the keyboard row says the 18 keys, the ones typed on and stored
    @Test
    @Uat("SP-26")
    fun theFourteenKeysUnderMicrosoftShowAsTheEighteen() {
        val keyboard = AppPrefs.getInstance().keyboard
        keyboard.shuangpinKeyboard.setValue(ShuangpinKeyboard.Keys14)
        try {
            val page = screen(InputMethodCatalog.SHUANGPIN, mutableMapOf())
            assertTrue(page.findPreference<ListPreference>(EngineSettings.SHUANGPIN_PROFILE)!!.callChangeListener(EngineSettings.MS))
            assertEquals(ShuangpinKeyboard.Keys18.name, page.findPreference<ListPreference>(AppPrefs.SHUANGPIN_KEYBOARD)!!.value)
        } finally {
            keyboard.shuangpinKeyboard.setValue(ShuangpinKeyboard.Full)
        }
    }
}
