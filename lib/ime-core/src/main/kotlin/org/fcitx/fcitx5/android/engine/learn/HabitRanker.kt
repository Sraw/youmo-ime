/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

/**
 * Orders one code's candidates by the user's habits: values in, a list out, nothing kept. The session
 * gives a level's items (those ending where it ends) in the decoder's order, its injected ones
 * ([Item.injected]) last; [rank] returns them as shown, each with the [Tier] that put it there (shown
 * as the reason).
 *
 * V, the original first, is the first item neither injected nor imported (an imported word is never
 * first by the decoder alone). First is:
 * 1. the user's FIRST under (code, prev), if among the items;
 * 2. else, if V is not a single character, the first in rule 4's order used more than V under the code
 *    and corrected to at least [CORRECTIONS] times there (across previous words);
 * 3. else V.
 *
 * Then (rule 4): words used under the code (by uses, ties by first use there, then decoder order), the
 * user's own words not used there (by when they became theirs), the rest in decoder order, and imported
 * words after the last of those whose keys are exactly the code's (after all if none), in import order.
 * Finally the code's pin, if among the items, goes in front; the rest keep order and tiers.
 *
 * Across the keyboard's spellings ([SpelledForm]): a word used [FULL_USES]+ times spelt in full, not used
 * under this code, rises to [LIFTED_TO] under its 简拼 or a part of it; one corrected to under a 简拼 is no
 * lower than [FLOOR] spelt in full or in part. Both only raise a word, after the pin: the floor right
 * behind it, the lift a place down like every other word, and after floored ones; one already higher
 * stays. Each at or below its place shows [Tier.FLOORED] or [Tier.LIFTED] (`kept` too), unless used here
 * or the user's own. Neither puts a word first.
 *
 * Text kept as letters is never put first by rules 1 or 2; as V it is displaced only by rule 1, not 2.
 *
 * 双拼's typed words read by lone keys ([Item.alone]): up to [ALONE_WORDS], in the order above, go right
 * after the pin and a rule 1/2 first: before V where the code does not read whole (`ufmuh`: V is a
 * syllable reading, nearly never meant), after it where it does (`umuh`, `ky`) or the caller's gate is
 * open (a typed word reads the code as whole syllables with its last key begun: likely that word half
 * typed). They show [Tier.ALONE]; a floored word still goes right behind the pin, the typed words after.
 * No lone-key word is lifted, even past [ALONE_WORDS]: [spellings] does not raise such words.
 */
object HabitRanker {
    /** The order of tiers is the list's, except V (wherever [rank]'s first puts it) and ALONE (right before or after V, by how the code reads). */
    enum class Tier { PINNED, FIRST_HERE, ACROSS, ALONE, USED, FLOORED, LIFTED, MINE, DICTIONARY, IMPORTED }

    /** Corrected to this often under a code, a word used there more than V goes first after any previous word. */
    const val CORRECTIONS = 2

    /** Used this often spelt in full, a word rises under its 简拼: as many as make a word the user's own. */
    const val FULL_USES = 2

    /** The highest place (from 0) a word used spelt in full rises to under its 简拼: third. */
    const val LIFTED_TO = 2

    /** The lowest place (from 0) of a word corrected to under its 简拼, spelt in full or in part: second. */
    const val FLOOR = 1

    /** The most lone-key typed words ([Item.alone]) put up front under a code. */
    const val ALONE_WORDS = 3

    /**
     * A candidate as ranking sees it: [single] one Han character; [letters] text kept as letters; [imported]
     * only an import has it; [injected] the session added it, the decoder lacking it; [exact] its keys
     * spell out the code (an import's place); [alone] under 双拼, a user-typed word read as whole syllables
     * with two or more lone keys.
     */
    class Item<T>(
        val value: T,
        val words: Words,
        val single: Boolean = false,
        val letters: Boolean = false,
        val imported: Boolean = false,
        val injected: Boolean = false,
        val exact: Boolean = false,
        val alone: Boolean = false,
    )

    /** [original]: V, the decoder's own first, wherever it was ranked. */
    class Ranked<T>(val item: Item<T>, val tier: Tier, val uses: Int, val original: Boolean = false) {
        val value get() = item.value
        override fun toString() = "${item.words.text}:$tier${if (tier == Tier.USED) "($uses)" else ""}"
    }

