/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.layout.LayoutCheck.Finding

/** What a layout lacks of what the app's board and its family have, and a number board's letters: [LayoutCheck]'s warnings. */
internal object LayoutLacks {
    /**
     * What [layout] lacks of its [base] and its family, each thing told once: a function key, a slot, the digits, the
     * letters. A number board's digits are held by what they type, each pad's by its scenes ([padNeeds]); a pad is named
     * only where not every pad of [pads] lacks it.
     */
    fun lacks(
        layout: Layout,
        fragments: Map<String, Fragment>,
        base: Layout,
        baseFragments: Map<String, Fragment>,
        pads: List<String>,
        padNeeds: Map<String, List<String>>,
    ): List<Finding> {
        if (layout.family !in LayoutCheck.FAMILIES) return emptyList()
        val everywhere = everywhere(layout, fragments, LayoutCheck.cells(base, baseFragments))
        val onPads = onPads(padNeeds, everywhere)
        fun padsOf(lack: Lack): String =
            onPads[lack]?.takeIf { lacking -> !lacking.containsAll(pads) }?.let { lacking -> pads.filter { it in lacking }.joinToString(" ") }.orEmpty()
        return findings((everywhere + onPads.keys).toList(), ::padsOf)
    }

    /** A thing lacked: a role, a slot's kind, a key the family wants; [item] one digit or letter of [what]. */
    private data class Lack(val what: String, val item: String? = null)

    private fun everywhere(layout: Layout, fragments: Map<String, Fragment>, theirs: List<Cell>): Set<Lack> {
        val mine = LayoutCheck.cells(layout, fragments)
        val own = roles(mine)
        val out = LinkedHashSet<Lack>()
        (slots(theirs) - slots(mine)).mapTo(out) { Lack(it) }
        (roles(theirs).filter { it in LayoutCheck.ROLES } - own).mapTo(out) { Lack(it) }
        if (layout.family != "number") (digits(theirs) - digits(mine)).mapTo(out) { Lack(DIGITS, it) }
        out += familyLacks(layout, fragments, mine, own)
        return out
    }

    private fun familyLacks(layout: Layout, fragments: Map<String, Fragment>, mine: List<Cell>, own: Set<String>): List<Lack> = when (layout.family) {
        "letters" -> listOf(SPACE, "shift").filter { it !in own }.map { Lack(it) } + letters(LayoutCheck.lettersLacking(layout, fragments))
        "grouped" -> listOf(SPACE).filter { it !in own }.map { Lack(it) } + letters(Groups.missing(LayoutCheck.groups(layout, fragments)))
        "symbols" -> if (mine.filterIsInstance<Key>().any { it.attrs.actions["send"] == PANEL_BACK }) emptyList() else listOf(Lack(PANEL_BACK))
        else -> emptyList()
    }

    private fun letters(missing: String): List<Lack> = missing.map { Lack(LETTERS, it.toString()) }

    // each pad's needs not lacked everywhere, with the pads lacking them
    private fun onPads(padNeeds: Map<String, List<String>>, everywhere: Set<Lack>): Map<Lack, Set<String>> {
        val out = LinkedHashMap<Lack, MutableSet<String>>()
        for ((pad, needs) in padNeeds) for (n in needs) {
            val lack = if (LayoutCheck.DIGIT.matches(n)) Lack(PAD_DIGITS, n) else Lack(n)
            if (lack !in everywhere) out.getOrPut(lack) { LinkedHashSet() } += pad
        }
        return out
    }

    private fun findings(lacks: List<Lack>, padsOf: (Lack) -> String): List<Finding> {
        // 地球键's way round (空格键长按) needs the space bar: lacking both, one finding says both
        val noSpace = Lack(LANG) in lacks && Lack(SPACE) in lacks && padsOf(Lack(SPACE)).isEmpty()
        return lacks.filter { !noSpace || it != Lack(SPACE) }.groupBy { it.what }.toList().sortedBy { (what, _) -> order(what) }
            .flatMap { (what, each) -> told(what, each, noSpace, padsOf) }
    }

    // the digits and letters of one set of pads in one finding
    private fun told(what: String, each: List<Lack>, noSpace: Boolean, padsOf: (Lack) -> String): List<Finding> = when (what) {
        DIGITS, PAD_DIGITS, LETTERS -> each.groupBy(padsOf).map { (on, items) -> finding(what, items.mapNotNull { it.item }.sorted().joinToString(" "), on) }
        LANG -> listOf(finding(if (noSpace) LANG_NO_SPACE else what, "", padsOf(Lack(LANG))))
        else -> each.map { finding(what, "", padsOf(it)) }
    }

    private fun finding(what: String, items: String, on: String) = Finding(0, "lacks", listOf(what, items, on).dropLastWhile { it.isEmpty() })

    private fun order(what: String): Int = LACKS_ORDER.indexOf(what).let { if (it < 0) LACKS_ORDER.size else it }

    private fun slots(c: List<Cell>) = c.filterIsInstance<Slot>().map { it.kind }.toSet()

    private fun roles(c: List<Cell>) = c.filterIsInstance<Key>().mapNotNull { it.attrs.role }.toSet()

    private fun digits(c: List<Cell>) = c.filterIsInstance<Key>().map { it.id }.filter { LayoutCheck.DIGIT.matches(it) }.toSet()

    const val DIGITS = "digits"
    const val PAD_DIGITS = "pad-digits"
    const val LETTERS = "letters"
    private const val LANG = "lang"
    private const val LANG_NO_SPACE = "lang-no-space"
    private const val SPACE = "space"
    private const val PANEL_BACK = "panel-back"

    /** What 0.6.0 refused any theme pack's board for lacking, drawing the app's (its `judge`): a slot, ⌫, 回车, 地球键, a digit. */
    val REFUSED_060: Set<String> = LayoutCheck.ROLES + LayoutReader.SLOT_KINDS + setOf(LANG_NO_SPACE, DIGITS, PAD_DIGITS)

    private val LACKS_ORDER = listOf("backspace", "enter", "back", PANEL_BACK, LANG, SPACE, "shift", DIGITS, PAD_DIGITS, LETTERS) + LayoutReader.SLOT_KINDS

    /** A number board's keys typing a letter (a tap's text, or `letters=`): told, a number field wants none. */
    fun padLetters(layout: Layout, fragments: Map<String, Fragment>): List<Finding> {
        if (layout.family != "number") return emptyList()
        fun letter(send: String?) = LayoutCheck.typed(send)?.any { it in 'a'..'z' || it in 'A'..'Z' } == true
        return LayoutCheck.keysOf(layout, fragments).filter { (k, a) -> k.letters != null || LayoutCheck.states(k, a).any { letter(it.actions["send"]) } }
            .map { (k, _) -> Finding(k.line, "pad-letters", listOf(k.id)) }.distinct()
    }
}
