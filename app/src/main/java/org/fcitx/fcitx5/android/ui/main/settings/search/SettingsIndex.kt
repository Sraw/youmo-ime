/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.StringRes
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutLooks
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.ui.main.MainFragment
import org.fcitx.fcitx5.android.ui.main.settings.AddressSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.BackupFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.PacksFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ClipboardSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.HardwareKeyboardFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeysSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SoundSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeSettingsFragment
import org.fcitx.fcitx5.android.ui.search.SettingsSearch
import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Entry
import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Kind

/**
 * Every setting the search finds, read from the pages themselves where they are built from a
 * list (the app's preference categories, the input methods' [InputMethodSettings]), so a setting
 * added there is found without a word here; the pages drawn by hand are listed by name. Each with
 * its id (dev/design/061-settings-search.md §4.1), the other names [SearchKeywords] has for it,
 * and, for a list, each of its choices worth one ([SettingsSearch.isWorthAValue]).
 */
object SettingsIndex {

    /**
     * The page to open, and the setting on it to point at (its preference's key), if not the page
     * itself; [via], the row to point at instead while that setting is hidden (InputMethodSettings.Section.via).
     */
    data class Target(val route: SettingsRoute, val key: String?, val via: String? = null) {
        /** What [SearchHighlight] is given: the setting's id and the row's, a preference's (pref:). */
        val highlight: String? get() = key?.let { SearchHighlight.PREF + it }
        val highlightVia: String? get() = via?.let { SearchHighlight.PREF + it }
    }

    /** The id of [route]'s page: its class, and for a route of a parameter the one that stays the same. */
    fun pageId(route: SettingsRoute): String = "page:" + when (route) {
        is SettingsRoute.InputMethodConfig -> "InputMethodConfig/${route.uniqueName}"
        // the class's own name: the app is not obfuscated (proguard-rules.pro)
        else -> route.javaClass.simpleName
    }

    /** The id of the five stroke commands, one result: five alike would fill the list. */
    const val STROKES = "cmd:stroke"

    /** The index in [context]'s language; [between] is called between pages (a cancelled build stops there). */
    fun build(context: Context, between: () -> Unit = {}): List<Entry<Target>> {
        val keywords = SearchKeywords.of(context)
        return raw(context, between).map { e ->
            // an input method's page's id says its page (@name); its other names are the setting's on any
            val named = keywords[e.id] ?: keywords[e.id.substringBefore('@')]
            if (named == null) e else e.copy(keywords = named)
        }
    }

