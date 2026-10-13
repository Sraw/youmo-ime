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
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings
import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Entry
import org.junit.Assert.assertEquals
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

    @Test
    fun everyBuiltInTableHasItsOwnPageAndOptionsFound() {
        val ctx = context()
        val all = entries(ctx)
        // the engine's own list: a table added there is held to this too
        for (table in Engines.TABLES.keys) {
            val on = all.filter { (it.target.route as? SettingsRoute.InputMethodConfig)?.uniqueName == table }
            assertEquals(table, 1, on.count { it.target.key == null })
            assertEquals(table, keysOn(ctx, table), on.mapNotNull { it.target.key }.toSet())
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
