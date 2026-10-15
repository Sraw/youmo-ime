/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import kotlin.math.log10

/**
 * A table input method (五笔, 仓颉 ...): the candidates are the entries whose code starts with
 * what was typed, ranked as libime ranks them. Codes no longer than [TableOptions.noSortInputLength]
 * (nor than what was typed) come first in the table's order; then the others, shorter codes
 * first ([TableOptions.sortByCodeLength]), and among codes as long what was picked most
 * ([TableOptions.orderByUse]). A text shows once, with its shortest code.
 *
 * With [TableOptions.autoSelect], typing commits by itself, as libime does: a code that leaves
 * one candidate, its code whole, commits it; a key past the longest code, or one that leads to no
 * entry, commits the first candidate on the page shown and starts a new code, or goes to the app
 * when no code starts with it (a table's punctuation). A code with no candidate is dropped by the
 * key that ends it, as 五笔 users expect of 空码. A space right after a code committed itself, within
 * a second of it, is taken, not typed: whether a code is the only one is not shown (the shared words
 * have codes too), and the space a user types to commit it either way made a gap only after the
 * lone ones. One that comes later is typed: a pause to read, then a space meant as one.
 *
 * [TableOptions.pinyinKey] starting a code hands the keys after it to [pinyin], and its
 * candidates show their code here: the way to type a character whose code one does not know.
 * A table with pinyin entries of its own ([TableDictionary.pinyinMarker], 五笔拼音) mixes them in
 * instead, as libime does: any letter is typed, a code has no length limit, and the entries it
 * spells are sorted with the codes past those that come first, after a code as long and never
 * first, showing their code. One left alone waits to be picked.
 *
 * A key of [TableOptions.endKeys] ends a code: the next key commits it. A key of
 * [TableOptions.selectionKeys] picks while there are candidates, a code key too, as in fcitx.
 *
 * Characters committed one by one become phrases ([TableOptions.autoPhraseLength]) coded by the
 * table's 组词规则, offered last for their code. One picked, or typed character by character
 * [TableOptions.saveAutoPhraseAfter] times, joins the table's own. What is picked and learned goes
 * to [user], which may keep it, and forgets it when asked to ([Action.Forget]).
 *
 * With a [language], a table has what every Chinese input method has (specs/engine.md). A code's
 * 重码, the candidates whose code is all that was typed, longer than [TableOptions.noSortInputLength]
 * (五笔's 一二级简码 stay as they are), are placed where the table orders by use
 * ([TableOptions.orderByUse]: off, the table's order is kept for those who type by position): the
 * user's words first (a word of the other input methods once picked here), then the table's entries by the word model after the text before, each pick
 * here adding to it (log10 of 1 + picks); the small
 * sentence model weighs them again (性能档位 medium), the large one while the user pauses (high,
 * [Action.Refine]). What is put in is learned by the one user model as well, after the word before
 * it, and what may follow is offered (联想) until the next key.
 */
