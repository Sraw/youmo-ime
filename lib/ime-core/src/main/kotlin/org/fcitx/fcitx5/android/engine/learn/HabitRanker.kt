/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

/**
 * The order of the candidates of one code by the user's habits (impl/design-1b.md §1; the spec's
 * U-02, U-08, U-10, U-83): values in, a list out, nothing kept. The session gives the items of a
 * level (those ending where the level ends), the decoder's order theirs, those it put in after
 * ([Item.injected]) last; [rank] gives them back in the order shown, each with the [Tier] that
 * put it there, which is what U-81 will show as the reason.
 *
 * V, the original first, is the first item neither injected nor imported (D23: an imported word is never first by the decoder alone). First is:
 * 1. the user's FIRST under (code, prev), if among the items;
 * 2. else, V being no single character, the first of the order of 4 used more than V under the
 *    code and corrected to at least [CORRECTIONS] times there (across the words before it);
 * 3. else V.
 *
 * Then the rest: those used under the code (by uses, ties by which was first used there first,
 * then the decoder's order), the user's own words not used there (by when they became theirs),
 * the rest as the decoder had them, and imported words after the last of those whose keys are
 * exactly the code's (after them all if none is), in the order of their import (D24). Last of all, the pin of the code, if among the items, goes in front: the
 * others keep their order and tiers, a place down.
 *
 * Across the spellings of the code's keyboard (U-20, D32 B; [SpelledForm]): a word used at least
 * [FULL_USES] times spelt in full, not used under the code, goes up to [LIFTED_TO] under its 简拼 or
 * a part of it; one corrected to under a 简拼 is no lower than [FLOOR] spelt in full or in part.
 * Each only moves a word up, after the pin: the floor right behind it, the lift a place down as
 * every other word, and after the floored; one already higher stays; each at or below its place shows
 * [Tier.FLOORED] or [Tier.LIFTED], which `kept` holds too, but for one used here or of the user's own.
 * None is put first so.
 *
 * Text kept as letters (U-27) is never put first, by 1 or 2; V, it is put after only by 1 (the
 * user corrected it twice there), not by 2.
 */
object HabitRanker {
    /** Why an item is where it is: the order of the tiers is the order of the list, but for V's. */
    enum class Tier { PINNED, FIRST_HERE, ACROSS, USED, FLOORED, LIFTED, MINE, DICTIONARY, IMPORTED }

    /** Corrected to this often under a code, a word used there more than V goes first after any word before it. */
    const val CORRECTIONS = 2

    /** Used this often spelt in full, a word goes up under its 简拼 (U-20): the same two as a word of the user's own. */
    const val FULL_USES = 2

    /** The highest place (from 0) a word used spelt in full goes up to under its 简拼: third. */
    const val LIFTED_TO = 2

    /** The lowest place (from 0) of a word corrected to under its 简拼, spelt in full or in part: second. */
    const val FLOOR = 1

    /**
     * A candidate as ranking sees it: [words], read so; [single] one Han character; [letters] text
     * kept as letters; [imported] a word only an import has; [injected] put in by the session, the
     * decoder not having it; [exact] its keys are the code's, spelt out (an import's place).
     */
    class Item<T>(
        val value: T,
        val words: Words,
        val single: Boolean = false,
        val letters: Boolean = false,
        val imported: Boolean = false,
        val injected: Boolean = false,
        val exact: Boolean = false,
    )

    /** [original]: V, the decoder's own first, wherever it was ranked (F2739). */
    class Ranked<T>(val item: Item<T>, val tier: Tier, val uses: Int, val original: Boolean = false) {
        val value get() = item.value
        override fun toString() = "${item.words.text}:$tier${if (tier == Tier.USED) "($uses)" else ""}"
    }

    /** What ranking reads of the habits ([Habits] has them all but [mineOrder]'s words added by hand). */
    interface View {
        fun pinned(code: Code): Words?
        fun first(code: Code, prev: String): Words?
        fun uses(code: Code, words: Words): Int
        fun corrections(code: Code, words: Words): Int
        fun firstUse(code: Code, words: Words): Long

