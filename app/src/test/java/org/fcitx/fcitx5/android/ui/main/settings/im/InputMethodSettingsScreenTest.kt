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
import org.fcitx.fcitx5.android.data.pack.TestShelves
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
import org.fcitx.fcitx5.android.input.keyboard.StrokeLift
import org.fcitx.fcitx5.android.input.keyboard.forgetTheTestsLayouts
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
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
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.data.layout.CustomLayouts
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.utils.appContext
import java.nio.file.Files
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

@RunWith(RobolectricTestRunner::class)
// the real application initialises the AppPrefs the nine keys' page reads
@Config(application = FcitxApplication::class)
class InputMethodSettingsScreenTest {
    // reset whether a test passed or not, or a failure leaks into later tests in this JVM
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

    private fun Map<String, String>.flattened(): String = entries.joinToString("") {
        "${it.key}=${it.value}\n"
    }

    // AndroidEngineHeaderTest holds that the host keeps these keys
    private fun engineReads(im: String, cfg: Map<String, String>) = EngineOptions().apply { setPage(im, ConfigTree.of(cfg)) }.toSettings()

    private val defaults = EngineOptions().toSettings()

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
        // the engine not there: not done, and said so
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
            assertEquals(page, InputMethodSettings.of(page)!!.flat().count { it is Choice || it is InputMethodSettings.AppChoice<*> || it is InputMethodSettings.TableKeyboard }, lists.size)
            lists.forEach {
                assertNotNull(page, it.key)
                assertSame("$page ${it.key}", it, screen.findPreference<ListPreference>(it.key))
            }
        }
    }

    // the app's preferences, nothing in fcitx's config
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
        AppPrefs.getInstance().keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Full)
        val row = screen(InputMethodCatalog.PINYIN).findPreference<ListPreference>(AppPrefs.T9_MULTI_TAP)!!
        assertFalse(row.parent!!.isVisible)
    }

    @Test
    fun nineKeyEnglishTypingIsTheAppsPreference() {
        val keyboard = AppPrefs.getInstance().keyboard
        val typing = screen(InputMethodCatalog.ENGLISH, mutableMapOf()).findPreference<ListPreference>(AppPrefs.T9_ENGLISH_TYPING)!!
        assertEquals(T9EnglishTyping.Predictive.name, typing.value)
        assertTrue(typing.callChangeListener(T9EnglishTyping.MultiTap.name))
        assertEquals(T9EnglishTyping.MultiTap, keyboard.t9EnglishTyping.getValue())
        keyboard.t9EnglishTyping.setValue(T9EnglishTyping.Predictive)
    }

    // handwriting's own, nothing in fcitx's config
    @Test
    @Uat("ST-121")
    fun thePauseOnTheSharedPageIsTheAppsPreference() {
        val pref = AppPrefs.getInstance().keyboard.handwritingPause
        pref.setValue(HandwritingPause.Medium)
        val cfg = mutableMapOf<String, String>()
        var saved = 0
        val list = create(InputMethodSettings.SHARED, cfg) { saved++ }.findPreference<ListPreference>(AppPrefs.HANDWRITING_PAUSE)!!
        assertEquals(HandwritingPause.Medium.name, list.value)
        assertTrue(list.callChangeListener(HandwritingPause.Slow.name))
        assertEquals(HandwritingPause.Slow, pref.getValue())
        assertEquals(0, saved)
        assertTrue(AppPrefs.HANDWRITING_PAUSE !in cfg.flattened())
        assertNull(screen(InputMethodCatalog.PINYIN).findPreference<ListPreference>(AppPrefs.HANDWRITING_PAUSE))
    }

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

    @Test
    @Uat("ST-121")
    fun keyDrawingIsOneChoiceSummarizedByItself() {
        val keyboard = AppPrefs.getInstance().keyboard
        try {
            TestShelves.use()
            keyboard.keyDrawing.setValue(KeyDrawing.Glide)
            val drawing = create(InputMethodSettings.SHARED).findPreference<ListPreference>(AppPrefs.KEY_DRAWING)!!
            assertEquals(listOf(KeyDrawing.Glide, KeyDrawing.StrokeFilter, KeyDrawing.Off).map { it.name }, drawing.entryValues.map { it.toString() })
            assertEquals(KeyDrawing.Glide.name, drawing.value)
            assertEquals(drawing.entry.toString(), drawing.summary?.toString())
            assertTrue(drawing.callChangeListener(KeyDrawing.StrokeFilter.name))
            assertEquals(KeyDrawing.StrokeFilter, keyboard.keyDrawing.getValue())
        } finally {
            TestShelves.use()
            keyboard.keyDrawing.setValue(KeyDrawing.Off)
        }
    }

    // 笔画筛选灵敏度 shows while 笔画筛选 is chosen, 首页单字 then or while a swipe is a stroke, toggled as 在键上划 changes
    @Test
    @Uat("ST-135")
    fun theStrokeRowsAreThereWhileStrokesAreDrawn() {
        val keyboard = AppPrefs.getInstance().keyboard
        val swipes = AppPrefs.getInstance().internal.keySwipes
        try {
            keyboard.keyDrawing.setValue(KeyDrawing.Glide)
            val page = create(InputMethodSettings.SHARED, mutableMapOf(EngineSettings.STROKE_SINGLES to "2"))
            val drawing = page.findPreference<ListPreference>(AppPrefs.KEY_DRAWING)!!
            val lift = page.findPreference<ListPreference>(AppPrefs.STROKE_LIFT)!!
            val singles = page.findPreference<ListPreference>(EngineSettings.STROKE_SINGLES)!!
            assertFalse(lift.isVisible)
            assertFalse(singles.isVisible)
            assertTrue(drawing.callChangeListener(KeyDrawing.StrokeFilter.name))
            assertTrue(lift.isVisible)
            assertTrue(singles.isVisible)
            assertEquals(listOf("High", "Medium", "Low"), lift.entryValues.map { it.toString() })
            assertEquals(StrokeLift.Medium.name, lift.value)
            assertEquals(listOf("0", "1", "2", "3", "4", "5"), singles.entryValues.map { it.toString() })
            assertEquals(placeholder(R.string.swipe_off), singles.entries[0].toString())
            assertEquals("2", singles.value)
            assertTrue(drawing.callChangeListener(KeyDrawing.Off.name))
            assertFalse(lift.isVisible)
            assertFalse(singles.isVisible)
            // a key's swipe set to a stroke: 首页单字 reorders what it filters too
            swipes.setValue(SwipeSlots.Default.with("full/a", up = false, SwipeSlot.Command(KeyCommand.StrokeShu)).serialize())
            val again = create(InputMethodSettings.SHARED, mutableMapOf(EngineSettings.STROKE_SINGLES to "2"))
            assertFalse(again.findPreference<ListPreference>(AppPrefs.STROKE_LIFT)!!.isVisible)
            assertTrue(again.findPreference<ListPreference>(EngineSettings.STROKE_SINGLES)!!.isVisible)
        } finally {
            keyboard.keyDrawing.setValue(KeyDrawing.Off)
            swipes.setValue("")
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

    @Test
    fun aValueNotAmongTheChoicesIsNotSetAboveTheExplanation() {
        val length = InputMethodSettings.SHARED.flat().first { it is Choice && it.key == EngineSettings.MODEL_LEVEL } as Choice
        val pref = create(InputMethodSettings.SHARED).findPreference<ListPreference>(length.key)!!
        pref.value = "7"
        assertEquals("${placeholder(androidx.preference.R.string.not_set)}\n${placeholder(length.summary)}", pref.summary?.toString())
    }

    // MyListPreferenceDialogFragment offers Default only when hasDefault() is true
    @Test
    fun aChoiceHasNoDefaultToGoBackTo() {
        appInputMethods.forEach { page -> screen(page).lists().forEach { assertFalse("$page ${it.key}", it.hasDefault()) } }
        val given = create(InputMethodSettings.SHARED).lists().first().apply { setDefaultValue("5") }
        assertTrue(given.hasDefault())
    }

    // a key the engine does not read is a dead switch; shared keys go into pinyin's config, which every session reads
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

    // a page saves only what it shows, so a table page opened earlier does not write back stale prediction
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
        // fuzzy ones as pairs, not the group's name alone
        assertEquals("${EngineSettings.EMOJI}=False\n${EngineSettings.FUZZY}/${Fuzzy.L_N.name}=True\n", InputMethodSettings.only(InputMethodSettings.SHARED, shared).flattened())
    }

    // the shared page says what each is no use to; an input method's own page says nothing of the kind
    @Test
    @Uat("ST-124")
    fun aSharedOptionSaysUnderItWhatItIsNoUseTo() {
        val shared = create(InputMethodSettings.SHARED)
        assertEquals(placeholder(R.string.im_prediction_summary), shared.titled(R.string.im_prediction).summary?.toString())
        assertEquals(placeholder(R.string.im_emoji_summary), shared.titled(R.string.im_emoji).summary?.toString())
        assertNull(shared.titled(R.string.auto_pairs).summary)
        val drawing = shared.findPreference<ListPreference>(AppPrefs.KEY_DRAWING)!!
        assertEquals(drawing.entry.toString(), drawing.summary?.toString())
        val level = shared.findPreference<ListPreference>(EngineSettings.MODEL_LEVEL)!!
        assertEquals("${level.entry}\n${placeholder(R.string.im_model_level_summary)}", level.summary?.toString())
        // not for the 14 keys (the hardware keyboard still types full pinyin)
        assertEquals("${placeholder(R.string.im_chaizi_summary)}\n${placeholder(R.string.im_not_for)}", screen(InputMethodCatalog.PINYIN).titled(R.string.im_chaizi).summary?.toString())
    }

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
        // off by default; on, it reaches the engine
        val keys = screen(InputMethodCatalog.SHUANGPIN, shuangpin).titled(R.string.im_shuangpin_keys_shown)
        assertFalse((keys as SwitchPreferenceCompat).isChecked)
        keys.callChangeListener(true)
        assertTrue(engineReads(InputMethodCatalog.SHUANGPIN, shuangpin).shuangpinKeysShown)
        val wubi = mutableMapOf<String, String>()
        val page = screen("engine-wubi", wubi)
        page.titled(R.string.im_auto_select).callChangeListener(false)
        page.titled(R.string.im_order_by_use).callChangeListener(false)
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

    // 双拼's 14 keys, then 微软 chosen: the keyboard row says the 18 keys
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

    // a readable user layout chosen for an input method it does not fit now: said unusable here, not unreadable
    @Test
    @Uat("ST-191")
    fun aLayoutThatReadsButIsNotOfThisInputMethodIsSaidSo() {
        val keyboard = AppPrefs.getInstance().keyboard
        val dirWas = CustomKeyboards.dir
        val dir = Files.createTempDirectory("layouts").toFile()
        try {
            CustomKeyboards.dir = { dir }
            val k18 = BuiltInLayouts(appContext).text("k18")!!
            CustomLayouts.save("mine", k18.replace("name=\"18 键\"", "name=\"我的\"").replaceFirst("id=\"k18\"", "id=\"mine\" based-on=\"k18\""), dir)
            assertTrue("read and drawn", CustomKeyboards.choice("mine")!!.drawn)
            keyboard.setImKeyboard(InputMethodCatalog.ENGLISH, ImKeyboard.CUSTOM, "mine")
            val row = screen(InputMethodCatalog.ENGLISH).findPreference<ListPreference>(AppPrefs.ENGLISH_KEYBOARD)!!
            val labels = row.entries.map { it.toString() }
            assertTrue(labels.toString(), placeholder(R.string.keyboard_custom_unusable) in labels)
            assertFalse(labels.toString(), placeholder(R.string.keyboard_custom_unread) in labels)
            assertEquals("Custom:mine", row.value)
            val real = InputMethodSettings.create(PreferenceManager(appContext), InputMethodSettings.of(InputMethodCatalog.ENGLISH)!!, mutableMapOf(), {}, open = {})
                .findPreference<ListPreference>(AppPrefs.ENGLISH_KEYBOARD)!!
            val said = appContext.getString(R.string.keyboard_custom_unusable, "我的", appContext.getString(R.string.pinyin_keyboard_full))
            assertTrue(real.entries.toList().toString(), real.entries.any { it.toString() == said })
            assertEquals(ImKeyboard.FULL, ImKeyboards.effective(InputMethodCatalog.ENGLISH, ImKeyboard.CUSTOM, EngineState.shuangpinScheme, CustomKeyboards.choice("mine")))
        } finally {
            keyboard.setImKeyboard(InputMethodCatalog.ENGLISH, ImKeyboard.FULL)
            keyboard.englishKeyboardLayout.setValue(keyboard.englishKeyboardLayout.defaultValue)
            forgetTheTestsLayouts(dirWas)
            CustomLayouts.forgetAll()
            dir.deleteRecursively()
        }
    }
}
