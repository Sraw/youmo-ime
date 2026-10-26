/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.content.Context
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.ui.main.MainFragment
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeysSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SoundSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings
import org.fcitx.fcitx5.android.ui.main.settings.im.flat
import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A search result for an input method's option opens the page that has it. */
@RunWith(RobolectricTestRunner::class)
// the real application, which initialises the AppPrefs the nine keys' and English pages read: a test
// run alone, or first, found none
@Config(application = FcitxApplication::class)
class SettingsIndexTest {

    private fun context(): Context = PlaceholderStrings(RuntimeEnvironment.getApplication())

    private fun entries(ctx: Context) = buildList<Entry<SettingsIndex.Target>> { SettingsIndex.inputMethods(ctx, this) }

    /** [uniqueName]'s page, as InputMethodConfigFragment draws it. */
    private fun pageOf(ctx: Context, uniqueName: String): PreferenceScreen =
        InputMethodSettings.create(PreferenceManager(ctx), InputMethodSettings.of(uniqueName)!!, RawConfig(), save = {}, open = {})

    /** The keys of the settings on [uniqueName]'s page. */
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
        val options = entries(ctx).filter { it.target.key != null }
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

    // moved off the input methods' pages: found where they are now, each by the key its row has there
    @Test
    fun everySharedOptionFoundOpensTheSharedPageThatHasIt() {
        val ctx = context()
        val found = buildList<Entry<SettingsIndex.Target>> { SettingsIndex.sharedInput(ctx, this) }
        val screen = InputMethodSettings.create(PreferenceManager(ctx), InputMethodSettings.SHARED, RawConfig(), save = {}, open = {})
        val options = found.filter { it.target.key != null }
        // each row of the shared page, sections aside, once
        assertEquals(InputMethodSettings.SHARED.flat().size, options.size)
        for (entry in options) {
            assertEquals("$entry", SettingsRoute.SharedInput, entry.target.route)
            assertEquals("$entry", entry.title, screen.findPreference<Preference>(entry.target.key!!)?.title?.toString())
        }
    }

    // each page the home page opens can be searched for, and the settings moved there with it
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
    }

    // the app's own keyboard choices on fcitx's pages: found, and opening their input method's page
    @Test
    fun theKeyboardChoicesAreFoundOnTheirInputMethodsPage() {
        val all = entries(context())
        assertRows(
            listOf(
                Triple(AppPrefs.PINYIN_KEYBOARD, Engines.PINYIN, "全拼's keyboard, on pinyin's page"),
                Triple(AppPrefs.SHUANGPIN_KEYBOARD, Engines.SHUANGPIN, "双拼's, under its scheme"),
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
        for (table in Engines.TABLES.keys) {
            val on = all.filter { (it.target.route as? SettingsRoute.InputMethodConfig)?.uniqueName == table }
            assertEquals(table, 1, on.count { it.target.key == null })
            assertEquals(table, keysOn(ctx, table) - InputMethodSettings.SHARED_LINK, on.mapNotNull { it.target.key }.toSet())
        }
    }

    /**
     * A result points at its setting by the key ([SettingsIndex.Target.key]), as the highlight finds
     * it: a setting with none, or one whose key another on its page has too, would open its page
     * and point at nothing, or at the other. The page itself is the one result with none.
     */
    @Test
    fun eachPageIsFoundOnceAndEachSettingOnItByAKeyOfItsOwn() {
        for ((route, on) in SettingsIndex.build(context()).groupBy { it.target.route }) {
            assertEquals("$route", 1, on.count { it.target.key == null })
            val keys = on.mapNotNull { it.target.key }
            assertEquals("$route: $keys", keys.size, keys.toSet().size)
        }
    }

    /**
     * The row on every input method's page that opens the shared page is not a result of each: a
     * search for the shared page, or what its summary names, found it once and some ten times over.
     */
    @Test
    fun noTwoResultsOpenTheSamePageUnderTheSameTitle() {
        val all = SettingsIndex.build(context())
        assertTrue(all.none { it.target.key == InputMethodSettings.SHARED_LINK })
        val twice = all.groupBy { it.target.route to it.title }.filterValues { it.size > 1 }
        assertTrue("$twice", twice.isEmpty())
    }

    // the keyboard's category over two pages, one section each: none of it on neither, none on both
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

    /**
     * Every setting's preference is made and attached to be read: one that saved its default as it
     * did would pin each default the user never touched, at every search.
     */
    @Test
    fun buildingTheIndexSavesNothing() {
        // where a preference attached with no data store saves, and where AppPrefs (and a level) does
        val stores = listOf(
            PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication()),
            AppPrefs.getInstance().keyboard.keyboardHeightPercent.sharedPreferences,
        )
        val before = stores.map { it.all.toMap() }
        assertTrue(SettingsIndex.build(context()).isNotEmpty())
        assertEquals(before, stores.map { it.all.toMap() })
    }
}