        /** Where [text] goes among the user's own words, or -1 if it is none of theirs. */
        fun mineOrder(text: String): Long

        /** Where [text] goes among the imported words (D24: its batch, then its place there); -1 for none. */
        fun importOrder(text: String): Long = -1L

        /** [words] across the spellings of the keyboard [kind] ([Habits.spelled]). */
        fun spelled(kind: String, words: Words): Habits.Spelled = Habits.Spelled.NONE
    }

    /** [items] of [code], after [prev], as shown. */
    fun <T> rank(items: List<Item<T>>, code: Code, prev: String, view: View): List<Ranked<T>> {
        if (items.isEmpty()) return emptyList()
        val uses = uses(items, code, view)
        val tiers = tiers(items, view, uses)
        val order = order(items, code, view, uses, tiers)
        val v = items.indices.firstOrNull { !items[it].injected && !items[it].imported }
        val first = first(items, code, prev, view, uses, order, v)
        val ranked = ArrayList<Ranked<T>>(items.size)
        if (first != null) ranked += Ranked(items[first.first], first.second ?: tiers[first.first], uses[first.first], first.first == v)
        for (i in order) if (i != first?.first) ranked += Ranked(items[i], tiers[i], uses[i], i == v)
        // the pin in front, the rest as they are (M4)
        val pin = view.pinned(code)?.let { p -> ranked.indexOfFirst { matches(it.item.words, p) } } ?: -1
        if (pin >= 0) {
            val pinned = ranked.removeAt(pin)
            ranked.add(0, Ranked(pinned.item, Tier.PINNED, pinned.uses, pinned.original))
        }
        spellings(ranked, code, view)
        return ranked
    }

    // U-20 across the keyboard's spellings: those corrected to under a 简拼 to FLOOR (right after a
    // pin), then those used spelt in full to LIFTED_TO and on, a place down behind a pin as every
    // other word is, never in front of a floored one (R14 F3732, F3742); each only up, by its counts
    private fun <T> spellings(ranked: MutableList<Ranked<T>>, code: Code, view: View) {
        if (ranked.size <= FLOOR) return
        val floor = ArrayList<Pair<Ranked<T>, Int>>()
        val lift = ArrayList<Pair<Ranked<T>, Int>>()
        for (r in ranked.filter { !it.item.letters && it.tier != Tier.PINNED }) {
            val form = SpelledForm.of(code.kind, code.keys, r.item.words.reading) ?: continue
            val s = view.spelled(code.kind, r.item.words)
            if (form != SpelledForm.INITIALS && s.initialsCorrections > 0) floor += r to s.initialsCorrections
            else if (form != SpelledForm.FULL && r.tier != Tier.USED && s.fullUses >= FULL_USES) lift += r to s.fullUses
        }
        val floored = raise(ranked, floor, FLOOR, Tier.FLOORED)
        val pinned = if (ranked[0].tier == Tier.PINNED) 1 else 0
        raise(ranked, lift, maxOf(LIFTED_TO + pinned, floored), Tier.LIFTED)
    }

    // [words] up to [to] and on, the next place after it; gives the place after the last. One
    // already at [to] or ahead stays, and those moved go after it (docs: 原来就在前三的不动, R14 F3749);
    // those moved, by most counts first, and one already at its place below the user's own tiers
    // take [tier]: what puts or keeps them there (F3736, F3762, F3771); one higher keeps its own
    private fun <T> raise(ranked: MutableList<Ranked<T>>, words: List<Pair<Ranked<T>, Int>>, to: Int, tier: Tier): Int {
        var at = to
        val placed = words.map { (r, n) -> Triple(r, n, ranked.indexOf(r)) }
        for (p in placed.filter { it.third <= to }.sortedBy { it.third }) if (p.third == at) {
            // one of the user's (used here, their word) has a truer reason, and kept holds it already (F3771)
            if (p.first.tier > Tier.MINE) ranked[at] = Ranked(p.first.item, tier, p.first.uses, p.first.original)
            at++
        }
        for ((r, _, _) in placed.filter { it.third > to }.sortedByDescending { it.second }) {
            val i = ranked.indexOf(r)
            if (i < at) continue
            ranked.removeAt(i)
            ranked.add(at, Ranked(r.item, tier, r.uses, r.original))
            at++
        }
        return at
    }

