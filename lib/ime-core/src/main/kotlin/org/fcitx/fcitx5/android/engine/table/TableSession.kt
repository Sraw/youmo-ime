/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.EmojiCandidates
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.FieldJournal
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.OfferRules
import org.fcitx.fcitx5.android.engine.session.Pager
import org.fcitx.fcitx5.android.engine.session.Predictions
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.KeyedRows
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.reading.WordReading
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import java.util.Calendar

/**
 * A table input method (五笔, 仓颉 ...), ranked as libime does: codes up to [TableOptions.noSortInputLength] first
 * in table order, then the others shorter first; at equal length the table's own before outside words ([shared],
 * [saved]). A text shows once, at its shortest code. A code the table alone gives one entry takes no outside word,
 * so one who types it blind keeps it.
 *
 * With [TableOptions.autoSelect] a lone whole-code candidate commits itself, and a key past the longest code or
 * leading nowhere commits the first shown (顶字) or goes to the app; a code with no candidate (空码) is dropped. A
 * space within [SPACE_TAKEN_MS] of a self-commit is swallowed: users can't see a code was lone and type it anyway.
 *
 * [TableOptions.pinyinKey], or every letter while 拼 ([Action.LookUp]) is on, hands keys to [pinyin]
 * ([PinyinLookUp]). A table with its own pinyin entries (五笔拼音) mixes them in instead, as libime does: they
 * never come first and never commit themselves. Code keys typed before the end mend the code, never commit.
 *
 * With a [language], 重码 past the 简码 are ordered by the models when 调频 is on, then by habits ([byHabit]).
 * Commits are learned under the keys alone ([KeyedRows]), never into pinyin's order. A pinyin block never hides
 * the table's own entries. What is committed and deleted at once is taken back ([FieldJournal]); retyped with the
 * same keys it is a correction, with other keys new input (形码看键). Custom phrases go at their order but never
 * first or where a lone entry commits itself; they never commit themselves.
 */
