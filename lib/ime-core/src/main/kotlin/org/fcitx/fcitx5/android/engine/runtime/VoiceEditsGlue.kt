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
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

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

    val edits = VoiceEdits(object : VoiceEdits.Words {
        override fun readings(c: Char) = assets.voiceReadings(c)

        override fun mine(): VoiceEdits.Mine {
            val all = words.userWords()
            userWords = all
            tables.clear()
            fun of(kind: UserWord.Kind) = all.filter { it.kind == kind }.mapTo(HashSet()) { it.text }
            return VoiceEdits.Mine(of(UserWord.Kind.ADDED), of(UserWord.Kind.LEARNED), of(UserWord.Kind.BLOCKED))
        }

        override fun pinyinOf(text: String) = words.pinyinOf(text)

        // whether a hold would happen with [word] added: the words as VoiceEngine reads them, [replacing] removed (an
        // earlier word at the place would keep it), then it
        override fun takes(text: String, at: Int, heard: String, word: String, pinyin: String, replacing: String?): Boolean {
            val added = UserWord(word, pinyin, UserWord.Kind.ADDED)
            val without = { userWords.filterNot { it.kind == UserWord.Kind.ADDED && it.text == replacing } }
            val base = tables.getOrPut(replacing) { words.voiceHomophones(without()) }
            val homophones = (if (base.isEmpty) null else assets.voiceWordReadings(added)?.let { base.withAdded(word, it) })
                ?: words.voiceHomophones(without() + added)
            val out = now { homophones.hold().replace(text) { spans -> words.voiceHomophonesAllowed(spans) } }
            return out.regionMatches(at, word, 0, word.length) && text.regionMatches(at, heard, 0, heard.length)
        }
    }, simplify)

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

    /** Adds [ask]'s word by hand, then removes one this window's prompt added there ([UserLexicon.unAdd]); a word that does not add leaves that be. */
    fun remember(ask: VoiceEdits.Ask): Boolean {
        if (!words.addWord(ask.word, ask.pinyin)) return false
        ask.replaces?.let(lexicon::unAdd)
        edits.remembered(ask)
        return true
    }

    /** Takes [word] off the added words, forgetting nothing in the record; out of the model at once ([UserLexicon.unAdd]), so typed again it is learned as if never added. */
    fun forget(word: String) {
        lexicon.unAdd(word)
        edits.forgot(word)
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


        // pinyin's keyboards and handwriting ([answered]'s inking) count for edits; tables and English do not
        private val TYPED = setOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.T9)

        // runs [block] to its end here: the engine answers at once on this thread, so it never suspends
        private fun <T> now(block: suspend () -> T): T {
            var out: Result<T>? = null
            block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
            return checkNotNull(out) { "suspended" }.getOrThrow()
        }
    }
}
