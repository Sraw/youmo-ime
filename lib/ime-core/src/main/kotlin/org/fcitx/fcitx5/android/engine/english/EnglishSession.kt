/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlidePath
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnCommit
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.OfferRules
import org.fcitx.fcitx5.android.engine.session.Pager
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import java.util.BitSet

/**
 * English typed a word at a time, as Gboard: the typed word and what it may be ([EnglishDecoder]), scored after
 * the words before the cursor. A non-word is corrected (dont as don't, teh as the) and space commits it;
 * backspace right after takes it back, and that word is never corrected again. A committed word gets a space and
 * predictions; punctuation right after moves the space behind it, a second space makes a full stop.
 *
 * Keys it does not [read][reads] are the app's. The text before the cursor is kept only while [learning]. On the
 * nine keys each key is any of its letters, exact length first, as old T9, with no slip correction or editing.
 */
class EnglishSession(
    private val data: EnglishData?,
    pageSize: Int = DEFAULT_PAGE_SIZE,
    private val costs: TypingCosts = TypingCosts(),
    maxCorrection: Float = MAX_CORRECTION,
    user: EnglishUser? = null,
    coarseWords: List<String> = CoarseWords.LIST,
    private val misspellings: Map<String, String> = Misspellings.MAP,
    private val nineKeys: NineKeyLetters = NineKeyLetters.STANDARD,
    private val phrases: EnglishPhrases = EnglishPhrases(),
    private val blocks: EnglishBlocks? = null,
) : Session {

    private val user = user ?: EnglishUser()

    private val autocorrect = Autocorrect(maxCorrection, misspellings)

    private val decoder = data?.let { d -> EnglishDecoder(d, costs) { context, word -> scored(d, context, word) } }

    private val nine by lazy { data?.let { d -> NineKeyWords(d, nineKeys) { context, word -> scored(d, context, word) } } }

    private val glider = data?.let { d -> GlideDecoder(d, score = { context, word -> scored(d, context, word) }) }

    // lazy: the session is made on the typing thread, and the lookups would build the word index there
    private val coarse by lazy { data?.let { d -> CoarseWords.of({ d.id(it) }, coarseWords) } ?: CoarseWords.NONE }

    private val misspelt by lazy {
        val ids = BitSet()
        data?.let { d -> for (word in misspellings.keys) d.id(word).takeIf { it != NgramModel.NO_WORD }?.let { ids.set(it) } }
        ids
    }

    // a coarse word the user typed is theirs to offer; a misspelling once theirs is never corrected
    private fun offered(word: Int): Boolean {
        if (blocks?.has(checkNotNull(data).word(word)) == true) return false
        val misspelling = misspelt[word]
        if (!misspelling && !coarse.has(word)) return true
        val text = checkNotNull(data).word(word)
        return if (misspelling) user.neverCorrected(text) else user.count(text) > 0f
    }

    private val input = TypedKeys()
    private var cursor = 0

    // a long-pressed letter keeps the word the nine keys' though no digit is left
    private var pinned = false

    private fun nineKeyed(): Boolean = input.isNotEmpty() && (pinned || input.any { it in '2'..'9' })

    // the nine keys' first letters where no word fits: a guess, not a word
    private fun guessed(): Boolean = phrases.noWord(candidates) && input.any { it in '2'..'9' }

    override val picksByDigit: Boolean get() = nineKeyed()
    private var candidates: List<String> = emptyList()
    private val pager = Pager(pageSize)

    private var correction: String? = null

    private var corrected: Corrected? = null

    private var keepTyped = false

    // a taken-back correction already learned this word: not again on commit
    private var reverted: String? = null

    private val corrections = Corrections()
    private var recalled: Corrections.Found? = null

    private var learnFlags = 0

    // what this session committed last while the cursor stayed after it: a trailing space is its own to take back
    private var last = ""

    // the end of the text before the cursor: from the app's Action.Context if [known], else only what was committed
    private var recent = ""
    private var known = false
    private var cut = false

    // where the field's own text starts in [recent], -1 if absent: before it, text from before the field cleared
    // itself, for scoring only
    private var clearedAt = -1

    // the end of the text before the cursor, learning or not, for Backspaces, glide spacing and capitals; never learned
    private var edge = ""

    override var learning = true
        set(value) {
            field = value
            if (!value) {
                recent = ""
                clearedAt = -1
                known = false
                corrections.clear()
            }
        }

    override fun reads(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || (c == '\'' || c == '-' && !nineKeyed()) && input.isNotEmpty()

    override val takesEveryCharacter: Boolean get() = true

    private class Glided(val committed: String, val words: List<String>, val lead: String = "", val edge: String = "", val seqs: List<Long> = emptyList())

    private var glided: Glided? = null

    override fun apply(action: Action): Snapshot {
        val r = recalled
        recalled = null
        if (r != null && (action == Action.Pick(0) || action == Action.Select(0))) return recall(r)
        // the offered typed word goes with the offer: a later pick would commit after the correction instead of in its place
        if (r != null) unoffer()
        val g = glided?.takeIf { input.isEmpty() && it.committed == last }
        if (!keepsGlided(action)) glided = null
        return when {
            g == null -> act(action)
            // the glide's own space is there already: not a full stop (any space: glided is part of the action's equality)
            action is Action.Key && action.char == ' ' -> {
                candidates = predictions()
                pager.reset()
                snapshot()
            }
            action == Action.Backspace -> unglide(g, null)
            action is Action.Pick -> g.words.getOrNull(action.index)?.let { unglide(g, it) } ?: snapshot()
            action is Action.Select && pager.at(action.index) >= 0 -> g.words.getOrNull(pager.at(action.index))?.let { unglide(g, it) } ?: snapshot()
            action is Action.Block -> blockGlided(g, action.index)
            else -> act(action)
        }
    }

    // 联想's × too: it hides the list, the glided word still whole for backspace
    private fun keepsGlided(action: Action) = when (action) {
        is Action.Pick, is Action.Select, is Action.Block, Action.Backspace, Action.NextPage, Action.PreviousPage, Action.DropPredictions -> true
        else -> false
    }

    private fun act(action: Action): Snapshot = when (action) {
        is Action.Key -> key(action.char, action.touch)
        is Action.Glide -> glide(action.glide)
        Action.Backspace -> backspace()
        is Action.Select -> select(action.index)
        is Action.Pick -> pick(action.index)
        Action.NextPage -> turn(pager.page + 1)
        Action.PreviousPage -> turn(pager.page - 1)
        Action.CommitRaw -> commitRaw()
        is Action.NineKey -> nineKey(action.digit, action.upper, action.touch)
        is Action.Cursor -> cursor(action.at)
        // the keyboard commits the word on an arrow (Keyboard.whileComposing): none comes here
        is Action.Step -> snapshot(handled = false)
        is Action.Context -> context(action)
        Action.Reset -> {
            forgetText()
            snapshot()
        }
        is Action.Forget -> forget(action.index)
        is Action.Unpin -> unpin(action.index)
        is Action.Block -> block(action.index)
        Action.DropPredictions -> { unoffer(); snapshot() }
        is Action.Ink, is Action.Stroke, is Action.Pin, is Action.Syllable, is Action.Letter, Action.LookUp, is Action.Remember, is Action.Unremember, is Action.OfferShown -> snapshot()
    }

    private fun commitRaw() = if (input.isEmpty()) snapshot(handled = false) else commit(typed(), space = false, learned = false).copy(handled = false)

    private fun unoffer() { candidates = emptyList(); pager.reset() }

    private fun cursor(at: Int): Snapshot {
        if (input.isEmpty() || nineKeyed()) return snapshot(handled = false)
        cursor = at.coerceIn(0, input.length)
        return snapshot()
    }

    // 从自定义词组中删除 is an unpin (LearningMenu): only phrases are pinned here

    private fun unpin(index: Int): Snapshot = also { if (phrases.delete(candidates, index)) read() }.snapshot()

    override fun reread(): Snapshot? {
        if (glided != null || recalled != null) return null
        if (input.isEmpty() && last.isEmpty()) return null
        if (input.isNotEmpty()) read() else candidates = predictions().also { pager.reset() }
        return snapshot()
    }

    override val rereads get() = true

    override fun fieldFlags(flags: Int) {
        learnFlags = flags
    }

    override fun candidates(from: Int, count: Int): List<Choice> =
        candidates.subList(minOf(from, candidates.size), minOf(from + count, candidates.size)).map { Choice(it) }

    // forgetting records no word, only removes counts: offered in a password field too
    override fun offers(index: Int): Set<Offer> = if (phrases.at(candidates, index) != null) OfferRules.offers(learning, forgets = { false }, phrase = true)
        else OfferRules.offers(learning = true, forgets = { candidates.getOrNull(index)?.let(::learnedAs) != null }, blocks = { blockable(index) })

    private fun learnedAs(text: String): String? =
        text.takeIf { user.count(it) > 0f } ?: text.replaceFirstChar(Char::lowercaseChar).takeIf { user.count(it) > 0f }

    // if no word fits, the typed word stays and the glide decodes after it as if space ended it
    private fun glide(glide: Glide?): Snapshot {
        glide ?: return snapshot()
        if (glider == null) return snapshot()
        // must stay in step with key(' ') below
        val ending = if (input.isNotEmpty()) "${correction ?: typed()} " else ""
        val lastChar = if (recent.length == clearedAt) null else recent.lastOrNull() ?: edge.lastOrNull()
        val space = if (ending.isEmpty() && lastChar?.let { it.isLetterOrDigit() || it in SPACED_AFTER } == true) " " else ""
        val ahead = if (learning) recent + ending + space else recent
        val context = EnglishText.context(ahead, start = known && !cut)
        val words = glideWords(glide, context)
        val word = words.firstOrNull() ?: return snapshot()
        val ended = if (ending.isNotEmpty()) key(' ').commit else ""
        val before = edge
        corrected = null
        wrote(space)
        val seqs = learn(word, word, EnCommit.TYPED)
        val made = "$word "
        last = made
        wrote(made)
        glided = Glided(made, words, space, before, seqs)
        candidates = words
        pager.reset()
        return snapshot(commit = ended + space + made)
    }

    private fun glideWords(glide: Glide, before: List<String>): List<String> {
        val data = checkNotNull(data)
        val context = checkNotNull(decoder).context(before)
        scoredPrev = previous(before)
        val found = checkNotNull(glider).decode(glide.path, glide.layout, context, GLIDE_SEARCHED)
            .filter { offered(it.word) }
            .map { data.word(it.word) to it.score }
        return (found + ownGlided(data, glide, scoredPrev))
            .sortedByDescending { it.second }
            // a capital only where Shift gives one: the data's sentence-start "Hello" is "hello"; a name keeps its own
            .map { (w, _) -> inSentence(w, first = true) }
            .map(::capitalI)
            .map { w -> if (glide.capsLock) w.uppercase() else if (glide.shift) w.replaceFirstChar(Char::uppercaseChar) else w }
            .distinct()
            .take(SUGGESTED + 1)
    }

    // the user's words the data lacks, which the glide's trie has not: scored by the user's counts alone
    private fun ownGlided(data: EnglishData, glide: Glide, prev: String?): List<Pair<String, Float>> {
        refreshUser(data)
        if (userOnly.isEmpty()) return emptyList()
        val path = GlidePath(glide.path, glide.layout, GlidePath.Weights(), EnglishDecoder.MAX_KEYS)
        if (path.points == 0) return emptyList()
        return userOnly.filterNot(::blocked).mapNotNull { word ->
            val key = EnglishData.key(word)
            val fits = key.length in 1..EnglishDecoder.MAX_KEYS && key.indices.all { path.step(it, key[it]) }
            val cost = if (fits) path.cost(key.length) else null
            cost?.let { word to user.score(Float.NEGATIVE_INFINITY, prev, word) - it }
        }
    }

    private fun unglide(g: Glided, other: String?): Snapshot {
        recent = recent.dropLast(g.committed.length)
        g.seqs.forEach(user::undo)
        if (other == null) {
            recent = recent.dropLast(g.lead.length)
            edge = g.edge
            last = ""
            candidates = emptyList()
            return snapshot(deleteBefore = g.committed.length + g.lead.length)
        }
        val seqs = learn(other, other, EnCommit.PICKED)
        val made = "$other "
        last = made
        wrote(made)
        glided = Glided(made, g.words, g.lead, g.edge, seqs)
        return snapshot(commit = made, deleteBefore = g.committed.length)
    }

    private fun context(action: Action.Context): Snapshot {
        forgetText()
        action.learnFlags?.let { learnFlags = it }
        if (!action.cleared) edge = endOf(action.before)
        if (learning) {
            recent = action.before.takeLast(EnglishText.CONTEXT_CHARS)
            known = true
            cut = action.before.length >= Action.Context.HOST_CHARS
            if (action.cleared) {
                if (recent.isNotEmpty() && !recent.last().isWhitespace()) recent += " "
                clearedAt = recent.length
            } else if (action.offers) {
                recalled = corrections.at(action.before)?.also { candidates = listOf(it.made.typed) }
            }
        }
        return snapshot()
    }

    private fun recall(found: Corrections.Found): Snapshot {
        val m = found.made
        corrections.remove(m)
        recent = recent.dropLast(found.length)
        edge = endOf(edge.dropLast(found.length))
        // past the log's last UNDOABLE commits an undo finds nothing: the corrected word stays counted (accepted)
        m.seqs.forEach(user::undo)
        learn(m.typed, m.typed, EnCommit.REVERTED)
        val made = if (found.spaced) "${m.typed} " else m.typed
        last = made
        wrote(made)
        candidates = emptyList()
        pager.reset()
        return snapshot(commit = made, deleteBefore = found.length)
    }

    private fun blocked(word: String) = blocks?.has(word) == true

    // an offered word, not the typed letters; on the nine keys only all-pinned letters are kept so; nor a just-glided word
    private fun blockable(index: Int): Boolean {
        val word = candidates.getOrNull(index) ?: return false
        val asTyped = if (nineKeyed()) input.none { it in '2'..'9' } && word == capitalI(input.toString()) else word == typed()
        return learning && blocks != null && EnglishText.isWord(word) && !(input.isNotEmpty() && asTyped) && !(input.isEmpty() && glided?.takeIf { it.committed == last }?.committed == "$word ")
    }

    private fun blockAt(index: Int): String? =
        candidates.getOrNull(index)?.takeIf { blockable(index) }?.also { checkNotNull(blocks).blockShown(it) { w -> (data?.id(w) ?: -1) >= 0 || user.count(w) > 0f } }

    private fun block(index: Int): Snapshot = shownWithout(blockAt(index))

    private fun blockGlided(g: Glided, index: Int): Snapshot {
        val word = blockAt(index) ?: return snapshot()
        glided = Glided(g.committed, g.words - word, g.lead, g.edge, g.seqs)
        return also { candidates = candidates - word }.snapshot()
    }

    private fun forget(index: Int): Snapshot = shownWithout(candidates.getOrNull(index)?.let(::learnedAs)?.also(user::forget))

    private fun shownWithout(gone: String?): Snapshot {
        if (gone != null) if (input.isEmpty()) candidates = predictions() else read()
        return snapshot()
    }

    private fun nineKey(digit: Char, upper: Boolean, touch: Touch? = null): Snapshot {
        val letter = digit in 'a'..'z' || digit in 'A'..'Z'
        if (!letter && (digit !in '2'..'9' || nineKeys.letters(digit).isEmpty())) return snapshot()
        corrected = null
        if (input.isEmpty()) {
            candidates = emptyList()
            pinned = false
        }
        pinned = pinned || letter
        input.append(digit, upper, touch)
        cursor = input.length
        read()
        return snapshot()
    }

    private fun key(c: Char, touch: Touch? = null): Snapshot {
        corrected = null
        if (reads(c)) {
            if (input.isEmpty()) {
                candidates = emptyList()
                pinned = false
            }
            input.insert(cursor++, c, touch)
            read()
            return snapshot()
        }
        if (input.isEmpty() && joins(c)) {
            last = ""
            recent = recent.dropLast(1)
            wrote(c.toString())
            candidates = emptyList()
            return snapshot(commit = c.toString(), deleteBefore = 1)
        }
        if (input.isNotEmpty()) {
            val typed = typed()
            val word = if (c == ' ' || c in SWAPPED) correction ?: typed else typed
            if (c == ' ') return commit(word, space = true, typed = typed)
            if (c in SWAPPED) {
                val lead = Corrections.lead(recent, clearedAt, known && !cut)
                corrections.made(typed, word, lead, learnTyped(word, typed), learning)
                clear()
                return spaced("$word$c ", deleteBefore = 0)
            }
            clear()
            last = ""
            wrote(word + c)
            return snapshot(commit = word, handled = false)
        }
        return afterOwnSpace(c)
    }

    // the session's space after a colon, taken back by / (https://); not a full stop's by a lower-case letter (e.g.)
    private fun joins(c: Char): Boolean = c == '/' && last.endsWith(": ")

    private fun afterOwnSpace(c: Char): Snapshot {
        val before = if (last.length >= 2 && last.last() == ' ') last[last.length - 2] else null
        val word = before != null && endsWord(before)
        val mark = before != null && before in MARKS && !word
        if (c == ' ' && mark) {
            last = ""
            return snapshot()
        }
        val made = madeAfterOwnSpace(c, before, word, mark) { quoteOpen(c) }
        if (made == null) {
            clear()
            last = ""
            wrote(c.toString())
            return snapshot(handled = false)
        }
        recent = recent.dropLast(1)
        return spaced(made, deleteBefore = 1)
    }

    // an apostrophe after a letter ends a word too (dogs'), as quoteOpen reads it
    private fun endsWord(c: Char): Boolean =
        c.isLetterOrDigit() || c == '\'' && last.getOrNull(last.length - 3)?.isLetterOrDigit() == true

    private fun quoteOpen(q: Char): Boolean = quoteOpenIn(recent, q, startKnown = known && !cut)

    private fun spaced(made: String, deleteBefore: Int): Snapshot {
        last = made
        wrote(made)
        candidates = predictions()
        pager.reset()
        return snapshot(commit = made, deleteBefore = deleteBefore)
    }

    private fun backspace(): Snapshot {
        val undo = corrected
        corrected = null
        if (input.isEmpty()) {
            if (undo != null && undo.committed == last) return takeBack(undo)
            last = ""
            candidates = emptyList()
            erased()
            return snapshot(handled = false)
        }
        last = ""
        if (cursor == 0) return snapshot()
        input.deleteAt(--cursor)
        read()
        return snapshot()
    }

    private fun takeBack(undo: Corrected): Snapshot {
        val typed = undo.typed
        last = ""
        recent = recent.dropLast(undo.committed.length)
        edge = ""
        corrections.remove(undo.made)
        undo.made.seqs.forEach(user::undo)
        learn(typed, typed, EnCommit.REVERTED)
        reverted = typed
        input.set(typed)
        cursor = input.length
        keepTyped = true
        read()
        return snapshot(deleteBefore = undo.committed.length)
    }

    private fun pick(index: Int): Snapshot {
        val text = candidates.getOrNull(index) ?: return snapshot(handled = candidates.isNotEmpty())
        corrected = null
        val kept = correction != null && text == typed()
        return if (phrases.at(candidates, index) != null) commit(text, space = false, typed = typed(), learned = false) else commit(text, space = true, typed = typed(), how = if (kept) EnCommit.REVERTED else EnCommit.PICKED)
    }

    private fun commit(text: String, space: Boolean, typed: String = text, how: Int = howOf(text, typed, keepTyped), learned: Boolean = true): Snapshot {
        val committed = if (space) "$text " else text
        val lead = Corrections.lead(recent, clearedAt, known && !cut)
        val seqs = if (!learned || text == reverted) emptyList() else learn(text, typed, how)
        clear()
        last = committed
        wrote(committed)
        // only what the session changed by itself is a correction to take back
        if (space && how == EnCommit.AUTOCORRECTED && typed.isNotEmpty()) {
            corrections.made(typed, text, lead, seqs, learning)?.let { corrected = Corrected(typed, committed, it) }
        }
        if (space) candidates = predictions()
        return snapshot(commit = committed)
    }

    private fun learnTyped(word: String, typed: String) = if (word == reverted) emptyList() else learn(word, typed, howOf(word, typed, keepTyped))

    // nothing to pick past the page: the key (space, a digit) is the app's
    private fun select(onPage: Int): Snapshot = pager.at(onPage).let { if (it >= 0) pick(it) else snapshot(handled = candidates.isNotEmpty()) }

    private fun turn(to: Int): Snapshot {
        pager.turn(to, candidates.size)
        return snapshot()
    }

    private fun clear() {
        input.clear()
        cursor = 0
        candidates = emptyList()
        pager.reset()
        correction = null
        keepTyped = false
        reverted = null
        pinned = false
    }

    private fun forgetText() {
        clear()
        last = ""
        recent = ""
        edge = ""
        known = false
        cut = false
        clearedAt = -1
        corrected = null
    }

    /** Learns each word of [text] (alot as a lot) after the one before; a non-word breaks the run, as in the pages. Returns the commits for undo. */
    private fun learn(text: String, typed: String, how: Int): List<Long> {
        if (!learning || guessed()) return emptyList()
        val before = before()
        var prev = previous(before)
        var first = before.lastOrNull() == EnglishText.START
        val seqs = ArrayList<Long>(1)
        for (part in text.split(' ')) {
            val word = if (EnglishText.isWord(part)) inSentence(part, first) else null
            if (word != null) user.commit(prev, word, typed, how, learnFlags)?.let(seqs::add)
            prev = word
            first = false
        }
        return seqs
    }

    /** [word] mid-sentence: a capital only from being a sentence's [first] word is dropped, unless written so anyway (London) or typed so mid-sentence. */
    private fun inSentence(word: String, first: Boolean): String {
        if (!first || word.drop(1).any { it.isUpperCase() }) return word
        val lower = word.replaceFirstChar(Char::lowercaseChar)
        val data = data
        val capital = data != null && data.id(word) != NgramModel.NO_WORD && data.id(lower) == NgramModel.NO_WORD ||
            user.count(word) > 0f && user.count(lower) == 0f
        return if (capital) word else lower
    }

    // sorted, so a search boxes no id
    private var userIds = IntArray(0)
    private var userOnly: List<String> = emptyList()
    private var userSeen = -1

    private fun refreshUser(data: EnglishData) {
        if (userSeen == user.changes) return
        userSeen = user.changes
        val ids = HashSet<Int>()
        val only = ArrayList<String>()
        for (word in user.words) {
            val id = data.id(word)
            if (id != NgramModel.NO_WORD) ids += id else if (data.id(word.replaceFirstChar(Char::lowercaseChar)) == NgramModel.NO_WORD) only += word
        }
        userIds = ids.toIntArray().apply { sort() }
        userOnly = only
    }

    private var scoredPrev: String? = null

    // the trigram run looked up once for the thousands of words a search scores
    private var trigramsOf = Long.MIN_VALUE
    private var trigrams = NgramModel.Trigrams.NONE

    private fun scored(data: EnglishData, context: Long, word: Int): Float {
        if (context != trigramsOf) {
            trigramsOf = context
            trigrams = data.model.trigrams(context)
        }
        val base = data.model.scoreAfter(context, trigrams, word)
        refreshUser(data)
        if (userIds.binarySearch(word) < 0) return base
        return user.score(base, scoredPrev, data.word(word))
    }

    private fun wrote(text: String) {
        edge = endOf(edge + text)
        if (!learning) return
        val all = recent + text
        cut = cut || all.length > EnglishText.CONTEXT_CHARS
        recent = all.takeLast(EnglishText.CONTEXT_CHARS)
        if (clearedAt >= 0) clearedAt = (clearedAt - (all.length - recent.length)).takeIf { it > 0 } ?: -1
    }

    // the app typed [text] itself: with nothing known it may be a word's tail, so only the edge takes it
    override fun typed(text: String) {
        glided = null
        clear()
        last = ""
        corrected = null
        when {
            text == Session.ERASED -> erased()
            known || recent.isNotEmpty() -> wrote(text)
            else -> edge = endOf(edge + text)
        }
    }

    // the app's Backspace with empty input: one code point off; where that may be a cluster an editor deletes
    // otherwise, nothing is known rather than guessed
    private fun erased() {
        if (known && recent.length <= clearedAt) return
        val end = if (known) recent else edge
        recent = withoutLast(recent)
        edge = if (known) endOf(recent) else endOf(withoutLast(edge))
        if (endsInCluster(end) || endsInCluster(if (known) recent else edge)) forgetText()
    }

    private fun read() = readWords().also { candidates = phrases.place(input, nineKeys.takeIf { nineKeyed() }, candidates, ::typed) }

    private fun readWords() {
        pager.reset()
        correction = null
        if (input.isEmpty()) return run { candidates = emptyList() }
        if (nineKeyed()) return readNineKeys()
        val typed = typed()
        val keys = EnglishData.key(input.toString())
        val before = before()
        val context = decoder?.context(before) ?: 0L
        scoredPrev = previous(before)
        val found = decoder?.let { d -> offeredOf(SUGGESTED + 1, ::offered) { d.suggest(keys, context, it, touches = input.keyTouches()) } }.orEmpty()
        // a capital the sentence start does not explain is the user's: a name, not a slip
        val upper = typed.count { it.isUpperCase() }
        val name = upper > 1 || upper == 1 && !(typed[0].isUpperCase() && before.lastOrNull().let { it == null || it == EnglishText.START })
        // each part of a hyphenated word is a word, which the data has none of: as typed
        val theirs = user.neverCorrected(typed) || user.neverCorrected(typed.replaceFirstChar(Char::lowercaseChar))
        correction = if (keepTyped || theirs || '-' in typed) null else data?.let { autocorrect.of(it, typed, keys, found, context, name) }?.let(::inCase)?.takeUnless(::blocked)
        val data = data ?: return run { candidates = listOf(typed) }
        val words = (found.map { data.word(it.word) to it.score } + ownWords(data, keys, before))
            .sortedByDescending { it.second }
            .map { inCase(it.first) }
        candidates = (listOfNotNull(correction) + typed + words).distinct().take(SUGGESTED + 1)
    }

    private fun readNineKeys() {
        val data = data ?: return run { candidates = emptyList() }
        val nine = checkNotNull(nine)
        val keys = nine.keys(input)
        val before = before()
        val context = nine.decoder.context(before)
        val prev = previous(before)
        scoredPrev = prev
        val found = offeredOf(SUGGESTED + 1, ::offered) { nine.decoder.suggest(keys, context, it, exactFirst = true, touches = input.keyTouches()) }
            .map { NineKeyWords.Found(data.word(it.word), it.score, it.completes) }
        refreshUser(data)
        val own = userOnly.mapNotNull { if (blocked(it)) null else nine.own(it, keys, user.score(Float.NEGATIVE_INFINITY, prev, it)) }
        val words = nine.ordered(found + own, input).map { nine.inCase(capitalI(it), input, input.upper) }
        val pinnedWord = listOfNotNull(capitalI(input.toString()).takeIf { input.none { it in '2'..'9' } })
        candidates = (pinnedWord + words).distinct().take(SUGGESTED + 1)
    }

    private fun ownWords(data: EnglishData, keys: String, before: List<String>): List<Pair<String, Float>> {
        refreshUser(data)
        val prev = previous(before)
        return userOnly.filterNot(::blocked).mapNotNull { word ->
            val key = EnglishData.key(word)
            val spelt = key == keys || keys.length >= costs.completeFrom && key.startsWith(keys)
            if (spelt) word to user.score(Float.NEGATIVE_INFINITY, prev, word) else null
        }
    }

    private fun predictions(): List<String> {
        val decoder = decoder ?: return emptyList()
        // a search field: no next words, as pinyin has no 联想 there
        if (learnFlags and Commit.NO_PREDICT != 0) return emptyList()
        val data = checkNotNull(data)
        val before = before()
        if (before.isEmpty()) return emptyList()
        val start = before.lastOrNull() == EnglishText.START
        val context = decoder.context(before)
        val prev = previous(before)
        val model = offeredOf(SUGGESTED + 1, ::offered) { decoder.predict(context, it) }
            .map { data.word(it.word) to user.score(it.lm, prev, data.word(it.word)) }
        val theirs = prev?.let(user::followers).orEmpty().filterNot(::blocked).map { word ->
            val id = data.id(word)
            word to user.score(if (id == NgramModel.NO_WORD) Float.NEGATIVE_INFINITY else data.model.scoreAfter(context, id), prev, word)
        }
        return (model + theirs).sortedByDescending { it.second }
            .map { it.first }
            .filter { it.first().isLetter() }
            .map { if (start && it.drop(1).none { c -> c.isUpperCase() }) it.replaceFirstChar(Char::uppercaseChar) else it }
            .distinct()
            .take(SUGGESTED + 1)
    }

    private fun before(): List<String> = EnglishText.context(recent, start = known && !cut)

    // the user's pairs are counted under a sentence's first word in mid-sentence form (Hello as hello)
    private fun previous(before: List<String>): String? {
        val word = before.lastOrNull()?.takeIf { it != EnglishText.START } ?: return null
        return inSentence(word, first = before.size == 2 && before[0] == EnglishText.START)
    }

    /** The typed word as committed: i and i' as I; on the nine keys the first word shown, else each key's first letter. */
    private fun typed(): String {
        if (nineKeyed()) return candidates.firstOrNull() ?: nine?.firstLetters(input, input.upper) ?: input.toString()
        return capitalI(input.toString())
    }

    // I' at a sentence start is I'm's, not I'M's
    private fun inCase(word: String): String = when {
        input.count { it.isLetter() } > 1 && input.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        input[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    private fun snapshot(commit: String = "", handled: Boolean = true, deleteBefore: Int = 0): Snapshot {
        val shown = pager.of(candidates)
        return Snapshot(
            commit = commit,
            preedit = if (nineKeyed()) typed() else input.toString(),
            candidates = shown,
            page = pager.page,
            hasPreviousPage = pager.hasPrevious,
            hasNextPage = pager.hasNext(candidates.size),
            handled = handled,
            predicting = input.isEmpty() && candidates.isNotEmpty(),
            total = candidates.size,
            first = pager.from,
            deleteBefore = deleteBefore,
            cursor = if (cursor == input.length || nineKeyed()) -1 else cursor,
            // long press acts (屏蔽, 删除) use the host's candidate actions, which a non-actionable list lacks
            actionable = candidates.isNotEmpty(),
        )
    }

    private class Corrected(val typed: String, val committed: String, val made: Corrections.Made)

    companion object {
        const val DEFAULT_PAGE_SIZE = 5

        const val SUGGESTED = 9

        const val MAX_CORRECTION = 3.0f

        /** Fewer keys are only corrected as respelt (i as I): too many words are a slip away. */
        const val MIN_CORRECTED = 2

        const val NAME_CORRECTION = 1.0f

        const val KNOWN_MARGIN = 2f
    }
}

// brackets a quote after opens one: ("hi"); after these a glided word gets a space before it
private const val SPACED_AFTER = ".,!?;:"

private const val GLIDE_SEARCHED = 30

private const val SWAPPED = ".,!?;:"

private const val CLOSING = ")]}"
private const val QUOTES = "\"'"

private const val MARKS = SWAPPED + CLOSING + QUOTES

private fun howOf(word: String, typed: String, keepTyped: Boolean) = when {
    keepTyped && word == typed -> EnCommit.REVERTED
    word != typed -> EnCommit.AUTOCORRECTED
    else -> EnCommit.TYPED
}

// what [c] after the session's own space puts in its place; null: the space stays and [c] goes after it
private fun madeAfterOwnSpace(c: Char, before: Char?, word: Boolean, mark: Boolean, quoteOpen: () -> Boolean): String? = when {
    before == null -> null
    c == ' ' && word -> ". "
    c in SWAPPED && (word || mark) -> "$c "
    c in CLOSING || c in QUOTES && quoteOpen() -> "$c "
    else -> null
}

private const val OPENING = "([{"

// the last quote not inside a word (don't) opens after a space, bracket or the field start, closes after a word
// or punctuation; with the start unknown the first one may be a close
private fun quoteOpenIn(text: String, q: Char, startKnown: Boolean): Boolean {
    var open = false
    for (i in text.indices) {
        if (text[i] != q || inWord(text, i)) continue
        val prev = text.getOrNull(i - 1)
        open = if (prev == null) startKnown else prev.isWhitespace() || prev in OPENING
    }
    return open
}

private fun inWord(text: String, i: Int): Boolean =
    text.getOrNull(i - 1)?.isLetterOrDigit() == true && text.getOrNull(i + 1)?.isLetterOrDigit() == true

// asks for more where some are dropped, so none leave a gap
private fun offeredOf(
    limit: Int,
    offered: (Int) -> Boolean,
    find: (Int) -> List<EnglishDecoder.Suggestion>,
): List<EnglishDecoder.Suggestion> {
    var asked = limit
    while (true) {
        val found = find(asked)
        val kept = found.filter { offered(it.word) }
        if (kept.size >= limit || found.size < asked) return kept.take(limit)
        asked *= 2
    }
}

private fun capitalI(s: String): String = if (s == "i" || s.startsWith("i'")) "I" + s.substring(1) else s

private fun endOf(text: String): String {
    val end = text.trimEnd().length
    val kept = text.substring(maxOf(0, end - EDGE_CHARS), end)
    return kept + text.substring(end).take(EnglishText.CONTEXT_CHARS - kept.length)
}

// with nothing known before the cursor (after a Reset, Enter, arrow), the app's Backspaces can still take back a word
private const val EDGE_CHARS = 32

private fun withoutLast(text: String): String =
    if (text.isEmpty()) text else text.substring(0, text.offsetByCodePoints(text.length, -1))

// a combining mark, ZWJ, anything outside the BMP, or a CR: an editor's Backspace may not take it by code point
private fun endsInCluster(text: String): Boolean {
    if (text.isEmpty()) return false
    val c = text.codePointBefore(text.length)
    return c > 0xFFFF || c == 0x200D || c == '\r'.code || when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK,
        Character.COMBINING_SPACING_MARK -> true
        else -> false
    }
}
