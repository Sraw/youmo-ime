/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import kotlin.math.roundToInt

/** The events the androidengine addon sends, numbered as it numbers them (androidengine_public.h). */
object EngineEvent {
    /** A character typed; the argument is its code point. */
    const val CHAR = 0
    const val BACKSPACE = 1
    const val ENTER = 2
    const val ESCAPE = 3
    const val PAGE_UP = 4
    const val PAGE_DOWN = 5

    /** A candidate tapped; the argument is its index among all of them. */
    const val PICK = 6

    /** The app moved the cursor, or the input method was left. */
    const val RESET = 7

    /** Any other key: an arrow, Home, Tab, Delete; the argument is its fcitx keysym. */
    const val OTHER = 8

    /** "Forget word" on a candidate; the argument is its index among all of them. */
    const val FORGET = 9

    /**
     * No key: the user pauses, and the last snapshot [refines][Snapshot.refines]. The snapshot
     * back is [handled][Snapshot.handled] only if it changed, to be shown in place of the last.
     */
    const val REFINE = 10

    /** "Pin to top as custom phrase" on a candidate; the argument is its index among all of them. */
    const val PIN = 11

    /** "Delete from custom phrase" on a candidate; the argument is its index among all of them. */
    const val UNPIN = 12

    /** "Never show this word" on a candidate; the argument is its index among all of them. */
    const val BLOCK = 13

    /** A tap in the composed text; the argument is the character it puts the cursor before, in code points. */
    const val CURSOR = 14

    /**
     * A key of the screen's that the app types itself, where English suggests nothing; the argument
     * is the char it types ([Session.ERASED]'s for Backspace), or 0 for none: the offer on show
     * put away, the text before the cursor as it was.
     */
    const val TYPED = 15

    /**
     * A keypad's character (KP_2, KP_Decimal): the number keyboard's keys, a digit swiped or
     * long-pressed off a letter. The argument is its code point. Never a key the session types
     * with: on the nine keys 2 is abc, KP_2 is 2.
     */
    const val KEYPAD = 16

    /**
     * A hardware keyboard's character no letter, sent to 双拼 alone (KeyRouter): a key the scheme may
     * type (微软's ;), its full keyboard's, where the screen's same char is a mark of the grouped keys'
     * typing (review S1). Any session takes it as [CHAR].
     */
    const val HARD_CHAR = 17
}

/**
 * A keyboard's events as a [session]'s actions: which keys the engine takes, which are the app's.
 *
 * A key the session reads is typed. While something is typed, space picks the first candidate
 * and a digit the one it numbers, Enter keeps the input as typed and Escape drops it; any other
 * character goes to the session to end the input, committed as the session would, then to the
 * app, so `nihao,` gives 你好， (the comma turned full-width by fcitx's punctuation addon, which
 * androidengine asks for every key handed back). Keys that are no characters (arrows, Home) are
 * swallowed while something is typed: the app must not move its cursor about the input shown.
 * Left, Right, Home and End move the session's cursor in its input, where it keeps one ([Action.Step]).
 *
 * While words that may follow are offered, any key the session does not read goes to the app and
 * the offer goes away: space after a word is a space, not the first prediction.
 *
 * [nineKeys]: the session is the nine keys' (九键), whose 重输 is Escape and whose syllables come
 * as [SYLLABLES]. The host says so: a table whose codes are digits reads 2 as well, and its
 * Escape and characters are the app's.
 *
 * [learning] is passed to the session: off where the app asks that nothing be learned.
 */
class Keyboard(private val session: Session, private val nineKeys: Boolean) {

    private var shown: Snapshot = IDLE

    /** What the last event or [context] left on show: preedit, candidates, predictions. */
    val showing: Snapshot get() = shown

    /** Where the page shown starts among all the candidates ([Snapshot.first]): a paged list's index counts from it. */
    val first get() = shown.first

    private val english get() = session.takesEveryCharacter

    /** Something typed, not a prediction. */
    val composing get() = shown.preedit.isNotEmpty() && !shown.predicting

