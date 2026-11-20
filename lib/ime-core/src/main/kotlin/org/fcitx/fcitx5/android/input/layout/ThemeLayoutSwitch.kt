/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keyboard.CustomChoice
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards

/** Which layouts made of theme packs' boards take the place of the boards they stood for: only at an upgrade from 0.6.0 or a restore of an older backup. */
object ThemeLayoutSwitch {
    /** One choice each for every input method. */
    const val NUMBER = "number"
    const val SYMBOLS = "symbols"

    /** [choice] as the keyboard reads it. */
    data class Made(val pack: String, val board: String, val choice: CustomChoice)

    /** [aside]: those of the other slot's pack that one of the shown pack's took the place of (listed, not chosen). */
    class Plan(
        val slots: Map<String, String>,
        val panels: Map<String, String>,
        val uses: Map<Made, List<String>>,
        val aside: List<Made>,
    )

    /** Null for a board no pack's file ever stood for (注音's, the user's own). */
    fun boardOf(im: String, chosen: ImKeyboards.Chosen, scheme: ShuangpinScheme): String? {
        val choice = chosen.of(im)
        if (choice == ImKeyboard.CUSTOM) return null
        return when (ImKeyboards.effective(im, choice, scheme)) {
            ImKeyboard.FULL -> "qwerty"
            ImKeyboard.KEYS18 -> "k18"
            ImKeyboard.KEYS14 -> "k14"
            ImKeyboard.NINE_KEYS -> "nine"
            else -> null
        }
    }

    /** The shown pack's board where it has one, else the other slot's; switched only where [ImKeyboards.usable]. A pack in neither slot changes nothing. */
    fun plan(
        chosen: ImKeyboards.Chosen,
        scheme: ShuangpinScheme,
        panels: Map<String, String>,
        shown: String?,
        other: String?,
        made: List<Made>,
    ): Plan {
        val byBoard = LinkedHashMap<String, Made>()
        val aside = ArrayList<Made>()
        for (board in made.map { it.board }.distinct()) {
            val mine = shown?.let { s -> made.firstOrNull { it.pack == s && it.board == board } }
            val theirs = other?.takeIf { it != shown }?.let { o -> made.firstOrNull { it.pack == o && it.board == board } }
            (mine ?: theirs)?.let { byBoard[board] = it }
            if (mine != null && theirs != null) aside += theirs
        }
        val uses = LinkedHashMap<Made, MutableList<String>>()
        val slots = LinkedHashMap<String, String>()
        for (slot in ImKeyboards.SLOTS) {
            val im = ImKeyboards.imOf(slot)
            val m = boardOf(im, chosen, scheme)?.let(byBoard::get)?.takeIf { ImKeyboards.usable(im, it.choice, scheme) } ?: continue
            slots[slot] = m.choice.id
            uses.getOrPut(m) { ArrayList() } += slot
        }
        val panelsOut = LinkedHashMap<String, String>()
        for (panel in listOf(NUMBER, SYMBOLS)) {
            val m = byBoard[panel]?.takeIf { it.choice.family == panel && it.choice.drawn && panels[panel].isNullOrEmpty() } ?: continue
            panelsOut[panel] = m.choice.id
            uses.getOrPut(m) { ArrayList() } += panel
        }
        val ordered = made.filter { it in uses }.associateWith { uses.getValue(it).toList() }
        return Plan(slots, panelsOut, ordered, aside)
    }
}
