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
 * A table's pinyin lookup ([TableOptions.pinyinKey], 反查; every letter while the 拼 key has it on,
 * [Action.LookUp]): [pinyin] reads the keys typed after the key, or from the first letter, each of
 * its candidates hinted with its code in the table ([codeOf]). A decorator over the
 * pinyin session, as [StrokeLookup][org.fcitx.fcitx5.android.engine.stroke.StrokeLookup] is over
 * pinyin's; [TableSession] hands it every action while it is [on] (S11: this was the table's own,
 * inline). How it ends is the table's to carry on from ([Ends]): it is the table's text put in.
 *
 * What is looked up is learned as full pinyin learns it (U-28), after the text before it ([start]).
 * Nothing of it is pinned or blocked: the pinyin has no phrases of the table's own to pin to, so a
 * long press offers forgetting alone. The cursor shown is the pinyin's.
 */
internal class PinyinLookUp(
    private val pinyin: Session,
    // the table's selection keys, shown while nothing is read yet as the table shows them
    private val labels: String,
    private val codeOf: (String) -> String?,
    private val ends: Ends,
) : Session {

    /** How a lookup ends, each the table's snapshot after it; the lookup is over when it is told. */
    interface Ends {
        /** [text] picked from the pinyin, put in, [record] the learning record's commit of it. */
        fun picked(text: String, record: Commit?): Snapshot

        /** [text] put in (looked up, [record] its commit, or else as typed), then [char], a key the app's. */
        fun passed(text: String, record: Commit?, char: Char): Snapshot

        /** [text], as typed, put in (Enter's [Action.CommitRaw]). */
        fun typed(text: String): Snapshot

        /** Left by [action], a Reset or a Context: nothing put in. */
        fun left(action: Action): Snapshot

        /** Dropped, nothing put in: the key alone deleted or a space after it. */
        fun dropped(): Snapshot
    }

    /** Whether a lookup is on: the input starts with its key, or the 拼 key's letters are looked up. */
    var on = false
        private set

    /** The key typed to start it ([TableOptions.pinyinKey]), null where the 拼 key's letters started it. */
    var key: Char? = null
        private set

    // the keys typed after [key]
    private val keys = StringBuilder()
    private var lookedUp: Snapshot? = null

    /**
     * Starts a lookup, [commit] put in before it; [context] what the pinyin learns it after. By
     * [key] typed, or with [first], a letter the 拼 key looks up, read at once.
     */
    fun start(context: Action.Context, commit: String, key: Char? = null, first: Char? = null): Snapshot {
        on = true
        this.key = key
        pinyin.apply(context)
        if (first == null) return idle(commit)
        val s = typeKey(Action.Key(first))
        return s.copy(commit = commit + s.commit)
    }

    /**
     * Ends it (the 拼 key turned off): what was picked of it put in as a pick is, its keys after
     * that handed back for the table to type as its code; nothing learned of them.
     */
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
        // not offered (see offers)
        is Action.Pin, is Action.Unpin, is Action.Block -> lookedUp ?: idle()
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
        // a key it would not take (' first) is swallowed, not handed on: the app must not get what
        // the preedit does not show
        if (!s.handled) return lookedUp ?: idle()
        keys.append(action.char)
        return fromPinyin(s)
    }

    // what was looked up is committed, or else what was typed as Enter has it; then the key is the app's
    private fun passOn(char: Char): Snapshot {
        var text = if (keys.isNotEmpty()) pinyin.apply(Action.Select(0)).commit else ""
        val record = if (text.isEmpty()) null else pinyin.lastCommit
        if (text.isEmpty()) text = typed()
        end()
        return ends.passed(text, record, char)
    }

    private fun backspace(): Snapshot = when {
        // the key alone, or the 拼 key's last letter
        keys.isEmpty() || (key == null && keys.length == 1 && lookedUp?.cursor != 0) -> {
            end()
            ends.dropped()
        }
        // the pinyin's cursor at its start: it deletes nothing
        lookedUp?.cursor == 0 -> lookedUp ?: idle()
        else -> {
            keys.setLength(keys.length - 1)
            fromPinyin(pinyin.apply(Action.Backspace))
        }
    }

    /**
     * What was typed, as typed: the key too, unless a word was picked from the start of it. The
     * keys here are as many as the pinyin's but, mended at its cursor, maybe not in its order: as
     * it has them, keys alone, nothing was picked.
     */
    private fun typed(): String {
        val raw = if (keys.isNotEmpty()) pinyin.apply(Action.CommitRaw).commit else ""
        val keysAlone = raw.length == keys.length && raw.all { it in 'a'..'z' || it == '\'' }
        return if (keysAlone) key?.let { it + raw } ?: raw else raw
    }

    private fun fromPinyin(s: Snapshot): Snapshot {
        if (s.commit.isNotEmpty()) {
            end()
            // looked up as full pinyin learns it (U-28), and taken back as it is (U-03)
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

    // the keys alone, nothing read of them yet: as the table shows a code with no candidate
    private fun idle(commit: String = "") = Snapshot(
        commit, shownKeys(), emptyList(), 0, hasPreviousPage = false, hasNextPage = false, handled = true, predicting = false, labels = labels,
        lookingUp = true,
    )

    /** What the lookup shows now, nothing committed: as its last key left it. */
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

    override fun offers(index: Int): Set<Offer> = pinyin.offers(index).intersect(setOf(Offer.FORGET))

    override fun why(index: Int): Why? = pinyin.why(index)

    override fun readingOf(index: Int): List<WordReading>? = pinyin.readingOf(index)

    override fun reread(): Snapshot? = pinyin.reread()

    override val rereads get() = pinyin.rereads

    override fun fieldFlags(flags: Int) = pinyin.fieldFlags(flags)
}
