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
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.TableSettings
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.input.keyboard.T9MultiTap
import org.fcitx.fcitx5.android.ui.main.modified.hasDefault
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Choice
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Item
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Section
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Toggle
import org.fcitx.fcitx5.android.ui.main.settings.placeholder
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

/** The pages as the preferences [InputMethodSettings.create] makes of them, over fcitx's config. */
@RunWith(RobolectricTestRunner::class)
// the real application, which initialises the AppPrefs the nine keys' page reads
@Config(application = FcitxApplication::class)
class InputMethodSettingsScreenTest {

    private fun create(items: List<Item>, cfg: RawConfig = RawConfig(), save: () -> Unit = {}): PreferenceScreen {
        val ctx = PlaceholderStrings(RuntimeEnvironment.getApplication())
        return InputMethodSettings.create(PreferenceManager(ctx), items, cfg, save, open = {})
    }

    private fun screen(page: String, cfg: RawConfig = RawConfig(), save: () -> Unit = {}) =
        create(InputMethodSettings.of(page)!!, cfg, save)

    private fun PreferenceGroup.all(): List<Preference> = (0 until preferenceCount).flatMap {
        val pref = getPreference(it)
        if (pref is PreferenceGroup) pref.all() else listOf(pref)
    }

    private fun PreferenceGroup.lists() = all().filterIsInstance<ListPreference>()

    private fun PreferenceGroup.titled(@StringRes title: Int) = all().single { it.title == placeholder(title) }

    // as the addon flattens its config for the engine (EngineSettings.parse): a group's keys after its name and a slash;
    // that it keeps these keys, the options androidengine.h declares, is AndroidEngineHeaderTest's to hold
    private fun RawConfig.flattened(prefix: String = ""): String = subItems.orEmpty().joinToString("") {
        if (it.subItems.isNullOrEmpty()) "$prefix${it.name}=${it.value}\n" else it.flattened("$prefix${it.name}/")
    }