    private fun raw(context: Context, between: () -> Unit): List<Entry<Target>> = buildList {
        fun page(@StringRes title: Int, route: SettingsRoute, vararg under: Int) =
            add(pageEntry(context, context.getString(title), route, under.map(context::getString)))

        fun managed(@StringRes title: Int, route: SettingsRoute, build: (PreferenceScreen) -> Unit) {
            between()
            managed(context, title, route, build, this)
        }

        // as the home page lists them (MainFragment.HOME), and its language row, which opens no page
        page(R.string.input_methods, SettingsRoute.InputMethodList)
        inputMethods(context, this)
        between()
        sharedInput(context, this)
        page(R.string.word_packs, SettingsRoute.PinyinDict(""))
        page(R.string.my_words, SettingsRoute.UserWords, R.string.word_packs)
        page(R.string.custom_phrases, SettingsRoute.PinyinCustomPhrase, R.string.word_packs)
        page(R.string.common_words, SettingsRoute.CommonWords, R.string.word_packs)
        page(R.string.rare_chars, SettingsRoute.RareChars, R.string.word_packs)
        page(R.string.quick_phrases, SettingsRoute.QuickPhrase(), R.string.word_packs)
        managed(R.string.theme_and_size, SettingsRoute.Theme) { ThemeSettingsFragment.build(it) }
        managed(R.string.virtual_keyboard, SettingsRoute.Keys) { KeysSettingsFragment.build(it) }
        val keys = listOf(context.getString(R.string.virtual_keyboard))
        add(Entry(context.getString(R.string.long_press_characters), context.getString(R.string.long_press_characters_summary),
            keys, Target(SettingsRoute.PopupOverrides, null), pageId(SettingsRoute.PopupOverrides), Kind.PAGE))
        commands(context, keys + context.getString(R.string.long_press_characters), this)
        add(Entry(context.getString(R.string.toolbar_buttons), context.getString(R.string.toolbar_buttons_summary),
            keys, Target(SettingsRoute.ToolbarButtons, null), pageId(SettingsRoute.ToolbarButtons), Kind.PAGE))
        managed(R.string.section_sound_and_vibration, SettingsRoute.SoundAndVibration) { SoundSettingsFragment.build(it) }
        managed(R.string.clipboard_and_emoji, SettingsRoute.Clipboard) { ClipboardSettingsFragment.build(it) }
        managed(R.string.hardware_keyboard, SettingsRoute.HardwareKeyboard) { HardwareKeyboardFragment.build(it) }
        // added to that page once fcitx answers
        val hardware = listOf(context.getString(R.string.hardware_keyboard))
        items(context, InputMethodSettings.HARDWARE_KEYBOARD, hardware) { row -> add(row.entry(SettingsRoute.HardwareKeyboard, "")) }
        // 通用输入 → 网址和邮箱, drawn by hand: its rows by keys of its own (the page itself is 通用输入's row)
        val addresses = listOf(context.getString(R.string.shared_input), context.getString(R.string.address_suggestions))
        for ((title, key, summary) in listOf(
            Triple(R.string.email_suffixes, AddressSettingsFragment.EMAIL_KEY, 0),
            Triple(R.string.url_suffixes, AddressSettingsFragment.URL_KEY, 0),
            Triple(R.string.remember_addresses, AddressSettingsFragment.REMEMBER_KEY, R.string.remember_addresses_summary),
            // 全部清空's row is in it only while an address is remembered: found by the group's result
            Triple(R.string.remembered_addresses, AddressSettingsFragment.REMEMBERED_KEY, R.string.forget_all_addresses),
        )) add(setting(context.getString(title), if (summary == 0) "" else context.getString(summary), addresses, SettingsRoute.Addresses, key))
        // drawn by hand on that page: its own ids, so a title changed there is still the one pointed at
        page(R.string.user_data, SettingsRoute.Backup)
        val backup = listOf(context.getString(R.string.user_data))
        add(setting(context.getString(BackupFragment.EXPORT_USER_DATA), context.getString(BackupFragment.EXPORT_USER_DATA_SUMMARY), backup,
            SettingsRoute.Backup, BackupFragment.EXPORT_USER_DATA_KEY))
        add(setting(context.getString(BackupFragment.IMPORT_USER_DATA), "", backup, SettingsRoute.Backup, BackupFragment.IMPORT_USER_DATA_KEY))
        page(R.string.packs, SettingsRoute.Packs)
        add(setting(context.getString(R.string.pack_import), context.getString(R.string.pack_import_summary), listOf(context.getString(R.string.packs)),
            SettingsRoute.Packs, PacksFragment.IMPORT_KEY))
        // on the home page, which is under every page: opening it goes back there (SettingsSearchFragment.open)
        add(setting(context.getString(R.string.app_language), "", emptyList(), SettingsRoute.Index, MainFragment.LANGUAGE_KEY))
        page(R.string.about, SettingsRoute.About)
    }

    private fun setting(title: String, summary: String, path: List<String>, route: SettingsRoute, key: String) =
        Entry(title, summary, path, Target(route, key), "pref:$key")

    // a page's entry, said as the home page says it if it is one of the home page's
    private fun pageEntry(context: Context, title: String, route: SettingsRoute, path: List<String>): Entry<Target> {
        val home = MainFragment.HOME.firstOrNull { it.route == route }?.summary
        return Entry(title, home?.let(context::getString).orEmpty(), path, Target(route, null), pageId(route), Kind.PAGE)
    }

    // what a key's swipe can run, each on the page that sets it, the five strokes as one
    private fun commands(context: Context, path: List<String>, into: MutableList<Entry<Target>>) {
        val target = Target(SettingsRoute.PopupOverrides, null)
        for (command in KeyCommand.entries.filter { it.group != KeyCommand.Group.Stroke }) {
            into += Entry(context.getString(KeyShortcutLooks.of(command).label), "", path, target, "cmd:${command.key}", Kind.COMMAND)
        }
        into += Entry(context.getString(R.string.search_stroke_commands), "", path, target, STROKES, Kind.COMMAND)
    }