class TableSession(
    private val table: TableDictionary,
    private val options: TableOptions = TableOptions(),
    private val pinyin: Session? = null,
    private val user: TableUser = TableUser(table),
    private val shared: SharedWords = SharedWords.NONE,
    private val language: TableLanguage? = null,
    /** Milliseconds on a clock that does not jump: how soon after a code committed itself a space comes. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : Session {

    /**
     * A candidate: a table entry (its [index]), or a phrase learned here ([index] -1). A [pinyin]
     * entry's [code] is its pinyin, without the marker.
     */
    private class Item(val code: String, val text: String, val auto: Boolean, val index: Int = -1, val pinyin: Boolean = false)

    private val input = StringBuilder()
    private var ranking = Ranking.NONE
    private var page = 0

    // the pinyin lookup: on while the input starts with the pinyin key
    private var lookingUp = false
    private var lookedUp: Snapshot? = null

    override var learning = true

    // what was committed last, one character at a time, for auto phrases
    private val recent = ArrayList<String>()
    private val autoPhraseLength = if (options.autoPhraseLength < 0) table.maxLength else options.autoPhraseLength

    // when a code just committed itself ([now]): the next key, if a space soon enough, is taken (see the class)
    private var committedItselfAt: Long? = null

    // the text before the cursor, for the word and sentence models; the last word put in, as the
    // user model has it, for what is learned after it
    private var before = ""
    private var lastEntry: Entry? = null

    // what may follow what was put in, offered while nothing is typed
    private var predictions: List<Candidate> = emptyList()

    // the code's 重码 as placed ([rank]); those the refiner is still to weigh, and the slices it took
    private var homographs: Homographs? = null
    private var unrefined: Homographs? = null
    private var refined = 0

    // Enter between: the space is on a new line, typed
    override fun passedOn() {
        committedItselfAt = null
        before = ""
        lastEntry = null
    }

    override fun apply(action: Action): Snapshot {
        val taken = action is Action.Key && takesSpace(action.char)
        committedItselfAt = null
        // the order shown is the one to weigh: anything but a slice of the weighing changes it, or the page
        if (action != Action.Refine) unrefined = null
        if (taken) return snapshot()
        if (predictions.isNotEmpty()) {
            when (action) {
                is Action.Pick -> return pickPrediction(action.index)
                is Action.Select -> return if (action.index in 0 until options.pageSize) pickPrediction(page * options.pageSize + action.index) else snapshot()
                Action.NextPage -> return turnPredictions(page + 1)
                Action.PreviousPage -> return turnPredictions(page - 1)
                Action.Refine, Action.Ink -> return snapshot()
                // anything else ends the offer, and goes on as it would without one
                else -> unoffer()
            }
        }
        return dispatch(action)
    }

    /** Whether [c] is the space right after a code committed itself, soon enough to be the one typed to commit it. */
    private fun takesSpace(c: Char): Boolean {
        val at = committedItselfAt ?: return false
        return c == ' ' && now() - at <= SPACE_TAKEN_MS
    }

    // read: [apply] alone asks the clock, once for the key; one later than a second it hands on to
    // the app as any key no code starts. Asked here too, the two could fall either side of the second
    private fun mayTakeSpace(c: Char) = c == ' ' && committedItselfAt != null

    private fun dispatch(action: Action): Snapshot = if (lookingUp) lookUp(action) else when (action) {
        is Action.Key -> type(action.char)
        Action.Backspace -> backspace()
        is Action.Select -> select(action.index)
        is Action.Pick -> pick(action.index)
        is Action.Forget -> forget(action.index)
        is Action.Block -> block(action.index)
        Action.NextPage -> turn(page + 1)
        Action.PreviousPage -> turn(page - 1)
        Action.CommitRaw -> {
            val text = input.toString()
            recent.clear()
            clear()
            wrote(text, null)
            snapshot(commit = text, handled = text.isNotEmpty())
        }
        Action.Refine -> refine()
        // a table has no custom phrases
        Action.Ink, is Action.Pin, is Action.Unpin, is Action.Syllable, is Action.Letter -> snapshot()
        is Action.Cursor -> snapshot(handled = false)
        // auto phrases are made of what was committed here, not of what the editor had: kept
        // only while the text still ends with it (the cursor did not go anywhere)
        Action.Reset, is Action.Context -> {
            if (action !is Action.Context || !action.before.endsWith(recent.joinToString(""))) recent.clear()
            moved((action as? Action.Context)?.before)
            clear()
            snapshot()
        }
    }

    // a lookup's pinyin has no phrases of the table's own to pin to
    override fun offers(index: Int): Set<Offer> = when {
        // not where nothing is to be kept (a password): forgetting and blocking record the word
        !learning || predictions.isNotEmpty() -> emptySet()
        lookingUp -> pinyin!!.offers(index).intersect(setOf(Offer.FORGET))
        ranking[index] != null -> if (shared.blockable(ranking[index]!!.text)) setOf(Offer.FORGET, Offer.BLOCK) else setOf(Offer.FORGET)
        else -> emptySet()
    }

    override fun reads(c: Char): Boolean = if (mayTakeSpace(c)) {
        true
    } else if (lookingUp) {
        c in 'a'..'z' || c == '\''
    } else {
        isCode(c) ||
            (c == options.pinyinKey && pinyin != null && input.isEmpty()) ||
            (input.isNotEmpty() && c in options.selectionKeys)
    }

    private fun isCode(c: Char) = c in table.keys || c == options.matchingKey || (table.pinyinMarker != null && c in 'a'..'z')

    override fun candidates(from: Int, count: Int): List<Choice> {
        if (lookingUp) return pinyin!!.candidates(from, count).map { Choice(it.text, table.codeOf(it.text).orEmpty()) }
        if (predictions.isNotEmpty()) return predictions.drop(from).take(count).map { Choice(it.text) }
        val wild = wild()
        return ranking.page(from, count).map { Choice(it.text, if (options.hint) hint(it, wild) else "") }
    }

    private fun type(c: Char): Snapshot {
        val startsLookUp = c == options.pinyinKey && pinyin != null
        // a selection key picks while there are candidates, a key of the codes too, as in fcitx;
        // with none it goes on as any other key
        val selects = if (input.isNotEmpty() && ranking[0] != null) options.selectionKeys.indexOf(c) else -1
        if (selects >= 0) return select(selects)
        val isCode = isCode(c)
        if (!isCode && !(startsLookUp && input.isEmpty())) {
            // not a code key: what was typed is committed, then the key is the app's
            val text = if (input.isEmpty()) "" else autoCommit()
            recent.clear()
            wrote(c.toString(), null)
            return snapshot(commit = text, handled = false)
        }
        var commit = ""
        if (options.autoSelect && input.isNotEmpty() && endsCode(c)) commit = autoCommit()
        // after a code just committed too: 叫 by kkkk, then z looks the next one up
        if (startsLookUp && input.isEmpty()) {
            lookingUp = true
            input.append(c)
            return snapshot(commit = commit)
        }
        // a key no code starts is the app's, as fcitx hands it on: a table's , and . may be its
        // punctuation, typed after a code as well as alone
        if (input.isEmpty() && !leadsAnywhere(c.toString())) {
            recent.clear()
            wrote(c.toString(), null)
            return snapshot(commit = commit, handled = false)
        }
        input.append(c)
        update()
        val only = ranking.only()
        if (only != null && commitsItself(only)) {
            commit += take(only)
            committedItselfAt = now()
            predict()
        }
        return snapshot(commit = commit)
    }

    /**
     * Whether [c] cannot go on the code typed: it is full (pinyin has no length limit), ends
     * with an end key, or [c] would lead nowhere.
     */
    private fun endsCode(c: Char) =
        (table.pinyinMarker == null && input.length >= table.maxLength) ||
            input.last() in options.endKeys ||
            (reaches(options.noMatchAutoSelectLength) && !leadsAnywhere("$input$c"))

    /** Whether [item], the only candidate, is committed without asking. */
    private fun commitsItself(item: Item): Boolean {
        // an auto phrase waits to be picked, and a pinyin entry, shown for the code to learn; so
        // does a code found through the matching key
        if (!options.autoSelect || item.auto || item.pinyin) return false
        return item.code == input.toString() && input.length <= table.maxLength && reaches(options.autoSelectLength)
    }

    /** Whether what is typed is long enough for a length [limit] of [TableOptions]. */
    private fun reaches(limit: Int) = limit != 0 && (limit < 0 || input.length >= limit)

    /** Commits the first candidate on the page shown, unless it is an auto phrase: then, as with no candidate, the code is dropped. */
    private fun autoCommit(): String {
        val first = ranking[page * options.pageSize] ?: ranking[0]
        return take(first?.takeUnless { it.auto })
    }

    private fun backspace(): Snapshot {
        if (input.isEmpty()) {
            recent.clear()
            // the app's: what was before goes with it
            before = before.dropLast(if (before.length >= 2 && Character.isLowSurrogate(before.last())) 2 else 1)
            lastEntry = null
            return snapshot(handled = false)
        }
        input.setLength(input.length - 1)
        update()
        return snapshot()
    }

    private fun select(index: Int): Snapshot {
        if (input.isEmpty()) {
            recent.clear()
            lastEntry = null
            return snapshot(handled = false)
        }
        // space on a code with no candidate drops it
        if (ranking[0] == null) return snapshot(commit = take(null))
        // first: a selection key past the page would have candidates decoded beyond it
        if (index !in 0 until options.pageSize) return snapshot()
        val item = ranking[page * options.pageSize + index] ?: return snapshot()
        return offering(take(item))
    }

    private fun pick(index: Int): Snapshot {
        val item = ranking[index] ?: return snapshot(handled = input.isNotEmpty())
        return offering(take(item))
    }

    /** [text] put in, nothing typed after it: what may follow offered. */
    private fun offering(text: String): Snapshot {
        predict()
        return snapshot(commit = text)
    }

    private fun predict() {
        val p = language?.predictor
        page = 0
        predictions = if (p == null || !learning || input.isNotEmpty()) emptyList() else TextWords.previous(language.words.lastTwo(before)).let { (prev2, prev) -> p.predict(prev2, prev) }
    }

    private fun unoffer() {
        predictions = emptyList()
        page = 0
    }

    private fun pickPrediction(index: Int): Snapshot {
        val c = predictions.getOrNull(index) ?: return snapshot()
        // learned after the word before it, as pinyin learns a prediction: it comes up after that word again
        val entry = c.words.singleOrNull()?.let { w -> language?.predictor?.reading(w)?.let { Entry(c.text, it) } }
        if (learning && entry != null) language?.user?.learn(lastEntry, listOf(entry))
        wrote(c.text, entry)
        recent.clear()
        return offering(c.text)
    }

    private fun turnPredictions(to: Int): Snapshot {
        if (to >= 0 && to * options.pageSize < predictions.size) page = to
        return snapshot()
    }

    /** [text] went into the editor, [entry] its word as learned (or none): the text before goes on from it. */
    private fun wrote(text: String, entry: Entry?) {
        before = kept(before + text)
        lastEntry = entry
    }

    /** The text before the cursor is [to] (none: reset): the last word put in is still the one before it only if it ends so. */
    private fun moved(to: String?) {
        if (to == null || lastEntry?.let { to.endsWith(it.text) } != true) lastEntry = null
        before = if (to == null) "" else kept(to)
    }

    // the text before the cursor as kept: as much as a sentence model reads (not from the middle of
    // a pair), only the last words where nothing is learned
    private fun kept(text: String) = if (learning) PinyinSession.tail(text) else text.takeLast(WORDS_KEPT).let {
        if (it.isNotEmpty() && Character.isLowSurrogate(it[0])) it.substring(1) else it
    }

    /** Forgets what was learned of the candidate at [index], ranking the code again on the page shown. */
    private fun forget(index: Int): Snapshot {
        // as offered: nothing recorded where nothing is to be kept
        if (!learning) return snapshot()
        val item = ranking[index] ?: return snapshot(handled = input.isNotEmpty())
        user.forget(item.code, item.text)
        // forgotten by every input method: a phrase saved here or a word of theirs would be back
        // as theirs, and what was put in here they learned too (the one user model)
        if (!item.pinyin && !item.auto) shared.forget(item.text)
        val shown = page
        update()
        if (ranking[shown * options.pageSize] != null) page = shown
        // weighed again only on the first page, as pinyin: not to move what is shown
        if (page != 0) unrefined = null
        return snapshot()
    }

    /** Blocks the candidate at [index] for every input method (see [SharedWords]), ranking the code again. */
    private fun block(index: Int): Snapshot {
        if (!learning) return snapshot()
        val item = ranking[index] ?: return snapshot(handled = input.isNotEmpty())
        if (!shared.block(item.text)) return snapshot()
        val shown = page
        update()
        if (ranking[shown * options.pageSize] != null) page = shown
        // weighed again only on the first page, as pinyin: not to move what is shown
        if (page != 0) unrefined = null
        return snapshot()
    }

    private fun turn(to: Int): Snapshot {
        // a code on screen keeps the key, candidates or not
        if (to < 0 || ranking[to * options.pageSize] == null) return snapshot(handled = input.isNotEmpty())
        page = to
        return snapshot()
    }

    /** Commits [item], learning from it, and clears the input; a code with none is dropped. */
    private fun take(item: Item?): String {
        clear()
        if (item == null) {
            recent.clear()
            return ""
        }
        // what pinyin found is neither counted nor built into phrases: libime counts it in its
        // history, but builds phrases from it with the pinyin as the code, which is a bug
        if (!learning || item.pinyin) {
            recent.clear()
            wrote(item.text, null)
            return item.text
        }
        val entry = language?.reading?.invoke(item.text)?.let { Entry(item.text, it) }
        if (entry != null) language.user?.learn(lastEntry, listOf(entry))
        wrote(item.text, entry)
        user.picked(item.code, item.text)
        // picked once, an auto phrase is the user's
        if (item.auto) user.save(item.code, item.text)
        learnPhrases(item.text)
        return item.text
    }

    /** Offers the last characters committed one by one, [text] ending them, as phrases. */
    private fun learnPhrases(text: String) {
        if (autoPhraseLength < 2 || table.rules.isEmpty) return
        val chars = PhraseRules.codePoints(text)
        // a phrase committed whole says nothing of what the user builds (fcitx's AutoPhraseWithPhrase)
        if (chars.size != 1) {
            recent.clear()
            return
        }
        recent += chars[0]
        while (recent.size > autoPhraseLength) recent.removeAt(0)
        for (n in 2..recent.size) sighted(recent.subList(recent.size - n, recent.size).joinToString(""))
    }

    /** Counts [phrase] typed once more, saving it when seen often enough. */
    private fun sighted(phrase: String) {
        val code = table.encode(phrase) ?: return
        if (table.contains(code, phrase)) return
        user.sighted(code, phrase, options.saveAutoPhraseAfter)
    }

    private fun clear() {
        input.setLength(0)
        ranking = Ranking.NONE
        homographs = null
        page = 0
    }

    /** Whether some entry, learned ones too, has a code [pattern] leads to. */
    private fun leadsAnywhere(pattern: String) =
        table.hasMatch(pattern, options.matchingKey) || user.leadsAnywhere(pattern) || shared.leadsAnywhere(pattern)

    private fun update() {
        page = 0
        homographs = null
        ranking = if (input.isEmpty()) Ranking.NONE else rank(input.toString())
        refined = 0
        unrefined = homographs?.takeIf { language?.refiner != null && it.texts.size > 1 }
    }

    /**
     * The candidates of [code], ranked; decoded as they are asked for. The user's words of the
     * other input methods ([shared]) after the table's own as long; none blocked.
     */
    private fun rank(code: String): Ranking {
        val coded = table.match(code, options.matchingKey)
        val spelt = table.matchPinyin(code, prefix = pinyinPrefix(code))
        // the pinyin entries last, so a code and its pinyin as long rank the code first
        val entries = if (spelt.isEmpty()) coded else coded + spelt
        // learned phrases are found by their plain code; the matching key is for the table's
        val learned = user.saved(code).map { (c, text) -> Item(c, text, false) }
        val others = shared.words(code).filterNot { (c, text) -> user.isSaved(c, text) }.map { (c, text) -> Item(c, text, false) }
        val noSort = minOf(code.length, options.noSortInputLength)
        val sharedFrom = learned.size + entries.size
        fun item(at: Int): Item? = when {
            at < learned.size -> learned[at]
            at >= sharedFrom -> others[at - sharedFrom]
            else -> null
        }
        val pinyinFrom = learned.size + coded.size
        fun spelt(at: Int) = at in pinyinFrom until sharedFrom
        // one sortable number per candidate: its group, length, picks, then where it was
        val keys = LongArray(sharedFrom + others.size) { at ->
            val extra = item(at)
            if (extra != null) key(at, extra.code.length, noSort, false) { user.picks(extra.code, extra.text) }
            else entries[at - learned.size].let { e -> key(at, table.codeLength(e) - (if (spelt(at)) 1 else 0), noSort, spelt(at)) { user.picks(e) } }
        }
        keys.sort()
        val order = IntArray(keys.size) { (keys[it] and POSITION_MASK).toInt() }
        ownFirst(order, ::spelt)
        // the most recently seen first
        val auto = user.seen(code).map { (c, text) -> Item(c, text, true) }
        val made = {
            Ranking(shared::blocked) { at ->
                when {
                    at < order.size -> order[at].let { item(it) ?: entryItem(entries[it - learned.size]) }
                    else -> auto.getOrNull(at - order.size)
                }
            }
        }
        // the 重码: the candidates (at [order]'s positions) of the whole code typed, past those kept in order
        if (code.length <= noSort) return made()
        return weighed(code, order, { k -> kindOf(order[k], code.length, item(order[k]), order[k] >= sharedFrom, spelt(order[k])) { entries[it - learned.size] } }, made)
    }

    /**
     * What candidate [i] of a code [length] long is among its 重码: [extra] the user's (one of the
     * other input methods' if [fromShared]: theirs here once picked here, as the user chose), else
     * table entry [entry] of it.
     */
    private fun kindOf(i: Int, length: Int, extra: Item?, fromShared: Boolean, spelt: Boolean, entry: (Int) -> Int): Int = when {
        spelt -> OTHER
        extra != null -> if (extra.code.length == length && (!fromShared || user.picks(extra.code, extra.text) > 0)) USERS else OTHER
        else -> entry(i).let { e -> if (table.codeLength(e) == length) e else OTHER }
    }

    /**
     * [made] with the 重码 of [code] in [order] placed ([kind] of each position: a table entry, [USERS]
     * for the user's words, or [OTHER]): the user's first, as they were, then the table's by the word
     * and sentence models, a blocked one left where it is (it is not shown). In the table's order
     * where it does not order by use (one setting: 常用的字词排前面), or a wildcard was typed.
     */
    private fun weighed(code: String, order: IntArray, kind: (Int) -> Int, made: () -> Ranking): Ranking {
        val language = language?.takeIf { options.orderByUse } ?: return made()
        if (options.matchingKey != null && code.contains(options.matchingKey)) return made()
        val kinds = IntArray(order.size, kind)
        val users = order.indices.filter { kinds[it] == USERS }
        val ranked = order.indices.filter { kinds[it] >= 0 && !shared.blocked(table.text(kinds[it])) }
        if (ranked.size < 2 && users.isEmpty()) return made()
        val (prev2, prev) = TextWords.previous(language.words.lastTwo(before))
        val texts = ranked.map { table.text(kinds[it]) }
        // each pick here counting as use, as the table ordered by use before: the user model's counts
        // alone move a pick too slowly for those who expect it forward next time
        val scores = FloatArray(ranked.size) { k -> language.words.logProb(texts[k], prev2, prev) + log10(1f + user.picks(kinds[ranked[k]])) }
        // by score, the table's order among equals
        val byScore = ranked.indices.sortedByDescending { scores[it] }
        val h = Homographs(
            order, (users + ranked).sorted().toIntArray(), users.map { order[it] }.toIntArray(),
            byScore.map { order[ranked[it]] }.toIntArray(), byScore.map { texts[it] }, byScore.map { scores[it] }, made,
        )
        homographs = h
        return h.place(if (texts.size > 1) language.reranker?.pick(context(), h.texts, h.scores) ?: 0 else 0)
    }

    /**
     * A code's 重码 in [order]: at the positions [at], the user's words [users] (order's values) first,
     * then [entries] by the word model, their [texts] and [scores] so; [place] puts one of the
     * entries first among them, the rest as ranked.
     */
    private class Homographs(
        private val order: IntArray,
        private val at: IntArray,
        private val users: IntArray,
        private val entries: IntArray,
        val texts: List<String>,
        val scores: List<Float>,
        private val made: () -> Ranking,
    ) {
        fun place(first: Int): Ranking {
            var k = 0
            for (u in users) order[at[k++]] = u
            if (entries.isNotEmpty()) order[at[k++]] = entries[first]
            for (j in entries.indices) if (j != first) order[at[k++]] = entries[j]
            return made()
        }
    }

    // what the sentence models read on from: none where nothing is learned (a password)
    private fun context() = if (learning) before else ""

    private fun refine(): Snapshot {
        val h = unrefined ?: return snapshot()
        val refiner = language?.refiner
        if (refiner == null || refined++ >= refiner.slices) {
            unrefined = null
            return snapshot()
        }
        val picked = refiner.refine(context(), h.texts, h.scores, REFINE_BUDGET) ?: return snapshot()
        unrefined = null
        if (picked != SentenceRefiner.NONE) ranking = h.place(picked)
        return snapshot()
    }

    /** A candidate's sortable number: its group, length, picks (asked for only when they count), then [at]. */
    private fun key(at: Int, length: Int, noSort: Int, isPinyin: Boolean, picks: () -> Int): Long {
        val sorted = length > noSort || isPinyin
        val used = if (sorted && options.orderByUse) picks() else 0
        return (if (sorted) 1L shl GROUP_SHIFT else 0L) or
            ((if (sorted && options.sortByCodeLength) minOf(length, MAX_LENGTH) else 0).toLong() shl LENGTH_SHIFT) or
            ((MAX_PICKS - minOf(used, MAX_PICKS)).toLong() shl PICKS_SHIFT) or
            at.toLong()
    }

    /** A code's own candidate first, if it has one (libime's rule): before what pinyin [spelt]. */
    private fun ownFirst(order: IntArray, spelt: (Int) -> Boolean) {
        if (order.isEmpty() || !spelt(order[0])) return
        val own = order.indexOfFirst { !spelt(it) }
        if (own > 0) {
            val first = order[own]
            System.arraycopy(order, 0, order, 1, own)
            order[0] = first
        }
    }

    private fun entryItem(index: Int) = if (table.isPinyin(index)) {
        Item(table.code(index).substring(1), table.text(index), false, index, pinyin = true)
    } else {
        Item(table.code(index), table.text(index), false, index)
    }

    /**
     * Whether pinyin entries the typed [code] starts are candidates too, not only those it
     * spells: libime's heuristic, which with every preset's lengths means from the second key.
     */
    private fun pinyinPrefix(code: String): Boolean {
        val n = code.length
        return n > 1 && (n >= options.autoSelectLength || n > table.maxLength || n >= options.noMatchAutoSelectLength)
    }

    /**
     * Ranked candidates made as needed, each text once: the first of it, which is its shortest
     * code when codes are sorted by length. Unsorted, the first stays (libime would show the
     * shorter code in its place); no preset turns sorting off.
     */
    private class Ranking(private val blocked: (String) -> Boolean = { false }, private val source: (Int) -> Item?) {
        private val items = ArrayList<Item>()
        private val seen = HashSet<String>()
        private var next = 0
        private var done = false

        operator fun get(i: Int): Item? {
            while (items.size <= i && !done) {
                val item = source(next++)
                if (item == null) done = true else if (!blocked(item.text) && seen.add(item.text)) items += item
            }
            return items.getOrNull(i)
        }

        fun only(): Item? = if (get(1) == null) get(0) else null

        /** How many there are, -1 until all were made: a key's range may hold thousands. */
        val size: Int get() = if (done) items.size else -1

        fun page(from: Int, size: Int): List<Item> = (from until from + size).mapNotNull { get(it) }

        companion object {
            val NONE = Ranking { null }
        }
    }

    private fun snapshot(commit: String = "", handled: Boolean = true): Snapshot {
        if (predictions.isNotEmpty() && input.isEmpty() && !lookingUp) return predicted(commit, handled)
        val from = page * options.pageSize
        val shown = ranking.page(from, options.pageSize)
        val wild = wild()
        return Snapshot(
            commit = commit,
            preedit = input.toString(),
            candidates = shown.map { it.text },
            page = page,
            hasPreviousPage = page > 0,
            hasNextPage = ranking[from + options.pageSize] != null,
            handled = handled,
            predicting = false,
            hints = if (!options.hint || shown.isEmpty()) emptyList() else shown.map { hint(it, wild) },
            total = ranking.size,
            first = from,
            actionable = shown.isNotEmpty(),
            labels = options.selectionKeys,
            refines = unrefined != null,
        )
    }

    private fun predicted(commit: String, handled: Boolean): Snapshot {
        val from = page * options.pageSize
        return Snapshot(
            commit = commit,
            preedit = "",
            candidates = predictions.drop(from).take(options.pageSize).map { it.text },
            page = page,
            hasPreviousPage = page > 0,
            hasNextPage = from + options.pageSize < predictions.size,
            handled = handled,
            predicting = true,
            total = predictions.size,
            first = from,
        )
    }

    private fun wild() = options.matchingKey != null && input.contains(options.matchingKey)

    /**
     * What is left of [item]'s code to type; all of it where a wildcard stands in the input, and
     * for a pinyin entry the table code it could have been typed by.
     */
    private fun hint(item: Item, wild: Boolean) = when {
        item.pinyin -> table.codeOf(item.text).orEmpty()
        wild -> item.code
        else -> item.code.substring(input.length)
    }

    /** An action while looking up by pinyin: [pinyin] reads the keys after the pinyin key. */
    private fun lookUp(action: Action): Snapshot {
        val pinyin = pinyin!!
        return when (action) {
            is Action.Key -> if (action.char in 'a'..'z' || action.char == '\'') {
                val s = pinyin.apply(action)
                // a key it would not take (' first) is swallowed, not handed on: the app must
                // not get what the preedit does not show
                if (s.handled) {
                    input.append(action.char)
                    fromPinyin(s)
                } else {
                    lookedUp ?: snapshot()
                }
            } else {
                // what was looked up is committed, or else what was typed as Enter has it; then
                // the key is the app's
                var text = if (input.length > 1) pinyin.apply(Action.Select(0)).commit else ""
                if (text.isEmpty()) text = typedInLookUp(pinyin)
                endLookUp()
                // then the key, the app's
                wrote(text + action.char, null)
                snapshot(commit = text, handled = false)
            }
            Action.Backspace -> if (input.length > 1) {
                input.setLength(input.length - 1)
                fromPinyin(pinyin.apply(action))
            } else {
                endLookUp()
                snapshot()
            }
            Action.CommitRaw -> {
                val text = typedInLookUp(pinyin)
                endLookUp()
                wrote(text, null)
                snapshot(commit = text)
            }
            Action.Reset, is Action.Context -> {
                endLookUp()
                moved((action as? Action.Context)?.before)
                snapshot()
            }
            // not offered (see offers): the lookup's pinyin has no phrases of the table's own
            is Action.Pin, is Action.Unpin, is Action.Block -> lookedUp ?: snapshot()
            // a tap in the preedit is fcitx's, as outside a lookup: it commits the preedit and resets
            is Action.Cursor -> (lookedUp ?: snapshot()).copy(handled = false)
            // the pinyin key alone: nothing to pick, and space drops it
            is Action.Select -> if (input.length > 1) fromPinyin(pinyin.apply(action)) else {
                endLookUp()
                snapshot()
            }
            else -> fromPinyin(pinyin.apply(action))
        }
    }

    /** What was typed in a lookup, as typed: the pinyin key too, unless a word was picked from the start of it. */
    private fun typedInLookUp(pinyin: Session): String {
        val raw = if (input.length > 1) pinyin.apply(Action.CommitRaw).commit else ""
        return if (raw == input.substring(1)) input.toString() else raw
    }

    private fun fromPinyin(s: Snapshot): Snapshot {
        if (s.commit.isNotEmpty()) {
            endLookUp()
            wrote(s.commit, null)
            return snapshot(commit = s.commit)
        }
        return s.copy(
            preedit = s.preedit.ifEmpty { input.toString() },
            // the lookup has the keys, whatever the pinyin session says of them
            handled = true,
            predicting = false,
            hints = s.candidates.map { table.codeOf(it).orEmpty() },
        ).also { lookedUp = it }
    }

    private fun endLookUp() {
        // no 联想 after a lookup, and no context carried into the next one
        pinyin?.apply(Action.Reset)
        lookingUp = false
        lookedUp = null
        // a looked-up character is not one typed by its code: no phrase from it
        recent.clear()
        clear()
    }

    companion object {
        // a ranking key: group, code length, picks (fewest last), position
        private const val GROUP_SHIFT = 62
        private const val LENGTH_SHIFT = 54
        private const val PICKS_SHIFT = 32
        private const val MAX_LENGTH = 0xFF
        private const val MAX_PICKS = 0x3FFFFF
        private const val POSITION_MASK = 0xFFFFFFFFL

        // a position's kind in a code's 重码 that is no table entry: the user's word, or none of them
        private const val USERS = -1
        private const val OTHER = -2

        // a space later than this after a code committed itself was typed on purpose, after a pause
        private const val SPACE_TAKEN_MS = 1000L

        // the text before the cursor kept where nothing is learned: the last words alone (else
        // as much as a sentence model reads, PinyinSession.tail)
        private const val WORDS_KEPT = 3 * TextWords.MAX_WORD

        // the refiner's work per Action.Refine: as pinyin's (PinyinSession.REFINE_BUDGET)
        private const val REFINE_BUDGET = 1
    }
}
