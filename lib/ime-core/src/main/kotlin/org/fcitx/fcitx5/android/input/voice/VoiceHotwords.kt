/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.engine.host.Engines

/**
 * Words the recognizer is told to listen for (sherpa-onnx's hotwords, a bonus on each of their
 * tokens as the beam search goes): the new-word pack's, put in the assets by VoiceDataPlugin by
 * [spelled] too, and the user's own. On the voice evaluation's new words, those in the list went
 * from 89 to some 104 of 138 heard, the rest of the speech much as it was; a larger bonus
 * hears more of them and mishears more of everything else.
 *
 * A word the user blocked is not written, the pack's or not, as a word: [VoiceBlocking] picks a
 * hypothesis without it, else our sherpa-onnx's beam search (lib/sherpa-onnx) is told not to let
 * one complete it. Only those [spelled]: two to twelve Chinese characters, [MAX_USER] of them;
 * and only once the engine has told the listener the user's words (VoiceEngine.userHotwords
 * waits so long).
 * Each stream with the user's words holds a graph of the pack's too, some 37 MB.
 */
object VoiceHotwords {

    /** the bonus on each token of a hotword: 1 to 2 measured, 3 too much for everyday speech */
    const val SCORE = 1.5f

    /**
     * the bonus on each token of a word the user added by hand (an imported word stays apart until
     * used twice, then is learned: [SCORE], the user's choice 2026-10-22): at [SCORE], 澜境花园
     * was heard 兰静花园 or 蓝镜花园 every time (VO-14). On five takes through the emulator's
     * microphone, 3 heard it in none, 4 in three, 5 in all five. Learned words keep [SCORE]: the
     * same 5 on 3000 of them (jieba's commonest) took aishell's CER from 3.9% to 9.9% and fleurs'
     * from 8.7% to 12.8%, and still lost 澜境花园 among them; with added at 5 and those 3000 at
     * [SCORE], 4.2% and 8.9%, 澜境花园 five of five. A big list belongs in the base lexicon.
     */
    const val ADDED_SCORE = 5f

    // the recognizer builds its graph again for each stretch from the pack's words and these:
    // a few thousand add little to the pack's 78 thousand
    const val MAX_USER = 3000

    /** [HotwordSpelling.spelled], which the dict tool spells the pack's words by. */
    fun spelled(word: String): String? = HotwordSpelling.spelled(word)

    /** What a stream is told of the user's words: each list's words [spelled], apart by `/`. */
    data class Words(val hotwords: String, val blocked: String) {
        /** the blocked words as written, not [spelled] */
        val blockedWords: List<String> get() = blocked.split('/').filter { it.isNotEmpty() }.map { it.replace(" ", "") }

        /**
         * As listened for with U-93's [habits]: off, none of the user's words, the blocked still
         * blocked. For words read before the switch went off (one shared, or the last read for a
         * read too late), which [ofUser] made with it on.
         */
        fun withHabits(habits: Boolean): Words = if (habits) this else copy(hotwords = "")

        companion object {
            val NONE = Words("", "")
        }
    }

    /**
     * The user's words as last read from the engine, for a listener whose read comes too late:
     * the engine busy on its thread, those words rather than none. Without them, a word the user
     * blocked was typed and one they added never heard (VO-10, VO-14), with nothing in the log.
     */
    class Remembered {
        @Volatile
        var last: Words = Words.NONE
            private set

        private var started = 0L
        private var lastFrom = 0L

        private var underWay: Any? = null

        /** A read about to start: its ticket for [read], later reads' larger. */
        @Synchronized
        fun start(): Long = ++started

        /**
         * The read under way while [running] says it still runs, else one [launch]ed now with its
         * ticket: one read at a time, so a hold while a busy engine has not answered waits on that
         * read rather than queueing another. Checked and set under one lock: two holds at once
         * launch one read.
         */
        @Synchronized
        fun <R : Any> shared(running: (R) -> Boolean, launch: (Long) -> R): R {
            @Suppress("UNCHECKED_CAST")
            (underWay as R?)?.takeIf(running)?.let { return it }
            return launch(++started).also { underWay = it }
        }

        /**
         * [words], read by the read [ticket] names, remembered unless a read started after it was
         * remembered first (reads run on another thread and may finish out of order); returned.
         * A read that comes after its listener stopped waiting is still remembered, for the next.
         */
        @Synchronized
        fun read(ticket: Long, words: Words): Words = words.also {
            if (ticket > lastFrom) {
                lastFrom = ticket
                last = it
            }
        }

        /** What a listener uses: [read] if it came in time (not null), else the words last read. */
        fun inTime(read: Words?): Words = read ?: last
    }

    /**
     * The user's words for one stream: as hotwords, those they added first, then those they made
     * as they typed, most used first ([Engines.userWords]' order): 我的词 alone (U-30, D29), no
     * word of another kind, nor one the user only typed once; blocked, those they blocked. With
     * [habits] off (U-93's 「用我的输入习惯」) no hotword of theirs, the blocked still blocked.
     */
    fun ofUser(words: List<Engines.UserWord>, habits: Boolean = true): Words {
        fun spell(kinds: (Engines.UserWord.Kind) -> Boolean) = words.asSequence()
            .filter { kinds(it.kind) }
            .mapNotNull { word -> spelled(word.text)?.let { it to word.kind } }
            // added and learned both: added, which comes first
            .distinctBy { it.first }
            .take(MAX_USER)
            // sherpa-onnx's own bonus for one hotword, after its tokens; learned ones get [SCORE]
            .joinToString("/") { (spelling, kind) -> if (kind == Engines.UserWord.Kind.ADDED) "$spelling :$ADDED_SCORE" else spelling }
        return Words(
            hotwords = if (habits) spell { it in MINE } else "",
            blocked = spell { it == Engines.UserWord.Kind.BLOCKED },
        )
    }

    // the kinds of [Engines.UserWord] that are the user's own words (稿子 §1 我的词)
    private val MINE = setOf(Engines.UserWord.Kind.ADDED, Engines.UserWord.Kind.LEARNED)
}