    // the English page's word list, opened as the app's settings open a page
    @Test
    fun theEnglishPageOpensItsWordList() {
        val opened = ArrayList<SettingsRoute>()
        val ctx = PlaceholderStrings(RuntimeEnvironment.getApplication())
        val screen = InputMethodSettings.create(PreferenceManager(ctx), InputMethodSettings.of(InputMethodNames.KEYBOARD)!!, RawConfig(), {}) { opened += it }
        val words = screen.titled(R.string.english_words)
        assertSame(words, screen.findPreference<Preference>(InputMethodSettings.ENGLISH_WORDS))
        words.performClick()
        assertEquals(listOf<SettingsRoute>(SettingsRoute.UserWordList("LEARNED", english = true)), opened)
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

    // the keyboard's own: in the app's preferences, nothing of it in fcitx's config
    @Test
    fun multiTapOnTheNineKeysPageIsTheAppsPreference() {
        val pref = AppPrefs.getInstance().keyboard.t9MultiTap
        pref.setValue(T9MultiTap.Medium)
        val cfg = RawConfig()
        var saved = 0
        val list = screen(Engines.T9, cfg) { saved++ }.findPreference<ListPreference>(AppPrefs.T9_MULTI_TAP)!!
        assertEquals(T9MultiTap.Medium.name, list.value)
        assertTrue(list.callChangeListener(T9MultiTap.Off.name))
        assertEquals(T9MultiTap.Off, pref.getValue())
        assertEquals(0, saved)
        assertTrue(AppPrefs.T9_MULTI_TAP !in cfg.flattened())
        assertNull(screen(Engines.PINYIN).findPreference<ListPreference>(AppPrefs.T9_MULTI_TAP))
        pref.setValue(T9MultiTap.Medium)
    }

    // as multi-tap: the keyboard's own, nothing of it in fcitx's config
    @Test
    fun autoCapsOnTheEnglishPageIsTheAppsPreference() {
        val pref = AppPrefs.getInstance().keyboard.englishAutoCaps
        pref.setValue(true)
        val cfg = RawConfig()
        var saved = 0
        val toggle = screen(InputMethodNames.KEYBOARD, cfg) { saved++ }.findPreference<SwitchPreferenceCompat>(AppPrefs.ENGLISH_AUTO_CAPS)!!
        assertTrue(toggle.isChecked)
        assertTrue(toggle.callChangeListener(false))
        assertFalse(pref.getValue())
        assertEquals(0, saved)
        assertTrue(AppPrefs.ENGLISH_AUTO_CAPS !in cfg.flattened())
        pref.setValue(true)
    }

    // a dialog message would leave the list's dialog with no choices in it
    @Test
    fun anExplanationIsUnderTheValueNotInTheDialog() {
        appInputMethods.forEach { page -> screen(page).lists().forEach { assertNull("$page ${it.key}", it.dialogMessage) } }
        val length = item("engine-wubi", "AutoPhraseLength") as Choice
        val pref = screen("engine-wubi").findPreference<ListPreference>(length.key)!!
        assertEquals("${pref.entry}\n${placeholder(length.summary)}", pref.summary?.toString())
    }

    // as a list without an explanation shows it, not as "null"
    @Test
    fun aValueNotAmongTheChoicesIsNotSetAboveTheExplanation() {
        val length = item("engine-wubi", "AutoPhraseLength") as Choice
        val pref = screen("engine-wubi").findPreference<ListPreference>(length.key)!!
        pref.value = "7"
        assertEquals("${placeholder(androidx.preference.R.string.not_set)}\n${placeholder(length.summary)}", pref.summary?.toString())
    }

    // MyListPreferenceDialogFragment offers Default only when hasDefault() is true: these lists have no default to go back to
    @Test
    fun aChoiceHasNoDefaultToGoBackTo() {
        appInputMethods.forEach { page -> screen(page).lists().forEach { assertFalse("$page ${it.key}", it.hasDefault()) } }
        val given = screen(Engines.PINYIN).lists().first().apply { setDefaultValue("5") }
        assertTrue(given.hasDefault())
    }

    // a key the engine does not read is a switch that does nothing
    @Test
    fun aChangeIsSavedWhereTheEngineReadsIt() {
        val cfg = RawConfig()
        var saves = 0
        val page = screen(Engines.PINYIN, cfg) { saves++ }
        page.titled(R.string.im_prediction).callChangeListener(false)
        page.titled(R.string.im_typos).callChangeListener(false)
        page.findPreference<ListPreference>(EngineSettings.PAGE_SIZE)!!.callChangeListener("5")
        assertEquals(3, saves)
        assertEquals("False", cfg[EngineSettings.FUZZY][EngineSettings.TYPOS].value)
        assertEquals(EngineSettings(prediction = false, typos = false, pageSize = 5), EngineSettings.parse(cfg.flattened()))
    }

    @Test
    fun aSchemeAndATablesOptionsAreSavedWhereTheEngineReadsThem() {
        val shuangpin = RawConfig()
        screen(Engines.SHUANGPIN, shuangpin).findPreference<ListPreference>(EngineSettings.SHUANGPIN_PROFILE)!!.callChangeListener("Xiaohe")
        assertEquals("Xiaohe", EngineSettings.parse(shuangpin.flattened()).shuangpin)
        val wubi = RawConfig()
        val page = screen("engine-wubi", wubi)
        page.titled(R.string.im_auto_select).callChangeListener(false)
        page.titled(R.string.im_order_by_use).callChangeListener(false)
        page.findPreference<ListPreference>(TableSettings.AUTO_PHRASE_LENGTH)!!.callChangeListener("2")
        // a table's page is its group of the addon's config
        val group = Engines.TABLES.getValue("engine-wubi").group
        assertEquals(
            TableSettings(autoSelect = false, orderByUse = false, autoPhraseLength = 2),
            EngineSettings.parse(wubi.flattened("$group/")).tables[group],
        )
    }

    @Test
    fun theFuzzyPairsStoredShowOnTheirRow() {
        val cfg = RawConfig().apply { getOrCreate(EngineSettings.FUZZY).getOrCreate(Fuzzy.L_N.name).value = "True" }
        assertEquals("l ↔ n", screen(Engines.PINYIN, cfg).titled(R.string.im_fuzzy).summary?.toString())
    }

    @Test
    fun aSectionInAnotherStaysInIt() {
        val inner = Section(R.string.im_section_phrases, listOf(Toggle("Inner", { "inner" })))
        val screen = create(listOf(Section(R.string.im_section_typing, listOf(inner))))
        assertEquals(1, screen.preferenceCount)
        val outer = screen.getPreference(0) as PreferenceGroup
        assertEquals(placeholder(R.string.im_section_phrases), outer.getPreference(0).title?.toString())
    }
}
