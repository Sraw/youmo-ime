/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.phrase.EmojiCandidates
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.FieldJournal
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Predictions
import org.fcitx.fcitx5.android.engine.session.RefineSlices
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.KeyedRows
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/**
 * A table input method (五笔, 仓颉 ...): the candidates are the entries whose code starts with
 * what was typed, ranked as libime ranks them. Codes no longer than [TableOptions.noSortInputLength]
 * (nor than what was typed) come first in the table's order; then the others, shorter codes
 * first ([TableOptions.sortByCodeLength]); among codes as long, the table's own before the words
 * from outside it (the user's: [shared], and [user]'s saved phrases; U-28). A text shows once, with
 * its shortest code. A code the table alone has one entry for, which commits it by itself, takes no
 * word from outside the table (D8): one who types it blind keeps it.
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
 * Characters committed one by one make no phrase (U-28 C46: 自动造词 is gone); the phrases an
 * older build made are [user]'s, words of the user's, forgotten when asked to ([Action.Forget]).
 *
 * With a [language], a table has what every Chinese input method has (specs/engine.md). A code's
 * 重码, the candidates whose code is all that was typed, longer than [TableOptions.noSortInputLength]
 * (五笔's 一二级简码 stay as they are), are placed where the table orders by use (调频,
 * [TableOptions.orderByUse]: off, the table's order is kept for those who type by position): the
 * table's entries by the word model after the text before; the small sentence model weighs them
 * again (性能档位 medium), the large one while the user pauses (high, [Action.Refine]). That is the
 * order the user's habits go on from ([byHabit]). What is put in is learned into the language's
 * [Habits] ([KeyedRows]): under the keys typed alone, and the whole of it after the word before it,
 * for 联想; never into pinyin's order (D28). What [pinyin] looks up is learned there, as full pinyin
 * is, after the text before; the table's codes are not touched. What may follow is offered (联想)
 * until the next key; a long press on it forgets or blocks it, as handwriting's (Predictions):
 * blocks only what [SharedWords.blockable] allows.
 *
 * A long press on a candidate (U-28): forgets what was learned of it, for a word from outside the
 * table, or one of the table's while it orders by use; pins it first under the code typed, ordered
 * by use or not; blocks it under that code alone. A block from pinyin never hides the table's own
 * entries (C12), only the words from outside it.
 *
 * What is put in and deleted at once is taken back, as pinyin's (U-03, [FieldJournal]): a code, a
 * 联想 pick, a word looked up by pinyin. Typed again with the same keys it is a pick there, its own
 * row the correction; with other keys it is new input, nothing more (形码看键, PLAN §2.5).
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
     * A candidate: a table entry (its [index]), a word from outside the table ([index] -1: a phrase
     * saved here, a word of the other input methods), or an [emoji] the first stands for. A
     * [pinyin] entry's [code] is its pinyin, without the marker.
     */
    private class Item(
        val code: String,
        val text: String,
        val index: Int = -1,
        val pinyin: Boolean = false,
        val emoji: Boolean = false,
    ) {
        // the table's own entry, not a word from outside it (the user's, saved or shared)
        val inTable get() = index >= 0
    }

    private val input = StringBuilder()
    private var cursor = 0 // where in the code the next key goes; in a lookup unused, the pinyin's
    private var ranking = Ranking.NONE
    private var page = 0

    // the pinyin lookup: on while the input starts with the pinyin key
    private var lookingUp = false
    private var lookedUp: Snapshot? = null

    // the lookup learns as full pinyin does, or nothing where nothing is learned here
    override var learning = true
        set(value) {
            field = value
            pinyin?.learning = value
        }

    private val habits: Habits get() = language?.habits ?: Habits.OFF

    // what a code typed is learned and kept under
    private fun keyed(code: String) = Code(language?.kind.orEmpty(), code)

    // when a code just committed itself ([now]): the next key, if a space soon enough, is taken (see the class)
    private var committedItselfAt: Long? = null

    // what may follow what was put in, offered while nothing is typed; the text before the cursor,
    // for the word and sentence models, and the last word put in, for what is learned after it
    private val predictions = Predictions(
        language?.predictor, language?.words, language?.user, language?.block, language?.forgotten ?: {}, shared::blockable,
        language?.habits ?: Habits.OFF, language?.simplify ?: { it },
    ) { learning }

    // the field as this session wrote it (U-03), compared as the record has its text (a traditional table's)
    private val journal = FieldJournal({ habits }, language?.simplify ?: { it })

    private val emojiOf: (String) -> List<String> = language?.emoji ?: { emptyList() }
    private val phrasesOf: (String) -> List<String> = language?.phrases ?: { emptyList() }

    // the code's 重码 as placed ([rank]); those the refiner is still to weigh
    private var homographs: Homographs? = null

    // how the habits ranked the 重码 of the code typed, by text: what 为什么排在这 says (U-81)
    private var ranked: Map<String, HabitRanker.Ranked<Int>> = emptyMap()
    private val unrefined = RefineSlices<Homographs>(language?.refiner)

    // Enter between: the space is on a new line, typed
    override fun passedOn() {
        journal.passedOn()
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
            // deleted whole at once, the pick is taken back (U-03)
            journal.committed(text, predictions.picked, learning)
            selfEmoji = emptyList()
            page = 0
            return snapshot(commit = text)
        }
        val emoji = selfEmoji[index]
        // the app deletes code points (FcitxInputMethodService.handleDeleteSurrounding)
        val replaced = selfCommitted.codePointCount(0, selfCommitted.length)
        selfEmoji = emptyList()
        // the word replaced, by the app: the field is no longer as written (its record stands)
        journal.unverify()
        // the word is gone from the text: nothing to follow it
        predictions.unoffer()
        predictions.forgetBefore()
        page = 0
        return snapshot(commit = emoji).copy(deleteBefore = replaced)
    }

    override fun apply(action: Action): Snapshot {
        journal.before(action, learning)
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
                // what may follow has nothing to pin
                is Action.Pin, is Action.Unpin -> return snapshot()
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
        is Action.Key -> type(action.char, action.glided)
        Action.Backspace -> backspace()
        is Action.Select -> select(action.index)
        is Action.Pick -> pick(action.index)
        is Action.Forget -> forget(action.index)
        is Action.Block -> block(action.index)
        is Action.Pin -> pin(action.index, on = true)
        is Action.Unpin -> pin(action.index, on = false)
        Action.NextPage -> turn(page + 1)
        Action.PreviousPage -> turn(page - 1)
        Action.CommitRaw -> {
            val text = input.toString()
            clear()
            // nothing typed: Enter is the app's, and what is before goes when it is passed on (passedOn), as handwriting's
            // the code as typed: no pick to take back
            if (text.isNotEmpty()) wrote(text, null)
            snapshot(commit = text, handled = text.isNotEmpty())
        }
        Action.Refine -> refine()
        Action.Ink, Action.Glide, is Action.Syllable, is Action.Letter, is Action.NineKey -> snapshot()
        is Action.Cursor -> moveCursor(action.at)
        is Action.Step -> moveCursor(action.applyTo(cursor, input.length))
        Action.Reset, is Action.Context -> {
            if (action is Action.Context) predictions.learnFrom(action)
            moved((action as? Action.Context)?.before)
            clear()
            snapshot()
        }
    }

    /** A glide's [word] the session held back, committed by the glide and told as context next: written here, nothing learned of it. */
    fun heldBack(word: String) = if (learning) journal.own(word, null) else Unit

    // [text] put in, [entry] its word as learned, [commit] its record: what follows goes on from it, and the journal has it (U-03)
    private fun wrote(text: String, entry: Entry?, commit: Commit? = null) = predictions.wrote(text, entry).also { journal.committed(text, commit, learning) }

    // what may follow says what it goes on from, for 不再联想 (U-72); a code's 重码 how the habits ranked them
    override fun why(index: Int): Why? = when {
        lookingUp -> pinyin!!.why(index)
        offering() -> predictions.why(index - selfEmoji.size)
        else -> ranking[index]?.let { TableHabitOrder.why(habits, keyed(input.toString()), it.text, ranked[it.text]) }
    }

    // what was learned read as it is now (an undo, a prediction turned off), the record's read live
    override fun reread(): Snapshot? = when {
        lookingUp -> pinyin!!.reread()
        // what may follow, offered anew: one taken back may be all there is
        input.isEmpty() -> {
            page = predictions.reread(page, options.pageSize)
            snapshot()
        }
        else -> rankedAgain()
    }

    override val rereads get() = !lookingUp || pinyin!!.rereads

    override fun fieldFlags(flags: Int) {
        predictions.learnFlags = flags
        pinyin?.fieldFlags(flags)
    }

    // a lookup's pinyin has no phrases of the table's own to pin to
    override fun offers(index: Int): Set<Offer> = when {
        // not where nothing is to be kept (a password): forgetting and blocking record the word
        !learning -> emptySet()
        offering() -> if (index < selfEmoji.size) emptySet() else predictions.offers(index - selfEmoji.size)
        lookingUp -> pinyin!!.offers(index).intersect(setOf(Offer.FORGET))
        // an emoji is no word: nothing learned of it to forget, nor to block
        ranking[index]?.emoji == true -> emptySet()
        ranking[index] != null -> offersOf(ranking[index]!!)
        else -> emptySet()
    }

    // U-28's long press. Forgetting only where something was learned of it, as pinyin offers it
    // (PY-33): the table's own learn nothing while the table keeps its order. Pinned and blocked
    // under the code typed, where the record keeps them; not under a wildcard, which is no code
    private fun offersOf(item: Item): Set<Offer> {
        val learned = when {
            item.inTable -> options.orderByUse && habits.knows(item.text)
            else -> user.isSaved(item.code, item.text) || shared.learned(item.text)
        }
        val keeps = habits.learns && !wild()
        val pinned = keeps && habits.pinned(keyed(input.toString()))?.text == item.text
        return setOfNotNull(
            Offer.FORGET.takeIf { learned },
            Offer.PIN.takeIf { keeps && !pinned },
            Offer.UNPIN.takeIf { pinned },
            Offer.BLOCK.takeIf { keeps },
        )
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

    private fun type(c: Char, glided: Boolean = false): Snapshot {
        val startsLookUp = c == options.pinyinKey && pinyin != null
        // a selection key picks while there are candidates, a key of the codes too, as in fcitx;
        // with none it goes on as any other key
        val selects = if (input.isNotEmpty() && ranking[0] != null) options.selectionKeys.indexOf(c) else -1
        if (selects >= 0) return select(selects)
        val isCode = isCode(c)
        if (!isCode && !(startsLookUp && input.isEmpty())) {
            // not a code key: what was typed is committed, then the key is the app's
            val text = if (input.isEmpty()) "" else autoCommit()
            // the key is in the field, not the journal's: a Backspace now deletes it, not a commit;
            // not a glide's key, which the glide drops (GlideSession hands nothing on)
            predictions.wrote(c.toString(), null).also { if (!glided) journal.unverify() }
            return snapshot(commit = text, handled = false)
        }
        var commit = ""
        if (options.autoSelect && typingAtEnd() && endsCode(c)) commit = autoCommit()
        // after a code just committed too: 叫 by kkkk, then z looks the next one up
        if (startsLookUp && input.isEmpty()) {
            lookingUp = true
            input.append(c)
            cursor = input.length
            // what is looked up is learned as full pinyin is, after the text before
            pinyin?.apply(Action.Context(predictions.before, predictions.cleared, predictions.learnFlags))
            return snapshot(commit = commit)
        }
        // a key no code starts is the app's, as fcitx hands it on: a table's , and . may be its
        // punctuation, typed after a code as well as alone
        if (input.isEmpty() && !leadsAnywhere(c.toString())) {
            predictions.wrote(c.toString(), null).also { if (!glided) journal.unverify() }
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
        // a pinyin entry waits to be picked, shown for the code to learn; so does a code found
        // through the matching key
        if (item.pinyin) return false
        return commitsWhole(item.code)
    }

    // whether an entry of [code], all that was typed, commits itself once it is the only one
    private fun commitsWhole(code: String) =
        options.autoSelect && code == input.toString() && input.length <= table.maxLength && reaches(options.autoSelectLength)

    /** Whether what is typed is long enough for a length [limit] of [TableOptions]. */
    private fun reaches(limit: Int) = limit != 0 && (limit < 0 || input.length >= limit)

    /** Commits the first candidate on the page shown, or the first of all where that is an emoji. */
    private fun autoCommit(): String {
        // an emoji first on a page (past the first) is no reading of the code: the first that is
        val first = ranking[page * options.pageSize]?.takeUnless { it.emoji } ?: ranking[0]
        return take(first)
    }

    private fun backspace(): Snapshot {
        if (input.isEmpty()) {
            // the app's: what was before goes with it
            journal.backspace()
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
        if (item.emoji || Offer.FORGET !in offersOf(item)) return snapshot()
        user.forget(item.code, item.text)
        // forgotten by every input method (U-84): a phrase saved here or a word of theirs would be
        // back as theirs, and what was learned of it here goes by its text too
        shared.forget(item.text)
        return rankedAgain()
    }

    /** Blocks the candidate at [index] under the code typed alone (U-28), ranking the code again. */
    private fun block(index: Int): Snapshot {
        if (!learning) return snapshot()
        val item = ranking[index] ?: return snapshot(handled = input.isNotEmpty())
        if (item.emoji || Offer.BLOCK !in offersOf(item)) return snapshot()
        val code = keyed(input.toString())
        // the block wins: a pin of it would show it all the same
        if (habits.pinned(code)?.text == item.text) habits.pin(code, item.text, "", on = false)
        habits.blockUnder(code, item.text, on = true)
        return rankedAgain()
    }

    /** Pins the candidate at [index] first under the code typed (U-28, 调频 on or off), or unpins it. */
    private fun pin(index: Int, on: Boolean): Snapshot {
        if (!learning) return snapshot()
        val item = ranking[index] ?: return snapshot(handled = input.isNotEmpty())
        if (item.emoji || (if (on) Offer.PIN else Offer.UNPIN) !in offersOf(item)) return snapshot()
        habits.pin(keyed(input.toString()), item.text, "", on)
        // back to the first page, unlike a block or a forget (rankedAgain): a pin is first there,
        // and an unpin is pressed on the first candidate
        update()
        return snapshot()
    }

    // the code ranked again after what a long press changed, on the page shown if it still has one
    private fun rankedAgain(): Snapshot {
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
        val typed = input.toString()
        // a code with the matching key in it is no code to learn under (nor pin nor block there)
        val wild = wild()
        // what the user picked over: the code's first (never an emoji, which come after it)
        val first = ranking[0]?.text.orEmpty()
        val own = ranking.own
        clear()
        if (item == null) return ""
        // an emoji is text, no word: nothing to learn
        if (!learning || item.emoji) {
            wrote(item.text, null)
            return item.text
        }
        // a word of the user's (a saved phrase, one of the other input methods') the word model lacks: as the user model has it
        val entry = language?.entryOf(item.text, theirs = !item.inTable)
        val h = habits
        val commit = if (h.learns) {
            val pick = KeyedRows.Pick(typed, item.text, if (item.text == first) 'A' else 'B', first, own = item.text == own)
            val picks = if (wild) emptyList() else listOf(pick)
            val word = Word(language?.simplify?.invoke(item.text) ?: item.text, "")
            // a wildcard's too: its whole row, the use and the pair, taken back if deleted
            h.record(KeyedRows.of(language?.kind.orEmpty(), picks, listOf(word), predictions.wordBefore()), flags = predictions.learnFlags)
        } else null
        // typed again with the same keys, its own row is the correction: no other (形码看键)
        wrote(item.text, entry, commit)
        return item.text
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

    /** Whether some entry, a saved phrase or a word of the user's too, has a code [pattern] leads to. */
    private fun leadsAnywhere(pattern: String) =
        table.hasMatch(pattern, options.matchingKey) || user.leadsAnywhere(pattern) || shared.leadsAnywhere(pattern)

    private fun update() {
        page = 0
        homographs = null
        ranking = if (input.isEmpty()) Ranking.NONE else rank(input.toString())
        unrefined.start(homographs, homographs?.texts?.size ?: 0)
    }

    /**
     * The candidates of [code], ranked; decoded as they are asked for. The words from outside the
     * table after its own as long, none where the table's lone entry commits itself (D8); none
     * blocked under the code, nor a word from outside blocked anywhere; the one pinned first.
     */
    private fun rank(code: String): Ranking {
        val coded = table.match(code, options.matchingKey)
        val spelt = table.matchPinyin(code, prefix = pinyinPrefix(code))
        // the pinyin entries last, so a code and its pinyin as long rank the code first
        val entries = if (spelt.isEmpty()) coded else coded + spelt
        val here = keyed(code)
        val blockedHere = language?.blockedUnder?.invoke(here).orEmpty()
        val alone = spelt.isEmpty() && alone(coded, blockedHere)
        // saved phrases are found by their plain code; the matching key is for the table's
        val saved = if (alone) emptyList() else user.saved(code).map { (c, text) -> Item(c, text) }
        val others = if (alone) emptyList() else shared.words(code).filterNot { (c, text) -> user.isSaved(c, text) }.map { (c, text) -> Item(c, text) }
        val noSort = minOf(code.length, options.noSortInputLength)
        val found = Found(saved, entries, coded.size, others)
        // one sortable number per candidate: its group, length, whether from outside the table, then where it was
        val keys = LongArray(found.size) { at ->
            val extra = found.item(at)
            if (extra != null) {
                key(at, extra.code.length, noSort, isPinyin = false, outside = true)
            } else {
                found.entry(at).let { e -> key(at, table.codeLength(e) - (if (found.spelt(at)) 1 else 0), noSort, found.spelt(at), outside = false) }
            }
        }
        keys.sort()
        val order = IntArray(keys.size) { (keys[it] and POSITION_MASK).toInt() }
        ownFirst(order, found::spelt)
        val pinned = habits.pinned(here)?.text
        // the 重码: the candidates (at [order]'s positions) of the whole code typed, past those kept in
        // order; sorted by length, none comes after the first longer code (what ownFirst moves forward
        // past pinyin entries is none either): the rest is not looked at
        val longer = keys.indexOfFirst { ((it shr LENGTH_SHIFT) and MAX_LENGTH.toLong()) > code.length }
        val reach = if (code.length <= noSort) 0 else if (longer < 0) order.size else longer
        val blocked = { item: Item -> item.text in blockedHere || (!item.inTable && shared.blocked(item.text)) }
        // the table's own first, before 调频's models and the habits ranked it: picked over another shown first, a use of it (F2739)
        val own = order.asSequence().map { found.item(it) ?: entryItem(found.entry(it)) }.firstOrNull { !blocked(it) }?.text.orEmpty()
        val made = { placed: IntArray ->
            val shown = TableHabitOrder.pinnedFirst(byHabit(code, placed, reach, { found.item(it) == null && !found.spelt(it) }) { found.text(it, table) }, pinned) {
                found.text(it, table)
            }
            Ranking(blocked, emojiOf, phrasesOf, own) { at ->
                shown.getOrNull(at)?.let { found.item(it) ?: entryItem(found.entry(it)) }
            }
        }
        if (reach == 0) return made(order)
        return weighed(code, order, reach, { k -> kind(code, found, order[k]) }, blockedHere, made)
    }

    /**
     * Whether the table's entries [coded] are one text, not [blocked], whose code commits itself
     * (D8): a row the table has twice is one text, a blocked one none. Done at the second text.
     */
    private fun alone(coded: IntArray, blocked: Set<String>): Boolean {
        var text: String? = null
        for (e in coded) {
            val t = table.text(e)
            if (t in blocked || t == text) continue
            if (text != null || !commitsWhole(table.code(e))) return false
            text = t
        }
        return text != null
    }

    // [TableHabitOrder.order] under [code] typed alone, what it ranked kept for 为什么排在这
    private fun byHabit(code: String, original: IntArray, reach: Int, own: (Int) -> Boolean, text: (Int) -> String): IntArray {
        val ordered = if (reach == 0 || wild()) null else TableHabitOrder.order(habits, options.orderByUse, keyed(code), original, reach, own, text)
        ranked = ordered?.second.orEmpty()
        return ordered?.first ?: original
    }

    /**
     * What candidate [at] of [found] is among the 重码 of [code]: a table entry of the code whole,
     * else [OTHER] (a word from outside the table goes where its length put it).
     */
    private fun kind(code: String, found: Found, at: Int): Int = when {
        found.spelt(at) || found.item(at) != null -> OTHER
        else -> found.entry(at).let { e -> if (table.codeLength(e) == code.length) e else OTHER }
    }

    /**
     * A code's candidates at [rank]'s positions: the [saved] phrases, the table's [entries]
     * (the first [coded] by code, the rest by pinyin), then the other input methods' words ([others]).
     */
    private class Found(val saved: List<Item>, val entries: IntArray, val coded: Int, val others: List<Item>) {
        private val sharedFrom = saved.size + entries.size
        val size get() = sharedFrom + others.size

        fun item(at: Int): Item? = when {
            at < saved.size -> saved[at]
            at >= sharedFrom -> others[at - sharedFrom]
            else -> null
        }

        fun spelt(at: Int) = at in saved.size + coded until sharedFrom

        fun entry(at: Int) = entries[at - saved.size]

        fun text(at: Int, table: TableDictionary) = item(at)?.text ?: table.text(entry(at))
    }

    /**
     * [made] with the 重码 of [code] in [order] placed ([kind] of each of its first [reach] positions,
     * past which there are none: a table entry, or [OTHER]): the table's by the word and sentence
     * models, one blocked under the code ([blockedHere]) left where it is (it is not shown). In the
     * table's order where it does not order by use (调频), or a wildcard was typed.
     */
    private fun weighed(code: String, order: IntArray, reach: Int, kind: (Int) -> Int, blockedHere: Set<String>, made: (IntArray) -> Ranking): Ranking {
        val language = language?.takeIf { options.orderByUse } ?: return made(order)
        if (options.matchingKey != null && code.contains(options.matchingKey)) return made(order)
        val kinds = IntArray(reach, kind)
        val rankable = kinds.indices.filter { kinds[it] >= 0 && table.text(kinds[it]) !in blockedHere }
        if (rankable.size < 2) return made(order)
        val (prev2, prev) = TextWords.previous(language.words.lastTwo(predictions.before))
        val texts = rankable.map { table.text(kinds[it]) }
        val scores = FloatArray(rankable.size) { k -> language.words.logProb(texts[k], prev2, prev) }
        // by score, the table's order among equals
        val byScore = rankable.indices.sortedByDescending { scores[it] }
        val h = Homographs(order, rankable.toIntArray(), byScore.map { order[rankable[it]] }.toIntArray(), byScore.map { texts[it] }, byScore.map { scores[it] }, made)
        homographs = h
        return h.place(language.reranker?.pick(predictions.context, h.texts, h.scores) ?: 0)
    }

    /**
     * A code's 重码 in [order]: at the positions [at], [entries] by the word model, their [texts]
     * and [scores] so; [place] puts one of them first, the rest as ranked, in a copy of [order]: a
     * Ranking reads its order as it is asked, and one made before goes on as it was.
     */
    private class Homographs(
        private val order: IntArray,
        private val at: IntArray,
        private val entries: IntArray,
        val texts: List<String>,
        val scores: List<Float>,
        private val made: (IntArray) -> Ranking,
    ) {
        fun place(first: Int): Ranking {
            val placed = order.copyOf()
            var k = 0
            placed[at[k++]] = entries[first]
            for (j in entries.indices) if (j != first) placed[at[k++]] = entries[j]
            return made(placed)
        }
    }

    private fun refine(): Snapshot {
        unrefined.slice(predictions.context, { it.texts }, { it.scores }) { h, picked -> ranking = h.place(picked) }
        return snapshot()
    }

    /** A candidate's sortable number: its group, length, whether it is from [outside] the table, then [at]. */
    private fun key(at: Int, length: Int, noSort: Int, isPinyin: Boolean, outside: Boolean): Long {
        val sorted = length > noSort || isPinyin
        return (if (sorted) 1L shl GROUP_SHIFT else 0L) or
            ((if (sorted && options.sortByCodeLength) minOf(length, MAX_LENGTH) else 0).toLong() shl LENGTH_SHIFT) or
            (if (outside) 1L shl OUTSIDE_SHIFT else 0L) or
            at.toLong()
    }

    private fun entryItem(index: Int) = if (table.isPinyin(index)) {
        Item(table.code(index).substring(1), table.text(index), index, pinyin = true)
    } else {
        Item(table.code(index), table.text(index), index)
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
        private val blocked: (Item) -> Boolean = { false },
        private val emoji: (String) -> List<String> = { emptyList() },
        private val phrases: (String) -> List<String> = { emptyList() },
        val own: String = "",
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
                if (item == null) done = true else if (!blocked(item) && seen.add(item.text)) items += item
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
                    items += Item("", it, emoji = true)
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

    private fun predicted(commit: String, handled: Boolean) = predicted(offeredTexts(), page, options.pageSize, commit, handled)

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
                val commit = if (text.isEmpty()) null else pinyin.lastCommit
                if (text.isEmpty()) text = typedInLookUp(pinyin)
                journal.committed(text, commit, learning)
                // then the key, not written here
                journal.unverify()
                endLookUp()
                // then the key, the app's
                predictions.wrote(text + action.char, null)
                snapshot(commit = text, handled = false)
            }
            Action.Backspace -> backspaceInLookUp(pinyin)
            Action.CommitRaw -> {
                val text = typedInLookUp(pinyin)
                endLookUp()
                wrote(text, null)
                snapshot(commit = text)
            }
            Action.Reset, is Action.Context -> {
                if (action is Action.Context) predictions.learnFrom(action)
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
            // looked up as full pinyin learns it (U-28), and taken back as it is (U-03)
            wrote(s.commit, null, pinyin?.lastCommit)
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
        clear()
    }

    companion object {
        // a ranking key: group, code length, from outside the table, position
        private const val GROUP_SHIFT = 62
        private const val LENGTH_SHIFT = 54
        private const val OUTSIDE_SHIFT = 32
        private const val MAX_LENGTH = 0xFF
        private const val POSITION_MASK = 0xFFFFFFFFL

        // a position's kind in a code's 重码 that is no table entry
        private const val OTHER = -1

        // a space later than this after a code committed itself was typed on purpose, after a pause
        private const val SPACE_TAKEN_MS = 1000L
    }
}

// what may follow, shown: page [page] of [offered] (a session's own emoji, then the predictions)
private fun predicted(offered: List<String>, page: Int, pageSize: Int, commit: String, handled: Boolean): Snapshot {
    val from = page * pageSize
    return Snapshot(
        commit = commit,
        preedit = "",
        candidates = offered.drop(from).take(pageSize),
        page = page,
        hasPreviousPage = page > 0,
        hasNextPage = from + pageSize < offered.size,
        handled = handled,
        predicting = true,
        // a prediction may be forgotten or blocked: see offers
        actionable = true,
        total = offered.size,
        first = from,
    )
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
