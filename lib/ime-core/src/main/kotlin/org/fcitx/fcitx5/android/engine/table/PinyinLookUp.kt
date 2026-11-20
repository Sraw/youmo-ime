/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.reading.WordReading
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.HandedBack
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * A table's pinyin lookup (反查: [TableOptions.pinyinKey], or every letter while the 拼 key is on): [pinyin] reads
 * the keys, each candidate hinted with its table code ([codeOf]). A decorator over the pinyin session, as
 * [StrokeLookup][org.fcitx.fcitx5.android.engine.stroke.StrokeLookup] is; how it ends is the table's ([Ends]).
 * Learned as full pinyin learns it; nothing is pinned or blocked, so a long press offers forgetting alone.
 */
internal class PinyinLookUp(
    private val pinyin: Session,
    private val labels: String,
    private val codeOf: (String) -> String?,
    private val ends: Ends,
) : Session {

    interface Ends {
        fun picked(text: String, record: Commit?): Snapshot

        /** [text] put in, then [char], a key the app's. */
        fun passed(text: String, record: Commit?, char: Char): Snapshot

        fun typed(text: String): Snapshot

        fun left(action: Action): Snapshot

        /** Dropped, nothing put in: the key alone deleted or a space after it. */
        fun dropped(): Snapshot
    }

    var on = false
        private set

    /** The key that started it, null where the 拼 key's letters did. */
    var key: Char? = null
        private set

    private val keys = StringBuilder()
    private var lookedUp: Snapshot? = null

    /** Starts a lookup after [commit], learned after [context]; by [key], or with [first], a letter the 拼 key looks up at once. */
    fun start(context: Action.Context, commit: String, key: Char? = null, first: Char? = null): Snapshot {
        on = true
        this.key = key
        pinyin.apply(context)
        if (first == null) return idle(commit)
        val s = typeKey(Action.Key(first))
        return s.copy(commit = commit + s.commit)
    }

    /** Ends it (拼 turned off): what was picked put in, the keys after handed back for the table to type; nothing learned of them. */
    fun drop(): HandedBack {
        val back = if (keys.isEmpty()) HandedBack("", null, "") else pinyin.handBack()
        end()
        return back
    }

    override var learning: Boolean
        get() = pinyin.learning
        set(value) {
            pinyin.learning = value
        }

    override fun reads(c: Char): Boolean = c in 'a'..'z' || c == '\''

    override fun apply(action: Action): Snapshot = when (action) {
        is Action.Key -> if (reads(action.char)) typeKey(action) else passOn(action.char)
        Action.Backspace -> backspace()
        Action.CommitRaw -> typed().let { text ->
            end()
            ends.typed(text)
        }
        Action.Reset, is Action.Context -> {
            end()
            ends.left(action)
        }
        is Action.Pin, is Action.Unpin -> lookedUp ?: idle()
        is Action.Cursor, is Action.Step -> if (keys.isNotEmpty()) fromPinyin(pinyin.apply(action)) else idle()
        // the key alone: nothing to pick, and space drops it
        is Action.Select -> if (keys.isNotEmpty()) fromPinyin(pinyin.apply(action)) else {
            end()
            ends.dropped()
        }
        else -> fromPinyin(pinyin.apply(action))
    }

    private fun typeKey(action: Action.Key): Snapshot {
        val s = pinyin.apply(action)
        // a key it would not take (' first) is swallowed: the app must not get what the preedit does not show
        if (!s.handled) return lookedUp ?: idle()
        keys.append(action.char)
        return fromPinyin(s)
    }

    private fun passOn(char: Char): Snapshot {
        var text = if (keys.isNotEmpty()) pinyin.apply(Action.Select(0)).commit else ""
        val record = if (text.isEmpty()) null else pinyin.lastCommit
        if (text.isEmpty()) text = typed()
        end()
        return ends.passed(text, record, char)
    }

    private fun backspace(): Snapshot = when {
        keys.isEmpty() || (key == null && keys.length == 1 && lookedUp?.cursor != 0) -> {
            end()
            ends.dropped()
        }
        lookedUp?.cursor == 0 -> lookedUp ?: idle()
        else -> {
            keys.setLength(keys.length - 1)
            fromPinyin(pinyin.apply(Action.Backspace))
        }
    }

    /** What was typed, raw: the key too, unless a word was picked; the keys here may differ in order from pinyin's after edits, so its own are used. */
    private fun typed(): String {
        val raw = if (keys.isNotEmpty()) pinyin.apply(Action.CommitRaw).commit else ""
        val keysAlone = raw.length == keys.length && raw.all { it in 'a'..'z' || it == '\'' }
        return if (keysAlone) key?.let { it + raw } ?: raw else raw
    }

    private fun fromPinyin(s: Snapshot): Snapshot {
        if (s.commit.isNotEmpty()) {
            end()
            return ends.picked(s.commit, pinyin.lastCommit)
        }
        return s.copy(
            preedit = s.preedit.ifEmpty { shownKeys() },
            // the lookup has the keys, whatever the pinyin session says of them
            handled = true,
            predicting = false,
            hints = s.candidates.map { codeOf(it).orEmpty() },
            lookingUp = true,
        ).also { lookedUp = it }
    }

    private fun idle(commit: String = "") = Snapshot(
        commit, shownKeys(), emptyList(), 0, hasPreviousPage = false, hasNextPage = false, handled = true, predicting = false, labels = labels,
        lookingUp = true,
    )

    fun shown(): Snapshot = lookedUp?.copy(commit = "") ?: idle()

    private fun shownKeys() = (key?.toString() ?: "") + keys

    private fun end() {
        // no 联想 after a lookup, and no context carried into the next one
        pinyin.apply(Action.Reset)
        on = false
        key = null
        lookedUp = null
        keys.setLength(0)
    }

    override fun candidates(from: Int, count: Int): List<Choice> = pinyin.candidates(from, count).map { Choice(it.text, codeOf(it.text).orEmpty()) }

    // not pinned: the pin belongs to the table's code
    override fun offers(index: Int): Set<Offer> = pinyin.offers(index).intersect(setOf(Offer.FORGET, Offer.BLOCK))

    override fun why(index: Int): Why? = pinyin.why(index)

    override fun readingOf(index: Int): List<WordReading>? = pinyin.readingOf(index)

    override fun reread(): Snapshot? = pinyin.reread()

    override val rereads get() = pinyin.rereads

    override fun fieldFlags(flags: Int) = pinyin.fieldFlags(flags)
}
