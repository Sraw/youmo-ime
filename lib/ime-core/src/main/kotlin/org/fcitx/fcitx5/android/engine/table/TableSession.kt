/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.EmojiCandidates
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.FieldJournal
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.OfferRules
import org.fcitx.fcitx5.android.engine.session.Pager
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
import java.util.Calendar

/**
 * A table input method (五笔, 仓颉 ...): the candidates are the entries whose code starts with
 * what was typed, ranked as libime ranks them. Codes no longer than [TableOptions.noSortInputLength]
 * (nor than what was typed) come first in the table's order; then the others, shorter codes
 * first ([TableOptions.sortByCodeLength]); among codes as long, the table's own before the words
 * from outside it (the user's: [shared], and the phrases [saved]; U-28). A text shows once, with
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
 * candidates show their code here: the way to type a character whose code one does not know. The
 * 拼 key ([Action.LookUp]) has every letter do so, till pressed again ([lookUpMode], kept by the
 * engine across sessions): what is typed as it is pressed goes over, a code into the lookup, the
 * lookup's letters back into a code.
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
 * older build made are [saved]'s, words of the user's, forgotten when asked to ([Action.Forget]).
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
 *
 * The user's custom phrases ([phrases], A1; not the built-in date and time, whose keys are pinyin's)
 * are offered under a code that is their key, where their order says but never first: space still
 * commits the code's own first, so a code typed blind keeps it, and none where the table's lone entry
 * commits itself (D8); a wildcard's letter (五笔's z) in it is the letter, among the wildcard's matches.
 * A phrase's key leads somewhere, so its letters are taken as a code is. A phrase
 * is text: never learned, never committing itself, deleted from the phrases by a long press.
 */
