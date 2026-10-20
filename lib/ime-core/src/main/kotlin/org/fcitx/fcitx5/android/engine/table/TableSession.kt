/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.phrase.EmojiCandidates
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Predictions
import org.fcitx.fcitx5.android.engine.session.RefineSlices
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
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
 * The code keeps a cursor, at its end till moved ([Action.Cursor], [Action.Step]): keys go in
 * there and backspace deletes before it. A code key typed before the end never commits what is
 * typed (the code is being mended), nor goes into a full code; the code whole still commits
 * itself when it is the only one. In a lookup the cursor is the pinyin's.
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
 * user's words once picked here first (a phrase saved as typed character by character, or a word of
 * the other input methods, stays where its picks put it till then: a pick of the table's goes
 * before it), then the table's entries by the word model after the text before, each pick here
 * adding to it (log10 of 1 + picks); the small sentence model weighs them again (性能档位 medium), the
 * large one while the user pauses (high, [Action.Refine]). What is put in is learned by the one
 * user model as well, after the word before it, a pinyin entry too; not what [pinyin] looks up,
 * which learns nothing (not how the user writes). What may follow is offered (联想) until the next
 * key; a long press on it forgets or blocks it, as handwriting's (Predictions): blocks only what
 * [SharedWords.blockable] allows, as for the table's own candidates.
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
     * A candidate: a table entry (its [index]), a phrase learned here ([index] -1), or an [emoji]
     * the first stands for. A [pinyin] entry's [code] is its pinyin, without the marker.
     */
    private class Item(
        val code: String,
        val text: String,
        val auto: Boolean,
        val index: Int = -1,
        val pinyin: Boolean = false,
        val emoji: Boolean = false,
    )

    private val input = StringBuilder()
    private var cursor = 0 // where in the code the next key goes; in a lookup unused, the pinyin's
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

    // what may follow what was put in, offered while nothing is typed; the text before the cursor,
    // for the word and sentence models, and the last word put in, for what is learned after it
    private val predictions = Predictions(
        language?.predictor, language?.words, language?.user, language?.block, language?.forgotten ?: {}, shared::blockable,
    ) { learning }

    private val emojiOf: (String) -> List<String> = language?.emoji ?: { emptyList() }
    private val phrasesOf: (String) -> List<String> = language?.phrases ?: { emptyList() }

    // the code's 重码 as placed ([rank]); those the refiner is still to weigh
    private var homographs: Homographs? = null
    private val unrefined = RefineSlices<Homographs>(language?.refiner)

    // Enter between: the space is on a new line, typed
    override fun passedOn() {
        committedItselfAt = null
        selfEmoji = emptyList()
        predictions.forgetBefore()
    }

    // a word that committed itself (no candidates were shown with its emoji) has them offered first
    // among what may follow (EmojiCandidates.pick); one picked takes the word's place: [selfCommitted]
    // deleted before it. Anything else but a page or a long press ends the offer, as it does 联想
    private var selfEmoji: List<String> = emptyList()
    private var selfCommitted = ""

    private fun offerEmojiOf(word: String) {
        selfEmoji = EmojiCandidates.pick(word, emojiOf, phrasesOf) { false }
        selfCommitted = word
    }

    private fun offering() = selfEmoji.isNotEmpty() || predictions.offered.isNotEmpty()

    private fun offeredTexts() = selfEmoji + predictions.offered.map { it.text }

    // the offer at [index] of all: an emoji in place of the word, or what may follow
    private fun pickOffered(index: Int): Snapshot {
        val n = selfEmoji.size
        // a prediction picked: the text no longer ends with the word, the offer is over
        if (index >= n) {
            // none there: nothing picked, the offer goes on
            val text = predictions.pick(index - n) ?: return snapshot()
            selfEmoji = emptyList()
            recent.clear()
            page = 0
            return snapshot(commit = text)
        }
        val emoji = selfEmoji[index]
        // the app deletes code points (FcitxInputMethodService.handleDeleteSurrounding)
        val replaced = selfCommitted.codePointCount(0, selfCommitted.length)
        selfEmoji = emptyList()
        // the word is gone from the text: no auto phrase of it, nothing to follow it
        recent.clear()
        predictions.unoffer()
        predictions.forgetBefore()
        page = 0
        return snapshot(commit = emoji).copy(deleteBefore = replaced)
    }

    override fun apply(action: Action): Snapshot {
        val taken = action is Action.Key && takesSpace(action.char)
        committedItselfAt = null
        // the order shown is the one to weigh: anything but a slice of the weighing changes it, or the page
        if (action != Action.Refine && action !is Action.Cursor && action !is Action.Step) unrefined.drop()
        if (taken) return snapshot()
        if (offering()) {
            val n = selfEmoji.size
            when (action) {
                is Action.Pick -> return pickOffered(action.index)
                is Action.Select -> return if (action.index in 0 until options.pageSize) pickOffered(page * options.pageSize + action.index) else snapshot()
                Action.NextPage -> return turnPredictions(page + 1)
                Action.PreviousPage -> return turnPredictions(page - 1)
                Action.Refine, Action.Ink, Action.Glide -> return snapshot()
                // an emoji is no word: nothing to forget or block
                is Action.Forget -> return if (action.index < n) snapshot() else pressed(action.index - n, Offer.FORGET)
                is Action.Block -> return if (action.index < n) snapshot() else pressed(action.index - n, Offer.BLOCK)
                // anything else ends the offer, and goes on as it would without one: its page too
                else -> {
                    predictions.unoffer()
                    selfEmoji = emptyList()
                    page = 0
                }
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
            // nothing typed: Enter is the app's, and what is before goes when it is passed on (passedOn), as handwriting's
            if (text.isNotEmpty()) predictions.wrote(text, null)
            snapshot(commit = text, handled = text.isNotEmpty())
        }
        Action.Refine -> refine()
        // a table has no custom phrases
        Action.Ink, Action.Glide, is Action.Pin, is Action.Unpin, is Action.Syllable, is Action.Letter, is Action.NineKey -> snapshot()
        is Action.Cursor -> moveCursor(action.at)
        is Action.Step -> moveCursor(action.applyTo(cursor, input.length))
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
        !learning -> emptySet()
        offering() -> if (index < selfEmoji.size) emptySet() else predictions.offers(index - selfEmoji.size)
        lookingUp -> pinyin!!.offers(index).intersect(setOf(Offer.FORGET))
        // an emoji is no word: nothing learned of it to forget, nor to block
        ranking[index]?.emoji == true -> emptySet()
        ranking[index] != null -> {
            val item = ranking[index]!!
            // forgetting only where something was learned of it, as pinyin offers it (PY-33)
            val learned = user.hasLearned(item.code, item.text) || (!item.pinyin && !item.auto && shared.learned(item.text))
            setOfNotNull(Offer.FORGET.takeIf { learned }, Offer.BLOCK.takeIf { shared.blockable(item.text) })
        }
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
        if (offering()) return offeredTexts().drop(from).take(count).map { Choice(it) }
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
            predictions.wrote(c.toString(), null)
            return snapshot(commit = text, handled = false)
        }
        var commit = ""
        if (options.autoSelect && typingAtEnd() && endsCode(c)) commit = autoCommit()
        // after a code just committed too: 叫 by kkkk, then z looks the next one up
        if (startsLookUp && input.isEmpty()) {
            lookingUp = true
            input.append(c)
            cursor = input.length
            return snapshot(commit = commit)
        }
        // a key no code starts is the app's, as fcitx hands it on: a table's , and . may be its
        // punctuation, typed after a code as well as alone
        if (input.isEmpty() && !leadsAnywhere(c.toString())) {
            recent.clear()
            predictions.wrote(c.toString(), null)
            return snapshot(commit = commit, handled = false)
        }
        return typeInto(c, commit)
    }

    // [c] put in the code at the cursor, after [committed]
    private fun typeInto(c: Char, committed: String): Snapshot {
        var commit = committed
        // a full code mended: the key has nowhere to go, the code shortened first
        if (table.pinyinMarker == null && input.length >= table.maxLength && !typingAtEnd()) return snapshot(commit = commit)
        input.insert(cursor++, c)
        update()
        val only = ranking.only()
        if (only != null && commitsItself(only)) {
            commit += take(only)
            offerEmojiOf(only.text)
            committedItselfAt = now()
            predict()
        }
        return snapshot(commit = commit)
    }

    // a code typed, the cursor at its end: not one being mended
    private fun typingAtEnd() = input.isNotEmpty() && cursor == input.length

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

    /**
     * Commits the first candidate on the page shown, or the first of all where that is an emoji,
     * unless it is an auto phrase: then, as with no candidate, the code is dropped.
     */
    private fun autoCommit(): String {
        // an emoji first on a page (past the first) is no reading of the code: the first that is
        val first = ranking[page * options.pageSize]?.takeUnless { it.emoji } ?: ranking[0]
        return take(first?.takeUnless { it.auto })
    }

    private fun backspace(): Snapshot {
        if (input.isEmpty()) {
            recent.clear()
            // the app's: what was before goes with it
            predictions.deleted()
            return snapshot(handled = false)
        }
        // at the start of the code: nothing before it is the session's to delete
        if (cursor == 0) return snapshot()
        input.deleteCharAt(--cursor)
        update()
        return snapshot()
    }

    /** The cursor put where [to] says while a code is typed; nothing read again. */
    private fun moveCursor(to: Int): Snapshot {
        if (input.isEmpty()) return snapshot(handled = false)
        cursor = to.coerceIn(0, input.length)
        return snapshot()
    }

    private fun select(index: Int): Snapshot {
        if (input.isEmpty()) {
            recent.clear()
            predictions.lastEntry = null
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

    // nothing typed: every caller has just cleared the code
    private fun predict() {
        page = 0
        predictions.predict()
    }

    // the prediction at [index] forgotten or blocked, as the long press offered: what may follow offered again
    private fun pressed(index: Int, offer: Offer): Snapshot {
        if (predictions.press(index, offer)) page = 0
        return snapshot()
    }

    private fun turnPredictions(to: Int): Snapshot {
        if (to >= 0 && to * options.pageSize < offeredTexts().size) page = to
        return snapshot()
    }

    /** The text before the cursor is [to] (none: reset): the last word put in is still the one before it only if it ends so. */
    private fun moved(to: String?) {
        val last = predictions.lastEntry
        if (to == null) predictions.forgetBefore() else predictions.goOnFrom(to)
        predictions.lastEntry = last?.takeIf { to != null && to.endsWith(it.text) }
    }

    /** Forgets what was learned of the candidate at [index], ranking the code again on the page shown. */
    private fun forget(index: Int): Snapshot {
        // as offered: nothing recorded where nothing is to be kept
        if (!learning) return snapshot()
        val item = ranking[index] ?: return snapshot(handled = input.isNotEmpty())
        if (item.emoji) return snapshot()
        user.forget(item.code, item.text)
        // forgotten by every input method: a phrase saved here or a word of theirs would be back
        // as theirs, and what was put in here they learned too (the one user model)
        if (!item.pinyin && !item.auto) shared.forget(item.text)
        val shown = page
        update()
        if (ranking[shown * options.pageSize] != null) page = shown
        // weighed again only on the first page, as pinyin: not to move what is shown
        if (page != 0) unrefined.drop()
        return snapshot()
    }

    /** Blocks the candidate at [index] for every input method (see [SharedWords]), ranking the code again. */
    private fun block(index: Int): Snapshot {
        if (!learning) return snapshot()
        val item = ranking[index] ?: return snapshot(handled = input.isNotEmpty())
        if (item.emoji || !shared.block(item.text)) return snapshot()
        val shown = page
        update()
        if (ranking[shown * options.pageSize] != null) page = shown
        // weighed again only on the first page, as pinyin: not to move what is shown
        if (page != 0) unrefined.drop()
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
        // an emoji is text, no word: nothing to learn, nor to build a phrase of
        if (!learning || item.emoji) {
            recent.clear()
            predictions.wrote(item.text, null)
            return item.text
        }
        // a word of the user's (a saved phrase, one of the other input methods') the word model lacks: as the user model has it
        val entry = language?.entryOf(item.text, theirs = item.index < 0 && !item.auto)
        if (entry != null) language.user?.learn(predictions.lastEntry, listOf(entry))
        predictions.wrote(item.text, entry)
        // what pinyin found is neither counted here nor built into phrases: libime counts it in its
        // history, but builds phrases from it with the pinyin as the code, which is a bug
        if (item.pinyin) {
            recent.clear()
            return item.text
        }
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
        cursor = 0
        ranking = Ranking.NONE
        homographs = null
        // nothing left to weigh: a code that commits itself may have set it (a row twice in the table, shown once)
        unrefined.drop()
        page = 0
    }

    /** Whether some entry, learned ones too, has a code [pattern] leads to. */
    private fun leadsAnywhere(pattern: String) =
        table.hasMatch(pattern, options.matchingKey) || user.leadsAnywhere(pattern) || shared.leadsAnywhere(pattern)

    private fun update() {
        page = 0
        homographs = null
        ranking = if (input.isEmpty()) Ranking.NONE else rank(input.toString())
        unrefined.start(homographs, homographs?.texts?.size ?: 0)
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
        val found = Found(learned, entries, coded.size, others)
        // one sortable number per candidate: its group, length, picks, then where it was
        val keys = LongArray(found.size) { at ->
            val extra = found.item(at)
            if (extra != null) key(at, extra.code.length, noSort, false) { user.picks(extra.code, extra.text) }
            else found.entry(at).let { e -> key(at, table.codeLength(e) - (if (found.spelt(at)) 1 else 0), noSort, found.spelt(at)) { user.picks(e) } }
        }
        keys.sort()
        val order = IntArray(keys.size) { (keys[it] and POSITION_MASK).toInt() }
        ownFirst(order, found::spelt)
        // the most recently seen first
        val auto = user.seen(code).map { (c, text) -> Item(c, text, true) }
        val made = { placed: IntArray ->
            Ranking(shared::blocked, emojiOf, phrasesOf) { at ->
                when {
                    at < placed.size -> placed[at].let { found.item(it) ?: entryItem(found.entry(it)) }
                    else -> auto.getOrNull(at - placed.size)
                }
            }
        }
        // the 重码: the candidates (at [order]'s positions) of the whole code typed, past those kept in order
        if (code.length <= noSort) return made(order)
        // sorted by length, none comes after the first longer code (what ownFirst moves forward past
        // pinyin entries is none either): the rest is not looked at
        val longer = keys.indexOfFirst { ((it shr LENGTH_SHIFT) and MAX_LENGTH.toLong()) > code.length }
        return weighed(code, order, if (longer < 0) order.size else longer, { k -> kind(code, found, order[k]) }, made)
    }

    /**
     * What candidate [at] of [found] is among the 重码 of [code]: a table entry of the code whole,
     * [USERS] for a word of the user's once picked here (one of the other input methods', or a phrase
     * saved as typed character by character, goes where its picks put it till then), else [OTHER].
     */
    private fun kind(code: String, found: Found, at: Int): Int {
        val extra = found.item(at)
        return when {
            found.spelt(at) -> OTHER
            extra != null -> if (extra.code.length == code.length && user.picks(extra.code, extra.text) > 0) USERS else OTHER
            else -> found.entry(at).let { e -> if (table.codeLength(e) == code.length) e else OTHER }
        }
    }

    /**
     * A code's candidates at [rank]'s positions: the user's [learned] phrases, the table's [entries]
     * (the first [coded] by code, the rest by pinyin), then the other input methods' words ([others]).
     */
    private class Found(val learned: List<Item>, val entries: IntArray, val coded: Int, val others: List<Item>) {
        private val sharedFrom = learned.size + entries.size
        val size get() = sharedFrom + others.size

        fun item(at: Int): Item? = when {
            at < learned.size -> learned[at]
            at >= sharedFrom -> others[at - sharedFrom]
            else -> null
        }

        fun spelt(at: Int) = at in learned.size + coded until sharedFrom

        fun entry(at: Int) = entries[at - learned.size]
    }

    /**
     * [made] with the 重码 of [code] in [order] placed ([kind] of each of its first [reach] positions,
     * past which there are none: a table entry, [USERS] for the user's words, or [OTHER]): the user's
     * first, as they were, then the table's by the word and sentence models, a blocked one left where
     * it is (it is not shown). In the table's order where it does not order by use (one setting:
     * 常用的字词排前面), or a wildcard was typed.
     */
    private fun weighed(code: String, order: IntArray, reach: Int, kind: (Int) -> Int, made: (IntArray) -> Ranking): Ranking {
        val language = language?.takeIf { options.orderByUse } ?: return made(order)
        if (options.matchingKey != null && code.contains(options.matchingKey)) return made(order)
        val kinds = IntArray(reach, kind)
        val users = kinds.indices.filter { kinds[it] == USERS }
        val ranked = kinds.indices.filter { kinds[it] >= 0 && !shared.blocked(table.text(kinds[it])) }
        if (ranked.size < 2 && users.isEmpty()) return made(order)
        val (prev2, prev) = TextWords.previous(language.words.lastTwo(predictions.before))
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
        return h.place(if (texts.size > 1) language.reranker?.pick(predictions.context, h.texts, h.scores) ?: 0 else 0)
    }

    /**
     * A code's 重码 in [order]: at the positions [at], the user's words [users] (order's values) first,
     * then [entries] by the word model, their [texts] and [scores] so; [place] puts one of the
     * entries first among them, the rest as ranked, in a copy of [order]: a Ranking reads its
     * order as it is asked, and one made before goes on as it was.
     */
    private class Homographs(
        private val order: IntArray,
        private val at: IntArray,
        private val users: IntArray,
        private val entries: IntArray,
        val texts: List<String>,
        val scores: List<Float>,
        private val made: (IntArray) -> Ranking,
    ) {
        fun place(first: Int): Ranking {
            val placed = order.copyOf()
            var k = 0
            for (u in users) placed[at[k++]] = u
            if (entries.isNotEmpty()) placed[at[k++]] = entries[first]
            for (j in entries.indices) if (j != first) placed[at[k++]] = entries[j]
            return made(placed)
        }
    }

    private fun refine(): Snapshot {
        unrefined.slice(predictions.context, { it.texts }, { it.scores }) { h, picked -> ranking = h.place(picked) }
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
    /**
     * The candidates [source] makes, as far as asked for, but those [blocked] and the same text
     * twice; the first's [emoji] after the first [EmojiCandidates.AFTER] of them.
     */
    private class Ranking(
        private val blocked: (String) -> Boolean = { false },
        private val emoji: (String) -> List<String> = { emptyList() },
        private val phrases: (String) -> List<String> = { emptyList() },
        private val source: (Int) -> Item?,
    ) {
        private val items = ArrayList<Item>()
        private val seen = HashSet<String>()
        private var next = 0
        private var done = false
        private var emojiPlaced = false

        operator fun get(i: Int): Item? {
            while (items.size <= i && !done) {
                if (!emojiPlaced && items.size == EmojiCandidates.AFTER) {
                    placeEmoji()
                    continue
                }
                val item = source(next++)
                if (item == null) done = true else if (!blocked(item.text) && seen.add(item.text)) items += item
            }
            // fewer than AFTER: after them all
            if (done && !emojiPlaced) placeEmoji()
            return items.getOrNull(i)
        }

        private fun placeEmoji() {
            emojiPlaced = true
            val first = items.firstOrNull() ?: return
            // seen too: an entry of the same text later is not listed again
            EmojiCandidates.pick(first.text, emoji, phrases) { it in seen }
                .forEach {
                    seen += it
                    items += Item("", it, auto = false, emoji = true)
                }
        }

        /** The one candidate of the code, the emoji aside: none if it has more. */
        fun only(): Item? {
            var found: Item? = null
            var i = 0
            while (true) {
                val item = get(i++) ?: return found
                if (item.emoji) continue
                if (found != null) return null
                found = item
            }
        }

        /** How many there are, -1 until all were made: a key's range may hold thousands. */
        val size: Int get() = if (done) items.size else -1

        fun page(from: Int, size: Int): List<Item> = (from until from + size).mapNotNull { get(it) }

        companion object {
            val NONE = Ranking { null }
        }
    }

    private fun snapshot(commit: String = "", handled: Boolean = true): Snapshot {
        if (offering() && input.isEmpty() && !lookingUp) return predicted(commit, handled)
        val from = page * options.pageSize
        val shown = ranking.page(from, options.pageSize)
        val wild = wild()
        return Snapshot(
            commit = commit,
            preedit = input.toString(),
            cursor = if (cursor < input.length) cursor else -1,
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
            refines = unrefined.refines,
        )
    }

    private fun predicted(commit: String, handled: Boolean): Snapshot {
        val from = page * options.pageSize
        val offered = offeredTexts()
        return Snapshot(
            commit = commit,
            preedit = "",
            candidates = offered.drop(from).take(options.pageSize),
            page = page,
            hasPreviousPage = page > 0,
            hasNextPage = from + options.pageSize < offered.size,
            handled = handled,
            predicting = true,
            // a prediction may be forgotten or blocked: see offers
            actionable = true,
            total = offered.size,
            first = from,
        )
    }

    private fun wild() = options.matchingKey != null && input.contains(options.matchingKey)

    /**
     * What is left of [item]'s code to type; all of it where a wildcard stands in the input, and
     * for a pinyin entry the table code it could have been typed by.
     */
    private fun hint(item: Item, wild: Boolean) = when {
        item.emoji -> ""
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
                predictions.wrote(text + action.char, null)
                snapshot(commit = text, handled = false)
            }
            Action.Backspace -> backspaceInLookUp(pinyin)
            Action.CommitRaw -> {
                val text = typedInLookUp(pinyin)
                endLookUp()
                predictions.wrote(text, null)
                snapshot(commit = text)
            }
            Action.Reset, is Action.Context -> {
                endLookUp()
                moved((action as? Action.Context)?.before)
                snapshot()
            }
            // not offered (see offers): the lookup's pinyin has no phrases of the table's own
            is Action.Pin, is Action.Unpin, is Action.Block -> lookedUp ?: snapshot()
            // the preedit shown is the pinyin's, and so is the cursor in it
            is Action.Cursor, is Action.Step -> if (input.length > 1) fromPinyin(pinyin.apply(action)) else snapshot()
            // the pinyin key alone: nothing to pick, and space drops it
            is Action.Select -> if (input.length > 1) fromPinyin(pinyin.apply(action)) else {
                endLookUp()
                snapshot()
            }
            else -> fromPinyin(pinyin.apply(action))
        }
    }

    private fun backspaceInLookUp(pinyin: Session): Snapshot = when {
        input.length <= 1 -> {
            endLookUp()
            snapshot()
        }
        // the pinyin's cursor at its start: it deletes nothing
        lookedUp?.cursor == 0 -> lookedUp ?: snapshot()
        else -> {
            input.setLength(input.length - 1)
            fromPinyin(pinyin.apply(Action.Backspace))
        }
    }

    /**
     * What was typed in a lookup, as typed: the pinyin key too, unless a word was picked from the
     * start of it. The keys here are as many as the pinyin's but, mended at its cursor, maybe not
     * in its order: as it has them, keys alone, nothing was picked.
     */
    private fun typedInLookUp(pinyin: Session): String {
        val raw = if (input.length > 1) pinyin.apply(Action.CommitRaw).commit else ""
        val keysAlone = raw.length == input.length - 1 && raw.all { it in 'a'..'z' || it == '\'' }
        return if (keysAlone) input[0] + raw else raw
    }

    private fun fromPinyin(s: Snapshot): Snapshot {
        if (s.commit.isNotEmpty()) {
            endLookUp()
            predictions.wrote(s.commit, null)
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
    }
}