    /** What ranking reads of the habits ([Habits] has all but [mineOrder]'s hand-added words). */
    interface View {
        fun pinned(code: Code): Words?
        fun first(code: Code, prev: String): Words?
        fun uses(code: Code, words: Words): Int
        fun corrections(code: Code, words: Words): Int
        fun firstUse(code: Code, words: Words): Long

        /** -1 if none of the user's. */
        fun mineOrder(text: String): Long

        /** By batch, then place there; -1 for none. */
        fun importOrder(text: String): Long = -1L

        /** See [Habits.spelled]. */
        fun spelled(kind: String, words: Words): Habits.Spelled = Habits.Spelled.NONE
    }

    /**
     * [items] of [code] after [prev], as shown. [readsWhole]: the code reads as whole syllables as typed, so
     * lone-key typed words go after V; else before it unless [gate]. The caller decides [gate] once over every
     * word the code reads, not the items read so far: else a list read on page by page would swap V and the
     * first typed word.
     */
    fun <T> rank(items: List<Item<T>>, code: Code, prev: String, view: View, readsWhole: Boolean = false, gate: Boolean = false): List<Ranked<T>> {
        if (items.isEmpty()) return emptyList()
        val uses = uses(items, code, view)
        val tiers = tiers(items, view, uses)
        val order = order(items, code, view, uses, tiers)
        val v = items.indices.firstOrNull { !items[it].injected && !items[it].imported }
        val first = first(items, code, prev, view, uses, order, v)
        val ranked = ArrayList<Ranked<T>>(items.size)
        if (first != null) ranked += Ranked(items[first.first], first.second ?: tiers[first.first], uses[first.first], first.first == v)
        for (i in order) if (i != first?.first) ranked += Ranked(items[i], tiers[i], uses[i], i == v)
        // the pin in front, the rest as they are
        val pin = view.pinned(code)?.let { p -> ranked.indexOfFirst { matches(it.item.words, p) } } ?: -1
        if (pin >= 0) {
            val pinned = ranked.removeAt(pin)
            ranked.add(0, Ranked(pinned.item, Tier.PINNED, pinned.uses, pinned.original))
        }
        alone(ranked, readsWhole || gate)
        spellings(ranked, code, view)
        return ranked
    }

    // lone-key typed words, up to ALONE_WORDS, right after the pin and a rule 1/2 first ([heads]): before the
    // next ([after] false: A1 V A2 A3) or after it (V A1 A2 A3), in order. If V is one of them it stays put and
    // the rest follow it: where the code reads whole the first is unchanged (自然码 `ky`: a typed 可以 that is
    // the decoder's first stays before 快)
    private fun <T> alone(ranked: MutableList<Ranked<T>>, after: Boolean) {
        val heads = ranked.indexOfFirst { it.tier > Tier.ACROSS }.let { if (it < 0) ranked.size else it }
        val alone = ranked.subList(heads, ranked.size).filter { it.item.alone }.take(ALONE_WORDS)
        if (alone.isEmpty()) return
        // V at heads (no rule 1/2 first before it) and one of them
        val vAlone = alone[0] === ranked[heads] && ranked[heads].original
        ranked.removeAll(alone.toSet())
        val words = alone.map { Ranked(it.item, Tier.ALONE, it.uses, it.original) }
        // where the code does not read whole only the first goes before the next, the rest after it
        if (!after && heads < ranked.size) {
            ranked.add(heads, words[0])
            ranked.addAll(heads + 2, words.drop(1))
        } else {
            ranked.addAll(if (vAlone) heads else minOf(heads + 1, ranked.size), words)
        }
    }