class TableSession(
    private val table: TableDictionary,
    private val options: TableOptions = TableOptions(),
    pinyin: Session? = null,
    private val saved: SavedPhrases = SavedPhrases(table, "", { null }),
    private val shared: SharedWords = SharedWords.NONE,
    private val language: TableLanguage? = null,
    /** Milliseconds on a clock that does not jump: how soon after a code committed itself a space comes. */
    private val now: () -> Long = JvmClock::uptimeMillis,
    /** The custom phrases, pinyin's: the user's own alone are offered here. */
    private val phrases: PhraseBook = PhraseBook(),
    /** The time a dynamic phrase is filled in with. */
    private val clock: () -> Calendar = JvmClock::calendar,
    /** Whether the 拼 key is on: the engine's, a session made anew keeps it. */
    private val lookUpMode: LookUpMode = LookUpMode(),
) : Session {

    /** Whether the 拼 key has the table look letters up by pinyin ([Action.LookUp]). */
    class LookUpMode {
        var on = false
    }

    /**
     * A candidate: a table entry (its [index]), a word from outside the table ([index] -1: a phrase
     * saved here, a word of the other input methods), an [emoji] the first stands for, or a custom
     * [phrase] under the code. A [pinyin] entry's [code] is its pinyin, without the marker.
     */
    internal class Item(
        val code: String,
        val text: String,
        val index: Int = -1,
        val pinyin: Boolean = false,
        val emoji: Boolean = false,
        val phrase: CustomPhrases.Phrase? = null,
    ) {
        // text, no word: an emoji or a phrase, nothing learned of it, nothing to forget or block
        val asText get() = emoji || phrase != null

        // the table's own entry, not a word from outside it (the user's, saved or shared)
        val inTable get() = index >= 0
    }

    private val input = StringBuilder()
    private var cursor = 0 // where in the code the next key goes; in a lookup the pinyin's (PinyinLookUp)
    private var ranking = Ranking.NONE
    private val pager = Pager(options.pageSize)

    // the pinyin lookup, on while the input starts with the pinyin key, or the 拼 key's letters are
    // looked up: it has the keys then, the code here is empty. Codes hinted as the preedit shows them
    private val lookUp = pinyin?.let { PinyinLookUp(it, options.selectionKeys, { text -> table.codeOf(text)?.let(options::shown) }, LookUpEnds()) }
    private val lookingUp get() = lookUp?.on == true

    // the lookup learns as full pinyin does, or nothing where nothing is learned here
    override var learning = true
        set(value) {
            field = value
            lookUp?.learning = value
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
            pager.reset()
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
        pager.reset()
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
                is Action.Select -> return pager.at(action.index).let { if (it >= 0) pickOffered(it) else snapshot() }
                Action.NextPage -> return turnPredictions(pager.page + 1)
                Action.PreviousPage -> return turnPredictions(pager.page - 1)
                Action.Refine, is Action.Ink, is Action.Glide, is Action.Stroke -> return snapshot()
                // an emoji is no word: nothing to forget or block
                is Action.Forget -> return if (action.index < n) snapshot() else pressed(action.index - n, Offer.FORGET)
                is Action.Block -> return if (action.index < n) snapshot() else pressed(action.index - n, Offer.BLOCK)
                // what may follow has nothing to pin
                is Action.Pin, is Action.Unpin -> return snapshot()
                // anything else ends the offer, and goes on as it would without one: its page too
                else -> {
                    predictions.unoffer()
                    selfEmoji = emptyList()
                    pager.reset()
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

    private fun dispatch(action: Action): Snapshot = when {
        action == Action.LookUp -> toggleLookUp()
        lookingUp -> lookUp!!.apply(action)
        else -> typed(action)
    }

    // [action] where no lookup has the keys
    private fun typed(action: Action): Snapshot = when (action) {
        is Action.Key -> type(action.char, action.glided)
        Action.Backspace -> backspace()
        is Action.Select -> select(action.index)
        is Action.Pick -> pick(action.index)
        is Action.Forget -> forget(action.index)
        is Action.Block -> block(action.index)
        is Action.Pin -> pin(action.index, on = true)
        is Action.Unpin -> pin(action.index, on = false)
        Action.NextPage -> turn(pager.page + 1)
        Action.PreviousPage -> turn(pager.page - 1)
        Action.CommitRaw -> {
            val text = input.toString()
            clear()
            // nothing typed: Enter is the app's, and what is before goes when it is passed on (passedOn), as handwriting's
            // the code as typed: no pick to take back
            if (text.isNotEmpty()) wrote(text, null)
            snapshot(commit = text, handled = text.isNotEmpty())
        }
        Action.Refine -> refine()
        is Action.Ink, is Action.Glide, is Action.Stroke, is Action.Syllable, is Action.Letter, is Action.NineKey, Action.LookUp -> snapshot()
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
        lookingUp -> lookUp!!.why(index)
        offering() -> predictions.why(index - selfEmoji.size)
        else -> ranking[index]?.takeIf { it.phrase == null }?.let { TableHabitOrder.why(habits, keyed(input.toString()), it.text, ranked[it.text]) }
    }

    // what was learned read as it is now (an undo, a prediction turned off), the record's read live
    override fun reread(): Snapshot? = when {
        lookingUp -> lookUp!!.reread()
        // what may follow, offered anew: one taken back may be all there is
        input.isEmpty() -> {
            predictions.reread(pager)
            snapshot()
        }
        else -> rankedAgain()
    }

    override val rereads get() = !lookingUp || lookUp!!.rereads

    override fun fieldFlags(flags: Int) {
        predictions.learnFlags = flags
        lookUp?.fieldFlags(flags)
    }

    override fun offers(index: Int): Set<Offer> = when {
        // a phrase is the settings', deleted from them here, learning or not, as pinyin's
        !offering() && !lookingUp && ranking[index]?.phrase != null -> OfferRules.offers(learning, forgets = { false }, phrase = true)
        // not where nothing is to be kept (a password): forgetting and blocking record the word
        !learning -> emptySet()
        offering() -> if (index < selfEmoji.size) emptySet() else predictions.offers(index - selfEmoji.size)
        lookingUp -> lookUp!!.offers(index)
        // an emoji is no word: nothing learned of it to forget, nor to block
        ranking[index]?.asText == true -> emptySet()
        ranking[index] != null -> offersOf(ranking[index]!!)
        else -> emptySet()
    }

    // U-28's long press. Forgetting only where something was learned of it, as pinyin offers it
    // (PY-33): the table's own learn nothing while the table keeps its order. Pinned and blocked
    // under the code typed, where the record keeps them; not under a wildcard, which is no code
    private fun offersOf(item: Item): Set<Offer> {
        val learned = when {
            item.inTable -> options.orderByUse && habits.knows(item.text)
            else -> saved.isSaved(item.code, item.text) || shared.learned(item.text)
        }
        val keeps = habits.learns && !wild()
        return OfferRules.offers(
            learning,
            forgets = { learned },
            pins = { keeps },
            pinned = { habits.pinned(keyed(input.toString()))?.text == item.text },
            blocks = { keeps },
        )
    }

    override fun reads(c: Char): Boolean = if (mayTakeSpace(c)) {
        true
    } else if (lookingUp) {
        lookUp!!.reads(c)
    } else {
        isCode(c) ||
            (startsLookUp(c) && input.isEmpty()) ||
            (input.isNotEmpty() && c in options.selectionKeys)
    }

    private fun isCode(c: Char) = c in table.keys || c == options.matchingKey || (table.pinyinMarker != null && c in 'a'..'z')

    // a letter the 拼 key has looked up
    private fun modeLooksUp(c: Char) = lookUpMode.on && c in 'a'..'z'

    // [c] starts a lookup where nothing is typed: the pinyin key, or a letter the 拼 key looks up
    private fun startsLookUp(c: Char) = (c == options.pinyinKey || modeLooksUp(c)) && lookUp != null

    // what is looked up is learned as full pinyin is, after the text before; the 拼 key's letter read at once
    private fun startLookUp(c: Char, commit: String): Snapshot {
        val context = Action.Context(predictions.before, predictions.cleared, predictions.learnFlags)
        return if (modeLooksUp(c)) lookUp!!.start(context, commit, first = c) else lookUp!!.start(context, commit, key = c)
    }

    /**
     * The 拼 key pressed: on, a code typed goes into a lookup (one with more than letters, a
     * wildcard or 二笔's `;`, stays a code: no pinyin spells it); off, the lookup the 拼 key started
     * goes back into a code: a word already picked of it put in as a pick is, the code keys after
     * it the code, as typed (the pinyin's `'` dropped, even where a code may have one, 二笔's; as
     * many as the longest code takes), nothing of them committed: the candidates they spell wait for a pick. Nothing of the pinyin is learned.
     * A lookup its key started stays one either way.
     */
    private fun toggleLookUp(): Snapshot {
        val look = lookUp ?: return snapshot()
        lookUpMode.on = !lookUpMode.on
        return when {
            lookUpMode.on && !lookingUp && input.isNotEmpty() && spellsPinyin() -> {
                val code = input.toString()
                clear()
                var s = startLookUp(code[0], "")
                for (c in code.drop(1)) {
                    if (!lookingUp) break
                    s = look.apply(Action.Key(c))
                }
                s
            }
            !lookUpMode.on && lookingUp && look.key == null -> {
                val back = look.drop()
                clear()
                // as the lookup puts a pick in (LookUpEnds.picked)
                if (back.picked.isNotEmpty()) wrote(back.picked, null, back.record)
                val code = back.keys.filter { it != '\'' && isCode(it) }.let { if (table.pinyinMarker == null) it.take(table.maxLength) else it }
                if (code.isNotEmpty()) {
                    input.append(code)
                    cursor = input.length
                    update()
                }
                snapshot(commit = back.picked)
            }
            // a lookup its key started goes on as it is, the key's lookups only after it
            lookingUp -> look.shown()
            else -> snapshot()
        }
    }

    // letters only, and no wildcard past the first (五笔's z, a letter: gz is a code; 五笔拼音's z is pinyin's)
    private fun spellsPinyin() = input.withIndex().all { (i, c) ->
        c in 'a'..'z' && (i == 0 || c != options.matchingKey || table.pinyinMarker != null)
    }

    override fun candidates(from: Int, count: Int): List<Choice> {
        if (lookingUp) return lookUp!!.candidates(from, count)
        if (offering()) return offeredTexts().drop(from).take(count).map { Choice(it) }
        val wild = wild()
        return ranking.page(from, count).map { Choice(it.text, if (options.hint) options.shown(hint(it, wild)) else "") }
    }

    private fun type(c: Char, glided: Boolean = false): Snapshot {
        val startsLookUp = startsLookUp(c)
        // a selection key picks while there are candidates, a key of the codes too, as in fcitx;
        // with none it goes on as any other key
        val selects = if (input.isNotEmpty() && hasEntry()) options.selectionKeys.indexOf(c) else -1
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
        if (startsLookUp && input.isEmpty()) return startLookUp(c, commit)
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
        // through the matching key, and a phrase
        if (item.pinyin || item.phrase != null) return false
        return commitsWhole(item.code)
    }

    // whether an entry of [code], all that was typed, commits itself once it is the only one
    private fun commitsWhole(code: String) =
        options.autoSelect && code == input.toString() && input.length <= table.maxLength && reaches(options.autoSelectLength)

    /** Whether what is typed is long enough for a length [limit] of [TableOptions]. */
    private fun reaches(limit: Int) = limit != 0 && (limit < 0 || input.length >= limit)

    // whether the code has a candidate to commit unasked: a phrase first is the only kind there is
    // (a code reached by phrases alone), which a key never commits, only a tap (a phrase never commits itself)
    private fun hasEntry() = ranking[0]?.let { it.phrase == null } == true

    /** Commits the first candidate on the page shown, or the first of all where that is an emoji; none where only phrases are. */
    private fun autoCommit(): String {
        if (!hasEntry()) return take(null)
        // an emoji first on a page (past the first) is no reading of the code: the first that is
        val first = ranking[pager.from]?.takeUnless { it.emoji } ?: ranking[0]
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
        // space on a code with no candidate drops it, as on one only phrases are under ([hasEntry])
        if (!hasEntry()) return snapshot(commit = take(null))
        // first: a selection key past the page would have candidates decoded beyond it
        val at = pager.at(index)
        if (at < 0) return snapshot()
        val item = ranking[at] ?: return snapshot()
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
        pager.reset()
        predictions.predict()
    }

    // the prediction at [index] forgotten or blocked, as the long press offered: what may follow offered again
    private fun pressed(index: Int, offer: Offer): Snapshot {
        if (predictions.press(index, offer)) pager.reset()
        return snapshot()
    }

    private fun turnPredictions(to: Int): Snapshot {
        pager.turn(to, offeredTexts().size)
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
        if (item.asText || Offer.FORGET !in offersOf(item)) return snapshot()
        saved.forget(item.code, item.text)
        // forgotten by every input method (U-84): a phrase saved here or a word of theirs would be
        // back as theirs, and what was learned of it here goes by its text too
        shared.forget(item.text)
        return rankedAgain()
    }

    /** Blocks the candidate at [index] under the code typed alone (U-28), ranking the code again. */
    private fun block(index: Int): Snapshot {
        if (!learning) return snapshot()
        val item = ranking[index] ?: return snapshot(handled = input.isNotEmpty())
        if (item.asText || Offer.BLOCK !in offersOf(item)) return snapshot()
        val code = keyed(input.toString())
        // the block wins: a pin of it would show it all the same
        if (habits.pinned(code)?.text == item.text) habits.pin(code, item.text, "", on = false)
        habits.blockUnder(code, item.text, on = true)
        return rankedAgain()
    }

    /** Pins the candidate at [index] first under the code typed (U-28, 调频 on or off), or unpins it. */
    private fun pin(index: Int, on: Boolean): Snapshot {
        // 从自定义词组中删除 is an unpin, as pinyin has it (LearningMenu), offered learning or not
        val phrase = ranking[index]?.phrase
        if (!on && phrase != null) {
            phrases.remove(phrase.key, phrase.value)
            return rankedAgain()
        }
        if (!learning) return snapshot()
        val item = ranking[index] ?: return snapshot(handled = input.isNotEmpty())
        if (item.asText || (if (on) Offer.PIN else Offer.UNPIN) !in offersOf(item)) return snapshot()
        habits.pin(keyed(input.toString()), item.text, "", on)
        // back to the first page, unlike a block or a forget (rankedAgain): a pin is first there,
        // and an unpin is pressed on the first candidate
        update()
        return snapshot()
    }

    // the code ranked again after what a long press changed, on the page shown if it still has one
    private fun rankedAgain(): Snapshot {
        val shown = pager.page
        update()
        pager.keep(shown) { ranking[it] != null }
        // weighed again only on the first page, as pinyin: not to move what is shown
        if (pager.page != 0) unrefined.drop()
        return snapshot()
    }

    private fun turn(to: Int): Snapshot {
        // a code on screen keeps the key, candidates or not
        return if (pager.turn(to) { ranking[it] != null }) snapshot() else snapshot(handled = input.isNotEmpty())
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
        // an emoji or a phrase is text, no word: nothing to learn
        if (!learning || item.asText) {
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
        pager.reset()
    }

    /** Whether some entry, a saved phrase, a word of the user's or a custom phrase's key too, has a code [pattern] leads to. */
    private fun leadsAnywhere(pattern: String) =
        table.hasMatch(pattern, options.matchingKey) || saved.leadsAnywhere(pattern) || shared.leadsAnywhere(pattern) || phraseLeads(pattern)

    // the user's phrases offered here; a key that leads to one, by itself (no wildcard)
    private fun customPhrases() = phrases.offered(builtIns = false)

    private fun phraseLeads(pattern: String): Boolean = customPhrases().leadsTo(pattern)

    /** The phrases under [code], as offered now, each with the place its order says (0 based). */
    private fun phrasesUnder(code: String): List<Pair<Int, Item>> {
        val custom = customPhrases()
        if (code !in custom) return emptyList()
        return custom.offered(code, clock()).map { it.index to Item(code, it.text, phrase = it.phrase) }
    }

    private fun update() {
        pager.reset()
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
        val savedHere = if (alone) emptyList() else saved.saved(code).map { (c, text) -> Item(c, text) }
        val others = if (alone) emptyList() else shared.words(code).filterNot { (c, text) -> saved.isSaved(c, text) }.map { (c, text) -> Item(c, text) }
        val noSort = minOf(code.length, options.noSortInputLength)
        val found = Found(savedHere, entries, coded.size, others)
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
        val tail = Longer(code, alone, keys, found)
        // the 重码: the candidates (at [order]'s positions) of the whole code typed, past those kept in
        // order; sorted by length, none comes after the first longer code (what ownFirst moves forward
        // past pinyin entries is none either): the rest is not looked at. The longer codes from
        // [tail] come after it, so it is the same with them or without
        val longer = keys.indexOfFirst { ((it shr LENGTH_SHIFT) and MAX_LENGTH.toLong()) > code.length }
        val reach = if (code.length <= noSort) 0 else if (longer < 0) keys.size else longer
        // what is reordered; the rest is read on as merged. Unsorted, all of it: every merged word
        // under a longer code read at each key (some 80,000 a letter with rime-frost), which no
        // preset does, sortByCodeLength being on in all of them (review F3320)
        val order = tail.order.prefix(if (options.sortByCodeLength) maxOf(reach, tail.order.indexOfFirst { !found.spelt(it) } + 1) else Int.MAX_VALUE)
        ownFirst(order, found::spelt)
        val pinned = habits.pinned(here)?.text
        val blocked = { item: Item -> item.text in blockedHere || (!item.inTable && shared.blocked(item.text)) }
        // a code that commits itself is typed blind: no phrase stops it (D8), nor under a wildcard, which is no key
        val custom = if (alone) emptyList() else phrasesUnder(code)
        // the table's own first, before 调频's models and the habits ranked it: picked over another shown first, a use of it (F2739)
        val own = (order.asSequence() + tail.order.from(order.size)).map(tail::item).firstOrNull { !blocked(it) }?.text.orEmpty()
        val made = { placed: IntArray ->
            val shown = TableHabitOrder.pinnedFirst(byHabit(code, placed, reach, { found.item(it) == null && !found.spelt(it) }) { found.text(it, table) }, pinned) {
                found.text(it, table)
            }
            val read = tail.read(shown, pinned)
            Ranking(blocked, emojiOf, phrasesOf, own, custom) { if (read.hasNext()) tail.item(read.next()) else null }
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
     * The merged dictionaries' words under codes longer than [code], read as far as they are
     * shown ([MergedOrder]): they sort after every other candidate as long; none where the code
     * commits its lone entry ([alone]).
     */
    private inner class Longer(private val code: String, alone: Boolean, keys: LongArray, private val found: Found) {
        private val words = if (alone) emptySequence() else shared.longer(code, options.sortByCodeLength).filterNot { (c, text) -> saved.isSaved(c, text) }.map { (c, text) -> Item(c, text) }

        val order = MergedOrder(IntArray(keys.size) { (keys[it] and POSITION_MASK).toInt() }, keys, words.iterator(), ::before, found::addTail)

        fun item(at: Int): Item = found.item(at) ?: entryItem(found.entry(at))

        /**
         * The positions to show: [shown] (those reordered), then the rest as [order] has them; one
         * [pinned] past [shown] put first, as pinnedFirst would, its later place then the same
         * text again (shown once).
         */
        fun read(shown: IntArray, pinned: String?): Iterator<Int> {
            val front = pinned?.takeIf { p -> shown.none { found.text(it, table) == p } }?.let { p -> order.first({ found.text(it, table) == p }, word(p)) }
            return (listOfNotNull(front).asSequence() + shown.asSequence() + order.from(shown.size)).iterator()
        }

        // [text] one of them, none saved here: the candidate it is
        private fun word(text: String): Item? = table.encode(text)?.takeIf { it.length > code.length && it.startsWith(code) && !saved.isSaved(it, text) }
            ?.let { c -> shared.words(c).firstOrNull { it == c to text } }?.let { (c, t) -> Item(c, t) }

        // whether [item] sorts before the candidate of [key], codes sorted by length
        private fun before(key: Long, item: Item) = options.sortByCodeLength &&
            (key shr GROUP_SHIFT) and 1L == 1L && ((key shr LENGTH_SHIFT) and MAX_LENGTH.toLong()) > minOf(item.code.length, MAX_LENGTH)
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
     * twice; the first's [emoji] after the first [EmojiCandidates.AFTER] of them; the [custom]
     * phrases among them as pinyin places its own (CustomPhrases.place), at their order but never first.
     */
    private class Ranking(
        private val blocked: (Item) -> Boolean = { false },
        private val emoji: (String) -> List<String> = { emptyList() },
        private val phrases: (String) -> List<String> = { emptyList() },
        val own: String = "",
        // the custom phrases, each with the place its order says (0 based), in order
        private val custom: List<Pair<Int, Item>> = emptyList(),
        private val source: (Int) -> Item?,
    ) {
        private val items = ArrayList<Item>()
        private val seen = HashSet<String>()
        private var next = 0
        private var done = false
        private var emojiPlaced = false
        // the emoji worked out, put in one at a time: a phrase goes between them where its order says, as in pinyin
        private val aside = ArrayDeque<Item>()
        private var entries = 0 // the items from source
        private var nextCustom = 0
        private var lastCustom = -1 // where the phrase before went: the next goes after it

        operator fun get(i: Int): Item? {
            while (items.size <= i && !done) step()
            return items.getOrNull(i)
        }

        // one more candidate made, or one more looked at, or the rest once [source] has none left
        private fun step() {
            if (placeCustom(atEnd = false) || placeAside()) return
            if (!emojiPlaced && entries == EmojiCandidates.AFTER) {
                placeEmoji()
                return
            }
            val item = source(next++)
            if (item == null) {
                done = true
                // fewer than AFTER: the emoji after them all; then the phrases past the end, all known now
                if (!emojiPlaced) placeEmoji()
                while (placeCustom(atEnd = false) || placeAside() || placeCustom(atEnd = true)) continue
            } else if (!blocked(item) && seen.add(item.text)) {
                items += item
                entries++
            }
        }

        private fun placeAside(): Boolean = aside.removeFirstOrNull()?.let { items += it } != null

        /**
         * The next phrase put in, if its place is the next ([atEnd]: the list ends before it, so it
         * goes last). One of a text listed before it is that candidate, shown there; it stands for
         * one listed later, which is then left out ([seen]).
         */
        private fun placeCustom(atEnd: Boolean): Boolean {
            val (order, item) = custom.getOrNull(nextCustom) ?: return false
            val at = maxOf(order, lastCustom + 1, 1)
            if (!atEnd && items.size < at) return false
            nextCustom++
            if (!seen.add(item.text)) {
                lastCustom = items.size - 1
                return true
            }
            items += item
            lastCustom = items.size - 1
            return true
        }

        private fun placeEmoji() {
            emojiPlaced = true
            val first = items.firstOrNull { it.phrase == null } ?: return
            // seen too: an entry of the same text later is not listed again
            EmojiCandidates.pick(first.text, emoji, phrases) { it in seen }
                .forEach {
                    seen += it
                    aside += Item("", it, emoji = true)
                }
        }

        /** The one candidate of the code, the emoji aside: none if it has more. */
        fun only(): Item? {
            var found: Item? = null
            var i = 0
            while (true) {
                val item = get(i++) ?: return found
                // a phrase beside the one entry stops nothing: it commits itself as without it (D8)
                if (item.asText) continue
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
        val shown = ranking.page(pager.from, pager.size)
        val wild = wild()
        return Snapshot(
            commit = commit,
            preedit = options.shown(input.toString()),
            cursor = if (cursor < input.length) cursor else -1,
            candidates = shown.map { it.text },
            page = pager.page,
            hasPreviousPage = pager.hasPrevious,
            hasNextPage = ranking[pager.from + pager.size] != null,
            handled = handled,
            predicting = false,
            hints = if (!options.hint || shown.isEmpty()) emptyList() else shown.map { options.shown(hint(it, wild)) },
            total = ranking.size,
            first = pager.from,
            actionable = shown.isNotEmpty(),
            labels = options.selectionKeys,
            refines = unrefined.refines,
        )
    }

    private fun predicted(commit: String, handled: Boolean) = predicted(offeredTexts(), pager, commit, handled)

    private fun wild() = options.matchingKey != null && input.contains(options.matchingKey)

    /**
     * What is left of [item]'s code to type; all of it where a wildcard stands in the input, and
     * for a pinyin entry the table code it could have been typed by.
     */
    private fun hint(item: Item, wild: Boolean) = when {
        item.asText -> ""
        item.pinyin -> table.codeOf(item.text).orEmpty()
        wild -> item.code
        else -> item.code.substring(input.length)
    }

    // how a lookup ends: the code here empty, what it put in goes on as the table's own
    private inner class LookUpEnds : PinyinLookUp.Ends {
        override fun picked(text: String, record: Commit?): Snapshot {
            clear()
            // looked up as full pinyin learns it (U-28), and taken back as it is (U-03)
            wrote(text, null, record)
            return snapshot(commit = text)
        }

        override fun passed(text: String, record: Commit?, char: Char): Snapshot {
            journal.committed(text, record, learning)
            // then the key, not written here
            journal.unverify()
            clear()
            // then the key, the app's
            predictions.wrote(text + char, null)
            return snapshot(commit = text, handled = false)
        }

        override fun typed(text: String): Snapshot {
            clear()
            wrote(text, null)
            return snapshot(commit = text)
        }

        override fun left(action: Action): Snapshot {
            if (action is Action.Context) predictions.learnFrom(action)
            clear()
            moved((action as? Action.Context)?.before)
            return snapshot()
        }

        override fun dropped(): Snapshot {
            clear()
            return snapshot()
        }
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

// what may follow, shown: the [pager]'s page of [offered] (a session's own emoji, then the predictions)
private fun predicted(offered: List<String>, pager: Pager, commit: String, handled: Boolean): Snapshot {
    return Snapshot(
        commit = commit,
        preedit = "",
        candidates = pager.of(offered),
        page = pager.page,
        hasPreviousPage = pager.hasPrevious,
        hasNextPage = pager.hasNext(offered.size),
        handled = handled,
        predicting = true,
        // a prediction may be forgotten or blocked: see offers
        actionable = true,
        total = offered.size,
        first = pager.from,
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
