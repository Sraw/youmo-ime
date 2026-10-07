/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings
import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Entry

/**
 * Every setting the search finds, read from the pages themselves where they are built from a
 * list (the app's preference categories, the input methods' [InputMethodSettings]), so a setting
 * added there is found without a word here; the pages drawn by hand are listed by name.
 */
object SettingsIndex {

    /** The page to open, and the setting on it to point at (its title), if not the page itself. */
    data class Target(val route: SettingsRoute, val title: String?)

    // the app's own input methods, whose pages InputMethodSettings draws; a table's is one for all
    private val InputMethods = listOf("engine-pinyin", "engine-t9", "engine-shuangpin", "keyboard-us")
    private const val ANY_TABLE = "engine-table"

    fun build(context: Context): List<Entry<Target>> = buildList {
        fun page(@StringRes title: Int, route: SettingsRoute, vararg under: Int) =
            add(Entry(context.getString(title), "", under.map(context::getString), Target(route, null)))

        page(R.string.input_methods, SettingsRoute.InputMethodList)
        inputMethods(context, this)
        page(R.string.custom_phrases, SettingsRoute.PinyinCustomPhrase)
        page(R.string.word_packs, SettingsRoute.PinyinDict(""))
        page(R.string.my_words, SettingsRoute.UserWords, R.string.word_packs)
        managed(context, R.string.theme_and_size, SettingsRoute.Theme, ThemeManager.prefs, this)
        managed(context, R.string.virtual_keyboard, SettingsRoute.VirtualKeyboard, AppPrefs.getInstance().keyboard, this)
        add(Entry(context.getString(R.string.long_press_characters), context.getString(R.string.long_press_characters_summary),
            listOf(context.getString(R.string.virtual_keyboard)), Target(SettingsRoute.PopupOverrides, null)))
        managed(context, R.string.clipboard, SettingsRoute.Clipboard, AppPrefs.getInstance().clipboard, this)
        managed(context, R.string.emoji_and_symbols, SettingsRoute.Symbol, AppPrefs.getInstance().symbols, this)
        managed(context, R.string.advanced, SettingsRoute.Advanced, AppPrefs.getInstance().advanced, this)
        managed(context, R.string.candidates_window, SettingsRoute.CandidatesWindow, AppPrefs.getInstance().candidates, this, R.string.advanced)
        page(R.string.punctuation_map, SettingsRoute.Punctuation(context.getString(R.string.punctuation_map), "zh_CN"), R.string.advanced)
        page(R.string.table_im, SettingsRoute.TableInputMethods, R.string.advanced)
        add(Entry(context.getString(R.string.export_user_data), context.getString(R.string.export_user_data_summary),
            listOf(context.getString(R.string.advanced)), Target(SettingsRoute.Advanced, context.getString(R.string.export_user_data))))
        add(Entry(context.getString(R.string.import_user_data), "",
            listOf(context.getString(R.string.advanced)), Target(SettingsRoute.Advanced, context.getString(R.string.import_user_data))))
        page(R.string.about, SettingsRoute.About)
    }

    /** [provider]'s page, built as its fragment builds it but never shown, read for its settings. */
    @SuppressLint("RestrictedApi") // a PreferenceManager of our own, only to make the screen with
    private fun managed(
        context: Context, @StringRes title: Int, route: SettingsRoute, provider: ManagedPreferenceProvider,
        into: MutableList<Entry<Target>>, @StringRes vararg under: Int,
    ) {
        val path = under.map(context::getString) + context.getString(title)
        into += Entry(path.last(), "", path.dropLast(1), Target(route, null))
        // over storage that keeps nothing: a preference attached saves its default, which would
        // pin every default the user never touched to what it is today
        val manager = PreferenceManager(context).apply { preferenceDataStore = Nothing }
        val screen = manager.createPreferenceScreen(context)
        provider.createUi(screen)
        walk(screen, path) { pref, at -> into += Entry(pref.title.toString(), summary(pref), at, Target(route, pref.title.toString())) }
    }

    private object Nothing : PreferenceDataStore() {
        override fun putString(key: String?, value: String?) = Unit
        override fun putStringSet(key: String?, values: MutableSet<String>?) = Unit
        override fun putInt(key: String?, value: Int) = Unit
        override fun putLong(key: String?, value: Long) = Unit
        override fun putFloat(key: String?, value: Float) = Unit
        override fun putBoolean(key: String?, value: Boolean) = Unit
    }

    private fun walk(group: PreferenceGroup, path: List<String>, visit: (Preference, List<String>) -> Unit) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceGroup) walk(pref, path + listOfNotNull(pref.title?.toString()), visit)
            else if (pref.title != null) visit(pref, path)
        }
    }

    // a list's summary is its value, read from storage, which is no description to search
    private fun summary(pref: Preference): String = if (pref.summaryProvider != null) "" else pref.summary?.toString().orEmpty()

    private fun inputMethods(context: Context, into: MutableList<Entry<Target>>) {
        val top = context.getString(R.string.input_methods)
        for (name in InputMethods + ANY_TABLE) {
            val items = InputMethodSettings.of(name) ?: continue
            val page = if (name == ANY_TABLE) context.getString(R.string.table_im) else InputMethodNames.of(context, name, name)
            val route = if (name == ANY_TABLE) SettingsRoute.TableInputMethods else SettingsRoute.InputMethodConfig(page, name)
            into += Entry(page, "", listOf(top), Target(route, null))
            items(context, items, listOf(top, page)) { title, summary, path ->
                into += Entry(title, summary, path, Target(route, title.takeIf { name != ANY_TABLE }))
            }
        }
    }

    private fun items(
        context: Context, items: List<InputMethodSettings.Item>, path: List<String>,
        visit: (String, String, List<String>) -> Unit,
    ) {
        fun text(@StringRes id: Int) = if (id == 0) "" else context.getString(id)
        for (item in items) when (item) {
            is InputMethodSettings.Toggle -> visit(item.title(context), text(item.summary), path)
            is InputMethodSettings.Choice -> visit(text(item.title), text(item.message), path)
            is InputMethodSettings.Flags -> visit(text(item.title), item.flags.joinToString(" ") { it.second }, path)
            is InputMethodSettings.Section -> items(context, item.items, path + text(item.title), visit)
        }
    }
}