    fun onEvent(event: Int, arg: Int, learning: Boolean = true): Snapshot {
        session.learning = learning
        shown = when (event) {
            EngineEvent.CHAR -> char(arg)
            EngineEvent.KEYPAD -> keypad(arg)
            EngineEvent.BACKSPACE -> session.apply(Action.Backspace)
            EngineEvent.ENTER, EngineEvent.PAGE_UP, EngineEvent.PAGE_DOWN, EngineEvent.CURSOR, EngineEvent.OTHER -> whileComposing(event, arg)
            // the nine keys' 重输 is Escape: with nothing typed, nothing to start over, not the app's
            EngineEvent.ESCAPE -> if (composing || shown.predicting) session.apply(Action.Reset) else if (nineKeys) IDLE.copy(handled = true) else passOn()
            EngineEvent.PICK -> session.apply(Action.Pick(arg))
            EngineEvent.FORGET, EngineEvent.PIN, EngineEvent.UNPIN, EngineEvent.BLOCK -> session.apply(pressed(event, arg))
            EngineEvent.RESET -> session.apply(Action.Reset).copy(handled = false)
            EngineEvent.REFINE -> refine()
            EngineEvent.TYPED -> typed(arg)
            else -> passOn()
        }
        return shown
    }

    /** The field's [flags] alone, for a keyboard made after its context: see [Session.fieldFlags]. */
    fun fieldFlags(flags: Int) = session.fieldFlags(flags)

