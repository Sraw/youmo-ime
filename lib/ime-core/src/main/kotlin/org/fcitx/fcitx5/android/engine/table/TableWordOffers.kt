/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.OfferedWord
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.session.WordOffer

/**
 * Offered, never learned untapped: characters typed one at a time are often not a word. Not under a code where
 * adding it would change what a shorter code types, which the typist's habits rely on.
 */
class TableWordOffers(
    private val table: TableDictionary,
    private val options: TableOptions,
    private val saved: SavedPhrases,
    // blocked under the code or everywhere
    private val blocked: (code: String, text: String) -> Boolean,
    // word starts of a text, 0 and its length included; null: no sentence model, nothing split
    private val boundaries: ((String) -> IntArray)?,
) {
    // the run's last characters, each one code point
    private val chars = ArrayList<String>()

    // the run's length, beyond what [chars] keeps: a whole run of 9 is no word
    private var count = 0

    /** What is offered now; null once withdrawn. */
    var offer: WordOffer? = null
        private set

    // put up by the app: one held back (another notice up, a finger down) was never seen
    private var seen = false

    // the code being typed has a glided key: its commit is a glide's, which ends a run
    private var glided = false
    private var nextId = 1

    // seen and left: never again in this field
    private val given = HashSet<String>()

    fun followed(action: Action, s: Snapshot, verified: Boolean, composing: Boolean): Boolean {
        if (action is Action.Key && action.glided) glided = true
        var wrote = false
        when {
            action is Action.Context -> if (action.started) newField() else end()
            action == Action.Reset || action == Action.CommitRaw || s.deleteBefore > 0 -> end()
            // the app's Backspace: the run's last character, while the field is as written
            action == Action.Backspace && !s.handled -> if (verified) deleted() else end()
            s.commit.isNotEmpty() -> {
                wrote = true
                if (glided) end() else committed(s.commit)
            }
            else -> withdraw()
        }
        // the key itself went into the field after
        if (action is Action.Key && !s.handled) end()
        if (!composing) glided = false
        return wrote
    }

    /** [text] went into the field: one Han character goes on the run, anything else ends it. */
    fun committed(text: String) {
        withdraw()
        if (!isHan(text)) return end()
        chars += text
        count++
        if (chars.size > KEPT) chars.removeAt(0)
    }

    /** A Backspace took the run's last character. */
    fun deleted() {
        withdraw()
        if (chars.isEmpty()) return end()
        chars.removeAt(chars.size - 1)
        count--
        if (chars.isEmpty()) count = 0
    }

    fun end() {
        withdraw()
        chars.clear()
        count = 0
    }

    /** Another field: what was left there may be offered here. */
    fun newField() {
        end()
        given.clear()
    }

    fun withdraw() {
        val o = offer ?: return
        if (seen) o.words.forEach { given += it.text }
        offer = null
    }

    /** The app showed offer [id]. */
    fun shown(id: Int) {
        if (offer?.id == id) seen = true
    }

    /** The words to offer after the last commit, made the [offer]; null for none. */
    fun offer(): WordOffer? {
        val words = picks().take(MAX_WORDS)
        if (words.isEmpty()) return null
        return WordOffer(nextId++, words).also {
            offer = it
            seen = false
        }
    }

    private fun picks(): List<OfferedWord> {
        if (count < 2 || table.rules.isEmpty) return emptyList()
        val lengths = buildList {
            if (count <= MAX_WHOLE) add(count)
            if (count >= 3) add(3)
            add(2)
        }.distinct()
        val kept = chars.joinToString("")
        val starts = boundaries?.invoke(kept)?.toSet()
        return lengths.mapNotNull { n ->
            val part = chars.subList(chars.size - n, chars.size)
            val text = part.joinToString("")
            // a start inside the model's word (结束也's 束也) is half a word
            if (starts != null && kept.length - text.length !in starts) return@mapNotNull null
            if (text in given || !wordLike(part)) return@mapNotNull null
            val code = table.encode(text)?.takeIf { it.length == table.maxLength } ?: return@mapNotNull null
            if (inTable(code, text) || saved.hasText(text) || blocked(code, text)) return@mapNotNull null
            if (!keepsPrefixes(code)) return@mapNotNull null
            OfferedWord(text, code)
        }
    }

    // under its code or a shorter one of it (a 简码): the reverse lookup knows single characters only
    private fun inTable(code: String, text: String) = (1..code.length).any { table.contains(code.substring(0, it), text) }

    // each shorter code types as before once the word is under [code]: one that 顶字 commits at a key leading
    // nowhere must lead on already; none may be a lone code that commits itself
    private fun keepsPrefixes(code: String): Boolean {
        for (n in 1 until code.length) {
            val prefix = code.substring(0, n)
            val under = table.match(prefix, null)
            // the table's code entries only: 五笔拼音's pinyin spellings lead elsewhere
            val longer = under.any { table.codeLength(it) > n && !table.isPinyin(it) }
            if (options.noMatchAutoSelectLength != 0 && !longer) return false
            if (!longer && commitsAlone(n) && under.map(table::text).distinct().size == 1) return false
        }
        return true
    }

    private fun commitsAlone(length: Int) = options.autoSelect && options.autoSelectLength.let { it < 0 || it in 1..length }

    private fun wordLike(part: List<String>): Boolean {
        if (part.all { it in FUNCTION_WORDS }) return false
        if (part.first() in NO_START || part.last() in NO_END) return false
        if (part.all { it == part[0] }) return false
        return !part.all { it in NUMERALS }
    }

    companion object {
        const val MAX_WORDS = 2

        /** A whole run is offered up to this long; longer ones are mostly half sentences. */
        const val MAX_WHOLE = 4

        // the run's end kept: the boundaries of the whole run up to here
        private const val KEPT = 8

        private val FUNCTION_WORDS = "的地得了着过是在我你您他她它们这那就也都和与跟吗呢吧啊呀哦嗯么个不没很太还又再才把被给对向从到".charSet()
        private val NO_START = "的地得了着过是在我你他她它和与跟就也都把被给对向从到".charSet()
        private val NO_END = FUNCTION_WORDS + "说要会能去来有啦嘛嘞滴".charSet()
        private val NUMERALS = "一二三四五六七八九十百千万两零".charSet()

        private fun String.charSet() = map { it.toString() }.toSet()

        private fun isHan(text: String): Boolean {
            if (text.codePointCount(0, text.length) != 1) return false
            val script = Character.UnicodeScript.of(text.codePointAt(0))
            return script == Character.UnicodeScript.HAN
        }
    }
}