    /**
     * [items] of [code] in the order of rule 4 alone, none put first nor pinned: 「首选以外」, as the
     * words from outside a table are among themselves (U-28).
     */
    fun <T> rest(items: List<Item<T>>, code: Code, view: View): List<Ranked<T>> {
        val uses = uses(items, code, view)
        val tiers = tiers(items, view, uses)
        return order(items, code, view, uses, tiers).map { Ranked(items[it], tiers[it], uses[it]) }
    }

    private fun <T> uses(items: List<Item<T>>, code: Code, view: View) = IntArray(items.size) { view.uses(code, items[it].words) }

    private fun <T> tiers(items: List<Item<T>>, view: View, uses: IntArray) = Array(items.size) { i ->
        when {
            uses[i] > 0 -> Tier.USED
            view.mineOrder(items[i].words.text) >= 0 -> Tier.MINE
            items[i].imported -> Tier.IMPORTED
            else -> Tier.DICTIONARY
        }
    }

    // the item first by rules 1 to 3, with the tier it shows if not its own; null for none
    private fun <T> first(items: List<Item<T>>, code: Code, prev: String, view: View, uses: IntArray, order: List<Int>, v: Int?): Pair<Int, Tier?>? {
        view.first(code, prev)?.let { f ->
            items.indices.firstOrNull { !items[it].letters && matches(items[it].words, f) }?.let { return it to Tier.FIRST_HERE }
        }
        // V kept as letters stays first but for the user's first here: the order typed (U-27)
        if (v != null && !items[v].single && !items[v].letters) {
            val across = order.firstOrNull { w ->
                val item = items[w]
                !item.single && !item.letters && uses[w] > uses[v] && view.corrections(code, item.words) >= CORRECTIONS
            }
            if (across != null) return across to Tier.ACROSS
        }
        return v?.let { it to null }
    }

    // rule 4: used, by uses then first use there, then the user's own, then the rest; an import after the last word spelt as the code
    private fun <T> order(items: List<Item<T>>, code: Code, view: View, uses: IntArray, tiers: Array<Tier>): List<Int> {
        val firstUse = { i: Int -> view.firstUse(code, items[i].words).takeIf { it > 0 } ?: Long.MAX_VALUE }
        val used = items.indices.filter { tiers[it] == Tier.USED }.sortedWith(compareByDescending<Int> { uses[it] }.thenBy(firstUse).thenBy { it })
        // each item's key looked up once, not at each comparison (ImportedWords.order makes a list): R11 F2990
        val mine = items.indices.filter { tiers[it] == Tier.MINE }.map { it to view.mineOrder(items[it].words.text) }
            .sortedWith(compareBy<Pair<Int, Long>> { it.second }.thenBy { it.first }).map { it.first }
        val dictionary = items.indices.filter { tiers[it] == Tier.DICTIONARY }
        val imported = items.indices.filter { tiers[it] == Tier.IMPORTED }.map { it to view.importOrder(items[it].words.text) }
            .sortedWith(compareBy<Pair<Int, Long>> { it.second }.thenBy { it.first }).map { it.first }
        // none spelt as the code: after them all, never before the dictionary's words (review-1b N1)
        val at = dictionary.indexOfLast { items[it].exact }.let { if (it < 0) dictionary.size else it + 1 }
        return used + mine + dictionary.subList(0, at) + imported + dictionary.subList(at, dictionary.size)
    }

    /** Whether [item] is [habit]: the same words, or, a habit of no reading (a pin kept from an older build), the same text. */
    fun matches(item: Words, habit: Words) = item.text == habit.text && (habit.reading.isEmpty() || item.reading == habit.reading)
}
