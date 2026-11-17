/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keyboard.CustomChoice
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards

/**
 * Which of the layouts made of the theme packs' boards (061-custom-layout §11, L7) take the place of the board each stood
 * for, where it is chosen: what 0.6.0 drew, the pack's `k18.xml` over the app's 18 keys while the pack was in use, drawn
 * as a layout of the user's chosen since. Only at an upgrade from 0.6.0 and at a restore of a backup made before 0.6.1:
 * the user picks these in 外观 → 布局 otherwise. The app reads the choices and the layouts; this decides.
 */
object ThemeLayoutSwitch {
    /** The number keyboard's and the symbol panel's boards: one choice each for every input method (§7, §8). */
    const val NUMBER = "number"
    const val SYMBOLS = "symbols"

    /** A layout made of pack [pack]'s (its file's) board [board] (`k18`), [choice] as the keyboard reads it. */
    data class Made(val pack: String, val board: String, val choice: CustomChoice)

    /**
     * What is to change: each [ImKeyboards.SLOTS] slot's layout of the user's ([slots], by slot: chosen as
     * [ImKeyboard.CUSTOM]) and each panel's ([panels], [NUMBER] and [SYMBOLS]); [uses] each layout switched in with the
     * slots and panels now on it, in [made]'s order; [aside] those of the pack in the other slot (dark where the light
     * is shown) that one of the shown pack's took the place of (Q15: listed, not chosen).
     */
    class Plan(
        val slots: Map<String, String>,
        val panels: Map<String, String>,
        val uses: Map<Made, List<String>>,
        val aside: List<Made>,
    )

    /**
     * The board of the app's [im] shows on its choice in [chosen] under [scheme] (the one a pack's file of that id stood
     * for: `qwerty`, `k14`, `k18`, `nine`); null for one a pack's board of those never was (注音's, a layout of the user's).
     */
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

    /**
     * What [made] (the packs' boards made layouts of the user's) changes of [chosen] under [scheme] and of [panels] (each
     * panel's id, "" the app's): the packs in use are [shown] (the pack of the theme shown now, its file) and [other] (the
     * one in the other slot of 跟随系统深色模式, null none). Each board the shown pack's where it has it, else the other's
     * (both slots count, §11); each input method on that board switched where it may be typed on the layout
     * ([ImKeyboards.usable]: 双拼 not on 17 keys of other groups, S8; one not drawn, none), each panel on the app's where
     * the layout is of its family and drawn. A pack in neither slot changes nothing: its layouts are only listed.
     */
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