    /** A page built as its fragment builds it ([build]), but never shown, read for its settings. */
    @SuppressLint("RestrictedApi") // a PreferenceManager of our own, only to make the screen with
    private fun managed(
        context: Context, @StringRes title: Int, route: SettingsRoute, build: (PreferenceScreen) -> Unit,
        into: MutableList<Entry<Target>>, @StringRes vararg under: Int,
    ) {
        val path = under.map(context::getString) + context.getString(title)
        into += pageEntry(context, path.last(), route, path.dropLast(1))
        // over storage that keeps nothing: a preference attached saves its default, which would
        // pin every default the user never touched to what it is today
        val manager = PreferenceManager(context).apply { preferenceDataStore = DiscardingDataStore }
        val screen = manager.createPreferenceScreen(context)
        build(screen)
        walk(screen, path) { pref, at ->
            val key = pref.key ?: return@walk
            val title = pref.title.toString()
            into += setting(title, summary(pref), at, route, key)
            if (pref is ListPreference) {
                val labels = pref.entries.orEmpty()
                val values = pref.entryValues.orEmpty()
                for (i in labels.indices) values.getOrNull(i)?.let { value ->
                    value(labels[i].toString(), key, value.toString(), at + title)?.let { into += it.entry(route, "") }
                }
            }
        }
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
        into += pageEntry(context, context.getString(R.string.shared_input), SettingsRoute.SharedInput, emptyList())
        items(context, InputMethodSettings.SHARED, listOf(context.getString(R.string.shared_input))) { row ->
            into += row.entry(SettingsRoute.SharedInput, "")
        }
    }

    internal fun inputMethods(context: Context, into: MutableList<Entry<Target>>) {
        val top = context.getString(R.string.input_methods)
        for (name in InputMethodSettings.BUILT_IN) {
            val items = InputMethodSettings.of(name) ?: continue
            val page = InputMethodNames.of(context, name, name)
            val route = SettingsRoute.InputMethodConfig(page, name)
            into += pageEntry(context, page, route, listOf(top))
            // not the row that opens the shared page, on every one: sharedInput has that page
            val own = items.filterNot { it is InputMethodSettings.Page && it.key == InputMethodSettings.SHARED_LINK }
            // the same option is on many input methods' pages: each its own id (@name)
            items(context, own, listOf(top, page)) { row -> into += row.entry(route, "@$name") }
        }
    }

    /** A row of an [InputMethodSettings] page found: a setting, or one of a list's choices ([kind] VALUE, [id] value:). */
    private class Row(
        val title: String, val summary: String, val key: String, val via: String?, val path: List<String>,
        val id: String, val kind: Kind,
    ) {
        fun entry(route: SettingsRoute, page: String) = Entry(title, summary, path, Target(route, key, via), id + page, kind)
    }

    // a choice of key's list, found by its own name: none for one worth none (关, 1, Up)
    private fun value(label: String, key: String, value: String, path: List<String>, via: String? = null): Row? =
        if (SettingsSearch.isWorthAValue(label)) Row(label, "", key, via, path, "value:$key=$value", Kind.VALUE) else null

    // each with the key InputMethodSettings.create gives its preference, and in a section a keyboard
    // choice shows, that choice's row ([InputMethodSettings.Section.via]): the setting may be hidden
    private fun items(
        context: Context, items: List<InputMethodSettings.Item>, path: List<String>, via: String? = null,
        visit: (Row) -> Unit,
    ) {
        fun text(@StringRes id: Int) = if (id == 0) "" else context.getString(id)
        fun setting(title: String, summary: String, key: String, via: String?) = Row(title, summary, key, via, path, "pref:$key", Kind.SETTING)
        for (item in items) when (item) {
            is InputMethodSettings.Toggle -> visit(setting(item.title(context), text(item.summary), item.key, via))
            is InputMethodSettings.Flags -> {
                visit(setting(text(item.title), "", item.path, via))
                for ((flag, label) in item.flags) value(label, item.path, flag, path + text(item.title), via)?.let(visit)
            }
            is InputMethodSettings.Titled -> {
                val at = item.via ?: via
                visit(setting(text(item.title), text(item.summary), item.key, at))
                val choices = when (item) {
                    is InputMethodSettings.Choice -> item.values.map { item.label(context, it) to it }
                    is InputMethodSettings.AppChoice<*> -> item.values.map { context.getString((it as ManagedPreferenceEnum).stringRes) to (it as Enum<*>).name }
                    else -> emptyList()
                }
                for ((label, v) in choices) value(label, item.key, v, path + text(item.title), at)?.let(visit)
            }
            is InputMethodSettings.Section -> items(context, item.items, path + text(item.title), item.via ?: via, visit)
        }
    }
}
