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
 * An input method's keyboards in one: grouped keys' codes and offered syllables go to their own keyed session
 * (made at first use); letters (and hardware keys) to [full]. Routed by the key itself, not a setting, so a code
 * typed before the keyboard is remade still reaches its grouped session.
 *
 * - Glides go to [glideKeyed] where its layout shares keys (nine keys), else to [full].
 * - Other events go to whichever took the last key ([keyedActive]), so a Backspace or space after its commit is
 *   its own; a digit to [keyed] goes as the keypad's.
 * - Escape with nothing shown is the app's via [full]; with 联想 shown the first closes them.
 * - Switching commits the previous one's input as typed and resets both, as for text another wrote.
 * - Resets and [context] go to both; a keyed session made later gets the last context unless something was
 *   committed since. A stale strip's syllable is swallowed.
 */
internal class KeyboardPair(
    /** The input method's name: [sessionOf] for every keyboard but an active keyed one. */
    private val name: String,
    /** Null for code points no keyed session takes. */
    private val keyedOf: (Int) -> String?,
    private val makeFull: () -> Keyboard,
    /** Made at its first code. */
    private val makeKeyed: (String) -> Keyboard,
    /** Whether a hardware non-letter ([EngineEvent.HARD_CHAR]) is a key of full's: 双拼's scheme's (微软's ;). */
    private val fullTypes: (Int) -> Boolean = { false },
    /** Where glides over multi-letter keys go (pinyin's nine keys); null: full's. */
    private val glideKeyed: String? = null,
) {

    private var madeFull: Keyboard? = null
    private val full: Keyboard get() = madeFull ?: makeFull().also { madeFull = it }
    private val made = LinkedHashMap<String, Keyboard>()

    // the keyed session that took the last key, null for full: whose candidates and offers show
    private var active: String? = null

    /** Whether a keyed session took the last key: its candidates and offers show. */
    val keyedActive: Boolean get() = active != null

    // the last context told, for a keyed session made after it
    private var lastContext: ((Keyboard) -> Unit)? = null

    /** The keyed one's where it took the last key. */
    fun sessionOf(im: String): String = if (im == name) active ?: im else im

    /** All dropped, remade at the next key. */
    fun drop() {
        madeFull = null
        made.clear()
        active = null
        lastContext = null
    }

    /** Only [keyed] dropped, remade at its next code with the last context; its input, if active, is gone and full's shows. */
    fun dropKeyed(keyed: String) {
        made.remove(keyed)
        if (active == keyed) active = null
    }

    /** [payload] goes with the key to whichever takes it. */
    fun onEvent(event: Int, arg: Int, learning: Boolean = true, payload: Action? = null): Snapshot {
        if (event == EngineEvent.RESET) return reset(arg, learning)
        // with nothing shown Escape is the app's (a keyed Keyboard would swallow it); with 联想 shown the first closes
        // them. The active one stays
        if (event == EngineEvent.ESCAPE && !shows(keyedActive)) return full.onEvent(event, arg, learning)
        // a hardware non-letter (微软's ;): full's while a keyed one types, as a letter is; after its commit the keyed
        // one's mark, as a screen ; always is
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
        // a syllable from a stale strip (full took a key since): nothing to take or commit
        if (syllable && !keyedActive) return (madeFull?.showing ?: NOTHING).copy(commit = "", deleteBefore = 0, handled = true)
        // letters and glides are full's except the nine keys' glides (the 14 keys' session was offered none); the rest
        // go to whichever took the last key, so a Backspace or mark after its commit is its own to learn by
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

    // [event] to keyed [target] or full (null), committing the previous one's input before it
    private fun to(target: String?, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot {
        val left = if (target != active) leave(learning) else null
        active = target
        val existed = (if (target != null) made[target] else madeFull) != null
        val to = keyboard(target)
        // its knowledge of the field is stale: the other wrote there since
        if (left != null && existed) to.onEvent(EngineEvent.RESET, 0, learning)
        val next = to.onEvent(sent(target != null, event, arg), arg, learning, payload)
        return left?.let { merged(it, next) } ?: next
    }

    fun glidedBy(layout: GlideLayout?): String? = glideKeyed?.takeIf { layout?.sharesKeys == true }

    /** Whether [session] is this pair's and made: it hears of the field via [context]. */
    fun holds(session: String): Boolean = (session == name && madeFull != null) || session in made

    /** As [Keyboard.context], to both; [offers] for the active one alone. Whether it offers candidates now. */
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

    // a hardware digit while the 14 keys type picks, as full pinyin's does: their nine-keys-style Keyboard would not
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

    /** The active keyboard's snapshot (nothing if none made): the answer for a key no keyboard takes. */
    val showing: Snapshot get() = (active.let { if (it != null) made[it] else madeFull })?.showing ?: NOTHING

    // read off the keyboard: its context may show or drop predictions since its last key
    private fun shows(keyedOne: Boolean): Boolean {
        val shown = (if (keyedOne) made[active] else madeFull)?.showing
        return shown != null && (shown.preedit.isNotEmpty() || shown.candidates.isNotEmpty())
    }

    // the active one leaving: its input committed as typed (Enter), then reset as for text another wrote; an empty
    // snapshot, never null, for nothing committed
    private fun leave(learning: Boolean): Snapshot {
        val was = active.let { if (it != null) made[it] else madeFull }
        val out = if (was?.composing == true) was.onEvent(EngineEvent.ENTER, 0, learning) else NOTHING
        was?.onEvent(EngineEvent.RESET, 0, learning)
        return out
    }

    internal companion object {
        /** Sessions taking grouped keys' codes: nine-keys-kind keyboards. */
        val KEYED = setOf(
            InputMethodCatalog.PINYIN_KEYS, InputMethodCatalog.PINYIN_KEYS18, InputMethodCatalog.PINYIN_KEYS_OWN, InputMethodCatalog.T9,
            InputMethodCatalog.ZHUYIN_NINE, InputMethodCatalog.ZHUYIN,
            InputMethodCatalog.SHUANGPIN_KEYS18, InputMethodCatalog.SHUANGPIN_KEYS14, InputMethodCatalog.SHUANGPIN_NINE,
        )

        /** Pinyin's: 14 keys, 18 keys, a layout's own groups, the nine keys (with 连按 letters and glides), 注音九键 and 大千 each to its keyed session; [keyboard] makes each. */
        fun pinyin(keyboard: (String) -> Keyboard) = KeyboardPair(
            InputMethodCatalog.PINYIN,
            ::pinyinKeyed,
            { keyboard(InputMethodCatalog.PINYIN) },
            keyboard,
            glideKeyed = InputMethodCatalog.T9,
        )

        /** 双拼's: the 18, 14 and nine keys each to their own session; hardware letters and other keys ([EngineEvent.HARD_CHAR]) to full 双拼. */
        fun shuangpin(keyboard: (String) -> Keyboard, scheme: () -> ShuangpinScheme) = KeyboardPair(
            InputMethodCatalog.SHUANGPIN,
            ::shuangpinKeyed,
            { keyboard(InputMethodCatalog.SHUANGPIN) },
            keyboard,
            { c -> c in 0..Char.MAX_VALUE.code && scheme().types(c.toChar()) },
        )

        /** Null for none. */
        fun pinyinKeyed(c: Int): String? = when {
            c in Keyboard.TAPS -> InputMethodCatalog.PINYIN_KEYS
            c in Keyboard.PINYIN_18 || c in Keyboard.TAPS_18 -> InputMethodCatalog.PINYIN_KEYS18
            c in Keyboard.PLACES || c in NineKeyLetters.CUSTOM_BLOCK -> InputMethodCatalog.PINYIN_KEYS_OWN
            c in Keyboard.ZHUYIN_NINE -> InputMethodCatalog.ZHUYIN_NINE
            c in Keyboard.ZHUYIN_FULL -> InputMethodCatalog.ZHUYIN
            c in Keyboard.PINYIN_NINE || c in Keyboard.LETTERS -> InputMethodCatalog.T9
            NineKeyLetters.ofCode(c) == NineKeyLetters.G14 -> InputMethodCatalog.PINYIN_KEYS
            else -> null
        }

        /** Null for none. */
        fun shuangpinKeyed(c: Int): String? = when {
            c in Keyboard.SHUANGPIN_14 -> InputMethodCatalog.SHUANGPIN_KEYS14
            else -> when (NineKeyLetters.ofCode(c)) {
                NineKeyLetters.G18 -> InputMethodCatalog.SHUANGPIN_KEYS18
                NineKeyLetters.NINE_SP -> InputMethodCatalog.SHUANGPIN_NINE
                else -> null
            }
        }

        fun lettersOf(name: String) = when (name) {
            InputMethodCatalog.SHUANGPIN_KEYS18 -> NineKeyLetters.G18
            InputMethodCatalog.SHUANGPIN_KEYS14 -> NineKeyLetters.G14
            else -> NineKeyLetters.NINE_SP
        }

        /** A to z, either case: full's, never keyed (KeyRouter's HARD_CHAR is any other). */
        fun isLetter(codePoint: Int): Boolean = codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code

        val NOTHING = Snapshot("", "", emptyList(), 0, hasPreviousPage = false, hasNextPage = false, handled = true, predicting = false)

        /** [left]'s commit and deletion then [next]'s, as one snapshot; [next] deleting more than [left] committed deletes on before it. */
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
