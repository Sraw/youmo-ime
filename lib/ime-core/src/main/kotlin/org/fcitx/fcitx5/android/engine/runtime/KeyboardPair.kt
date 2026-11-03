/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * An input method's keyboards in one: the screen's grouped keys, whose codes ([keyedOf]: pinyin's
 * 14, 18 and nine keys; 双拼's 18, 14 and nine keys, a session each) and syllables picked
 * ([Keyboard.SYLLABLES]) go to a session of their own, made at the first of them; and the full
 * keyboard's, [full], which a hardware keyboard always types with. A code of another keyed session
 * than the active one turns to it as to [full]. Told apart by
 * the key itself, not a setting (a tap's code with where it fell, [Keyboard.TAPS], is a code):
 * a code typed while the keyboard shown is not yet made again for a change of it still reaches
 * the grouped session, never full pinyin to be typed as it is.
 *
 * - A code or a syllable goes to [keyed]; a letter a to z, upper case too, to [full]; a glide's
 *   [Keyboard.GLIDE] to [glideKeyed] where its layout has letters sharing keys (pinyin's nine keys),
 *   else to [full] (no other grouped keyboard glides).
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
    /** The keyed session a glide over keys of more than one letter goes to (pinyin's nine keys'), null for none: full's then. */
    private val glideKeyed: String? = null,
) {

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

    /** As [Keyboard.onEvent]: [payload] goes with the key to the one that takes it. */
    fun onEvent(event: Int, arg: Int, learning: Boolean = true, payload: Action? = null): Snapshot {
        if (event == EngineEvent.RESET) return reset(arg, learning)
        // with nothing shown Escape is the app's, as full pinyin has it (a keyed Keyboard would swallow it);
        // with 联想 shown the first closes them, the second is the app's (HK-03). The active one stays
        if (event == EngineEvent.ESCAPE && !shows(keyedActive)) return full.onEvent(event, arg, learning)
        // a hardware keyboard's char no letter ([EngineEvent.HARD_CHAR], 微软's ;): full's while a keyed one
        // types, as a letter is; after its commit the keyed one's mark, as a screen's ; always is (review S1, N9)
        if (event == EngineEvent.HARD_CHAR) {
            val hard = fullTypes(arg) && (active == null || made[active]?.composing == true)
            return route(EngineEvent.CHAR, arg, learning, toFull = hard, payload)
        }
        return route(event, arg, learning, toFull = false, payload)
    }

    private fun route(event: Int, arg: Int, learning: Boolean, toFull: Boolean, payload: Action?): Snapshot {
        val char = event == EngineEvent.CHAR
        val keyed = if (char) keyedOf(arg) else null
        val syllable = char && keyed == null && arg in 0..Char.MAX_VALUE.code && arg.toChar() in Keyboard.SYLLABLES
        // a syllable picked off a strip gone stale (full took a key since): nothing to take, and nothing committed for it
        if (syllable && !keyedActive) return (madeFull?.showing ?: NOTHING).copy(commit = "", deleteBefore = 0, handled = true)
        // a letter is full's, a glide's key too but the nine keys' (only those and the full keyboard glide:
        // after the 14 keys' commit it would reach their session, which was offered no glide); the rest the
        // one's that took the last key, typing or not: a Backspace, space or
        // mark after its commit is its own to learn by (U-03: a commit deleted whole is unlearned) and the text before
        val glide = char && arg == Keyboard.GLIDE
        val letter = char && !syllable && (isLetter(arg) || glide)
        val target = keyed ?: (if (glide) glidedBy((payload as? Action.Glide)?.glide?.layout) else null) ?: if (toFull || letter) null else active
        val out = to(target, event, arg, learning, payload)
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
    private fun to(target: String?, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot {
        val left = if (target != active) leave(learning) else null
        active = target
        val existed = (if (target != null) made[target] else madeFull) != null
        val to = keyboard(target)
        // what it knew of the field is old: the other wrote there since (one made now knows only the last context)
        if (left != null && existed) to.onEvent(EngineEvent.RESET, 0, learning)
        val next = to.onEvent(sent(target != null, event, arg), arg, learning, payload)
        return left?.let { merged(it, next) } ?: next
    }

    /** The session a glide over [layout] is read by: [glideKeyed] where letters share its keys, else the full keyboard's, [name]. */
    fun glidedBy(layout: GlideLayout?): String? = glideKeyed?.takeIf { layout?.sharesKeys == true }

    /** Whether [session] is one of this pair's, made: it hears of the field through [context], not on its own. */
    fun holds(session: String): Boolean = (session == name && madeFull != null) || session in made

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
        /** The sessions a pair hands grouped keys' codes to: a keyboard of the nine keys' kind (Keyboard's nineKeys). */
        val KEYED = setOf(
            InputMethodCatalog.PINYIN_KEYS, InputMethodCatalog.PINYIN_KEYS18, InputMethodCatalog.T9,
            InputMethodCatalog.ZHUYIN_NINE, InputMethodCatalog.ZHUYIN,
            InputMethodCatalog.SHUANGPIN_KEYS18, InputMethodCatalog.SHUANGPIN_KEYS14, InputMethodCatalog.SHUANGPIN_NINE,
        )

        /**
         * Pinyin's: the 14 keys' codes (a tap's with its place too) to [InputMethodCatalog.PINYIN_KEYS],
         * the 18 keys' to [InputMethodCatalog.PINYIN_KEYS18], the nine keys' (their digits, a letter 连按
         * types, [Keyboard.LETTERS], and a glide over them) to [InputMethodCatalog.T9], 注音九键's and 大千's
         * to [InputMethodCatalog.ZHUYIN_NINE] and [InputMethodCatalog.ZHUYIN]; [keyboard] making
         * each session's.
         */
        fun pinyin(keyboard: (String) -> Keyboard) = KeyboardPair(
            InputMethodCatalog.PINYIN,
            ::pinyinKeyed,
            { keyboard(InputMethodCatalog.PINYIN) },
            keyboard,
            glideKeyed = InputMethodCatalog.T9,
        )

        /**
         * 双拼's: the 18 keys' codes, the 14 keys' and the nine keys' each to a session of their own; a hardware
         * keyboard's letters and its other keys ([EngineEvent.HARD_CHAR], 微软's ;) full 双拼's.
         */
        fun shuangpin(keyboard: (String) -> Keyboard, scheme: () -> ShuangpinScheme) = KeyboardPair(
            InputMethodCatalog.SHUANGPIN,
            ::shuangpinKeyed,
            { keyboard(InputMethodCatalog.SHUANGPIN) },
            keyboard,
            { c -> c in 0..Char.MAX_VALUE.code && scheme().types(c.toChar()) },
        )

        /** Pinyin's keyed session code point [c] is for ([pinyin]), null for none. */
        fun pinyinKeyed(c: Int): String? = when {
            c in Keyboard.TAPS -> InputMethodCatalog.PINYIN_KEYS
            c in Keyboard.PINYIN_18 -> InputMethodCatalog.PINYIN_KEYS18
            c in Keyboard.ZHUYIN_NINE -> InputMethodCatalog.ZHUYIN_NINE
            c in Keyboard.ZHUYIN_FULL -> InputMethodCatalog.ZHUYIN
            c in Keyboard.PINYIN_NINE || c in Keyboard.LETTERS -> InputMethodCatalog.T9
            NineKeyLetters.ofCode(c) == NineKeyLetters.G14 -> InputMethodCatalog.PINYIN_KEYS
            else -> null
        }

        /** 双拼's keyed session code point [c] is for ([shuangpin]), null for none. */
        fun shuangpinKeyed(c: Int): String? = when {
            c in Keyboard.SHUANGPIN_14 -> InputMethodCatalog.SHUANGPIN_KEYS14
            else -> when (NineKeyLetters.ofCode(c)) {
                NineKeyLetters.G18 -> InputMethodCatalog.SHUANGPIN_KEYS18
                NineKeyLetters.NINE_SP -> InputMethodCatalog.SHUANGPIN_NINE
                else -> null
            }
        }

        /** The keys of 双拼's keyed session [name]. */
        fun lettersOf(name: String) = when (name) {
            InputMethodCatalog.SHUANGPIN_KEYS18 -> NineKeyLetters.G18
            InputMethodCatalog.SHUANGPIN_KEYS14 -> NineKeyLetters.G14
            else -> NineKeyLetters.NINE_SP
        }

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
