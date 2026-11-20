/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.fcitx.fcitx5.android.data.theme.Theme
import kotlin.reflect.KClass

/** A two-version theme (`pixel#light`, `pixel#dark`) is one card showing one, 浅/深 switching; others a card each. */
class ThemeCards(themes: List<Theme>, dark: Boolean = false) {

    private val others: Map<String, Theme>

    val shown: MutableList<Theme>

    init {
        // one of each name, never both (an import refuses a built-in's name)
        val versioned = themes.filter { it is Theme.Versioned }
        val pairs = versioned.groupBy(::family).values.filter { it.size == 2 }
        others = pairs.flatMap { (a, b) -> listOf(a.name to b, b.name to a) }.toMap()
        val seen = HashSet<Pair<KClass<out Theme>, String>>()
        // a pair's [dark] version shown at first
        shown = themes.mapNotNull { t ->
            if (t !is Theme.Versioned || t.name !in others) return@mapNotNull t
            if (!seen.add(family(t))) null else if (t.isDark == dark) t else others.getValue(t.name)
        }.toMutableList()
    }

    // a built-in's and a pack's never share a card
    private fun family(t: Theme): Pair<KClass<out Theme>, String> = t::class to (t as Theme.Versioned).file

    fun other(theme: Theme): Theme? = others[theme.name]

    /** Or would be on switching it; -1 for none. */
    fun indexOf(theme: Theme?): Int {
        if (theme == null) return -1
        return shown.indexOfFirst { it.name == theme.name || others[it.name]?.name == theme.name }
    }

    /** -1 if no card has it. */
    fun show(theme: Theme?): Int {
        val i = indexOf(theme)
        if (i >= 0 && shown[i].name != theme!!.name) switch(i)
        return i
    }

    /** False if it has none. */
    fun switch(index: Int): Boolean {
        shown[index] = others[shown[index].name] ?: return false
        return true
    }
}