    // across the keyboard's spellings: those corrected to under a 简拼 to FLOOR (right after a pin), then those
    // used spelt in full to LIFTED_TO and on, a place down behind a pin like every word, never ahead of a
    // floored one; each only up, by counts
    private fun <T> spellings(ranked: MutableList<Ranked<T>>, code: Code, view: View) {
        if (ranked.size <= FLOOR) return
        val floor = ArrayList<Pair<Ranked<T>, Int>>()
        val lift = ArrayList<Pair<Ranked<T>, Int>>()
        for (r in ranked.filter { !it.item.letters && it.tier != Tier.PINNED }) {
            val form = SpelledForm.of(code.kind, code.keys, r.item.words.reading) ?: continue
            val s = view.spelled(code.kind, r.item.words)
            // a lone-key typed word is ranked as one: up front, or past ALONE_WORDS where it was
            val upFront = r.tier == Tier.USED || r.item.alone
            if (form != SpelledForm.INITIALS && s.initialsCorrections > 0) floor += r to s.initialsCorrections
            else if (form != SpelledForm.FULL && !upFront && s.fullUses >= FULL_USES) lift += r to s.fullUses
        }
        val floored = raise(ranked, floor, FLOOR, Tier.FLOORED)
        val pinned = if (ranked[0].tier == Tier.PINNED) 1 else 0
        raise(ranked, lift, maxOf(LIFTED_TO + pinned, floored), Tier.LIFTED)
    }

    // moves [words] to [to] and on, returning the place after the last. One already at [to] or ahead stays
    // (原来就在前三的不动) and the moved go after it, most counts first; moved ones, and ones already at their
    // place below the user's own tiers, take [tier]; higher ones keep their own
    private fun <T> raise(ranked: MutableList<Ranked<T>>, words: List<Pair<Ranked<T>, Int>>, to: Int, tier: Tier): Int {
        var at = to
        val placed = words.map { (r, n) -> Triple(r, n, ranked.indexOf(r)) }
        for (p in placed.filter { it.third <= to }.sortedBy { it.third }) if (p.third == at) {
            // a user's word (used here, their own) has a truer reason, already in kept
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

    /** [items] in rule 4's order alone, nothing put first or pinned: words from outside a table among themselves. */
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

    // the rule 1-3 first, with the tier it shows if not its own; null for none
    private fun <T> first(items: List<Item<T>>, code: Code, prev: String, view: View, uses: IntArray, order: List<Int>, v: Int?): Pair<Int, Tier?>? {
        view.first(code, prev)?.let { f ->
            items.indices.firstOrNull { !items[it].letters && matches(items[it].words, f) }?.let { return it to Tier.FIRST_HERE }
        }
        // V kept as letters stays first except for the user's first here: the order typed
        if (v != null && !items[v].single && !items[v].letters) {
            val across = order.firstOrNull { w ->
                val item = items[w]
                !item.single && !item.letters && uses[w] > uses[v] && view.corrections(code, item.words) >= CORRECTIONS
            }
            if (across != null) return across to Tier.ACROSS
        }
        return v?.let { it to null }
    }

    // rule 4: used (by uses, then first use), the user's own, the rest; imports after the last word spelt as the code
    private fun <T> order(items: List<Item<T>>, code: Code, view: View, uses: IntArray, tiers: Array<Tier>): List<Int> {
        val firstUse = { i: Int -> view.firstUse(code, items[i].words).takeIf { it > 0 } ?: Long.MAX_VALUE }
        val used = items.indices.filter { tiers[it] == Tier.USED }.sortedWith(compareByDescending<Int> { uses[it] }.thenBy(firstUse).thenBy { it })
        // each item's key looked up once, not per comparison (ImportedWords.order makes a list)
        val mine = items.indices.filter { tiers[it] == Tier.MINE }.map { it to view.mineOrder(items[it].words.text) }
            .sortedWith(compareBy<Pair<Int, Long>> { it.second }.thenBy { it.first }).map { it.first }
        val dictionary = items.indices.filter { tiers[it] == Tier.DICTIONARY }
        val imported = items.indices.filter { tiers[it] == Tier.IMPORTED }.map { it to view.importOrder(items[it].words.text) }
            .sortedWith(compareBy<Pair<Int, Long>> { it.second }.thenBy { it.first }).map { it.first }
        // none spelt as the code: after them all, never before the dictionary's words
        val at = dictionary.indexOfLast { items[it].exact }.let { if (it < 0) dictionary.size else it + 1 }
        return used + mine + dictionary.subList(0, at) + imported + dictionary.subList(at, dictionary.size)
    }

    /** The same words, or the same text for a habit without a reading (a pin from an older build). */
    fun matches(item: Words, habit: Words) = item.text == habit.text && (habit.reading.isEmpty() || item.reading == habit.reading)
}