    /**
     * [before] is the text before the cursor, which the user put somewhere the engine did not
     * (a field focused, a tap): what is typed next follows it. Nothing on show is dropped, as the
     * host would not know: the service resets fcitx first where there is something. [learning]
     * is as for [onEvent], set before: this comes before any key typed in a field just focused.
     * [cleared], [started], [after], [selected] and [offers] as [Action.Context]'s. Whether the session
     * offers candidates now (English's word typed in place of its correction before the cursor), which a [RECALL] shows.
     */
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
        if (shown.preedit.isNotEmpty() || shown.candidates.isNotEmpty()) return false
        session.learning = learning
        shown = session.apply(Action.Context(before, cleared, learnFlags, started, after, selected, offers, said))
        return shown.candidates.isNotEmpty()
    }

    /** What a long press on the candidate at [index] offered, and the user chose. */
    private fun pressed(event: Int, index: Int) = when (event) {
        EngineEvent.FORGET -> Action.Forget(index)
        EngineEvent.PIN -> Action.Pin(index)
        EngineEvent.BLOCK -> Action.Block(index)
        else -> Action.Unpin(index)
    }

    // the host's own characters, no key's: null for any other
    private fun hostChar(codePoint: Int): Snapshot? = when (codePoint) {
        INK -> session.apply(Action.Ink)
        GLIDE -> session.apply(Action.Glide)
        RECALL -> again(handled = true)
        AGAIN -> session.reread() ?: again(handled = true)
        else -> null
    }

    private fun char(codePoint: Int): Snapshot {
        hostChar(codePoint)?.let { return it }
        // whatever the session: one sent the wrong way reads as its code would, never as U+FFFD ending the input
        if (codePoint in TAPS) {
            val (code, at) = untapped(codePoint)
            return session.apply(Action.Key(code, at = at))
        }
        if (codePoint in NINE_DIGITS) {
            val n = codePoint - NINE_DIGITS.first
            return session.apply(Action.NineKey('0' + n % DIGITS, upper = n >= DIGITS))
        }
        if (codePoint in NINE_LETTERS) {
            val n = codePoint - NINE_LETTERS.first
            return session.apply(Action.NineKey((if (n >= ALPHABET) 'A' else 'a') + n % ALPHABET))
        }
        if (codePoint in LETTERS && nineKeys) {
            val n = codePoint - LETTERS.first
            return session.apply(Action.Letter('a' + n % ALPHABET, again = n >= ALPHABET))
        }
        // past the BMP (an emoji key): no session reads one, but it still ends the input
        val c = if (codePoint in 0..Char.MAX_VALUE.code) codePoint.toChar() else REPLACEMENT
        if (c in SYLLABLES && nineKeys) {
            val n = c - SYLLABLES.first
            return session.apply(Action.Syllable(n % Action.Syllable.MAX, n / Action.Syllable.MAX))
        }
        if (session.reads(c) || english) return session.apply(Action.Key(c))
        // on the nine keys a digit is a key: one not read (0) ends the input, as punctuation does
        val digit = c.digitToIntOrNull()?.takeUnless { nineKeys }
        return when {
            composing && c == ' ' -> session.apply(Action.Select(0))
            // 1 is the first; 0 the tenth
            composing && digit != null -> session.apply(Action.Select((digit + DIGITS - 1) % DIGITS))
            else -> session.apply(Action.Key(c)).copy(handled = false)
        }
    }

    // a keypad's digit is never a key the session types with (the nine keys' 2, a table's code):
    // as pinyin has any digit, it picks the candidate it numbers while something is typed, and is
    // the app's otherwise. English reads it as it reads any digit, but where its keys are the nine
    // keys' (Session.picksByDigit); the keypad's other characters (. + =) go as the keyboard's own do
    private fun keypad(codePoint: Int): Snapshot {
        val digit = codePoint - '0'.code
        val picks = !english || composing && session.picksByDigit
        if (!picks || digit !in 0 until DIGITS) return char(codePoint)
        return if (composing) session.apply(Action.Select((digit + DIGITS - 1) % DIGITS)) else passOn()
    }

    // keys the session takes while something is typed, the app's otherwise
    private fun whileComposing(event: Int, arg: Int): Snapshot = when {
        // an arrow, Tab: English commits its word and the key moves on from it; another session
        // moves its cursor by an arrow, Home or End, and keeps any other key, its input as it was
        composing && event == EngineEvent.OTHER -> when {
            english -> session.apply(Action.CommitRaw)
            else -> step(arg)?.let { session.apply(it).takeIf(Snapshot::handled) } ?: again(handled = true)
        }
        composing -> session.apply(
            when (event) {
                EngineEvent.ENTER -> Action.CommitRaw
                EngineEvent.PAGE_UP -> Action.PreviousPage
                EngineEvent.PAGE_DOWN -> Action.NextPage
                // the session counts UTF-16 units: a piece picked may be past the BMP
                EngineEvent.CURSOR -> Action.Cursor(utf16Offset(shown.preedit, arg))
                else -> error("event $event is not one taken while composing")
            },
        )
        // a tap in the app's text: the panel as it was
        event == EngineEvent.CURSOR -> again(handled = false).also { session.passedOn() }
        else -> passOn()
    }

    // what the app typed itself where English suggests nothing: after the text before the cursor,
    // for a glide after it to know whether a space goes first. Past the BMP (an emoji) it is passed
    // on as a key the session knows nothing of. While a word is typed it is committed as an Enter
    // would, not dropped by the session forgetting its input, and the char still goes after it
    private fun typed(codePoint: Int): Snapshot {
        val committed = if (composing) whileComposing(EngineEvent.ENTER, 0) else null
        if (!english || codePoint !in 0..Char.MAX_VALUE.code) {
            // the word committed goes in all the same, the panel the key leaves
            val passed = passOn()
            return committed?.let { passed.copy(commit = it.commit, deleteBefore = it.deleteBefore) } ?: passed
        }
        session.passedOn()
        session.typed(if (codePoint == 0) "" else codePoint.toChar().toString())
        return committed ?: IDLE
    }

    // an arrow, Home or End as a move of the cursor in the input
    private fun step(keySym: Int) = when (keySym) {
        // the keypad's too, Num Lock off
        KEY_LEFT, KEY_KP_LEFT -> Action.Step(-1)
        KEY_RIGHT, KEY_KP_RIGHT -> Action.Step(1)
        KEY_HOME, KEY_KP_HOME -> Action.Step.START
        KEY_END, KEY_KP_END -> Action.Step.END
        else -> null
    }

    // [codePoints] into [text] in UTF-16 units, no further than its end: the host's tap counts code points (PreeditUi.preeditAt)
    private fun utf16Offset(text: String, codePoints: Int) =
        text.offsetByCodePoints(0, codePoints.coerceIn(0, text.codePointCount(0, text.length)))

    private fun refine(): Snapshot {
        val last = again(handled = true)
        if (!last.refines) return last.copy(handled = false)
        val next = session.apply(Action.Refine)
        return if (next.copy(refines = false) == last.copy(refines = false)) next.copy(handled = false) else next
    }

    // the last snapshot shown again, what it committed and deleted done: androidengine deletes
    // before the cursor for any snapshot it shows, handled or not
    private fun again(handled: Boolean) = shown.copy(commit = "", deleteBefore = 0, handled = handled)

    /**
     * The key is the app's; an offer of what may follow goes away with it, and so does what English
     * knows of the text before the cursor (an Enter or an arrow: the space it added is not there).
     */
    private fun passOn(): Snapshot {
        session.passedOn()
        return if (shown.predicting || english) session.apply(Action.Reset).copy(handled = false) else IDLE
    }

    companion object {
        private const val DIGITS = 10
        private const val ALPHABET = 26
        private const val REPLACEMENT = '\uFFFD'

        // X keysyms, as androidengine passes a key that is no character (EngineEvent.OTHER)
        private const val KEY_HOME = 0xff50
        private const val KEY_LEFT = 0xff51
        private const val KEY_RIGHT = 0xff53
        private const val KEY_END = 0xff57
        private const val KEY_KP_HOME = 0xff95
        private const val KEY_KP_LEFT = 0xff96
        private const val KEY_KP_RIGHT = 0xff98
        private const val KEY_KP_END = 0xff9c

        /**
         * Characters of the private use area the nine-key keyboard sends for a syllable it offers
         * (see [Snapshot.syllables]): [syllableKey]. fcitx passes any character on, so this needs
         * no event of its own; no keyboard types one otherwise. U+F8C0 on (the area's last 64) is
         * not theirs: it is the grouped keyboards' codes (NineKeyLetters.CODES).
         */
        val SYLLABLES = '\uE000' until '\uE000' + Action.Syllable.IDS * Action.Syllable.MAX

        /** The character for the syllable at [index] of the list [id] ([Snapshot.syllablesId]). */
        fun syllableKey(index: Int, id: Int): Char {
            require(index in 0 until Action.Syllable.MAX && id in 0 until Action.Syllable.IDS) { "syllable $index of $id" }
            return SYLLABLES.first + id * Action.Syllable.MAX + index
        }

        /**
         * Characters of the supplementary private use area the nine-key keyboard sends for a
         * letter typed as itself ([Action.Letter]): [letterKey]. Past the BMP, which [SYLLABLES]
         * fill.
         */
        val LETTERS = 0xF0000 until 0xF0000 + 2 * ALPHABET

        /** The character for [letter] typed as itself, [again] a key pressed again soon: see [Action.Letter]. */
        fun letterKey(letter: Char, again: Boolean): Int {
            require(letter in 'a'..'z') { "letter $letter" }
            return LETTERS.first + (if (again) ALPHABET else 0) + (letter - 'a')
        }

        /**
         * The character the handwriting pad sends to have what it read shown ([Action.Ink]): of the
         * supplementary private use area, past [LETTERS]. No keyboard types it otherwise.
         */
        const val INK = 0xF0100

        /** The character the keyboard sends after a glide, to have its word committed ([Action.Glide]); as [INK]. */
        const val GLIDE = 0xF0101

        /**
         * The character the host sends after a [context] the session offers candidates for (English's
         * word typed, a tap after its correction): those shown, as no key asked for them; as [INK].
         */
        const val RECALL = 0xF0102

        /**
         * The character the app sends after what was learned changed under the input (a long press
         * taken back, a clear: LearningOp), to have the candidates read anew and shown
         * ([Session.reread]); as [INK].
         */
        const val AGAIN = 0xF0103

        /**
         * Characters of the supplementary private use area the nine keys send for English
         * ([Action.NineKey]): [nineKey]. Past [LETTERS], short of [INK]; a digit typed otherwise
         * (a hardware key, the number pad) stays a digit.
         */
        val NINE_DIGITS = 0xF0040 until 0xF0040 + 2 * DIGITS

        /** The character for the nine keys' [digit] typed for English, [upper] with Shift on. */
        fun nineKey(digit: Char, upper: Boolean): Int {
            require(digit in '2'..'9') { "nine key $digit" }
            return NINE_DIGITS.first + (if (upper) DIGITS else 0) + (digit - '0')
        }

        /**
         * Characters for a letter a nine key's long press pins into the word English spells
         * ([Action.NineKey] with the letter): [nineLetter]. After [NINE_DIGITS], short of [INK].
         */
        val NINE_LETTERS = 0xF0060 until 0xF0060 + 2 * ALPHABET

        /** The character for [letter] (a to z, either case) pinned into the word on the nine keys. */
        fun nineLetter(letter: Char): Int = when (letter) {
            in 'a'..'z' -> NINE_LETTERS.first + (letter - 'a')
            in 'A'..'Z' -> NINE_LETTERS.first + ALPHABET + (letter - 'A')
            else -> throw IllegalArgumentException("nine key letter $letter")
        }

        // the 14 keys' codes' block (NineKeyLetters.G14_BLOCK)
        private val FIRST_CODE = NineKeyLetters.G14_BLOCK.first.toChar()
        private val LAST_CODE = NineKeyLetters.G14_BLOCK.last.toChar()

        /** How many places across a key [tapKey] tells apart: the middle one, 8, is the key's centre. */
        const val TAP_STEPS = 17

        /**
         * Characters of the supplementary private use area a grouped keyboard sends for a tap on a
         * key of two letters, a code and where across the key it fell: [tapKey]. After [AGAIN],
         * in the codes' alphabetical order: the 14 keys' alone (26 × [TAP_STEPS]); G18's would take
         * 26 to 51, not in this range yet. Only on a key's way to the engine: never
         * learned, kept or backed up, so its numbering may change.
         */
        val TAPS = 0xF0200 until 0xF0200 + ALPHABET * TAP_STEPS

        /**
         * The character for a tap at [at] across the key of [code] (0 its left edge, 1 its right),
         * to the nearest of [TAP_STEPS]. Clamped first: just past 1 would round into the next code's
         * places, learned under that key. NaN is no place; send the code itself.
         */
        fun tapKey(code: Char, at: Float): Int {
            require(code in FIRST_CODE..LAST_CODE && !at.isNaN()) { "tap $code at $at" }
            return TAPS.first + (code - FIRST_CODE) * TAP_STEPS + (at.coerceIn(0f, 1f) * (TAP_STEPS - 1)).roundToInt()
        }

        // a [tapKey]'s code and place back
        private fun untapped(codePoint: Int): Pair<Char, Float> {
            val n = codePoint - TAPS.first
            return (FIRST_CODE + n / TAP_STEPS) to (n % TAP_STEPS).toFloat() / (TAP_STEPS - 1)
        }

        /**
         * The letter [codePoint] stands for typed as it is, for a temporary mode (quick phrase,
         * Unicode) that reads letters and none of a keyboard's codes: a [tapKey] its half's letter
         * (the first at the centre), a grouped keyboard's code (the 14 keys', the 18 keys', the nine
         * keys' 双拼's: [NineKeyLetters.ofCode]) its key's first letter ([NineKeyLetters.asTyped]);
         * null for any other.
         */
        fun typedLetter(codePoint: Int): Char? {
            // a code alone has no side: its key's first letter, whichever keyboard's it is
            if (codePoint !in TAPS) return NineKeyLetters.ofCode(codePoint)?.letters(codePoint.toChar())?.first()
            val (code, at) = untapped(codePoint)
            // the taps are the 14 keys' alone yet: G18's go at indices 26 to 51 (design §1.4)
            val group = NineKeyLetters.G14.letters(code).ifEmpty { return null }
            // past the middle goes against the first letter
            return if (group.length > 1 && NineKeyLetters.G14.against(0, at) > 0f) group[1] else group[0]
        }

        private val IDLE = Snapshot(
            commit = "",
            preedit = "",
            candidates = emptyList(),
            page = 0,
            hasPreviousPage = false,
            hasNextPage = false,
            handled = false,
            predicting = false,
        )
    }
}
