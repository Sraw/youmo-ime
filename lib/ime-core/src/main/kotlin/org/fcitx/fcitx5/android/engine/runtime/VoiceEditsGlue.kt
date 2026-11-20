/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.input.voice.VoiceEdits
import org.fcitx.fcitx5.android.input.voice.VoiceHomophones
import org.fcitx.fcitx5.android.input.voice.VoiceRerank

/** [VoiceEdits] wired into [Engines]: fed commits, Backspaces, contexts and app calls; reads the user's words, readings and voice's replacing ([VoiceHomophones]) from [words] and [assets]. */
internal class VoiceEditsGlue(
    private val words: LexiconFacade,
    private val lexicon: UserLexicon,
    private val assets: EngineAssets,
    simplify: (String) -> String,
) {
    // the user's words as an edit read them, and voice's table of them, made once per edit by the word taken off
    private var userWords: List<UserWord> = emptyList()
    private val tables = HashMap<String?, VoiceHomophones>()

    // the last talks' stretches as voice kept them, the latest last: a word judged on the stretch voice judged
    private val talks = ArrayDeque<List<String>>()

    val edits = VoiceEdits(object : VoiceEdits.Words {
        override fun readings(c: Char) = assets.voiceReadings(c)

        override fun mine(): VoiceEdits.Mine {
            val all = words.userWords()
            userWords = all
            tables.clear()
            fun of(kind: UserWord.Kind) = all.filter { it.kind == kind }.mapTo(HashSet()) { it.text }
            return VoiceEdits.Mine(
                of(UserWord.Kind.ADDED), of(UserWord.Kind.LEARNED), of(UserWord.Kind.BLOCKED),
                all.filter { it.voiceAlways }.mapTo(HashSet()) { it.text },
            )
        }

        override fun pinyinOf(text: String) = words.pinyinOf(text)

        // as voice would write it with [word] added: the words as VoiceEngine reads them, [replacing] removed (an earlier
        // word at the place would keep it), then it; judged at the place, on the stretch voice judged after the one
        // before it, else in its clause after what is before it
        override fun takes(text: String, at: Int, heard: String, word: String, pinyin: String, replacing: String?): VoiceEdits.Takes {
            if (!text.regionMatches(at, heard, 0, heard.length)) return VoiceEdits.Takes.NO
            val without = { userWords.filterNot { it.kind == UserWord.Kind.ADDED && it.text == replacing } }
            val base = tables.getOrPut(replacing) { words.voiceHomophones(without()) }
            val readings = assets.voiceWordReadings(UserWord(word, pinyin, UserWord.Kind.ADDED))
            val judged = stretchAt(talks, text, at, heard.length) ?: clauseAt(text, at, heard.length)
            val blocked = userWords.filter { it.kind == UserWord.Kind.BLOCKED }.map { it.text }
            fun puts(always: Boolean): Boolean {
                val bonus = if (always) VoiceHomophones.ALWAYS else VoiceHomophones.ADDED_BONUS
                val table = (if (base.isEmpty) null else readings?.let { base.withAdded(word, it, bonus) })
                    ?: words.voiceHomophones(without() + UserWord(word, pinyin, UserWord.Kind.ADDED, voiceAlways = always))
                val out = words.voicePick(table, judged.text, judged.before, blocked)
                return out.regionMatches(judged.at, word, 0, word.length)
            }
            return when {
                puts(always = false) -> VoiceEdits.Takes.YES
                puts(always = true) -> VoiceEdits.Takes.IF_ALWAYS
                else -> VoiceEdits.Takes.NO
            }
        }
    }, simplify)

    /** A talk's stretches as voice kept them ([VoiceChoice.Talk.stretches]). */
    fun stretches(texts: List<String>) {
        talks.addLast(texts.toList())
        while (talks.sumOf { t -> t.sumOf { it.length } } > MAX_STRETCHES) talks.removeFirst()
    }

    /** [im]'s answer to [event]: a commit or app-taken Backspace. [predicting]: 联想 showed (a pick is no typed word); [inking]: handwriting over the keys showed, whose commit it is. Nothing is followed without [learning]. */
    fun answered(im: String, event: Int, s: Snapshot, predicting: Boolean, learning: Boolean, reading: String? = null, inking: Boolean = false) {
        try {
            follow(im, event, s, predicting, learning, reading, inking)
        } finally {
            releaseIfClosed()
        }
    }

    private fun follow(im: String, event: Int, s: Snapshot, predicting: Boolean, learning: Boolean, reading: String?, inking: Boolean) {
        if (!edits.open) return
        // the engine took back text before the cursor (English's correction): not followed
        if (!learning || s.deleteBefore > 0) return edits.clear()
        if (event == EngineEvent.BACKSPACE && !s.handled && s.commit.isEmpty()) edits.backspace()
        // handwriting's commits are read by the readings shared with what voice heard, not by the input method's last pinyin
        if (s.commit.isNotEmpty()) edits.committed(s.commit, typed = (inking || im in TYPED) && !predicting, reading.takeIf { !inking })
    }

    /** As [Engines.context] has it. */
    fun context(before: String, learning: Boolean, cleared: Boolean, after: String?, selected: Boolean, said: Boolean) {
        if (!edits.open) return releaseIfClosed()
        try {
            if (!learning) edits.clear() else edits.context(before, after, selected, said, cleared)
        } finally {
            releaseIfClosed()
        }
    }

    /** Stops following voice text (another field, habits off), releasing what edits read. */
    fun end() {
        edits.clear()
        releaseIfClosed()
    }

    /** Whether user words or voice tables are held for an edit. */
    val holdsWords: Boolean get() = userWords.isNotEmpty() || tables.isNotEmpty()

    // window closed: release the user's words and voice's tables rather than holding them till the next window
    private fun releaseIfClosed() {
        if (edits.open) return
        userWords = emptyList()
        tables.clear()
    }

    /**
     * Adds [ask]'s word by hand ([VoiceEdits.Ask.always]: and always put in), then removes one this window's prompt
     * added there ([UserLexicon.unAdd]); a word that does not add leaves that be.
     */
    fun remember(ask: VoiceEdits.Ask): Boolean {
        if (!ask.added && !words.addWord(ask.word, ask.pinyin)) return false
        // one added before and deleted while asked: nothing to mark
        if (ask.always && !lexicon.setVoiceAlways(ask.word, true) && ask.added) return false
        ask.replaces?.let(lexicon::unAdd)
        edits.remembered(ask)
        return true
    }

    /**
     * Takes [ask]'s word off the added words, forgetting nothing in the record; out of the model at once
     * ([UserLexicon.unAdd]), so typed again it is learned as if never added. One added before the ask stays, no longer always put in.
     */
    fun forget(ask: VoiceEdits.Ask) {
        if (ask.always) lexicon.setVoiceAlways(ask.word, false)
        if (ask.added) return
        lexicon.unAdd(ask.word)
        edits.forgot(ask.word)
    }

    companion object {
        /** The syllables [commit] was typed as (`xu jing`), from the session's [record]: rows of the whole commit or its picks; null where they do not spell it. */
        fun readingOf(commit: String, record: LearnRecord.Commit?): String? {
            val rows = record?.rows ?: return null
            rows.firstOrNull { it.whole && it.words.text == commit }?.let { return it.words.reading.ifEmpty { null } }
            val picks = rows.filter { it.flags and Row.PICK != 0 }
            if (picks.joinToString("") { it.words.text } != commit) return null
            return picks.joinToString(" ") { it.words.reading }.takeIf { it.isNotBlank() }
        }


        /** A place judged: [text] as voice read it, the place [at] in it, after [before]. */
        internal class Judged(val text: String, val at: Int, val before: String)

        /** The stretch of [talks] (the latest last) that holds the place, found in [text] without marks and spaces. */
        internal fun stretchAt(talks: Collection<List<String>>, text: String, at: Int, length: Int): Judged? {
            val kept = text.indices.filter { VoiceRerank.bare(text[it].toString()).isNotEmpty() }
            val bare = String(CharArray(kept.size) { text[kept[it]] })
            val place = kept.indexOf(at)
            if (place < 0) return null
            var end = bare.length
            for (talk in talks.reversed()) {
                for (k in talk.indices.reversed()) {
                    val b = VoiceRerank.bare(talk[k])
                    val i = if (b.isEmpty() || end < b.length) -1 else bare.lastIndexOf(b, end - b.length)
                    if (i < 0) continue
                    end = i
                    if (place >= i && place + length <= i + b.length) return Judged(b, place - i, talk.getOrNull(k - 1).orEmpty())
                }
            }
            return null
        }

        // a stretch not found as kept (the polish wrote it otherwise): the clause around the place, after what is before it
        internal fun clauseAt(text: String, at: Int, length: Int): Judged {
            var from = at
            while (from > 0 && Character.isLetterOrDigit(text[from - 1])) from--
            var to = at + length
            while (to < text.length && Character.isLetterOrDigit(text[to])) to++
            return Judged(text.substring(from, to), at - from, text.substring(0, from))
        }

        // characters of stretches kept: more than an edited talk holds
        private const val MAX_STRETCHES = 4000

        // pinyin's keyboards and handwriting ([answered]'s inking) count for edits; tables and English do not
        private val TYPED = setOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.T9)
    }
}
