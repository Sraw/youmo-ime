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
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings
import org.fcitx.fcitx5.android.ui.search.SettingsSearch
import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Entry
import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Kind
import org.fcitx.fcitx5.android.ui.main.settings.search.SettingsPages.Source

/** Read from the pages themselves where they are list-built, so additions there are found automatically. */
object SettingsIndex {

    /** [key] null for the page; [via]: the row pointed at while it is hidden; [where] it is when it has no View (⋮'s). */
    data class Target(
        val route: SettingsRoute, val key: String?, val via: String? = null, val view: String? = null, @StringRes val where: Int = 0,
    ) {
        val highlight: String? get() = view ?: key?.let { SearchHighlight.PREF + it }
        val highlightVia: String? get() = via?.let { SearchHighlight.PREF + it }
    }

    fun pageId(route: SettingsRoute): String = "page:" + when (route) {
        is SettingsRoute.InputMethodConfig -> "InputMethodConfig/${route.uniqueName}"
        // the app is not obfuscated (proguard-rules.pro)
        else -> route.javaClass.simpleName
    }

    /** One result: five alike would fill the list. */
    const val STROKES = "cmd:stroke"

    fun build(context: Context, between: () -> Unit = {}): List<Entry<Target>> {
        val keywords = SearchKeywords.of(context)
        return raw(context, between).map { e ->
            // an input method page's id ends @name; its other names are the setting's on any
            val named = keywords[e.id] ?: keywords[e.id.substringBefore('@')]
            if (named == null) e else e.copy(keywords = named)
        }
    }

    private fun raw(context: Context, between: () -> Unit): List<Entry<Target>> = buildList {
        for ((_, source) in SettingsPages.PAGES) {
            between()
            when (source) {
                is Source.Listed -> source.more.add(context, emptyList(), this)
                is Source.Managed -> {
                    val path = listOf(context.getString(source.title))
                    managed(context, source.title, source.open, source.build, this)
                    source.also?.let { hand(context, it, source.open, path, demoted = false, into = this) }
                    source.more?.add(context, path, this)
                }
                is Source.Hand -> {
                    val under = source.under.map(context::getString)
                    val title = context.getString(source.title)
                    if (source.listed) add(pageEntry(context, title, source.open, under, source.summary).copy(demoted = source.demoted))
                    hand(context, source.page, source.open, under + title, source.demoted, this)
                    source.more?.add(context, under + title, this)
                }
                // opening it goes back to the home page (SettingsSearchFragment.open)
                Source.Index -> add(setting(context.getString(R.string.app_language), "", emptyList(), SettingsRoute.Index, MainFragment.LANGUAGE_KEY))
                is Source.Elsewhere, is Source.NotSearched -> Unit
            }
        }
    }

    private fun hand(context: Context, page: SearchablePage, route: SettingsRoute, path: List<String>, demoted: Boolean, into: MutableList<Entry<Target>>) {
        for (e in page.searchEntries(context)) {
            val target = when {
                e.where != 0 -> Target(route, null, where = e.where)
                e.id.startsWith(SearchHighlight.PREF) -> Target(route, e.id.removePrefix(SearchHighlight.PREF))
                else -> Target(route, null, view = e.id)
            }
            into += Entry(e.title, e.summary, path, target, e.id, demoted = demoted)
        }
    }

    internal fun hardwareItems(context: Context, path: List<String>, into: MutableList<Entry<Target>>) =
        items(context, InputMethodSettings.HARDWARE_KEYBOARD, path) { row -> into += row.entry(SettingsRoute.HardwareKeyboard, "") }

    private fun setting(title: String, summary: String, path: List<String>, route: SettingsRoute, key: String) =
        Entry(title, summary, path, Target(route, key), "pref:$key")

    private fun pageEntry(context: Context, title: String, route: SettingsRoute, path: List<String>, @StringRes summary: Int = 0): Entry<Target> {
        val said = MainFragment.HOME.firstOrNull { it.route == route }?.summary ?: summary
        return Entry(title, if (said == 0) "" else context.getString(said), path, Target(route, null), pageId(route), Kind.PAGE)
    }

