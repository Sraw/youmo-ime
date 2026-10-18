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
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.BackupFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ClipboardSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.HardwareKeyboardFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeysSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SoundSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeSettingsFragment
import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Entry

/**
 * Every setting the search finds, read from the pages themselves where they are built from a
 * list (the app's preference categories, the input methods' [InputMethodSettings]), so a setting
 * added there is found without a word here; the pages drawn by hand are listed by name.
 */
object SettingsIndex {

    /** The page to open, and the setting on it to point at (its preference's key), if not the page itself. */
    data class Target(val route: SettingsRoute, val key: String?)

    fun build(context: Context): List<Entry<Target>> = buildList {
        fun page(@StringRes title: Int, route: SettingsRoute, vararg under: Int) =
            add(Entry(context.getString(title), "", under.map(context::getString), Target(route, null)))

        // as the home page lists them (MainFragment.HOME)
        page(R.string.input_methods, SettingsRoute.InputMethodList)
        inputMethods(context, this)
        sharedInput(context, this)
        page(R.string.word_packs, SettingsRoute.PinyinDict(""))
        page(R.string.my_words, SettingsRoute.UserWords, R.string.word_packs)
        page(R.string.custom_phrases, SettingsRoute.PinyinCustomPhrase, R.string.word_packs)
        managed(context, R.string.theme_and_size, SettingsRoute.Theme, { ThemeSettingsFragment.build(it) }, this)
        managed(context, R.string.virtual_keyboard, SettingsRoute.Keys, { KeysSettingsFragment.build(it) }, this)
        add(Entry(context.getString(R.string.long_press_characters), context.getString(R.string.long_press_characters_summary),
            listOf(context.getString(R.string.virtual_keyboard)), Target(SettingsRoute.PopupOverrides, null)))
        managed(context, R.string.section_sound_and_vibration, SettingsRoute.SoundAndVibration, { SoundSettingsFragment.build(it) }, this)
        managed(context, R.string.clipboard_and_emoji, SettingsRoute.Clipboard, { ClipboardSettingsFragment.build(it) }, this)
        managed(context, R.string.hardware_keyboard, SettingsRoute.HardwareKeyboard, { HardwareKeyboardFragment.build(it) }, this)
        // added to that page once fcitx answers
        val hardware = listOf(context.getString(R.string.hardware_keyboard))
        items(context, InputMethodSettings.HARDWARE_KEYBOARD, hardware) { title, summary, key, path ->
            add(Entry(title, summary, path, Target(SettingsRoute.HardwareKeyboard, key)))
        }
        // drawn by hand on that page: its own ids, so a title changed there is still the one pointed at
        page(R.string.user_data, SettingsRoute.Backup)
        val backup = listOf(context.getString(R.string.user_data))
        add(Entry(context.getString(BackupFragment.EXPORT_USER_DATA), context.getString(BackupFragment.EXPORT_USER_DATA_SUMMARY), backup,
            Target(SettingsRoute.Backup, BackupFragment.EXPORT_USER_DATA_KEY)))
        add(Entry(context.getString(BackupFragment.IMPORT_USER_DATA), "", backup,
            Target(SettingsRoute.Backup, BackupFragment.IMPORT_USER_DATA_KEY)))
        page(R.string.about, SettingsRoute.About)
    }

    /** A page built as its fragment builds it ([build]), but never shown, read for its settings. */
    @SuppressLint("RestrictedApi") // a PreferenceManager of our own, only to make the screen with
    private fun managed(
        context: Context, @StringRes title: Int, route: SettingsRoute, build: (PreferenceScreen) -> Unit,
        into: MutableList<Entry<Target>>, @StringRes vararg under: Int,
    ) {
        val path = under.map(context::getString) + context.getString(title)
        into += Entry(path.last(), "", path.dropLast(1), Target(route, null))
        // over storage that keeps nothing: a preference attached saves its default, which would
        // pin every default the user never touched to what it is today
        val manager = PreferenceManager(context).apply { preferenceDataStore = DiscardingDataStore }
        val screen = manager.createPreferenceScreen(context)
        build(screen)
        walk(screen, path) { pref, at -> into += Entry(pref.title.toString(), summary(pref), at, Target(route, pref.key)) }
    }

    private object DiscardingDataStore : PreferenceDataStore() {
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

    /** The page of what every input method shares, and the settings on it. */
    internal fun sharedInput(context: Context, into: MutableList<Entry<Target>>) {
        val page = context.getString(R.string.shared_input)
        into += Entry(page, "", emptyList(), Target(SettingsRoute.SharedInput, null))
        items(context, InputMethodSettings.SHARED, listOf(page)) { title, summary, key, path ->
            into += Entry(title, summary, path, Target(SettingsRoute.SharedInput, key))
        }
    }

    internal fun inputMethods(context: Context, into: MutableList<Entry<Target>>) {
        val top = context.getString(R.string.input_methods)
        for (name in InputMethodSettings.BUILT_IN) {
            val items = InputMethodSettings.of(name) ?: continue
            val page = InputMethodNames.of(context, name, name)
            val route = SettingsRoute.InputMethodConfig(page, name)
            into += Entry(page, "", listOf(top), Target(route, null))
            // not the row that opens the shared page, on every one: sharedInput has that page
            val own = items.filterNot { it is InputMethodSettings.Page && it.key == InputMethodSettings.SHARED_LINK }
            items(context, own, listOf(top, page)) { title, summary, key, path ->
                into += Entry(title, summary, path, Target(route, key))
            }
        }
    }

    // each with the key InputMethodSettings.create gives its preference
    private fun items(
        context: Context, items: List<InputMethodSettings.Item>, path: List<String>,
        visit: (String, String, String, List<String>) -> Unit,
    ) {
        fun text(@StringRes id: Int) = if (id == 0) "" else context.getString(id)
        for (item in items) when (item) {
            is InputMethodSettings.Toggle -> visit(item.title(context), text(item.summary), item.key, path)
            is InputMethodSettings.Flags -> visit(text(item.title), item.flags.joinToString(" ") { it.second }, item.path, path)
            is InputMethodSettings.Titled -> visit(text(item.title), text(item.summary), item.key, path)
            is InputMethodSettings.Section -> items(context, item.items, path + text(item.title), visit)
        }
    }
}
