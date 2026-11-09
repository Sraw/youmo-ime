/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.fcitx.fcitx5.android.data.theme.Theme

/**
 * The theme lists' cards (060-render-p3 T3): a built-in theme or a pack (T8a) of two versions (`pixel#light`,
 * `pixel#dark`) is one card showing one of them, its 浅/深 switching to the other; any other theme a
 * card of its own. [shown] is each card's theme as listed, in the order of the themes it is made of.
 */
class ThemeCards(themes: List<Theme>, dark: Boolean = false) {

    // each version of a pair by its name: the other
    private val others: Map<String, Theme>

    val shown: MutableList<Theme>

    init {
        // a built-in file's or a pack's (Theme.Versioned): one of each name, never both (an import refuses a built-in's)
        val versioned = themes.filter { it is Theme.Versioned }
        val pairs = versioned.groupBy { it::class to (it as Theme.Versioned).file }.values.filter { it.size == 2 }
        others = pairs.flatMap { (a, b) -> listOf(a.name to b, b.name to a) }.toMap()
        val seen = HashSet<String>()
        // a pair's [dark] version shown at first (the light one in the list, the slot's in a slot's)
        shown = themes.mapNotNull { t ->
            if (t !is Theme.Versioned || t.name !in others) return@mapNotNull t
            if (!seen.add(t::class.simpleName + "/" + t.file)) null else if (t.isDark == dark) t else others.getValue(t.name)
        }.toMutableList()
    }

    /** The other version of [theme]'s card, if it has two. */
    fun other(theme: Theme): Theme? = others[theme.name]

    /** The index of the card [theme] is shown on, or would be on switching it; -1 for none. */
    fun indexOf(theme: Theme?): Int {
        if (theme == null) return -1
        return shown.indexOfFirst { it.name == theme.name || others[it.name]?.name == theme.name }
    }

    /** Shows [theme] on its card, its other version switched out; its index, -1 if none has it. */
    fun show(theme: Theme?): Int {
        val i = indexOf(theme)
        if (i >= 0 && shown[i].name != theme!!.name) switch(i)
        return i
    }

    /** The card at [index] shows its other version; false if it has none. */
    fun switch(index: Int): Boolean {
        shown[index] = others[shown[index].name] ?: return false
        return true
    }
}
