/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.scene.layout.Layout

/**
 * The number keyboard's and the symbol panel's layout (061-custom-layout §7, §8, L5): no input method's, one choice each
 * for every input method (外观 → 布局: 数字键盘用这个, 符号面板用这个), the app's where none is chosen. A number board of
 * the user's is for the pads its `pads=` names (`full` alone where it names none: the one the 123 key brings), the app's
 * pads for the rest. The app reads the choices from its preferences and the files; this decides.
 */
object PanelLayouts {

    /** A panel a layout of the user's may be chosen for, by its [family]. */
    enum class Panel(val family: String) {
        NUMBER("number"),
        SYMBOLS("symbols"),
        ;

        companion object {
            /** The panel a layout of [family] is for; null for an input method's (letters, grouped, nine). */
            fun of(family: String?): Panel? = entries.firstOrNull { it.family == family }
        }
    }

    /** The pads a number board [layout] is for (its `pads=`, `full` where it names none); none for any other family. */
    fun pads(layout: Layout): List<String> = if (layout.family == Panel.NUMBER.family) layout.pads ?: listOf(NumberPad.FULL.id) else emptyList()

    /**
     * The layout of the user's [panel] draws: [choice], what the file of the id chosen reads as (null: none chosen, or its
     * file gone or unread), where it is of the panel's family and drawn (held to what it must be as it is read); null where
     * the app's is drawn. A choice not drawn is left as it is (§4.4: 读不出，暂用内置的).
     */
    fun drawn(panel: Panel, choice: CustomChoice?): CustomChoice? = choice?.takeIf { it.family == panel.family && it.drawn }

    /** Whether the number board [numbers] ([drawn]) is for [pad]: drawn there, the app's pad else. */
    fun draws(numbers: CustomChoice?, pad: NumberPad): Boolean = numbers != null && pad.id in numbers.pads

    /** Whether [chosen], a panel's id ("" the app's), is chosen but not drawn ([drawn] of what it reads as, [choice]): 读不出，暂用内置的. */
    fun unread(panel: Panel, chosen: String, choice: CustomChoice?): Boolean = chosen.isNotEmpty() && drawn(panel, choice) == null

    /** The panels whose layout is [id] in [chosen] (each panel's id, "" the app's): back on the app's as it is deleted (§4.4). */
    fun usedBy(chosen: Map<Panel, String>, id: String): List<Panel> = Panel.entries.filter { chosen[it] == id }

    /**
     * The panel a layout of the user's [id] of [family] is listed under: its family's; one that does not read at all
     * (no family) the panel's [chosen] it (§4.4: 读不出, the app's drawn meanwhile); none for letters.
     */
    fun listedUnder(family: String?, id: String, chosen: Map<Panel, String>): Panel? =
        Panel.of(family) ?: chosen.entries.firstOrNull { family == null && it.value == id }?.key

    /**
     * Whether [panel]'s choice in [chosen] is none of the user's layouts listed under it ([listed]: each one's id to
     * [listedUnder]): its file gone (a backup restored without it), another family's now. The app's card says so (§4.4).
     */
    fun missing(panel: Panel, chosen: Map<Panel, String>, listed: Map<String, Panel?>): Boolean =
        chosen[panel].orEmpty().let { it.isNotEmpty() && listed[it] != panel }
}