    internal fun commands(context: Context, path: List<String>, into: MutableList<Entry<Target>>) {
        val target = Target(SettingsRoute.PopupOverrides, null)
        for (command in KeyCommand.entries.filter { it.group != KeyCommand.Group.Stroke }) {
            into += Entry(context.getString(KeyShortcutLooks.of(command).label), "", path, target, "cmd:${command.key}", Kind.COMMAND)
        }
        into += Entry(context.getString(R.string.search_stroke_commands), "", path, target, STROKES, Kind.COMMAND)
    }

    /** Built as its fragment builds it ([build]), never shown. */
    @SuppressLint("RestrictedApi") // a PreferenceManager of our own, only to make the screen with
    private fun managed(
        context: Context, @StringRes title: Int, route: SettingsRoute, build: (PreferenceScreen) -> Unit,
        into: MutableList<Entry<Target>>,
    ) {
        val path = listOf(context.getString(title))
        into += pageEntry(context, path.last(), route, path.dropLast(1))
        // over storage that keeps nothing: an attached preference saves its default, which would pin every untouched default
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

    // a list's summary is its stored value, not a description to search
    private fun summary(pref: Preference): String = if (pref.summaryProvider != null) "" else pref.summary?.toString().orEmpty()

    internal fun sharedInput(context: Context, into: MutableList<Entry<Target>>) {
        into += pageEntry(context, context.getString(R.string.shared_input), SettingsRoute.SharedInput, emptyList())
        items(context, InputMethodSettings.SHARED, listOf(context.getString(R.string.shared_input))) { row ->
            into += row.entry(SettingsRoute.SharedInput, "")
        }
    }

    internal fun inputMethods(context: Context, into: MutableList<Entry<Target>>) {
        val top = context.getString(R.string.input_methods)
        into += pageEntry(context, top, SettingsRoute.InputMethodList, emptyList())
        for (name in InputMethodSettings.BUILT_IN) {
            val items = InputMethodSettings.of(name) ?: continue
            val page = InputMethodNames.of(context, name, name)
            val route = SettingsRoute.InputMethodConfig(page, name)
            into += pageEntry(context, page, route, listOf(top))
            val own = items.filterNot { it is InputMethodSettings.Page && it.key == InputMethodSettings.SHARED_LINK }
            // the same option is on many pages: each its own id (@name)
            items(context, own, listOf(top, page)) { row -> into += row.entry(route, "@$name") }
        }
    }

    private class Row(
        val title: String, val summary: String, val key: String, val via: String?, val path: List<String>,
        val id: String, val kind: Kind,
    ) {
        fun entry(route: SettingsRoute, page: String) = Entry(title, summary, path, Target(route, key, via), id + page, kind)
    }

    // none for one worth none (关, 1, Up)
    private fun value(label: String, key: String, value: String, path: List<String>, via: String? = null): Row? =
        if (SettingsSearch.isWorthAValue(label)) Row(label, "", key, via, path, "value:$key=$value", Kind.VALUE) else null

    // the setting may be hidden: in a section a keyboard choice shows, that choice's row
    private fun items(
        context: Context, items: List<InputMethodSettings.Item>, path: List<String>, via: String? = null,
        visit: (Row) -> Unit,
    ) {
        fun text(@StringRes id: Int) = if (id == 0) "" else context.getString(id)
        fun setting(title: String, summary: String, key: String, via: String?) = Row(title, summary, key, via, path, "pref:$key", Kind.SETTING)
        for (item in items) when (item) {
            // else five 「键盘」 results would open a page without it. Off the main thread, where layouts may be read
            is InputMethodSettings.TableKeyboard -> if (InputMethodSettings.tableKeyboardShown(context)) {
                visit(setting(text(item.title), text(item.summary), item.key, item.via ?: via))
            }
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
