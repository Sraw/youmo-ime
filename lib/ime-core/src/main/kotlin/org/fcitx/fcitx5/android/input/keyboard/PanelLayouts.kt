/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.scene.layout.Layout

/** The number keyboard's and symbol panel's layout: one choice each for every input method; a number board serves the pads its `pads=` names (`full` by default). */
object PanelLayouts {

    enum class Panel(val family: String) {
        NUMBER("number"),
        SYMBOLS("symbols"),
        ;

        companion object {
            /** Null for an input method's (letters, grouped, nine). */
            fun of(family: String?): Panel? = entries.firstOrNull { it.family == family }
        }
    }

    /** `full` where it names none; none for any other family. */
    fun pads(layout: Layout): List<String> = if (layout.family == Panel.NUMBER.family) layout.pads ?: listOf(NumberPad.FULL.id) else emptyList()

    /** [choice] where of the panel's family and drawn; null for the app's. A choice not drawn is left as is (读不出，暂用内置的). */
    fun drawn(panel: Panel, choice: CustomChoice?): CustomChoice? = choice?.takeIf { it.family == panel.family && it.drawn }

    fun draws(numbers: CustomChoice?, pad: NumberPad): Boolean = numbers != null && pad.id in numbers.pads

    /** Chosen but not [drawn]: 读不出，暂用内置的. */
    fun unread(panel: Panel, chosen: String, choice: CustomChoice?): Boolean = chosen.isNotEmpty() && drawn(panel, choice) == null

    /** Back on the app's as it is deleted. */
    fun usedBy(chosen: Map<Panel, String>, id: String): List<Panel> = Panel.entries.filter { chosen[it] == id }

    /** Its family's; one that does not read at all, the panel that chose it; none for letters. */
    fun listedUnder(family: String?, id: String, chosen: Map<Panel, String>): Panel? =
        Panel.of(family) ?: chosen.entries.firstOrNull { family == null && it.value == id }?.key

    /** None of the layouts listed under it: its file gone (a backup without it), or another family's now. */
    fun missing(panel: Panel, chosen: Map<Panel, String>, listed: Map<String, Panel?>): Boolean =
        chosen[panel].orEmpty().let { it.isNotEmpty() && listed[it] != panel }
}
