/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlidePath
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import java.util.BitSet

/**
 * English typed a word at a time, as Gboard has it. The candidates are the word typed and the
 * words it may be ([EnglishDecoder]: those it spells, starts, or spells with a slip), scored after
 * the words before the cursor. A word typed that is none is corrected: the likeliest word it
 * spells otherwise (dont as don't, london as London) or with a slip (teh as the) is the first
 * candidate, what space commits; backspace right after takes the correction back, the word typed
 * shown again, and that word is never corrected again. Punctuation commits the word as space does,
 * correction and all.
 *
 * A word committed by space or picked is followed by a space and by the words that may come next
 * ([Snapshot.predicting]); punctuation typed right after puts that space behind it (`word .` as
 * `word. `), as a closing bracket does and a quote where one is open (`word.)` as `word.) `);
 * space typed again turns it into a full stop (`word  ` as `word. `). A lone i is
 * I. Enter commits the word as typed and is the app's all the same: a search runs at once. A tap
 * in the word puts the cursor there, where letters go in and backspace deletes.
 *
 * Keys it does not [read][reads] (digits, punctuation, space with nothing typed) are the app's.
 * The host hands it every key (see the host's `Keyboard`): space and digits pick nothing here.
 * Where nothing is to be suggested (a password, an editor asking for none, suggestions turned
 * off) the addon hands it none: the keys type themselves.
 *
 * What it knows of the text before the cursor ([Action.Context] and what it committed since) is
 * kept only while [learning]: without, every word is scored as a sentence's first might be.
 *
 * While [learning], each word committed is counted in [EnglishUser], after the word before it:
 * scored up as typed again, offered after that word, and, the data lacking it, offered by its keys.
 * A word counted twice is the user's, never corrected; a correction taken back makes it theirs at
 * once. A long press on a word counted forgets it.
 */
