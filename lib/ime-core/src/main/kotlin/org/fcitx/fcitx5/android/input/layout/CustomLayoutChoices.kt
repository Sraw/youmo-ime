/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.input.keyboard.CustomChoice
import org.fcitx.fcitx5.android.input.keyboard.PanelLayouts
import org.fcitx.fcitx5.android.input.scene.SceneGroups
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.layout.Row

/** A user layout's file read to draw: untrusted, held again to [LayoutCheck.loaded]; one that fails is not drawn, its base in its place (读不出，暂用 …), the choices left. */
object CustomLayoutChoices {
    /** [problems] empty: drawn. */
    class Read(val choice: CustomChoice, val problems: List<LayoutCheck.Finding>)

    fun of(layout: Layout, sources: LayoutCheck.Sources, appVersion: String, stamp: String): Read {
        val problems = runCatching { LayoutCheck.loaded(layout, sources, appVersion) }
            .getOrElse { listOf(LayoutCheck.Finding(0, "read", listOf(it.javaClass.simpleName))) }
        val fragments = layout.parts.filterIsInstance<Include>().mapNotNull { inc -> sources.fragment(inc.src)?.let { inc.src to it } }.toMap()
        val choice = CustomChoice(
            id = layout.id,
            name = LayoutCheck.plain(layout.name).takeIf { it.isNotBlank() } ?: layout.id,
            family = layout.family,
            // the board it is drawn as where it cannot be: of its own family, whatever based-on= says
            basedOn = runCatching { LayoutCheck.baseId(layout, sources) }.getOrNull(),
            groups = runCatching { SceneGroups.of(layout, fragments) }.getOrDefault(emptyList()),
            drawn = problems.isEmpty(),
            tableExtras = TABLE_EXTRAS.all { tableExtra(layout, fragments, it) },
            stamp = stamp,
            pads = PanelLayouts.pads(layout),
        )
        return Read(choice, problems)
    }

    // the keys a table types beside the letters, the full keyboard's (TextKeyboard.EXTRA_SLOTS): `;` and `*`
    private val TABLE_EXTRAS = listOf("semicolon", "star")

    // one whose when= or a variant's asks table-extra=[extra]
    private fun tableExtra(layout: Layout, fragments: Map<String, Fragment>, extra: String): Boolean {
        val rows = layout.parts.flatMap { part ->
            when (part) {
                is Row -> listOf(part)
                is Include -> fragments[part.src]?.rows.orEmpty()
            }
        }
        return rows.flatMap { it.cells }.filterIsInstance<Key>().any { k ->
            (listOfNotNull(k.condition) + k.variants.map { it.condition }).any { c ->
                c.anyOf.any { group -> group.any { t -> t.name == "table-extra" && !t.negated && t.values?.contains(extra) != false } }
            }
        }
    }
}