class TableSession(
    private val table: TableDictionary,
    private val options: TableOptions = TableOptions(),
    pinyin: Session? = null,
    private val saved: SavedPhrases = SavedPhrases(table, "", { null }),
    private val shared: SharedWords = SharedWords.NONE,
    private val language: TableLanguage? = null,
    /** A clock that does not jump: how soon after a self-commit a space comes. */
    private val now: () -> Long = JvmClock::uptimeMillis,
    private val phrases: PhraseBook = PhraseBook(),
    private val clock: () -> Calendar = JvmClock::calendar,
    /** Whether 拼 is on: the engine's, so a session made anew keeps it. */
    private val lookUpMode: LookUpMode = LookUpMode(),
    /** What reading a tap as a neighbour key costs ([TableSlips]); null: keys read as typed. */
    slips: TableSlips.Costs? = null,
) : Session {

    class LookUpMode {
        var on = false
    }

    /** A candidate: a table entry ([index]), an outside word ([index] -1), an [emoji], or a custom [phrase]; a [pinyin] entry's [code] is its pinyin without the marker. */
    internal class Item(
        val code: String,
        val text: String,
        val index: Int = -1,
        val pinyin: Boolean = false,
        val emoji: Boolean = false,
        val phrase: CustomPhrases.Phrase? = null,
    ) {
        val asText get() = emoji || phrase != null

        val inTable get() = index >= 0
    }

    private val input = StringBuilder()
    private var cursor = 0 // in a lookup the pinyin's
    // a code with a key read as its neighbour never commits itself as typed, only on a key that cannot go on ([waits])
    private val slipReader = slips?.let { TableSlips(it, options, table, language, ::isCode, ::leadsAnywhere, ::topped) }
    private var ranking = Ranking.NONE
    private val pager = Pager(options.pageSize)

    // on while the lookup has the keys; the code here is empty then
    private val lookUp = pinyin?.let { PinyinLookUp(it, options.selectionKeys, { text -> table.codeOf(text)?.let(options::shown) }, LookUpEnds()) }
    private val lookingUp get() = lookUp?.on == true

    override var learning = true
        set(value) {
            field = value
            lookUp?.learning = value
        }

    private val habits: Habits get() = language?.habits ?: Habits.OFF

    private fun keyed(code: String) = Code(language?.kind.orEmpty(), code)

    private var committedItselfAt: Long? = null

    private val predictions = Predictions(
        language?.predictor, language?.words, language?.user, language?.block, language?.forgotten ?: {}, shared::blockable,
        language?.habits ?: Habits.OFF, language?.simplify ?: { it },
    ) { learning }

    // compared as the record has its text (a traditional table's)
    private val journal = FieldJournal({ habits }, language?.simplify ?: { it }, undone = { offers.undone(it) })

    private val offers = Offers()

    // set by [acted]: the key was a space swallowed after a self-commit
    private var spaceTaken = false

    private val spacePicks = SpacePicks()

    private val emojiOf: (String) -> List<String> = language?.emoji ?: { emptyList() }
    private val phrasesOf: (String) -> List<String> = language?.phrases ?: { emptyList() }

    private var ranked: Map<String, HabitRanker.Ranked<Int>> = emptyMap()

    // Enter between: the space is on a new line, typed
    override fun passedOn() {
        offers.words.end()
        journal.passedOn()
        committedItselfAt = null
        selfEmoji = emptyList()
        predictions.forgetBefore()
    }

    // a self-committed word's emoji are offered first after it; one picked replaces the word
    private var selfEmoji: List<String> = emptyList()
    private var selfCommitted = ""

    private fun offerEmojiOf(word: String) {
        selfEmoji = EmojiCandidates.pick(word, emojiOf, phrasesOf) { false }
        selfCommitted = word
    }

    private fun offering() = selfEmoji.isNotEmpty() || predictions.offered.isNotEmpty()

    private fun offeredTexts() = selfEmoji + predictions.offered.map { it.text }

    private fun pickOffered(index: Int): Snapshot {
        val n = selfEmoji.size
        if (index >= n) {
            val text = predictions.pick(index - n) ?: return snapshot()
            journal.committed(text, predictions.picked, learning)
            selfEmoji = emptyList()
            pager.reset()
            return snapshot(commit = text)
        }
        val emoji = selfEmoji[index]
        // the app deletes code points (FcitxInputMethodService.handleDeleteSurrounding)
        val replaced = selfCommitted.codePointCount(0, selfCommitted.length)
        selfEmoji = emptyList()
        // replaced by the app: the field is no longer as written
        journal.unverify()
        predictions.unoffer()
        predictions.forgetBefore()
        pager.reset()
        return snapshot(commit = emoji).copy(deleteBefore = replaced)
    }

    override fun apply(action: Action): Snapshot {
        spaceTaken = false
        when (action) {
            is Action.Remember -> return offers.remember(action)
            is Action.Unremember -> return offers.unremember(action)
            is Action.OfferShown -> return spacePicks.marked(snapshot().copy(wordOffer = offers.words.also { it.shown(action.id) }.offer))
            else -> Unit
        }
        val verified = journal.journal.verified
        return spacePicks.marked(offers.offered(action, spacePicks.apply(action), verified))
    }

    // one tap made the word, so deleting it whole right after takes it back as the notice's 撤销 would
    private inner class Offers {
        val words = TableWordOffers(
            table, options, saved,
            blocked = { code, text -> text in language?.blockedUnder?.invoke(keyed(code)).orEmpty() || shared.blocked(text) },
            boundaries = language?.words?.let { w -> { text -> w.boundaries(text) } },
        )

        private var remembered: SavedPhrases.Tapped? = null

        // the field's text reads: a tap is checked against it, so where it does not, a tap could only do nothing
        private var reads = true

        // what the run offers as [action] made [s]
        fun offered(action: Action, s: Snapshot, verified: Boolean): Snapshot {
            if (spaceTaken) return s.copy(wordOffer = words.offer)
            if (action is Action.Context) reads = action.said || action.before.isNotEmpty()
            if (words.followed(action, s, verified, composing = input.isNotEmpty())) remembered = null
            val due = s.commit.isNotEmpty() && s.preedit.isEmpty() && s.handled && !lookingUp && offersWords() && reads
            return if (due) s.copy(wordOffer = words.offer()) else s
        }

        private fun offersWords() = options.offerWords && learning && habits.learns && language != null

        // the offer's word tapped: the characters it was typed as are taken back and it is committed whole, saved under
        // its code; nothing if the offer, the field or the text before the cursor ([Action.Remember.before]) changed since
        fun remember(action: Action.Remember): Snapshot {
            val word = words.offer?.takeIf { it.id == action.id }?.words?.getOrNull(action.index)
            words.end()
            val idle = input.isEmpty() && !lookingUp
            if (word == null || !learning || !idle) return snapshot()
            val n = word.text.codePointCount(0, word.text.length)
            val simplify = language?.simplify ?: { it }
            if (journal.lastWhole(n)?.joinToString("") != simplify(word.text) || !action.before.endsWith(word.text)) return snapshot()
            if (!saved.remember(word.code, word.text)) return snapshot()
            predictions.goOnFrom(action.before.dropLast(word.text.length))
            val first = table.match(word.code, null).firstOrNull { table.code(it) == word.code }?.let(table::text).orEmpty()
            val h = habits
            val commit = if (h.learns) {
                val pick = KeyedRows.Pick(word.code, word.text, 'B', first)
                h.record(KeyedRows.of(language?.kind.orEmpty(), listOf(pick), listOf(Word(simplify(word.text), "")), predictions.wordBefore()), flags = predictions.learnFlags)
            } else {
                null
            }
            journal.merge(n, word.text, commit)
            predictions.wrote(word.text, language?.entryOf(word.text, theirs = true))
            SavedPhrases.Tapped(word.text, word.code, commit).let {
                remembered = it
                saved.tapped = it
            }
            predict()
            return snapshot(commit = word.text).copy(deleteBefore = n, remembered = word)
        }

        // the notice's 撤销: the word unsaved and its commit taken back, its text left
        fun unremember(action: Action.Unremember): Snapshot {
            val r = saved.tapped?.takeIf { it.text == action.text && it.code == action.code }
            saved.forget(action.code, action.text)
            r?.commit?.let(journal::takeBack)
            if (remembered === r) remembered = null
            saved.tapped = null
            if (input.isNotEmpty()) update()
            return snapshot()
        }

        // the remembered word deleted whole right after: unsaved with its commit
        fun undone(commit: Commit) {
            val r = remembered?.takeIf { it.commit === commit } ?: return
            saved.forget(r.code, r.text)
            remembered = null
            if (saved.tapped === r) saved.tapped = null
        }
    }

    // 空格上屏联想词
    private inner class SpacePicks {
        // when the predictions shown came, and whether a space just took one
        private var predictionsAt = 0L
        private var picked = false

        // a space now takes the first prediction (past any emoji), unless it follows a self-commit or a space that took one
        fun takes() = options.spacePicksPrediction && input.isEmpty() && !lookingUp && predictions.offered.isNotEmpty() &&
            !picked && committedItselfAt == null

        fun apply(action: Action): Snapshot {
            val was = if (offering()) offeredTexts() else null
            val picksSpace = action is Action.Key && action.char == ' ' && takes()
            val at = now()
            val s = if (picksSpace && at - predictionsAt >= SPACE_PICK_AFTER_MS) {
                journal.before(action, learning)
                committedItselfAt = null
                pickOffered(selfEmoji.size).also { picked = true }
            } else {
                picked = false
                acted(action)
            }
            // each new round, a tapped prediction's next too: a space right after it is still a space
            if (offering() && offeredTexts() != was) predictionsAt = at
            return s
        }

        // the bar draws as first what a space takes, none while it would type a space
        fun marked(s: Snapshot): Snapshot {
            val shown = options.spacePicksPrediction && input.isEmpty() && predictions.offered.isNotEmpty()
            return if (shown) s.copy(spacePicks = if (takes()) selfEmoji.size else -1, spaceMarks = true) else s
        }
    }

    private fun acted(action: Action): Snapshot {
        journal.before(action, learning)
        val taken = action is Action.Key && takesSpace(action.char)
        spaceTaken = taken
        committedItselfAt = null
        if (taken) return snapshot()
        if (offering()) {
            val n = selfEmoji.size
            when (action) {
                is Action.Pick -> return pickOffered(action.index)
                is Action.Select -> return pager.at(action.index).let { if (it >= 0) pickOffered(it) else snapshot() }
                Action.NextPage -> return turnPredictions(pager.page + 1)
                Action.PreviousPage -> return turnPredictions(pager.page - 1)
                is Action.Ink, is Action.Glide, is Action.Stroke -> return snapshot()
                is Action.Forget -> return if (action.index < n) snapshot() else pressed(action.index - n, Offer.FORGET)
                is Action.Block -> return if (action.index < n) snapshot() else pressed(action.index - n, Offer.BLOCK)
                is Action.Pin, is Action.Unpin -> return snapshot()
                else -> {
                    predictions.unoffer()
                    selfEmoji = emptyList()
                    pager.reset()
                }
            }
        }
        return dispatch(action)
    }

    private fun takesSpace(c: Char): Boolean {
        val at = committedItselfAt ?: return false
        return c == ' ' && now() - at <= SPACE_TAKEN_MS
    }

    // only [apply] asks the clock, once per key: asked here too, the two could fall either side of the second
    private fun mayTakeSpace(c: Char) = c == ' ' && committedItselfAt != null

    private fun dispatch(action: Action): Snapshot = when {
        action == Action.LookUp -> toggleLookUp()
        lookingUp -> lookUp!!.apply(action)
        else -> typed(action)
    }

    private fun typed(action: Action): Snapshot = when (action) {
        is Action.Key -> type(action.char, action.glided, action.touch)
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
            // nothing typed: Enter is the app's; a code as typed has no pick to take back
            if (text.isNotEmpty()) wrote(text, null)
            snapshot(commit = text, handled = text.isNotEmpty())
        }
        is Action.Ink, is Action.Glide, is Action.Stroke, is Action.Syllable, is Action.Letter, is Action.NineKey, Action.LookUp, is Action.Remember, is Action.Unremember, is Action.OfferShown,
        Action.DropPredictions -> snapshot()
        is Action.Cursor -> moveCursor(action.at)
        is Action.Step -> moveCursor(action.applyTo(cursor, input.length))
        Action.Reset, is Action.Context -> {
            if (action is Action.Context) predictions.learnFrom(action)
            moved((action as? Action.Context)?.before)
            clear()
            snapshot()
        }
    }

    /** A glide's held-back word, committed by the glide: written here, nothing learned. */
    fun heldBack(word: String) {
        offers.words.end()
        if (learning) journal.own(word, null)
    }

    private fun wrote(text: String, entry: Entry?, commit: Commit? = null) = predictions.wrote(text, entry).also { journal.committed(text, commit, learning) }

    override fun why(index: Int): Why? = when {
        lookingUp -> lookUp!!.why(index)
        offering() -> predictions.why(index - selfEmoji.size)
        else -> ranking[index]?.takeIf { it.phrase == null }?.let {
            TableHabitOrder.why(habits, keyed(input.toString()), it.text, ranked[it.text], saved.isSaved(input.toString(), it.text))
        }
    }

    override fun readingOf(index: Int): List<WordReading>? = if (lookingUp) lookUp!!.readingOf(index) else null

    override fun blockScope(index: Int): BlockScope? = if (lookingUp) BlockScope.EVERYWHERE else null

    override fun reread(): Snapshot? = when {
        lookingUp -> lookUp!!.reread()
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
        // a phrase is the settings', deletable here learning or not, as pinyin's
        !offering() && !lookingUp && ranking[index]?.phrase != null -> OfferRules.offers(learning, forgets = { false }, phrase = true)
        // not without learning: forgetting and blocking record the word
        !learning -> emptySet()
        offering() -> if (index < selfEmoji.size) emptySet() else predictions.offers(index - selfEmoji.size)
        lookingUp -> lookUp!!.offers(index)
        ranking[index]?.asText == true -> emptySet()
        ranking[index] != null -> offersOf(ranking[index]!!)
        else -> emptySet()
    }

    // forget only where something was learned (not the table's own while it keeps its order); pin and block under
    // the code typed, never under a wildcard
    private fun offersOf(item: Item): Set<Offer> {
        val learned = when {
            item.inTable -> options.orderByUse && habits.knowsUnder(keyed(input.toString()), item.text)
            // pinyin's own words are deleted in pinyin; a table deletes what it remembered
            else -> saved.isSaved(item.code, item.text)
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

    override fun reads(c: Char): Boolean = if (mayTakeSpace(c) || c == ' ' && spacePicks.takes()) {
        true
    } else if (lookingUp) {
        lookUp!!.reads(c)
    } else {
        isCode(c) ||
            (startsLookUp(c) && input.isEmpty()) ||
            (input.isNotEmpty() && c in options.selectionKeys)
    }

    private fun isCode(c: Char) = c in table.keys || c == options.matchingKey || (table.pinyinMarker != null && c in 'a'..'z')

    private fun modeLooksUp(c: Char) = lookUpMode.on && options.lookUpKey && c in 'a'..'z'

    private fun startsLookUp(c: Char) = (c == options.pinyinKey || modeLooksUp(c)) && lookUp != null

    private fun startLookUp(c: Char, commit: String): Snapshot {
        val context = Action.Context(predictions.before, predictions.cleared, predictions.learnFlags)
        return if (modeLooksUp(c)) lookUp!!.start(context, commit, first = c) else lookUp!!.start(context, commit, key = c)
    }

    /**
     * The 拼 key: on, a typed code goes into a lookup (unless it has non-letters no pinyin spells); off, a 拼-started
     * lookup goes back into a code: a picked word put in, the rest as code keys (`'` dropped), nothing committed or
     * learned. A lookup its key started stays one either way.
     */
    private fun toggleLookUp(): Snapshot {
        val look = lookUp?.takeIf { options.lookUpKey } ?: return snapshot()
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
                if (back.picked.isNotEmpty()) wrote(back.picked, null, back.record)
                val code = back.keys.filter { it != '\'' && isCode(it) }.let { if (table.pinyinMarker == null) it.take(table.maxLength) else it }
                if (code.isNotEmpty()) {
                    input.append(code)
                    cursor = input.length
                    update()
                }
                snapshot(commit = back.picked)
            }
            lookingUp -> look.shown()
            else -> snapshot()
        }
    }

    // letters only, and no wildcard past the first (五笔's gz is a code; 五笔拼音's z is pinyin's)
    private fun spellsPinyin() = input.withIndex().all { (i, c) ->
        c in 'a'..'z' && (i == 0 || c != options.matchingKey || table.pinyinMarker != null)
    }

    override fun candidates(from: Int, count: Int): List<Choice> {
        if (lookingUp) return lookUp!!.candidates(from, count)
        if (offering()) return offeredTexts().drop(from).take(count).map { Choice(it) }
        val wild = wild()
        return ranking.page(from, count).map { Choice(it.text, if (options.hint) options.shown(hint(it, wild)) else "") }
    }

    private fun type(typed: Char, glided: Boolean = false, touch: Touch? = null): Snapshot {
        // a slip is read first, so 顶字 and the 空码 drop never see it; one read so leads on, so is no selection key
        val c = if (touch == null) typed else slipped(typed, touch)
        val startsLookUp = startsLookUp(c)
        // a selection key picks while there are candidates, a code key too, as in fcitx
        val selects = if (input.isNotEmpty() && hasEntry()) options.selectionKeys.indexOf(c) else -1
        if (selects >= 0) return select(selects)
        val isCode = isCode(c)
        if (!isCode && !(startsLookUp && input.isEmpty())) {
            val text = if (input.isEmpty()) "" else autoCommit()
            // the key is in the field: a Backspace now deletes it, not a commit; not a glide's key, which the glide drops
            predictions.wrote(c.toString(), null).also { if (!glided) journal.unverify() }
            return snapshot(commit = text, handled = false)
        }
        var commit = ""
        if (options.autoSelect && typingAtEnd() && endsCode(c)) commit = autoCommit()
        // after a self-commit too: 叫 by kkkk, then z looks the next one up
        if (startsLookUp && input.isEmpty()) return startLookUp(c, commit)
        // a key no code starts is the app's, as in fcitx: a table's , and . may be its punctuation
        if (input.isEmpty() && !leadsAnywhere(c.toString())) {
            predictions.wrote(c.toString(), null).also { if (!glided) journal.unverify() }
            return snapshot(commit = commit, handled = false)
        }
        return typeInto(c, commit)
    }

    // slips at the code's end only: past its first key, short of a full code or end key, with no wildcard
    private fun slipped(c: Char, touch: Touch): Char =
        slipReader?.takeIf { typingAtEnd() && !wild() && input.length < table.maxLength && input.last() !in options.endKeys }?.read(c, touch, input) ?: c

    private fun topped(): String? = if (options.autoSelect && hasEntry() && topsOnNoMatch()) firstShown()?.text else null

    private fun topsOnNoMatch() = reaches(options.noMatchAutoSelectLength) || waits()

    // a slip-read code whose lone candidate would have committed itself waits to be seen; a key that cannot go on
    // commits it (the user's call)
    private fun waits() = slipReader?.marks() == true && ranking.only()?.let { !it.pinyin && it.phrase == null && commitsWhole(it.code) } == true

    private fun typeInto(c: Char, committed: String): Snapshot {
        var commit = committed
        // a full code mended: the key has nowhere to go
        if (table.pinyinMarker == null && input.length >= table.maxLength && !typingAtEnd()) return snapshot(commit = commit)
        slipReader?.inserted(cursor, input.length)
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

    private fun typingAtEnd() = input.isNotEmpty() && cursor == input.length

    /** Whether [c] cannot go on the code: full (pinyin has no limit), ended by an end key, or leading nowhere where that commits. */
    private fun endsCode(c: Char) =
        (table.pinyinMarker == null && input.length >= table.maxLength) ||
            input.last() in options.endKeys ||
            (topsOnNoMatch() && !leadsAnywhere("$input$c"))

    private fun commitsItself(item: Item): Boolean {
        // a pinyin entry waits to be picked, shown for the code to learn; so does a phrase
        if (item.pinyin || item.phrase != null) return false
        // never a slip-read guess committed unseen
        if (slipReader?.marks() == true) return false
        return commitsWhole(item.code)
    }

    private fun commitsWhole(code: String) =
        options.autoSelect && code == input.toString() && input.length <= table.maxLength && reaches(options.autoSelectLength)

    private fun reaches(limit: Int) = limit != 0 && (limit < 0 || input.length >= limit)

    // a phrase first means only phrases (a code reached by them alone): a key never commits those
    private fun hasEntry() = ranking[0]?.let { it.phrase == null } == true

    /** Commits the first candidate on the page shown (the first of all where that is an emoji); none where only phrases are. */
    private fun autoCommit(): String = if (hasEntry()) take(firstShown()) else take(null)

    private fun firstShown() = ranking[pager.from]?.takeUnless { it.emoji } ?: ranking[0]

    private fun backspace(): Snapshot {
        if (input.isEmpty()) {
            journal.backspace()
            predictions.deleted()
            return snapshot(handled = false)
        }
        if (cursor == 0) return snapshot()
        input.deleteCharAt(--cursor)
        slipReader?.deleted(cursor)
        update()
        return snapshot()
    }

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

    private fun offering(text: String): Snapshot {
        predict()
        return snapshot(commit = text)
    }

    private fun predict() {
        pager.reset()
        predictions.predict()
    }

    private fun pressed(index: Int, offer: Offer): Snapshot {
        if (predictions.press(index, offer)) pager.reset()
        return snapshot()
    }

    private fun turnPredictions(to: Int): Snapshot {
        pager.turn(to, offeredTexts().size)
        return snapshot()
    }

    /** The text before the cursor is [to] (none: reset): the last word still precedes it only if it ends so. */
    private fun moved(to: String?) {
        val last = predictions.lastEntry
        if (to == null) predictions.forgetBefore() else predictions.goOnFrom(to)
        predictions.lastEntry = last?.takeIf { to != null && to.endsWith(it.text) }
    }

    private fun forget(index: Int): Snapshot {
        if (!learning) return snapshot()
        val item = ranking[index] ?: return snapshot(handled = input.isNotEmpty())
        if (item.asText || Offer.FORGET !in offersOf(item)) return snapshot()
        if (saved.forget(item.code, item.text) != null) {
            // the same text from pinyin's words would still show here: blocked under this code alone, pinyin's kept
            if (shared.words(item.code).any { it.second == item.text }) habits.blockUnder(keyed(item.code), item.text, on = true)
        } else if (item.inTable) {
            // this code's order alone: what pinyin learned of the text is pinyin's to forget
            habits.forgetUnder(keyed(input.toString()), item.text)
        }
        return rankedAgain()
    }

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

    private fun pin(index: Int, on: Boolean): Snapshot {
        // 从自定义词组中删除 is an unpin, offered learning or not
        val phrase = ranking[index]?.phrase
        if (!on && phrase != null) {
            phrases.remove(phrase.key, phrase.value)
            return rankedAgain()
        }
        if (!learning) return snapshot()
        val item = ranking[index] ?: return snapshot(handled = input.isNotEmpty())
        if (item.asText || (if (on) Offer.PIN else Offer.UNPIN) !in offersOf(item)) return snapshot()
        habits.pin(keyed(input.toString()), item.text, "", on)
        // back to the first page, unlike a block or a forget: a pin is first there, and an unpin is pressed on the first
        update()
        return snapshot()
    }

    private fun rankedAgain(): Snapshot {
        val shown = pager.page
        update()
        pager.keep(shown) { ranking[it] != null }
        return snapshot()
    }

    private fun turn(to: Int): Snapshot {
        return if (pager.turn(to) { ranking[it] != null }) snapshot() else snapshot(handled = input.isNotEmpty())
    }

    private fun take(item: Item?): String {
        val typed = input.toString()
        // a code with the wildcard in it is no code to learn, pin or block under
        val wild = wild()
        val first = ranking[0]?.text.orEmpty()
        val own = ranking.own
        clear()
        if (item == null) return ""
        if (!learning || item.asText) {
            wrote(item.text, null)
            return item.text
        }
        val entry = language?.entryOf(item.text, theirs = !item.inTable)
        val h = habits
        val commit = if (h.learns) {
            val pick = KeyedRows.Pick(typed, item.text, if (item.text == first) 'A' else 'B', first, own = item.text == own)
            val picks = if (wild) emptyList() else listOf(pick)
            val word = Word(language?.simplify?.invoke(item.text) ?: item.text, "")
            h.record(KeyedRows.of(language?.kind.orEmpty(), picks, listOf(word), predictions.wordBefore()), flags = predictions.learnFlags)
        } else null
        // typed again with the same keys, its own row is the correction (形码看键)
        wrote(item.text, entry, commit)
        return item.text
    }

    private fun clear() {
        input.setLength(0)
        cursor = 0
        slipReader?.clear()
        ranking = Ranking.NONE
        pager.reset()
    }

    private fun leadsAnywhere(pattern: String) =
        table.hasMatch(pattern, options.matchingKey) || saved.leadsAnywhere(pattern) || shared.leadsAnywhere(pattern) || phraseLeads(pattern)

    private fun customPhrases() = phrases.offered(builtIns = false)

    private fun phraseLeads(pattern: String): Boolean = customPhrases().leadsTo(pattern)

    private fun phrasesUnder(code: String): List<Pair<Int, Item>> {
        val custom = customPhrases()
        if (code !in custom) return emptyList()
        return custom.offered(code, clock()).map { it.index to Item(code, it.text, phrase = it.phrase) }
    }

    private fun update() {
        pager.reset()
        ranking = if (input.isEmpty()) Ranking.NONE else rank(input.toString())
    }

    /** [code]'s candidates ranked lazily: outside words after the table's own of equal length (none where a lone entry commits itself), blocked ones out, the pin first. */
    private fun rank(code: String): Ranking {
        val coded = table.match(code, options.matchingKey)
        val spelt = table.matchPinyin(code, prefix = pinyinPrefix(code))
        // pinyin entries last, so a code and its pinyin as long rank the code first
        val entries = if (spelt.isEmpty()) coded else coded + spelt
        val here = keyed(code)
        val blockedHere = language?.blockedUnder?.invoke(here).orEmpty()
        // what the code does not show: blocked under it, or 生僻字屏蔽; the lone one left commits itself, a code whose one entry is held commits none
        val held = language?.held
        val hidden: (String) -> Boolean = if (held == null) blockedHere::contains else { t -> t in blockedHere || held(t) }
        // the table's remembered words under the code as typed, a lone code's too: the user tapped to have them there.
        // They alone join a lone code (no longer the only one, it commits nothing itself): pinyin's words and phrases stay out
        val savedHere = saved.saved(code).filterNot { hidden(it.second) }.map { (c, text) -> Item(c, text) }
        val alone = spelt.isEmpty() && alone(coded, hidden)
        val others = if (alone) emptyList() else shared.words(code).filterNot { (c, text) -> saved.isSaved(c, text) }.map { (c, text) -> Item(c, text) }
        val noSort = minOf(code.length, options.noSortInputLength)
        val found = Found(savedHere, entries, coded.size, others)
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
        // the 重码: whole-code candidates past those kept in order; sorted by length, none after the first longer code,
        // so the rest (and [tail]) is not looked at
        val longer = keys.indexOfFirst { ((it shr LENGTH_SHIFT) and MAX_LENGTH.toLong()) > code.length }
        val reach = if (code.length <= noSort) 0 else if (longer < 0) keys.size else longer
        // unsorted, every merged word under a longer code would be read at each key; all presets sort
        val order = tail.order.prefix(if (options.sortByCodeLength) maxOf(reach, tail.order.indexOfFirst { !found.spelt(it) } + 1) else Int.MAX_VALUE)
        ownFirst(order, found::spelt)
        val pinned = habits.pinned(here)?.text
        val blocked = { item: Item -> hidden(item.text) || (!item.inTable && shared.blocked(item.text)) }
        // a self-committing code is typed blind: no phrase stops it, nor under a wildcard
        val custom = if (alone) emptyList() else phrasesUnder(code)
        // the table's own first before reranking: picked over another shown first is a use of it
        val own = (order.asSequence() + tail.order.from(order.size)).map(tail::item).firstOrNull { !blocked(it) }?.text.orEmpty()
        val made = { placed: IntArray ->
            val shown = TableHabitOrder.pinnedFirst(byHabit(code, placed, reach, { found.item(it) == null && !found.spelt(it) }) { found.text(it, table) }, pinned) {
                found.text(it, table)
            }
            val read = tail.read(shown, pinned)
            Ranking(blocked, emojiOf, phrasesOf, own, custom) { if (read.hasNext()) tail.item(read.next()) else null }
        }
        if (reach == 0) return made(order)
        return weighed(code, order, reach, { k -> kind(code, found, order[k]) }, hidden, made)
    }

    /** Whether [coded] is one text, not [hidden], whose code commits itself (a row twice is one text); decided at the second text. */
    private fun alone(coded: IntArray, hidden: (String) -> Boolean): Boolean {
        var text: String? = null
        for (e in coded) {
            val t = table.text(e)
            if (t == text || hidden(t)) continue
            if (text != null || !commitsWhole(table.code(e))) return false
            text = t
        }
        return text != null
    }

    private fun byHabit(code: String, original: IntArray, reach: Int, own: (Int) -> Boolean, text: (Int) -> String): IntArray {
        val ordered = if (reach == 0 || wild()) null else TableHabitOrder.order(habits, options.orderByUse, keyed(code), original, reach, own, text)
        ranked = ordered?.second.orEmpty()
        return ordered?.first ?: original
    }

    /** What candidate [at] is among [code]'s 重码: a whole-code table entry, else [OTHER]. */
    private fun kind(code: String, found: Found, at: Int): Int = when {
        found.spelt(at) || found.item(at) != null -> OTHER
        else -> found.entry(at).let { e -> if (table.codeLength(e) == code.length) e else OTHER }
    }

    /** The merged dictionaries' longer-code words, read as far as shown ([MergedOrder]): after every candidate as long; none where the code commits its lone entry. */
    private inner class Longer(private val code: String, alone: Boolean, keys: LongArray, private val found: Found) {
        private val words = if (alone) emptySequence() else shared.longer(code, options.sortByCodeLength).filterNot { (c, text) -> saved.isSaved(c, text) }.map { (c, text) -> Item(c, text) }

        val order = MergedOrder(IntArray(keys.size) { (keys[it] and POSITION_MASK).toInt() }, keys, words.iterator(), ::before, found::addTail)

        fun item(at: Int): Item = found.item(at) ?: entryItem(found.entry(at))

        /** The positions to show: [shown], then the rest of the order; one [pinned] past [shown] put first, its later place skipped. */
        fun read(shown: IntArray, pinned: String?): Iterator<Int> {
            val front = pinned?.takeIf { p -> shown.none { found.text(it, table) == p } }?.let { p -> order.first({ found.text(it, table) == p }, word(p)) }
            return (listOfNotNull(front).asSequence() + shown.asSequence() + order.from(shown.size)).iterator()
        }

        private fun word(text: String): Item? = table.encode(text)?.takeIf { it.length > code.length && it.startsWith(code) && !saved.isSaved(it, text) }
            ?.let { c -> shared.words(c).firstOrNull { it == c to text } }?.let { (c, t) -> Item(c, t) }

        private fun before(key: Long, item: Item) = options.sortByCodeLength &&
            (key shr GROUP_SHIFT) and 1L == 1L && ((key shr LENGTH_SHIFT) and MAX_LENGTH.toLong()) > minOf(item.code.length, MAX_LENGTH)
    }

    /** [made] with [code]'s 重码 placed by the models, hidden ones left in place; table order where 调频 is off or a wildcard was typed. */
    private fun weighed(code: String, order: IntArray, reach: Int, kind: (Int) -> Int, hidden: (String) -> Boolean, made: (IntArray) -> Ranking): Ranking {
        val language = language?.takeIf { options.orderByUse } ?: return made(order)
        if (options.matchingKey != null && code.contains(options.matchingKey)) return made(order)
        val kinds = IntArray(reach, kind)
        val rankable = kinds.indices.filter { kinds[it] >= 0 && !hidden(table.text(kinds[it])) }
        if (rankable.size < 2) return made(order)
        val (prev2, prev) = TextWords.previous(language.words.lastTwo(predictions.before))
        val texts = rankable.map { table.text(kinds[it]) }
        val scores = FloatArray(rankable.size) { k -> language.words.logProb(texts[k], prev2, prev) }
        val byScore = rankable.indices.sortedByDescending { scores[it] }
        val h = Homographs(order, rankable.toIntArray(), byScore.map { order[rankable[it]] }.toIntArray(), byScore.map { texts[it] }, byScore.map { scores[it] }, made)
        return h.place(language.reranker?.pick(predictions.context, h.texts, h.scores) ?: 0)
    }

    /** A code's 重码 at positions [at] with their model [scores]; [place] puts one first in a copy of [order], since a Ranking reads its order lazily. */
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

    /** Whether pinyin entries [code] starts are candidates too: libime's heuristic, from the second key with every preset. */
    private fun pinyinPrefix(code: String): Boolean {
        val n = code.length
        return n > 1 && (n >= options.autoSelectLength || n > table.maxLength || n >= options.noMatchAutoSelectLength)
    }

    /**
     * Ranked candidates made lazily, each text once at its shortest code (unsorted, the first stays; no preset is
     * unsorted), [blocked] ones out, [emoji] after the first [EmojiCandidates.AFTER], custom phrases at their order
     * but never first (CustomPhrases.place).
     */
    private class Ranking(
        private val blocked: (Item) -> Boolean = { false },
        private val emoji: (String) -> List<String> = { emptyList() },
        private val phrases: (String) -> List<String> = { emptyList() },
        val own: String = "",
        private val custom: List<Pair<Int, Item>> = emptyList(),
        private val source: (Int) -> Item?,
    ) {
        private val items = ArrayList<Item>()
        private val seen = HashSet<String>()
        private var next = 0
        private var done = false
        private var emojiPlaced = false
        // emoji put in one at a time: a phrase goes between them where its order says, as in pinyin
        private val aside = ArrayDeque<Item>()
        private var entries = 0
        private var nextCustom = 0
        private var lastCustom = -1

        operator fun get(i: Int): Item? {
            while (items.size <= i && !done) step()
            return items.getOrNull(i)
        }

        private fun step() {
            if (placeCustom(atEnd = false) || placeAside()) return
            if (!emojiPlaced && entries == EmojiCandidates.AFTER) {
                placeEmoji()
                return
            }
            val item = source(next++)
            if (item == null) {
                done = true
                if (!emojiPlaced) placeEmoji()
                while (placeCustom(atEnd = false) || placeAside() || placeCustom(atEnd = true)) continue
            } else if (!blocked(item) && seen.add(item.text)) {
                items += item
                entries++
            }
        }

        private fun placeAside(): Boolean = aside.removeFirstOrNull()?.let { items += it } != null

        /** The next phrase put in, if its place is next ([atEnd]: last); one of a text already listed is that candidate, and one listed later is left out. */
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
            EmojiCandidates.pick(first.text, emoji, phrases) { it in seen }
                .forEach {
                    seen += it
                    aside += Item("", it, emoji = true)
                }
        }

        fun only(): Item? {
            var found: Item? = null
            var i = 0
            while (true) {
                val item = get(i++) ?: return found
                // a phrase beside the lone entry stops nothing: it commits itself as without it
                if (item.asText) continue
                if (found != null) return null
                found = item
            }
        }

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
        )
    }

    private fun predicted(commit: String, handled: Boolean) = predicted(offeredTexts(), pager, commit, handled)

    private fun wild() = options.matchingKey != null && input.contains(options.matchingKey)

    /** What is left of [item]'s code to type; all of it under a wildcard; for a pinyin entry, its table code. */
    private fun hint(item: Item, wild: Boolean) = when {
        item.asText -> ""
        item.pinyin -> table.codeOf(item.text).orEmpty()
        wild -> item.code
        else -> item.code.substring(input.length)
    }

    private inner class LookUpEnds : PinyinLookUp.Ends {
        override fun picked(text: String, record: Commit?): Snapshot {
            clear()
            wrote(text, null, record)
            return snapshot(commit = text)
        }

        override fun passed(text: String, record: Commit?, char: Char): Snapshot {
            journal.committed(text, record, learning)
            journal.unverify()
            clear()
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

        private const val OTHER = -1

        private const val SPACE_TAKEN_MS = 1000L

        // predictions shown less than this: a space is a space (two quick spaces after a pick add no unseen word)
        private const val SPACE_PICK_AFTER_MS = 300L
    }
}

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
