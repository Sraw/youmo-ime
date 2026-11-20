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
    @Suppress("CyclomaticComplexMethod")
    fun lacks(
        layout: Layout,
        fragments: Map<String, Fragment>,
        base: Layout,
        baseFragments: Map<String, Fragment>,
        pads: List<String>,
        padNeeds: Map<String, List<String>>,
    ): List<Finding> {
        if (layout.family !in LayoutCheck.FAMILIES) return emptyList()
        val mine = LayoutCheck.cells(layout, fragments)
        val theirs = LayoutCheck.cells(base, baseFragments)
        fun slots(c: List<Cell>) = c.filterIsInstance<Slot>().map { it.kind }.toSet()
        fun roles(c: List<Cell>) = c.filterIsInstance<Key>().mapNotNull { it.attrs.role }.toSet()
        fun digits(c: List<Cell>) = c.filterIsInstance<Key>().map { it.id }.filter { LayoutCheck.DIGIT.matches(it) }.toSet()
        val everywhere = LinkedHashSet<Pair<String, String>>()
        (slots(theirs) - slots(mine)).forEach { everywhere += it to "" }
        (roles(theirs).filter { it in LayoutCheck.ROLES } - roles(mine)).forEach { everywhere += it to "" }
        if (layout.family != "number") (digits(theirs) - digits(mine)).forEach { everywhere += DIGITS to it }
        val own = roles(mine)
        when (layout.family) {
            "letters" -> {
                listOf("space", "shift").filter { it !in own }.forEach { everywhere += it to "" }
                LayoutCheck.lettersLacking(layout, fragments).forEach { everywhere += LETTERS to it }
            }
            "grouped" -> {
                if ("space" !in own) everywhere += "space" to ""
                Groups.missing(LayoutCheck.groups(layout, fragments)).forEach { everywhere += LETTERS to it.toString() }
            }
            "symbols" -> if (mine.filterIsInstance<Key>().none { it.attrs.actions["send"] == "panel-back" }) everywhere += "panel-back" to ""
        }
        val onPads = LinkedHashMap<Pair<String, String>, MutableSet<String>>()
        for ((pad, needs) in padNeeds) for (n in needs) {
            val thing = if (LayoutCheck.DIGIT.matches(n)) PAD_DIGITS to n else n to ""
            if (thing !in everywhere) onPads.getOrPut(thing) { LinkedHashSet() } += pad
        }
        fun padsOf(thing: Pair<String, String>): String =
            onPads[thing]?.takeIf { lacking -> !lacking.containsAll(pads) }?.let { lacking -> pads.filter { it in lacking }.joinToString(" ") }.orEmpty()
        val things = (everywhere + onPads.keys).toList()
        val lang = "lang" to ""
        val space = "space" to ""
        // 地球键's way round (空格键长按) needs the space bar: lacking both, one finding says both
        val noSpace = lang in things && space in things && padsOf(space).isEmpty()
        return things.filter { !noSpace || it != space }.groupBy { (what, _) -> what }.toList().sortedBy { (what, _) -> LACKS_ORDER.indexOf(what).let { if (it < 0) LACKS_ORDER.size else it } }
            .flatMap { (what, each) ->
                when (what) {
                    DIGITS, PAD_DIGITS, LETTERS -> each.groupBy(::padsOf).map { (on, items) -> listOf(what, items.map { it.second }.sorted().joinToString(" "), on) }
                    "lang" -> listOf(listOf(if (noSpace) "lang-no-space" else what, "", padsOf(lang)))
                    else -> each.map { listOf(what, "", padsOf(it)) }
                }
            }.map { args -> Finding(0, "lacks", args.dropLastWhile { it.isEmpty() }) }
    }

    private const val DIGITS = "digits"
    private const val PAD_DIGITS = "pad-digits"
    private const val LETTERS = "letters"

    private val LACKS_ORDER = listOf(
        "backspace", "enter", "back", "panel-back", "lang", "space", "shift", DIGITS, PAD_DIGITS, LETTERS, "nine-side",
        "symbol-categories", "symbol-grid",
    )

    /** A number board's keys typing a letter (a tap's text, or `letters=`): told, a number field wants none. */
    fun padLetters(layout: Layout, fragments: Map<String, Fragment>): List<Finding> {
        if (layout.family != "number") return emptyList()
        fun letter(send: String?) = LayoutCheck.typed(send)?.any { it in 'a'..'z' || it in 'A'..'Z' } == true
        return LayoutCheck.keysOf(layout, fragments).filter { (k, a) -> k.letters != null || LayoutCheck.states(k, a).any { letter(it.actions["send"]) } }
            .map { (k, _) -> Finding(k.line, "pad-letters", listOf(k.id)) }.distinct()
    }
}
