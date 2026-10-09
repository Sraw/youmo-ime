/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.core.RawConfig
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

/**
 * A search result for an input method's option opens the page that has it: a table's once opened
 * the list of imported tables, which has none.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsIndexTest {

    // the app's own strings are not on the unit-test classpath (isIncludeAndroidResources): "#<id>" stands in
    private class PlaceholderStrings(base: Context) : ContextWrapper(base) {
        private val res = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getText(id: Int): CharSequence = "#$id"
            override fun getString(id: Int): String = "#$id"
            override fun getString(id: Int, vararg formatArgs: Any?): String = "#$id"
            override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String = "#$id"
        }

        override fun getResources(): Resources = res
    }

    private val tables = listOf("engine-wubi", "engine-cangjie", "engine-ziranma", "engine-erbi", "engine-wubipinyin")

    private fun context(): Context = PlaceholderStrings(RuntimeEnvironment.getApplication())

    private fun entries(ctx: Context) = buildList<Entry<SettingsIndex.Target>> { SettingsIndex.inputMethods(ctx, this) }

    /** The titles of the settings on [uniqueName]'s page, as InputMethodConfigFragment draws it. */
    private fun titlesOn(ctx: Context, uniqueName: String): Set<String> {
        val screen = InputMethodSettings.create(PreferenceManager(ctx), InputMethodSettings.of(uniqueName)!!, RawConfig()) {}
        fun PreferenceGroup.titles(): List<String> = (0 until preferenceCount).flatMap {
            val pref = getPreference(it)
            if (pref is PreferenceGroup) pref.titles() else listOfNotNull(pref.title?.toString())
        }
        return screen.titles().toSet()
    }

    @Test
    fun everyOptionFoundOpensAnInputMethodsPageThatHasIt() {
        val ctx = context()
        val options = entries(ctx).filter { it.target.title != null }
        assertTrue(options.isNotEmpty())
        val pages = mutableMapOf<String, Set<String>>()
        for (entry in options) {
            val route = entry.target.route as? SettingsRoute.InputMethodConfig
            assertNotNull("$entry", route)
            val titles = pages.getOrPut(route!!.uniqueName) { titlesOn(ctx, route.uniqueName) }
            assertTrue("$entry", entry.target.title!! in titles)
        }
    }

    @Test
    fun everyBuiltInTableHasItsOwnPageAndOptionsFound() {
        val ctx = context()
        val all = entries(ctx)
        for (table in tables) {
            val on = all.filter { (it.target.route as? SettingsRoute.InputMethodConfig)?.uniqueName == table }
            assertEquals(table, 1, on.count { it.target.title == null })
            assertEquals(table, titlesOn(ctx, table), on.mapNotNull { it.target.title }.toSet())
        }
    }
}
