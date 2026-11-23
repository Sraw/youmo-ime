/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.engine.runtime.UserWord

/**
 * Words the recognizer listens for (sherpa-onnx hotwords, a bonus per token in the beam search): the pack's and
 * the user's, [HotwordSpelling.spelled]. Blocked words are never written: [VoiceBlocking] picks a hypothesis
 * without them, else our sherpa-onnx fork's search refuses to complete one. A larger bonus mishears everything else more.
 */
object VoiceHotwords {

    /** Per token: 3 is too much for everyday speech. */
    const val SCORE = 1.5f

    /** For words added by hand: at [SCORE] they were misheard every time. Learned words keep [SCORE]: thousands at 5 doubled the CER. */
    const val ADDED_SCORE = 5f

    // the graph is rebuilt per stretch from the pack's words and these: a few thousand add little
    const val MAX_USER = 3000

    data class Words(
        val hotwords: String,
        val blocked: String,
        val homophones: VoiceHomophones = VoiceHomophones.NONE,
        /** 生僻字屏蔽's held characters: none completed in the search nor typed. Not in [blockedWords], which match whole words. */
        val held: Set<String> = emptySet(),
    ) {
        /** As written, not spelled. */
        val blockedWords: List<String> get() = blocked.split('/').filter { it.isNotEmpty() }.map { it.replace(" ", "") }

        /** The blocked words, then each held character as a token of its own. */
        val searchBlocked: String get() = (listOf(blocked).filter { it.isNotEmpty() } + held.sorted()).joinToString("/")

        fun holds(text: String): Boolean = held.isNotEmpty() && held.any { it in text }

        /** [habits] off: none of the user's words, the blocked still blocked (for words read while it was on). */
        fun withHabits(habits: Boolean): Words = if (habits) this else copy(hotwords = "", homophones = VoiceHomophones.NONE)

        companion object {
            val NONE = Words("", "")
        }
    }

    /** The last words read, for a listener whose read comes too late: without them a blocked word was typed, an added one never heard. */
    class Remembered {
        @Volatile
        var last: Words = Words.NONE
            private set

        private var started = 0L
        private var lastFrom = 0L

        private var underWay: Any? = null

        /** One read at a time: a hold while the engine is busy waits on that read rather than queueing another. */
        @Synchronized
        fun <R : Any> shared(running: (R) -> Boolean, launch: (Long) -> R): R {
            @Suppress("UNCHECKED_CAST")
            (underWay as R?)?.takeIf(running)?.let { return it }
            return launch(++started).also { underWay = it }
        }

        /** Remembered unless a later-started read was remembered first (reads may finish out of order). */
        @Synchronized
        fun read(ticket: Long, words: Words): Words = words.also {
            if (ticket > lastFrom) {
                lastFrom = ticket
                last = it
            }
        }

        /** [read] if it came in time, else the words last read. */
        fun inTime(read: Words?): Words = read ?: last
    }

    /** Less those with a held character, the blocked kept. */
    fun unheld(words: List<UserWord>, held: Set<String>): List<UserWord> =
        if (held.isEmpty()) words else words.filter { it.kind == UserWord.Kind.BLOCKED || it.text !in held }

    /**
     * The user's words for one stream: added then learned as hotwords (no other kind), blocked as blocked. A word
     * with a held character is no hotword; [Words.held] keeps only the recognizer's tokens among [chars].
     */
    fun ofUser(words: List<UserWord>, held: Set<String> = emptySet(), chars: Collection<String> = emptyList()): Words {
        val kept = unheld(words, held)
        fun spell(kinds: (UserWord.Kind) -> Boolean) = kept.asSequence()
            // a table's remembered words are the table's: voice does not listen for them
            .filter { kinds(it.kind) && it.table.isEmpty() }
            .mapNotNull { word -> HotwordSpelling.spelled(word.text)?.let { it to word.kind } }
            // added and learned both: added, which comes first
            .distinctBy { it.first }
            .take(MAX_USER)
            // sherpa-onnx's own bonus for one hotword, after its tokens
            .joinToString("/") { (spelling, kind) -> if (kind == UserWord.Kind.ADDED) "$spelling :$ADDED_SCORE" else spelling }
        return Words(
            hotwords = spell { it in MINE },
            blocked = spell { it == UserWord.Kind.BLOCKED },
            held = chars.filterTo(HashSet()) { it in held },
        )
    }

    private val MINE = setOf(UserWord.Kind.ADDED, UserWord.Kind.LEARNED)
}
