/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.TwoStatePreference
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.EnglishKeyboard
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.pack.TestShelves
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.preedit.CodeRowStyle
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.host.ConfigTree
import org.fcitx.fcitx5.android.host.EngineOptions
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.T9MultiTap
import org.fcitx.fcitx5.android.ui.main.MainFragment
import org.fcitx.fcitx5.android.ui.main.settings.NineKeyLettersFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeListAdapter
import org.fcitx.fcitx5.android.until
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class SettingsUatTest {

    private val api = FakeHostApi()
    private val st = SettingsHarness(api)
    private val activity get() = st.activity

    // PackStore is a singleton the JVM keeps: the goldens were drawn with the voice pack (the preview's microphone), so
    // the shelf a test before left is put back
    @Before
    fun packs() {
        TestShelves.use()
    }

    @After
    fun close() = st.close()

    private fun idle() = st.idle()

    private fun open(route: SettingsRoute) = st.open(route)

    private val page: Fragment get() = st.page

    private fun s(id: Int) = st.s(id)

    private fun preferences(group: PreferenceGroup): List<Preference> =
        (0 until group.preferenceCount).flatMap { i ->
            val p = group.getPreference(i)
            listOf(p) + ((p as? PreferenceGroup)?.let(::preferences) ?: emptyList())
        }.filter { it.isVisible }

    private fun titles() = preferences((page as PreferenceFragmentCompat).preferenceScreen).map { it.title.toString() }

    private fun capture(name: String) = st.capture("SettingsUatTest/$name")

    @Test
    @Uat("ST-01", "ST-33")
    fun theHomePage() {
        val prefs = preferences((page as PreferenceFragmentCompat).preferenceScreen)
        assertEquals(MainFragment.HOME.map { s(it.title) } + s(R.string.app_language), prefs.map { it.title.toString() })
        assertTrue(prefs.all { !it.summary.isNullOrEmpty() })
        val menu = shadowOf(activity).optionsMenu
        assertEquals(
            listOf(s(R.string.search_settings), s(R.string.help), s(R.string.developer), s(R.string.about)),
            (0 until menu.size()).map { menu.getItem(it).title.toString() },
        )
        capture("home")
    }

    @Test
    @Uat("ST-33")
    fun pagesFromTopToBottom() {
        activity
        open(SettingsRoute.UserWords)
        val words = preferences((page as PreferenceFragmentCompat).preferenceScreen)
        val switches = words.filterIsInstance<TwoStatePreference>()
        assertEquals(listOf(s(R.string.learning_use_habits), s(R.string.learning_auto_backup), s(R.string.learning_voice_ask)), switches.map { it.title.toString() })
        assertTrue(switches.all { it.isChecked })
        val titles = words.map { it.title.toString() }
        for (t in listOf(R.string.learning_clear_learned, R.string.learning_backups, R.string.english_words, R.string.export_words)) {
            assertTrue("${s(t)} in $titles", s(t) in titles)
        }
        capture("user_words")
        open(SettingsRoute.HardwareKeyboard)
        val hk = titles()
        assertEquals(
            listOf(R.string.show_candidates_window, R.string.hardware_switch_shortcuts, R.string.candidates_orientation, R.string.candidates_font_size).map(::s),
            hk.filter { it.isNotEmpty() }.take(4),
        )
        capture("hardware_keyboard")
    }

    @Test
    @Uat("WD-02")
    fun aBlankPinyinIsFilledIn() {
        api.readings["幽默"] = "you mo"
        activity
        open(SettingsRoute.UserWordList(UserWord.Kind.ADDED.name))
        KeyboardHarness.all(activity.window.decorView).filterIsInstance<FloatingActionButton>().single().performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val (text, pinyin) = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<EditText>().toList()
        text.setText("幽默")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("you mo", pinyin.text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(api.calls.toString(), "addUserWord(幽默, you mo)" in api.calls)
        // the list reloads after a hop off the main thread: waited for
        fun list() = KeyboardHarness.all(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        until("the word listed", state = ::list, step = ::idle) { list().let { l -> "幽默" in l && l.any { "you mo" in it } } }
    }

    @Test
    @Uat("ST-05")
    fun aDarkThemeTurnsPreviewAndKeyboardDark() {
        // the prefs outlive the test (Robolectric keeps the sandbox)
        val followWas = ThemeManager.prefs.followSystemDayNightTheme.getValue()
        val themeWas = ThemeManager.prefs.normalModeTheme.getValue()
        val codeRowWas = ThemeManager.prefs.codeRow.getValue()
        try {
            ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
            ThemeManager.setNormalModeTheme(TestThemes.PixelLight)
            // the preview as shipped, the tests' baseline embedded (OneDefaultPreferencesShadow)
            ThemeManager.prefs.codeRow.setValue(CodeRowStyle.Floating)
            activity
            open(SettingsRoute.Theme)
            val list = KeyboardHarness.all(activity.window.decorView).filterIsInstance<RecyclerView>()
                .firstOrNull { it.adapter is ThemeListAdapter }
                ?: error(KeyboardHarness.all(activity.window.decorView).filterIsInstance<RecyclerView>().map { it.adapter?.javaClass?.name }.toList().toString())
            pickTheme(list, TestThemes.PixelDark.name, ::idle)
            assertEquals(TestThemes.PixelDark.name, ThemeManager.activeTheme.name)
            capture("theme_dark")
            KeyboardHarness(theme = null).use { h ->
                h.start()
                val bitmap = Bitmap.createBitmap(h.keyboard.width, h.keyboard.height, Bitmap.Config.ARGB_8888)
                h.keyboard.draw(Canvas(bitmap))
                val corner = bitmap.getPixel(2, bitmap.height - 2)
                assertTrue("background ${Integer.toHexString(corner)}", Color.luminance(corner) < 0.2f)
            }
        } finally {
            ThemeManager.setNormalModeTheme(themeWas)
            ThemeManager.prefs.followSystemDayNightTheme.setValue(followWas)
            ThemeManager.prefs.codeRow.setValue(codeRowWas)
        }
    }

    @Test
    @Uat("ST-117")
    fun theCodeRowsThreePlacesTheFieldOnlyOnceItsNoteIsAgreedTo() {
        val was = ThemeManager.prefs.codeRow.getValue()
        try {
            ThemeManager.prefs.codeRow.setValue(CodeRowStyle.Floating)
            activity
            open(SettingsRoute.Theme)
            st.tab(1)
            val row = listed(R.string.code_row_style)
            val entries = row.entries.map { it.toString() }
            row.callChangeListener(CodeRowStyle.InField.name)
            idle()
            val note = ShadowDialog.getLatestDialog() as AlertDialog
            val noteText = KeyboardHarness.all(note.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            val beforeOk = ThemeManager.prefs.codeRow.getValue()
            note.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            idle()
            val cancelled = ThemeManager.prefs.codeRow.getValue() to row.value
            row.callChangeListener(CodeRowStyle.InField.name)
            idle()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            val agreed = ThemeManager.prefs.codeRow.getValue() to row.value
            val dialogs = ShadowDialog.getShownDialogs().size
            if (row.callChangeListener(CodeRowStyle.Embedded.name)) row.value = CodeRowStyle.Embedded.name
            idle()
            assertRows(
                "three places, in the keyboard, over it, in the field" to {
                    assertEquals(listOf(R.string.code_row_embedded, R.string.code_row_floating, R.string.code_row_in_field).map(::s), entries)
                },
                "the note says what the field gets" to { assertTrue(noteText.toString(), s(R.string.code_row_in_field_note) in noteText) },
                "not set while the note is up" to { assertEquals(CodeRowStyle.Floating, beforeOk) },
                "cancelled: as it was" to { assertEquals(CodeRowStyle.Floating to CodeRowStyle.Floating.name, cancelled) },
                "OK: in the field" to { assertEquals(CodeRowStyle.InField to CodeRowStyle.InField.name, agreed) },
                "the others set at once, no note" to {
                    assertEquals(CodeRowStyle.Embedded, ThemeManager.prefs.codeRow.getValue()); assertEquals(dialogs, ShadowDialog.getShownDialogs().size)
                },
            )
        } finally {
            ThemeManager.prefs.codeRow.setValue(was)
        }
    }

    // each page's save loaded into them as setImOptions does, every page then reading them again
    private val options = EngineOptions()

    private fun pages() = runBlocking {
        for (im in PAGED) options.page(im)?.let { api.setImOptions(im, it.leaves()) }
        api.calls.clear()
    }

    private fun saved() = runBlocking {
        // the page saves on the fcitx thread (HostPreferenceFragment.save): waited for, not idled
        until("the page saved", state = { api.calls.toList() }, step = ::idle) { api.calls.toList().any { it.startsWith("setImOptions") } }
        val pinyin = InputMethodCatalog.PINYIN
        options.setPage(pinyin, ConfigTree.of(api.imOptions(pinyin)!!))
        pages()
    }

    @Test
    @Uat("ST-32")
    fun anInputMethodsPageAndTheHardwareKeyboardsSavedLeaveTheSharedOptions() {
        val es = EngineSettings
        options.load("${es.MODEL_LEVEL}=Low\n[${es.FUZZY}]\nZ_ZH=True\n")
        pages()
        activity
        val pinyin = InputMethodCatalog.PINYIN
        open(SettingsRoute.InputMethodConfig("Pinyin", pinyin))
        (st.pref(R.string.im_latin_words) as TwoStatePreference).performClick()
        saved()
        assertEquals("False", options["${es.LATIN_WORDS}"])
        open(SettingsRoute.HardwareKeyboard)
        val size = st.pref(R.string.im_page_size) as ListPreference
        if (size.callChangeListener("5")) size.value = "5"
        saved()
        assertEquals("5", options[es.PAGE_SIZE])
        assertEquals("True", options["${es.FUZZY}/Z_ZH"])
        assertEquals("Low", options[es.MODEL_LEVEL])
        open(SettingsRoute.SharedInput)
        assertEquals("Low", (st.pref(R.string.im_model_level) as ListPreference).value)
    }

    private fun notFor(vararg ims: String) =
        st.s(R.string.im_not_for, ims.joinToString(s(R.string.list_separator)) { InputMethodNames.of(activity, it, it) })

    private fun summary(title: Int) = st.pref(title).summary.toString()

    private fun im(uniqueName: String) = open(SettingsRoute.InputMethodConfig(uniqueName, uniqueName))

    private fun checked(title: Int) = (st.pref(title) as TwoStatePreference).isChecked

    private fun listed(title: Int) = st.pref(title) as ListPreference

    @Test
    @Uat("ST-28", "ST-33", "ST-136")
    fun everyInputMethodsPageAndTheSharedOnes() {
        val c = InputMethodCatalog
        pages()
        activity
        open(SettingsRoute.PinyinDict())
        val lexicon = listOf(
            R.string.word_packs_hint, R.string.layer_user, R.string.my_words, R.string.layer_base, R.string.builtin_base_title,
            R.string.builtin_base_summary, R.string.import_section, R.string.import_dict, R.string.section_phrases, R.string.custom_phrases,
        ).map(::s)
        until("the lexicon page's groups", { st.texts() }, step = { idle() }) { st.texts().containsAll(lexicon) }
        assertEquals(lexicon.distinct(), st.texts().filter { it in lexicon }.distinct())
        open(SettingsRoute.LearningBackups)
        assertEquals(listOf(R.string.learning_restore, R.string.learning_save_copy, R.string.learning_revert).map(::s), st.titles().take(3))
        open(SettingsRoute.SharedInput)
        val groups = listOf(R.string.shared_section_all, R.string.shared_section_chinese, R.string.shared_section_pinyin).map(::s)
        assertEquals(groups, st.titles().filter { it in groups })
        assertTrue(st.titles().toString(), st.titles().none { "punctuation" in it.lowercase() || "标点" in it })
        assertEquals(s(R.string.swipe_off), summary(R.string.key_drawing))
        // every keyboard reads a slip, 注音九键 too: no 不适用 line
        assertEquals(s(R.string.im_typos_summary), summary(R.string.im_typos))
        // no 不适用 line names English: the Chinese section's title says it for its rows
        val chinese = st.preferences().map { it.summary.toString() }
        assertTrue(chinese.flatMap { it.lines() }.none { c.ENGLISH.let { e -> InputMethodNames.of(activity, e, e) } in it && "Not for" in it })
        st.pref(R.string.im_fuzzy).performClick()
        idle()
        val fuzzy = KeyboardHarness.all(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(fuzzy.toString(), fuzzy.any { notFor(c.T9, c.SHUANGPIN_NINE) in it })
        ShadowDialog.getLatestDialog().dismiss()
        idle()
        val shared = listOf(R.string.im_page_size, R.string.im_prediction, R.string.im_model_level, R.string.im_typos, R.string.im_fuzzy).map(::s)
        im(c.PINYIN)
        assertEquals(s(R.string.shared_input_link), st.titles().first())
        assertTrue(s(R.string.im_latin_words) in st.titles())
        assertTrue(st.titles().none { it in shared })
        assertTrue(s(R.string.handwriting_pause) !in st.titles())
        open(SettingsRoute.SharedInput)
        assertTrue(s(R.string.handwriting_pause) in st.titles())
        im(c.SHUANGPIN)
        assertTrue(s(R.string.im_shuangpin_scheme) in st.titles())
        assertTrue(s(R.string.im_shuangpin_keys_shown) in st.titles())
        assertFalse(checked(R.string.im_shuangpin_keys_shown))
        assertTrue(s(R.string.im_latin_words) !in st.titles())
        for (table in listOf(c.WUBI, c.ERBI, c.CANGJIE, c.ZIRANMA, c.WUBI_PINYIN)) {
            im(table)
            assertEquals(s(R.string.shared_input_link), st.titles().first())
            assertTrue(table, s(R.string.im_reset_table_order) in st.titles())
            assertTrue(table, !checked(R.string.im_order_by_use))
            assertTrue(table, checked(R.string.im_auto_select))
            assertEquals(table, table !in listOf(c.ERBI, c.CANGJIE), checked(R.string.im_hint))
        }
        open(SettingsRoute.HardwareKeyboard)
        assertTrue(checked(R.string.hardware_switch_shortcuts))
        assertEquals(s(R.string.im_page_size), st.titles().last())
        assertEquals("7", listed(R.string.im_page_size).value)
    }

    @Test
    @Uat("ST-40", "ST-137")
    fun pinyinsKeyboardOnItsPageAndTheSharedOptionsSayingFourteenKeys() {
        val c = InputMethodCatalog
        val setting = AppPrefs.getInstance().keyboard.pinyinKeyboard
        try {
            pages()
            activity
            im(c.PINYIN)
            val keyboard = listed(R.string.pinyin_keyboard)
            assertEquals(s(R.string.pinyin_keyboard_full), keyboard.entry.toString())
            assertTrue(keyboard.summary.toString().contains(s(R.string.pinyin_keyboard_summary)))
            for (t in listOf(R.string.im_chaizi, R.string.im_latin_words)) assertTrue(summary(t), summary(t).endsWith(notFor(c.T9, c.PINYIN_KEYS18, c.PINYIN_KEYS, c.ZHUYIN_NINE, c.ZHUYIN)))
            open(SettingsRoute.SharedInput)
            for (k in listOf(c.PINYIN_KEYS, c.SHUANGPIN_KEYS14)) assertFalse(summary(R.string.im_typos), InputMethodNames.of(activity, k, k) in summary(R.string.im_typos))
            im(c.PINYIN)
            val keys14 = PinyinKeyboard.Keys14
            listed(R.string.pinyin_keyboard).let { if (it.callChangeListener(keys14.name)) it.value = keys14.name }
            idle()
            assertEquals(keys14, setting.getValue())
        } finally {
            setting.setValue(setting.defaultValue)
        }
    }

    @Test
    @Uat("SP-27")
    fun theShuangpinSchemesAreTheThreeKept() {
        pages()
        activity
        im(InputMethodCatalog.SHUANGPIN)
        assertEquals(
            listOf(R.string.shuangpin_ziranma, R.string.shuangpin_ms, R.string.shuangpin_xiaohe).map(::s),
            listed(R.string.im_shuangpin_scheme).entries.map { it.toString() },
        )
    }

    @Test
    @Uat("ST-42")
    fun shuangpinsKeyboardUnderItsScheme() {
        val c = InputMethodCatalog
        val setting = AppPrefs.getInstance().keyboard.shuangpinKeyboard
        try {
            pages()
            activity
            im(c.SHUANGPIN)
            val titles = st.titles()
            assertEquals(titles.indexOf(s(R.string.im_shuangpin_scheme)) + 1, titles.indexOf(s(R.string.shuangpin_keyboard)))
            val keyboard = listed(R.string.shuangpin_keyboard)
            assertEquals(s(R.string.pinyin_keyboard_full), keyboard.entry.toString())
            assertEquals(
                listOf(R.string.pinyin_keyboard_full, R.string.keyboard_18, R.string.pinyin_keyboard_14, R.string.keyboard_nine).map(::s),
                keyboard.entries.map { it.toString() },
            )
            assertTrue(keyboard.summary.toString().contains(s(R.string.shuangpin_keyboard_summary)))
            val nine = ShuangpinKeyboard.NineKeys
            if (keyboard.callChangeListener(nine.name)) keyboard.value = nine.name
            idle()
            assertEquals(nine, setting.getValue())
            open(SettingsRoute.SharedInput)
            assertEquals(s(R.string.swipe_off), summary(R.string.key_drawing))
        } finally {
            setting.setValue(setting.defaultValue)
        }
    }

    @Test
    @Uat("ST-46", "ST-121")
    fun keyDrawingIsOneListOfItsChoices() {
        val keyboard = AppPrefs.getInstance().keyboard
        try {
            TestShelves.use()
            pages()
            activity
            open(SettingsRoute.SharedInput)
            val drawing = listed(R.string.key_drawing)
            assertEquals(
                listOf(R.string.glide_typing, R.string.stroke_filter, R.string.swipe_off).map(::s),
                drawing.entries.map { it.toString() },
            )
            assertEquals(s(R.string.swipe_off), summary(R.string.key_drawing))
            if (drawing.callChangeListener(KeyDrawing.StrokeFilter.name)) drawing.value = KeyDrawing.StrokeFilter.name
            idle()
            assertEquals(KeyDrawing.StrokeFilter, keyboard.keyDrawing.getValue())
            assertEquals(s(R.string.stroke_filter), summary(R.string.key_drawing))
            open(SettingsRoute.SharedInput)
            assertEquals(s(R.string.stroke_filter), summary(R.string.key_drawing))
        } finally {
            TestShelves.use()
            keyboard.keyDrawing.setValue(KeyDrawing.Off)
        }
    }

    // the nine keys' own settings a section 「九键」 on each input method's page whose keyboard they are, there at once
    // while chosen; none on 按键. 九键双拼's letters its scheme's, so it has none
    @Test
    @Uat("ST-45", "ST-107")
    fun theNineKeysSettingsAreASectionOfEachKeyboardsPage() {
        val c = InputMethodCatalog
        val keyboard = AppPrefs.getInstance().keyboard
        val nine = listOf(R.string.t9_multi_tap, R.string.t9_letters).map(::s)
        // the whole of it, so a row come back fails
        fun section() = st.preferences().firstOrNull { it is PreferenceGroup && it.title.toString() == s(R.string.keyboard_nine) }
            ?.let { g -> (g as PreferenceGroup).let { c -> listOf(c.title.toString()) + (0 until c.preferenceCount).map(c::getPreference).filter { it.isVisible }.map { it.title.toString() } } }
        fun choose(title: Int, value: String) = listed(title).let { if (it.callChangeListener(value)) it.value = value }
        try {
            keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Full)
            keyboard.shuangpinKeyboard.setValue(ShuangpinKeyboard.Full)
            keyboard.englishKeyboard.setValue(EnglishKeyboard.Full)
            keyboard.t9MultiTap.setValue(T9MultiTap.Off)
            pages()
            // which PAGED leaves out
            runBlocking { api.setImOptions(c.ENGLISH, options.page(c.ENGLISH)!!.leaves()) }
            activity
            open(SettingsRoute.Keys)
            assertTrue(st.titles().toString(), st.titles().none { it in nine })
            capture("keys")
            im(c.PINYIN)
            assertEquals(null, section())
            choose(R.string.pinyin_keyboard, PinyinKeyboard.NineKeys.name)
            idle()
            assertEquals(PinyinKeyboard.NineKeys, keyboard.pinyinKeyboard.getValue())
            val titles = st.titles()
            assertEquals(titles.indexOf(s(R.string.pinyin_keyboard)) + 1, titles.indexOf(s(R.string.keyboard_nine)))
            assertEquals(listOf(s(R.string.keyboard_nine)) + nine, section())
            assertTrue(summary(R.string.t9_multi_tap).contains(s(R.string.t9_multi_tap_summary)))
            capture("pinyin_nine_keys")
            choose(R.string.t9_multi_tap, T9MultiTap.entries.last().name)
            idle()
            assertEquals(T9MultiTap.entries.last(), keyboard.t9MultiTap.getValue())
            choose(R.string.pinyin_keyboard, PinyinKeyboard.Full.name)
            idle()
            assertEquals(null, section())
            choose(R.string.pinyin_keyboard, PinyinKeyboard.NineKeys.name)
            idle()
            st.pref(R.string.t9_letters).performClick()
            idle()
            assertTrue(st.page.javaClass.name, st.page is NineKeyLettersFragment)
            im(c.ENGLISH)
            val english = listed(R.string.english_keyboard)
            assertEquals(listOf(R.string.pinyin_keyboard_full, R.string.keyboard_nine).map(::s), english.entries.map { it.toString() })
            assertEquals(s(R.string.pinyin_keyboard_full), english.entry.toString())
            assertEquals(null, section())
            assertTrue(st.titles().toString(), s(R.string.t9_english_typing) !in st.titles())
            choose(R.string.english_keyboard, EnglishKeyboard.NineKeys.name)
            idle()
            assertEquals(listOf(R.string.keyboard_nine, R.string.t9_english_typing).map(::s) + nine, section())
            assertEquals(s(T9MultiTap.entries.last().stringRes), listed(R.string.t9_multi_tap).entry.toString())
            capture("english_nine_keys")
            im(c.SHUANGPIN)
            choose(R.string.shuangpin_keyboard, ShuangpinKeyboard.NineKeys.name)
            idle()
            assertEquals(null, section())
        } finally {
            keyboard.pinyinKeyboard.setValue(keyboard.pinyinKeyboard.defaultValue)
            keyboard.shuangpinKeyboard.setValue(keyboard.shuangpinKeyboard.defaultValue)
            keyboard.englishKeyboard.setValue(keyboard.englishKeyboard.defaultValue)
            keyboard.t9MultiTap.setValue(keyboard.t9MultiTap.defaultValue)
        }
    }

    private companion object {
        val PAGED = InputMethodCatalog.run {
            listOf(PINYIN, SHUANGPIN, WUBI, ERBI, CANGJIE, ZIRANMA, WUBI_PINYIN)
        }
    }
}