class EnglishSession(
    private val data: EnglishData?,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val costs: TypingCosts = TypingCosts(),
    private val maxCorrection: Float = MAX_CORRECTION,
    user: EnglishUser? = null,
    coarseWords: List<String> = CoarseWords.LIST,
    private val misspellings: Map<String, String> = Misspellings.MAP,
) : Session {

    // what the user committed, kept by the host; else for this session alone
    private val user = user ?: EnglishUser()

    private val decoder = data?.let { d -> EnglishDecoder(d, costs) { context, word -> scored(d, context, word) } }

    private val glider = data?.let { d -> GlideDecoder(d, score = { context, word -> scored(d, context, word) }) }

    // never offered, completed or corrected to; typed, they are kept as typed. Looked up at the first
    // key, not here: the session is made on the thread that types, and the lookups would build the
    // data's word index there rather than leave it to the warm-up
    private val coarse by lazy { data?.let { d -> CoarseWords.of({ d.id(it) }, coarseWords) } ?: CoarseWords.NONE }

    // the model's words that are misspellings, by id, looked up as late
    private val misspelt by lazy {
        val ids = BitSet()
        data?.let { d -> for (word in misspellings.keys) d.id(word).takeIf { it != NgramModel.NO_WORD }?.let { ids.set(it) } }
        ids
    }

    // a coarse word the user typed is theirs to be offered; a misspelling once it is theirs, never
    // corrected (see read)
    private fun offered(word: Int): Boolean {
        val misspelling = misspelt[word]
        if (!misspelling && !coarse.has(word)) return true
        val text = checkNotNull(data).word(word)
        return if (misspelling) user.learned(text) else user.count(text) > 0f
    }

    private val input = StringBuilder()
    private var cursor = 0 // in input
    private var candidates: List<String> = emptyList()
    private var page = 0

    // what space commits in place of the word typed, if anything
    private var correction: String? = null

    // the word typed and what space committed for it, while backspace may take the correction back
    private var corrected: Corrected? = null

    // shown again by backspace: committed as typed
    private var keepTyped = false

    // what this session committed last, if the cursor stayed after it: a space it ends with is
    // the session's own, which the space rules may take back
    private var last = ""

    // the end of the text before the cursor, as far as the session knows it: if [known], from the
    // app's (Action.Context) on, [cut] where the text goes on before it; else what it committed alone
    private var recent = ""
    private var known = false
    private var cut = false

    // where the field's own text starts in [recent], -1 if not there: before it, what was written
    // in a field that has cleared itself (Action.Context.cleared), a space put after it as a word's
    // end for scoring. At the mark nothing is before the cursor: no glide's space, and a Backspace
    // takes nothing
    private var clearedAt = -1

    // the end of the text before the cursor, known while learning or not: its last few chars not
    // spaces (a few, for the app's Backspaces to take back one by one) and the spaces after it,
    // empty if unknown. A glide's space before its word, and its
    // case after a full stop, need it where [recent] is kept empty. It is never learned or scored
    private var edge = ""

    override var learning = true
        set(value) {
            field = value
            if (!value) {
                recent = ""
                clearedAt = -1
                known = false
            }
        }

    override fun reads(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || (c == '\'' || c == '-') && input.isNotEmpty()

    override val takesEveryCharacter: Boolean get() = true

    // the word a glide committed, the space it put before it ([lead]) and the others it may have
    // been, while the cursor stays after it; [edge], the end of the text before them, for a glide after the
    // word is taken back
    private class Glided(val committed: String, val words: List<String>, val lead: String = "", val edge: String = "")

    // the keyboard's last glide, read at the Glide key sent after it
    private var pendingGlide: Glide? = null
    private var glided: Glided? = null

    /** The keyboard's [glide], for the [Action.Glide] sent after it to read. */
    fun offerGlide(glide: Glide) {
        pendingGlide = glide
    }

    override fun apply(action: Action): Snapshot {
        val g = glided?.takeIf { input.isEmpty() && it.committed == last }
        // what goes on from the word glided: a pick, a backspace, a page; anything else leaves it
        if (!keepsGlided(action)) glided = null
        return when {
            g == null -> act(action)
            // the glide's own space is there already: not a full stop; its others are no longer offered
            action == Action.Key(' ') -> {
                candidates = predictions()
                page = 0
                snapshot()
            }
            action == Action.Backspace -> unglide(g, null)
            action is Action.Pick -> g.words.getOrNull(action.index)?.let { unglide(g, it) } ?: snapshot()
            action is Action.Select && action.index in 0 until pageSize -> g.words.getOrNull(page * pageSize + action.index)?.let { unglide(g, it) } ?: snapshot()
            else -> act(action)
        }
    }

    private fun keepsGlided(action: Action) = when (action) {
        is Action.Pick, is Action.Select, Action.Backspace, Action.NextPage, Action.PreviousPage -> true
        else -> false
    }

    private fun act(action: Action): Snapshot = when (action) {
        is Action.Key -> key(action.char)
        Action.Glide -> glide()
        Action.Backspace -> backspace()
        is Action.Select -> if (action.index in 0 until pageSize) pick(page * pageSize + action.index) else snapshot(handled = candidates.isNotEmpty())
        is Action.Pick -> pick(action.index)
        Action.NextPage -> turn(page + 1)
        Action.PreviousPage -> turn(page - 1)
        // an arrow, a tab, enter: the word left as it is, maybe half typed, so not learned
        Action.CommitRaw -> if (input.isEmpty()) snapshot(handled = false) else commit(typed(), space = false, learned = 0f).copy(handled = false)
        is Action.Cursor -> if (input.isEmpty()) {
            snapshot(handled = false)
        } else {
            cursor = action.at.coerceIn(0, input.length)
            snapshot()
        }
        // the keyboard commits the word on an arrow (Keyboard.whileComposing): none comes here
        is Action.Step -> snapshot(handled = false)
        is Action.Context -> context(action)
        Action.Reset -> {
            forgetText()
            snapshot()
        }
        is Action.Forget -> forget(action.index)
        Action.Refine, Action.Ink, is Action.Pin, is Action.Unpin, is Action.Block, is Action.Syllable, is Action.Letter -> snapshot()
    }

    override fun candidates(from: Int, count: Int): List<Choice> =
        candidates.subList(minOf(from, candidates.size), minOf(from + count, candidates.size)).map { Choice(it) }

    override fun offers(index: Int): Set<Offer> =
        if (candidates.getOrNull(index)?.let(::learnedAs) != null) setOf(Offer.FORGET) else emptySet()

    // the candidate [text] as the user committed it, if they did: as shown, or as at a sentence's start
    private fun learnedAs(text: String): String? =
        text.takeIf { user.count(it) > 0f } ?: text.replaceFirstChar(Char::lowercaseChar).takeIf { user.count(it) > 0f }

    // the pending glide's word committed, a word typed before it ended as space ends it; a glide no
    // word fits leaves that word as it is, so it is decoded after the word as space would end it
    private fun glide(): Snapshot {
        val glide = pendingGlide ?: return snapshot()
        pendingGlide = null
        if (glider == null) return snapshot()
        // what key(' ') below commits: the two must stay in step. It is not called first, as a glide
        // no word fits leaves the word typed
        val ending = if (input.isNotEmpty()) "${correction ?: typed()} " else ""
        // right after a word or punctuation not the session's (the cursor put there): a space between them
        val lastChar = if (recent.length == clearedAt) null else recent.lastOrNull() ?: edge.lastOrNull()
        val space = if (ending.isEmpty() && lastChar?.let { it.isLetterOrDigit() || it in SPACED_AFTER } == true) " " else ""
        // what is before the cursor once the word is ended, as wrote would keep it
        val ahead = if (learning) recent + ending + space else recent
        val context = EnglishText.context(ahead, start = known && !cut)
        val words = glideWords(glide, context)
        val word = words.firstOrNull() ?: return snapshot()
        val ended = if (ending.isNotEmpty()) key(' ').commit else ""
        val before = edge
        // a correction space made is no longer the last thing before the cursor
        corrected = null
        wrote(space)
        learn(word, 1f)
        val made = "$word "
        last = made
        wrote(made)
        glided = Glided(made, words, space, before)
        candidates = words
        page = 0
        return snapshot(commit = ended + space + made)
    }

    // the words [glide] may be after [before], best first, in the keyboard's case: the data's and the user's own
    private fun glideWords(glide: Glide, before: List<String>): List<String> {
        val data = checkNotNull(data)
        val context = checkNotNull(decoder).context(before)
        scoredPrev = previous(before)
        val found = checkNotNull(glider).decode(glide.path, glide.layout, context, GLIDE_SEARCHED)
            .filter { offered(it.word) }
            .map { data.word(it.word) to it.score }
        return (found + ownGlided(data, glide, scoredPrev))
            .sortedByDescending { it.second }
            // a capital only where Shift gives one, as typing: the data's sentence-start "Hello" is
            // "hello" (a field that asks for capitals has Shift on there); a name or iPhone keeps its own
            .map { (w, _) -> inSentence(w, first = true) }
            // i and i'm as tapping commits them ([typed]), whichever case the data has them in
            .map(::capitalI)
            .map { w -> if (glide.capsLock) w.uppercase() else if (glide.shift) w.replaceFirstChar(Char::uppercaseChar) else w }
            .distinct()
            .take(SUGGESTED + 1)
    }

    // the words the user committed that the data lacks, which the trie the glide searches has not:
    // each held to the path as the decoder holds its words, scored by the user's counts alone
    private fun ownGlided(data: EnglishData, glide: Glide, prev: String?): List<Pair<String, Float>> {
        refreshUser(data)
        if (userOnly.isEmpty()) return emptyList()
        val path = GlidePath(glide.path, glide.layout, GlidePath.Weights(), EnglishDecoder.MAX_KEYS)
        if (path.points == 0) return emptyList()
        return userOnly.mapNotNull { word ->
            val key = EnglishData.key(word)
            val fits = key.length in 1..EnglishDecoder.MAX_KEYS && key.indices.all { path.step(it, key[it]) }
            val cost = if (fits) path.cost(key.length) else null
            cost?.let { word to user.score(Float.NEGATIVE_INFINITY, prev, word) - it }
        }
    }

    // the word [g] glided taken back: deleted (a backspace), the space it put before it too, or
    // another it may have been in its place
    private fun unglide(g: Glided, other: String?): Snapshot {
        recent = recent.dropLast(g.committed.length)
        learn(g.committed.trimEnd(' '), -1f)
        if (other == null) {
            recent = recent.dropLast(g.lead.length)
            edge = g.edge
            last = ""
            candidates = emptyList()
            return snapshot(deleteBefore = g.committed.length + g.lead.length)
        }
        learn(other, 1f)
        val made = "$other "
        last = made
        wrote(made)
        glided = Glided(made, g.words, g.lead, g.edge)
        return snapshot(commit = made, deleteBefore = g.committed.length)
    }

    private fun context(action: Action.Context): Snapshot {
        forgetText()
        if (!action.cleared) edge = endOf(action.before)
        if (learning) {
            recent = action.before.takeLast(EnglishText.CONTEXT_CHARS)
            known = true
            cut = action.before.length >= Action.Context.HOST_CHARS
            if (action.cleared) {
                if (recent.isNotEmpty() && !recent.last().isWhitespace()) recent += " "
                clearedAt = recent.length
            }
        }
        return snapshot()
    }

    private fun forget(index: Int): Snapshot {
        val word = candidates.getOrNull(index)?.let(::learnedAs) ?: return snapshot()
        user.forget(word)
        if (input.isEmpty()) candidates = predictions() else read()
        return snapshot()
    }

    private fun key(c: Char): Snapshot {
        corrected = null
        if (reads(c)) {
            if (input.isEmpty()) candidates = emptyList()
            input.insert(cursor++, c)
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
            // space and punctuation end a word as meant; a digit or a symbol goes on with it as typed
            val word = if (c == ' ' || c in SWAPPED) correction ?: typed else typed
            // space ends the word with a space of the session's own, and punctuation with itself
            // and one; anything else is the app's
            if (c == ' ') return commit(word, space = true).also { if (word != typed) corrected = Corrected(typed, last) }
            if (c in SWAPPED) {
                learn(word, 1f)
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

    // the session's own space after a colon, taken back by / (https://). Not a full stop's by a
    // lower-case letter (e.g.): where the field asks for no capitals, a sentence starts with one
    private fun joins(c: Char): Boolean = c == '/' && last.endsWith(": ")

    // [c] with no word typed: after the session's own space, it may take its place
    private fun afterOwnSpace(c: Char): Snapshot {
        // before the session's own space, if the cursor is still after it
        val before = if (last.length >= 2 && last.last() == ' ') last[last.length - 2] else null
        val word = before != null && endsWord(before)
        val mark = before != null && before in MARKS && !word
        // after punctuation, or a bracket or quote closed, the space is there already
        if (c == ' ' && mark) {
            last = ""
            return snapshot()
        }
        val made = when {
            before == null -> null
            // twice after a word: a full stop
            c == ' ' && word -> ". "
            // in place of the space, and one after it: "word, " then "?" is "word? ", "?! " after "? "
            c in SWAPPED && (word || mark) -> "$c "
            // a bracket closed, likewise ("(see above.) ", "hello:) "), and a quote where one is open
            c in CLOSING || c in QUOTES && quoteOpen(c) -> "$c "
            else -> null
        }
        if (made == null) {
            clear()
            last = ""
            wrote(c.toString())
            return snapshot(handled = false)
        }
        recent = recent.dropLast(1)
        return spaced(made, deleteBefore = 1)
    }

    // whether [c], before the session's own space, ends a word: an apostrophe too where it follows
    // a letter (dogs'), as quoteOpen reads one in a word; one put in after anything else is a quote
    private fun endsWord(c: Char): Boolean =
        c.isLetterOrDigit() || c == '\'' && last.getOrNull(last.length - 3)?.isLetterOrDigit() == true

    // whether a quote [q] is open in the text before the cursor, as far as the session knows it: the
    // last of them not in a word (don't, 90's) opens one after a space, a bracket or the field's
    // start, and closes one after a word or punctuation (dogs', "Stop."), so one whose opening one is
    // out of what the session knows is no opening one, nor one first of a text cut short or unknown
    // (after a Reset), which may close one. An opening one is the app's to type
    private fun quoteOpen(q: Char): Boolean {
        var open = false
        for (i in recent.indices) {
            if (recent[i] != q || inWord(i)) continue
            val prev = recent.getOrNull(i - 1)
            open = if (prev == null) known && !cut else prev.isWhitespace() || prev in OPENING
        }
        return open
    }

    // whether the char at [i] of [recent] has a letter or digit on both sides, as the quote in don't
    private fun inWord(i: Int): Boolean =
        recent.getOrNull(i - 1)?.isLetterOrDigit() == true && recent.getOrNull(i + 1)?.isLetterOrDigit() == true

    // [made], ending with a space of the session's own, in place of the [deleteBefore] before it
    private fun spaced(made: String, deleteBefore: Int): Snapshot {
        last = made
        wrote(made)
        candidates = predictions()
        page = 0
        return snapshot(commit = made, deleteBefore = deleteBefore)
    }

    private fun backspace(): Snapshot {
        val undo = corrected
        corrected = null
        if (input.isEmpty()) {
            if (undo != null && undo.committed == last) return takeBack(undo.typed, undo.committed)
            last = ""
            candidates = emptyList()
            erased()
            return snapshot(handled = false)
        }
        last = ""
        // at the start of the word: nothing before it is the session's to delete
        if (cursor == 0) return snapshot()
        input.deleteCharAt(--cursor)
        read()
        return snapshot()
    }

    // the correction [committed] for [typed] taken back: the word typed shown again, kept as typed
    private fun takeBack(typed: String, committed: String): Snapshot {
        last = ""
        recent = recent.dropLast(committed.length)
        edge = ""
        // what was counted of the correction taken back, and the word typed the user's at once
        learn(committed.trimEnd(' '), -1f)
        learn(typed, EnglishUser.LEARNED)
        input.setLength(0)
        input.append(typed)
        cursor = input.length
        keepTyped = true
        read()
        return snapshot(deleteBefore = committed.length)
    }

    private fun pick(index: Int): Snapshot {
        val text = candidates.getOrNull(index) ?: return snapshot(handled = candidates.isNotEmpty())
        corrected = null
        // the word typed picked over its correction: kept as typed, as by a correction taken back
        val kept = correction != null && text == typed()
        return commit(text, space = true, learned = if (kept) EnglishUser.LEARNED else 1f)
    }

    private fun commit(text: String, space: Boolean, learned: Float = 1f): Snapshot {
        val committed = if (space) "$text " else text
        learn(text, learned)
        clear()
        last = committed
        wrote(committed)
        if (space) candidates = predictions()
        return snapshot(commit = committed)
    }

    private fun turn(to: Int): Snapshot {
        if (to >= 0 && to * pageSize < candidates.size) page = to
        return snapshot()
    }

    private fun clear() {
        input.setLength(0)
        cursor = 0
        candidates = emptyList()
        page = 0
        correction = null
        keepTyped = false
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

    /**
     * [text] committed: each word of it (two for alot as a lot) counted [count] times after the
     * word before it, while learning. What is no word (a run of letters past [EnglishText.MAX_WORD])
     * is not counted, and breaks the run, as in the pages.
     */
    private fun learn(text: String, count: Float) {
        if (count == 0f || !learning) return
        val before = before()
        var prev = previous(before)
        var first = before.lastOrNull() == EnglishText.START
        for (part in text.split(' ')) {
            val word = if (EnglishText.isWord(part)) inSentence(part, first) else null
            if (word != null) user.learn(prev, word, count)
            prev = word
            first = false
        }
    }

    /**
     * [word] as written inside a sentence, as the data has its words: a capital a sentence's start
     * accounts for, where it is the sentence's [first], is not the word's (Hello as hello), unless
     * the word is written so anyway, as the data has it (London, I) or the user typed it inside a
     * sentence; a word in capitals as it is.
     */
    private fun inSentence(word: String, first: Boolean): String {
        if (!first || word.drop(1).any { it.isUpperCase() }) return word
        val lower = word.replaceFirstChar(Char::lowercaseChar)
        val data = data
        val capital = data != null && data.id(word) != NgramModel.NO_WORD && data.id(lower) == NgramModel.NO_WORD ||
            user.count(word) > 0f && user.count(lower) == 0f
        return if (capital) word else lower
    }

    // the user's words the data has, by id (sorted, so a key's search boxes no id it scores), and
    // those it lacks, as of the user's [EnglishUser.changes]
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

    // the word before, as the user's pairs have it: the same for every word a key's search scores (see read)
    private var scoredPrev: String? = null

    // the trigrams after the context last scored, looked up once for the thousands of words a key or
    // two's search scores after it, as PinyinDecoder does; at first of a context no model makes
    private var trigramsOf = Long.MIN_VALUE
    private var trigrams = NgramModel.Trigrams.NONE

    // [word]'s score after [context], with the user's chance of it added
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

    // [text] went in before the cursor
    private fun wrote(text: String) {
        edge = endOf(edge + text)
        if (!learning) return
        val all = recent + text
        cut = cut || all.length > EnglishText.CONTEXT_CHARS
        recent = all.takeLast(EnglishText.CONTEXT_CHARS)
        // the mark goes with the text it is in, and once out of it is no longer in reach
        if (clearedAt >= 0) clearedAt = (clearedAt - (all.length - recent.length)).takeIf { it > 0 } ?: -1
    }

    // the app typed [text] itself (a key where English suggests nothing): kept after what is known
    // of the text before the cursor, as what the session wrote; with nothing known, a word's tail
    // perhaps, it is no word to score, only the edge. A Backspace is [erased]
    override fun typed(text: String) {
        glided = null
        pendingGlide = null
        clear()
        last = ""
        corrected = null
        when {
            text == Session.ERASED -> erased()
            known || recent.isNotEmpty() -> wrote(text)
            else -> edge = endOf(edge + text)
        }
    }

    // the app's Backspace, the session's input empty: the last code point off what is known, and
    // off the edge, which may know more where the field's text is not. An editor may take more (an
    // emoji sequence, a flag, a "\r\n") or less; where the code point taken or the char now
    // last may belong to such a cluster, nothing is known rather than guessed (a glide right after
    // may then add no space)
    private fun erased() {
        // the field empty, nothing taken
        if (known && recent.length <= clearedAt) return
        val end = if (known) recent else edge
        recent = withoutLast(recent)
        edge = if (known) endOf(recent) else endOf(withoutLast(edge))
        if (endsInCluster(end) || endsInCluster(if (known) recent else edge)) forgetText()
    }

    // the word typed, then what it may be, in its case; the correction first, if any
    private fun read() {
        page = 0
        correction = null
        if (input.isEmpty()) {
            candidates = emptyList()
            return
        }
        val typed = typed()
        val keys = EnglishData.key(input.toString())
        val before = before()
        // no decoder, no correction: the context is not looked at
        val context = decoder?.context(before) ?: 0L
        scoredPrev = previous(before)
        val found = decoder?.let { d -> offeredOf(SUGGESTED + 1) { d.suggest(keys, context, it) } }.orEmpty()
        // a capital the sentence's start does not account for is the user's: a name, not a slip
        val upper = typed.count { it.isUpperCase() }
        val name = upper > 1 || upper == 1 && !(typed[0].isUpperCase() && before.lastOrNull().let { it == null || it == EnglishText.START })
        // each part of a word with a hyphen is one, which the words have none of: as typed
        val theirs = learnedAs(typed)?.let(user::learned) == true
        correction = if (keepTyped || theirs || '-' in typed) null else correctionOf(typed, keys, found, context, name)?.let(::inCase)
        val data = data ?: return run { candidates = listOf(typed) }
        val words = (found.map { data.word(it.word) to it.score } + ownWords(data, keys, before))
            .sortedByDescending { it.second }
            .map { inCase(it.first) }
        candidates = (listOfNotNull(correction) + typed + words).distinct().take(SUGGESTED + 1)
    }

    // the words the user committed that the data lacks, which [keys] spell or start, scored by the user's counts alone
    private fun ownWords(data: EnglishData, keys: String, before: List<String>): List<Pair<String, Float>> {
        refreshUser(data)
        val prev = previous(before)
        return userOnly.mapNotNull { word ->
            val key = EnglishData.key(word)
            val spelt = key == keys || keys.length >= costs.completeFrom && key.startsWith(keys)
            if (spelt) word to user.score(Float.NEGATIVE_INFINITY, prev, word) else null
        }
    }

    /**
     * What the word [typed] was meant to be, of what was [found] for its [keys] after [context]:
     * - typed no word, the likeliest the keys spell written otherwise (dont as don't, london as
     *   London), or else the likeliest a slip of no more than [maxCorrection] from them (teh as
     *   the), from [MIN_CORRECTED] keys; typed as a [name], a slip of no more than
     *   [NAME_CORRECTION], of a word with a capital (Stebe as Steve; Anushika is someone's);
     * - typed a word (nad, oft, of the many the pages have), the same if [KNOWN_MARGIN] likelier,
     *   but not a capital more or less (ladies, Ladies), nor a longer word (gear, years).
     * An apostrophe typed is never taken away. Null for none, or for keys that start a word likelier.
     * A [misspelling][Misspellings] is no word, whatever the pages have: the word meant.
     */
    private fun correctionOf(typed: String, keys: String, found: List<EnglishDecoder.Suggestion>, context: Long, name: Boolean): String? {
        val data = data ?: return null
        misspellings[typed.lowercase()]?.let { return it }
        val id = data.id(typed).takeIf { it != NgramModel.NO_WORD } ?: data.id(typed.lowercase())
        val own = if (id == NgramModel.NO_WORD) null else data.model.scoreAfter(context, id)
        val allowed = allowedFor(data, typed, id, known = own != null)
        val written = found.firstOrNull { !it.completes && EnglishData.key(data.word(it.word)) == keys && allowed(it) }
        val slip = slipOf(data, keys, found, name, allowed)
        val best = (if (own == null) written ?: slip else slip ?: written) ?: return null
        if (startsInstead(data, best, keys, known = own != null)) return null
        if (own != null && best.score - own < KNOWN_MARGIN) return null
        return data.word(best.word)
    }

    // what may stand for [typed] (the word [id], if [known]): not itself, an apostrophe typed kept,
    // and a word not as typed but for a capital more or less
    private fun allowedFor(data: EnglishData, typed: String, id: Int, known: Boolean): (EnglishDecoder.Suggestion) -> Boolean {
        val apostrophe = '\'' in typed
        // what follows the last apostrophe typed: s, ll, t, or nothing (dogs')
        val after = typed.substringAfterLast('\'').lowercase()
        return { s ->
            val text = data.word(s.word)
            val kept = !apostrophe || '\'' in text && text.substringAfterLast('\'').lowercase() == after
            s.word != id && kept && !(known && text.equals(typed, ignoreCase = true))
        }
    }

    private fun slipOf(
        data: EnglishData,
        keys: String,
        found: List<EnglishDecoder.Suggestion>,
        name: Boolean,
        allowed: (EnglishDecoder.Suggestion) -> Boolean,
    ): EnglishDecoder.Suggestion? {
        if (keys.length < MIN_CORRECTED) return null
        val most = if (name) NAME_CORRECTION else maxCorrection
        return found.firstOrNull { it.cost <= most && allowed(it) && (!name || data.word(it.word)[0].isUpperCase()) }
    }

    // typed so far, not wrong: the start of a word likelier than a slip (hel for hello, not he);
    // a letter short with a slip as well is wrong all the same (softwae for software), but for a word
    private fun startsInstead(data: EnglishData, best: EnglishDecoder.Suggestion, keys: String, known: Boolean): Boolean {
        if (!best.completes || known) return best.completes
        return best.cost == 0f || EnglishData.key(data.word(best.word)).length > keys.length + 1
    }

    // the words that may come next, a sentence's first capitalised but for one with a capital of its own (iPhone)
    private fun predictions(): List<String> {
        val decoder = decoder ?: return emptyList()
        val data = checkNotNull(data)
        val before = before()
        // nothing known of the text: nothing to follow
        if (before.isEmpty()) return emptyList()
        val start = before.lastOrNull() == EnglishText.START
        val context = decoder.context(before)
        val prev = previous(before)
        val model = offeredOf(SUGGESTED + 1) { decoder.predict(context, it) }
            .map { data.word(it.word) to user.score(it.lm, prev, data.word(it.word)) }
        // what the user typed after the word before: theirs to type again
        val theirs = prev?.let(user::followers).orEmpty().map { word ->
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

    // the [limit] best that [find] finds, asked for how many, that are offered: more asked for where
    // some are not, so those left out leave no gap
    private fun offeredOf(limit: Int, find: (Int) -> List<EnglishDecoder.Suggestion>): List<EnglishDecoder.Suggestion> {
        var asked = limit
        while (true) {
            val found = find(asked)
            val kept = found.filter { offered(it.word) }
            if (kept.size >= limit || found.size < asked) return kept.take(limit)
            asked *= 2
        }
    }

    // the words before the cursor, as EnglishText reads them: what the session committed, if that
    // is all it knows, starting no sentence that is known
    private fun before(): List<String> = EnglishText.context(recent, start = known && !cut)

    // the word last in [before], if one is known, as it was learned: a sentence's first as written
    // inside one (Hello as hello), which the user's pairs after it are counted under
    private fun previous(before: List<String>): String? {
        val word = before.lastOrNull()?.takeIf { it != EnglishText.START } ?: return null
        return inSentence(word, first = before.size == 2 && before[0] == EnglishText.START)
    }

    /** What was typed, as committed: i alone, and i before an apostrophe (i'm), as I. */
    private fun typed(): String {
        return capitalI(input.toString())
    }

    // i alone, and i before an apostrophe (i'm), as I; tapping and gliding alike
    private fun capitalI(s: String): String = if (s == "i" || s.startsWith("i'")) "I" + s.substring(1) else s

    // [word], as written, as the input has its case: Hello for he, HELLO for HE; I' at a
    // sentence's start is I'm's, not I'M's
    private fun inCase(word: String): String = when {
        input.count { it.isLetter() } > 1 && input.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        input[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    private fun snapshot(commit: String = "", handled: Boolean = true, deleteBefore: Int = 0): Snapshot {
        val from = page * pageSize
        val shown = candidates.subList(minOf(from, candidates.size), minOf(from + pageSize, candidates.size))
        return Snapshot(
            commit = commit,
            preedit = input.toString(),
            candidates = shown,
            page = page,
            hasPreviousPage = page > 0,
            hasNextPage = from + pageSize < candidates.size,
            handled = handled,
            predicting = input.isEmpty() && candidates.isNotEmpty(),
            total = candidates.size,
            first = from,
            deleteBefore = deleteBefore,
            cursor = if (cursor == input.length) -1 else cursor,
        )
    }

    // a correction space committed: the word [typed], and what it put in ([committed], its space too)
    private class Corrected(val typed: String, val committed: String)

    companion object {
        const val DEFAULT_PAGE_SIZE = 5

        /** How many words are suggested besides the one typed. */
        const val SUGGESTED = 9

        // after these, not the session's, a glide's word has a space before it
        private const val SPACED_AFTER = ".,!?;:"

        // [text]'s end as [edge] keeps it: its last [EDGE_CHARS] chars up to its last not a space,
        // and the spaces after them, as many as fit after those
        private fun endOf(text: String): String {
            val end = text.trimEnd().length
            val kept = text.substring(maxOf(0, end - EDGE_CHARS), end)
            return kept + text.substring(end).take(EnglishText.CONTEXT_CHARS - kept.length)
        }

        // the chars [edge] keeps before the spaces at its end: where nothing is known of the text
        // before the cursor (forgotten at a Reset, an Enter or an arrow, a hardware key, the field
        // left, and no Context read since), the app's Backspaces after keys typed take back a
        // word's worth before nothing is known
        private const val EDGE_CHARS = 32

        // [text] less its last code point
        private fun withoutLast(text: String): String =
            if (text.isEmpty()) text else text.substring(0, text.offsetByCodePoints(text.length, -1))

        // whether [text]'s last code point may belong to a cluster an editor's Backspace takes
        // otherwise than by code point: a mark (an accent, a variation selector, a keycap), a
        // ZWJ, anything outside the BMP (an emoji, a skin tone, a regional indicator, a tag), or
        // a CR, taken or left, perhaps a "\r\n"'s half (a key event takes both, an opted-in editor
        // the "\n")
        private fun endsInCluster(text: String): Boolean {
            if (text.isEmpty()) return false
            val c = text.codePointBefore(text.length)
            return c > 0xFFFF || c == 0x200D || c == '\r'.code || when (Character.getType(c).toByte()) {
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK,
                Character.COMBINING_SPACING_MARK -> true
                else -> false
            }
        }

        // words a glide's search keeps, before those never offered are left out
        private const val GLIDE_SEARCHED = 30

        /** The most a slip may cost ([TypingCosts]) for space to correct it: a key far from the one meant. */
        const val MAX_CORRECTION = 3.0f

        /** Fewer keys than this are corrected only as written otherwise (i as I): too many words are a slip from one. */
        const val MIN_CORRECTED = 2

        /** The most a slip may cost in a word typed with a capital of its own, a name likelier than not: a key beside. */
        const val NAME_CORRECTION = 1.0f

        /** How much likelier (log10) a word must be than the word typed, one too, to be put in its place: a hundred times. */
        const val KNOWN_MARGIN = 2f

        /** Punctuation that takes the place of the space after a word, a space after it. */
        private const val SWAPPED = ".,!?;:"

        // brackets that close, and quotes that may: in the place of the session's space, a space after them
        private const val CLOSING = ")]}"
        private const val QUOTES = "\"'"

        // brackets a quote after opens one: ("hi")
        private const val OPENING = "([{"

        // what the session's own space may follow besides a word: a space typed after it is that one
        private const val MARKS = SWAPPED + CLOSING + QUOTES
    }
}
