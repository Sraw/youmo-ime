/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * An input method's keyboards in one: the screen's grouped keys, whose codes ([keyedOf]: pinyin's
 * 14 keys; 双拼's 18 keys and nine keys, a session each) and syllables picked
 * ([Keyboard.SYLLABLES]) go to a session of their own, made at the first of them; and the full
 * keyboard's, [full], which a hardware keyboard always types with. A code of another keyed session
 * than the active one turns to it as to [full]. Told apart by
 * the key itself, not a setting (a tap's code with where it fell, [Keyboard.TAPS], is a code):
 * a code typed while the keyboard shown is not yet made again for a change of it still reaches
 * the grouped session, never full pinyin to be typed as it is.
 *
 * - A code or a syllable goes to [keyed]; a letter a to z, upper case too, and a glide's
 *   [Keyboard.GLIDE] (the full keyboard's alone glides) to [full].
 * - Any other event to the one that took the last key ([keyedActive]), typing or not: a Backspace,
 *   space or mark after its commit is its own (what it learned of a commit deleted whole, U-03; the
 *   text before the next word). A digit to [keyed] goes as the keypad's, to pick as full pinyin's does.
 * - Escape with nothing shown is the app's, through [full], the 14 keys' Keyboard swallowing it (the
 *   nine keys' rule); with 联想 shown the first closes them (HK-03).
 * - Turning to the other, what the one before typed is kept as typed, an Enter's as a switch of
 *   input method has it, its commit put before what the other then commits. Both are reset then, as
 *   for text another wrote: neither keeps a context or a journal the other made old.
 * - A reset and a [context] go to both; the 14 keys' session, made later, is told the last context
 *   unless something was committed since. A context only the active one's to offer by. The field's
 *   flags each keyboard is made with (Engines). A syllable of a strip gone stale is swallowed.
 */
internal class KeyboardPair(
    /** The input method's name: its sessions' ([sessionOf]) for every keyboard but a keyed one active. */
    private val name: String,
    /** The keyed session a code point is for (a code, a tap with its place), null for any other. */
    private val keyedOf: (Int) -> String?,
    private val makeFull: () -> Keyboard,
    /** The keyed session of that name, made at its first code. */
    private val makeKeyed: (String) -> Keyboard,
    /** Whether a hardware keyboard's char no letter ([EngineEvent.HARD_CHAR]) is a key of full's: 双拼's scheme's (微软's ;). */
    private val fullTypes: (Int) -> Boolean = { false },
) {
    /** One keyed session, [keyedName], for [isCode] and a tap with its place (pinyin's 14 keys). */
    constructor(name: String, keyedName: String, makeFull: () -> Keyboard, keyed: () -> Keyboard, isCode: (Char) -> Boolean) : this(
        name,
        { c -> keyedName.takeIf { c in Keyboard.TAPS || c in 0..Char.MAX_VALUE.code && isCode(c.toChar()) } },
        makeFull,
        { keyed() },
    )

    private var madeFull: Keyboard? = null
    private val full: Keyboard get() = madeFull ?: makeFull().also { madeFull = it }
    private val made = LinkedHashMap<String, Keyboard>()

    // the keyed session that took the last key, null for full: whose candidates and offers are shown
    private var active: String? = null

    /** Whether a keyed session was the last to take a key: whose candidates and offers are shown. */
    val keyedActive: Boolean get() = active != null

    // the last context told, given to a keyed session made after it
    private var lastContext: ((Keyboard) -> Unit)? = null

    /** The session [im]'s candidates are now of: the keyed one's where it took the last key. */
    fun sessionOf(im: String): String = if (im == name) active ?: im else im

    /** All dropped, as the sessions are: made again at the next key. */
    fun drop() {
        madeFull = null
        made.clear()
        active = null
        lastContext = null
    }

    fun onEvent(event: Int, arg: Int, learning: Boolean = true): Snapshot {
        if (event == EngineEvent.RESET) return reset(arg, learning)
        // with nothing shown Escape is the app's, as full pinyin has it (a keyed Keyboard would swallow it);
        // with 联想 shown the first closes them, the second is the app's (HK-03). The active one stays
        if (event == EngineEvent.ESCAPE && !shows(keyedActive)) return full.onEvent(event, arg, learning)
        // a hardware keyboard's char no letter ([EngineEvent.HARD_CHAR], 微软's ;): full's while a keyed one
        // types, as a letter is; after its commit the keyed one's mark, as a screen's ; always is (review S1, N9)
        if (event == EngineEvent.HARD_CHAR) {
            val hard = fullTypes(arg) && (active == null || made[active]?.composing == true)
            return route(EngineEvent.CHAR, arg, learning, toFull = hard)
        }
        return route(event, arg, learning, toFull = false)
    }

    private fun route(event: Int, arg: Int, learning: Boolean, toFull: Boolean): Snapshot {
        val char = event == EngineEvent.CHAR
        val keyed = if (char) keyedOf(arg) else null
        val syllable = char && keyed == null && arg in 0..Char.MAX_VALUE.code && arg.toChar() in Keyboard.SYLLABLES
        // a syllable picked off a strip gone stale (full took a key since): nothing to take, and nothing committed for it
        if (syllable && !keyedActive) return (madeFull?.showing ?: NOTHING).copy(commit = "", deleteBefore = 0, handled = true)
        // a letter is full's, a glide's key too (only the full keyboard glides: after the 14 keys' commit
        // it would reach their session, which was offered no glide); the rest the one's that took the
        // last key, typing or not: a Backspace, space or
        // mark after its commit is its own to learn by (U-03: a commit deleted whole is unlearned) and the text before
        val letter = char && !syllable && (isLetter(arg) || arg == Keyboard.GLIDE)
        val target = keyed ?: if (toFull || letter) null else active
        val out = to(target, event, arg, learning)
        // a session made after this starts from no known text, not from the context before it
        if (out.commit.isNotEmpty()) lastContext = null
        return out
    }

    private fun reset(arg: Int, learning: Boolean): Snapshot {
        val fromFull = full.onEvent(EngineEvent.RESET, arg, learning)
        var fromActive: Snapshot? = null
        for ((n, k) in made) k.onEvent(EngineEvent.RESET, arg, learning).let { if (n == active) fromActive = it }
        return fromActive ?: fromFull
    }

    // [event] to the keyed session [target] or full's (null), what the one before typed committed before it
    private fun to(target: String?, event: Int, arg: Int, learning: Boolean): Snapshot {
        val left = if (target != active) leave(learning) else null
        active = target
        val existed = (if (target != null) made[target] else madeFull) != null
        val to = keyboard(target)
        // what it knew of the field is old: the other wrote there since (one made now knows only the last context)
        if (left != null && existed) to.onEvent(EngineEvent.RESET, 0, learning)
        val next = to.onEvent(sent(target != null, event, arg), arg, learning)
        return left?.let { merged(it, next) } ?: next
    }

    /** As [Keyboard.context], to both; [offers] for the active one alone. Whether the active one offers candidates now. */
    fun context(
        before: String,
        learning: Boolean = true,
        cleared: Boolean = false,
        learnFlags: Int = 0,
        started: Boolean = false,
        after: String? = null,
        selected: Boolean = false,
        offers: Boolean = true,
        said: Boolean = false,
    ): Boolean {
        val fullOffers = madeFull?.context(before, learning, cleared, learnFlags, started, after, selected, offers && !keyedActive, said)
        var keyedOffers: Boolean? = null
        for ((n, k) in made) k.context(before, learning, cleared, learnFlags, started, after, selected, offers && n == active, said).let { if (n == active) keyedOffers = it }
        // a session made later starts from this field's text too, offering nothing for it
        lastContext = { it.context(before, learning, cleared, learnFlags, started, after, selected, offers = false, said = said) }
        return (if (keyedActive) keyedOffers else fullOffers) == true
    }

    // a hardware digit while the 14 keys type picks, as full pinyin's does: their Keyboard, a nine keys' one, has digits as no picks
    private fun sent(toKeyed: Boolean, event: Int, arg: Int) =
        if (toKeyed && event == EngineEvent.CHAR && arg in '0'.code..'9'.code) EngineEvent.KEYPAD else event

    private fun keyboard(keyed: String?): Keyboard = if (keyed == null) {
        full
    } else {
        made[keyed] ?: makeKeyed(keyed).also {
            made[keyed] = it
            lastContext?.invoke(it)
        }
    }

    // read off the keyboard, not kept here: its context may show 联想 or drop predictions since its last key
    private fun shows(keyedOne: Boolean): Boolean {
        val shown = (if (keyedOne) made[active] else madeFull)?.showing
        return shown != null && (shown.preedit.isNotEmpty() || shown.candidates.isNotEmpty())
    }

    // the active one left for the other: what it typed committed as typed (an Enter), then reset, as for
    // any text another wrote (PinyinSession's Reset): what it showed, its context and its journal dropped.
    // An empty snapshot for nothing committed, never null: the other is reset as it comes back too
    private fun leave(learning: Boolean): Snapshot {
        val was = active.let { if (it != null) made[it] else madeFull }
        val out = if (was?.composing == true) was.onEvent(EngineEvent.ENTER, 0, learning) else NOTHING
        was?.onEvent(EngineEvent.RESET, 0, learning)
        return out
    }

    internal companion object {
        /** The sessions a pair hands grouped keys' codes to. */
        val KEYED = setOf(Engines.PINYIN_KEYS, Engines.SHUANGPIN_KEYS18, Engines.SHUANGPIN_NINE)

        /** Pinyin's: the 14 keys' codes to [Engines.PINYIN_KEYS], [keyboard] making each input method's. */
        fun pinyin(keyboard: (String) -> Keyboard) =
            KeyboardPair(Engines.PINYIN, Engines.PINYIN_KEYS, { keyboard(Engines.PINYIN) }, { keyboard(Engines.PINYIN_KEYS) }, NineKeyLetters.G14::isCode)

        /**
         * 双拼's: the 18 keys' codes and the nine keys' each to a session of their own; a hardware
         * keyboard's letters and its other keys ([EngineEvent.HARD_CHAR], 微软's ;) full 双拼's.
         */
        fun shuangpin(keyboard: (String) -> Keyboard, scheme: () -> ShuangpinScheme) = KeyboardPair(
            Engines.SHUANGPIN,
            { c ->
                when (NineKeyLetters.ofCode(c)) {
                    NineKeyLetters.G18 -> Engines.SHUANGPIN_KEYS18
                    NineKeyLetters.NINE_SP -> Engines.SHUANGPIN_NINE
                    else -> null
                }
            },
            { keyboard(Engines.SHUANGPIN) },
            keyboard,
            { c -> c in 0..Char.MAX_VALUE.code && scheme().types(c.toChar()) },
        )

        /** The keys of 双拼's keyed session [name]. */
        fun lettersOf(name: String) = if (name == Engines.SHUANGPIN_KEYS18) NineKeyLetters.G18 else NineKeyLetters.NINE_SP

        /** A letter a to z, upper case too: full's, never a keyed session's (KeyRouter's HARD_CHAR is any other). */
        fun isLetter(codePoint: Int): Boolean = codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code

        /** Nothing committed or shown, handled. */
        val NOTHING = Snapshot("", "", emptyList(), 0, hasPreviousPage = false, hasNextPage = false, handled = true, predicting = false)

        /**
         * [left]'s commit and deletion, then [next]'s: as one snapshot, the deletion first. [next]
         * deleting more than [left] committed deletes on before it.
         */
        fun merged(left: Snapshot, next: Snapshot): Snapshot {
            val kept = left.commit.length - next.deleteBefore
            return if (kept >= 0) {
                next.copy(commit = left.commit.substring(0, kept) + next.commit, deleteBefore = left.deleteBefore)
            } else {
                next.copy(deleteBefore = left.deleteBefore - kept)
            }
        }
    }
}
