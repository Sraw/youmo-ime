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

/**
 * [VoiceEdits] as [Engines] has it (061-voice-learn-from-edits.md): told the engines' commits and
 * Backspaces as they answer, the host's contexts, and the app's calls; asking the user's words,
 * the characters' readings and voice's own replacing ([VoiceHomophones]) of [words] and [assets].
 */
internal class VoiceEditsGlue(
    private val words: LexiconFacade,
    private val lexicon: UserLexicon,
    private val assets: EngineAssets,
    simplify: (String) -> String,
) {
    // the user's words as an edit's [VoiceEdits.Words.mine] read them, and voice's table of them made once
    // for the edit, by the word taken off them ([VoiceEdits.Words.takes]' replacing): review S7
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

        // what a hold would do with [word] added as the settings add it: the words as VoiceEngine reads them, [replacing]
        // off them (review M4: one added before at the place, ranked first, would keep its place), and it after them
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

    /**
     * [im]'s answer [s] to [event]: a commit, a Backspace the app takes. [predicting]: what showed
     * before it was 联想, whose pick is no word typed; [inking]: handwriting written over the keys showed
     * before it (InkOver.inking), whose commit it is. Nothing is followed where nothing is [learning].
     */
    fun answered(im: String, event: Int, s: Snapshot, predicting: Boolean, learning: Boolean, reading: String? = null, inking: Boolean = false) {
        if (!edits.open) return
        // the engine taking back what was before the cursor (English's correction, say): not followed
        if (!learning || s.deleteBefore > 0) return edits.clear()
        if (event == EngineEvent.BACKSPACE && !s.handled && s.commit.isEmpty()) edits.backspace()
        // handwriting's (written over the keys, [inking]: the commit is its), read by the characters' readings it
        // shares with what voice heard (§3), not by the pinyin the input method's own last commit had
        if (s.commit.isNotEmpty()) edits.committed(s.commit, typed = (inking || im in TYPED) && !predicting, reading.takeIf { !inking })
    }

    /** The host's context, as [Engines.context] has it. */
    fun context(before: String, learning: Boolean, cleared: Boolean, after: String?, selected: Boolean, said: Boolean) {
        if (!edits.open) return
        if (!learning) edits.clear() else edits.context(before, after, selected, said, cleared)
    }

    /**
     * [ask]'s word added by hand, then one this window's prompt added at its place taken off ([UserLexicon.unAdd]):
     * a word that does not add leaves that one be (review S8).
     */
    fun remember(ask: VoiceEdits.Ask): Boolean {
        if (!words.addWord(ask.word, ask.pinyin)) return false
        ask.replaces?.let(lexicon::unAdd)
        edits.remembered(ask)
        return true
    }

    /**
     * [word] off the added words again, nothing forgotten in the learning record (review R1). Out of the
     * model at once ([UserLexicon.unAdd]): typed again it is learned as if never added; kept listed till
     * the next start ([UserModel.unlistByHand] alone), it was learned not at all (review S3, measured).
     */
    fun forget(word: String) {
        lexicon.unAdd(word)
        edits.forgot(word)
    }

    companion object {
        /**
         * The syllables [commit] was typed as (`xu jing`), from [record], the session's record of it: its
         * rows of the whole commit, or its picks joined; null where they do not spell it (a table's, handwriting's).
         */
        fun readingOf(commit: String, record: LearnRecord.Commit?): String? {
            val rows = record?.rows ?: return null
            rows.firstOrNull { it.whole && it.words.text == commit }?.let { return it.words.reading.ifEmpty { null } }
            val picks = rows.filter { it.flags and Row.PICK != 0 }
            if (picks.joinToString("") { it.words.text } != commit) return null
            return picks.joinToString(" ") { it.words.reading }.takeIf { it.isNotBlank() }
        }


        // pinyin's (全拼, 双拼, the nine keys and the rest of its keyboards), and handwriting's ([answered]'s inking): the edits
        // the user's choice (r13 U-30, Q5) counts; a table's, English's not
        private val TYPED = setOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.T9)

        // [block] run to its end here: its suspensions are none (the engine answers at once, on this thread)
        private fun <T> now(block: suspend () -> T): T {
            var out: Result<T>? = null
            block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
            return checkNotNull(out) { "suspended" }.getOrThrow()
        }
    }
}
