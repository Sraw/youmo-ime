/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession.Companion.NO_PIECE
import org.fcitx.fcitx5.android.engine.handwriting.InkOver
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import kotlin.math.roundToInt

/** The androidengine addon's events, numbered as androidengine_public.h. */
object EngineEvent {
    /** Argument: the code point. */
    const val CHAR = 0
    const val BACKSPACE = 1
    const val ENTER = 2
    const val ESCAPE = 3
    const val PAGE_UP = 4
    const val PAGE_DOWN = 5

    /** Argument: index among all candidates. */
    const val PICK = 6

    /** Cursor moved or input method left: input ends ([InkOver.endInput]); with [RESET_DROP], what is shown is dropped (清除). */
    const val RESET = 7

    /** [RESET]'s argument: drop what is shown, handwriting's too. */
    const val RESET_DROP = 1

    /** Argument: fcitx keysym. */
    const val OTHER = 8

    /** Argument: index among all candidates. */
    const val FORGET = 9

    /** The user pauses and the last snapshot [refines][Snapshot.refines]; [handled][Snapshot.handled] only if it changed. */
    const val REFINE = 10

    /** Argument: index among all candidates. */
    const val PIN = 11

    /** Argument: index among all candidates. */
    const val UNPIN = 12

    /** Argument: index among all candidates. */
    const val BLOCK = 13

    /** Argument: the character the cursor goes before, in code points. */
    const val CURSOR = 14

    /** A screen key the app types itself where English suggests nothing; argument the char ([Session.ERASED]'s for Backspace) or 0: the offer is put away. */
    const val TYPED = 15

    /** A keypad character (KP_2): argument the code point; never a session key (on nine keys 2 is abc, KP_2 is 2). */
    const val KEYPAD = 16

    /** A hardware non-letter sent to 双拼 alone (KeyRouter): a key the scheme may type (微软's ;), where the screen's same char marks grouped typing. Taken as [CHAR]. */
    const val HARD_CHAR = 17

    /** The bar's ×: 联想 put away ([Action.DropPredictions]), the commit still the context; Escape would reset the session. */
    const val DROP_PREDICTIONS = 18
}

/**
 * A keyboard's events as a [session]'s actions. While composing, space picks the first candidate, a digit the
 * numbered one, Enter keeps the input as typed, Escape drops it; other characters end the input then go to the
 * app (`nihao,` gives 你好，), and non-character keys are swallowed except cursor moves within the input. While
 * predicting, unread keys go to the app and the offer goes away. [nineKeys]: 重输 is Escape and syllables come
 * as [SYLLABLES] (the host says so, since a digit-coded table also reads 2).
 */
class Keyboard(private val session: Session, private val nineKeys: Boolean) {

    private var shown: Snapshot = IDLE

    val showing: Snapshot get() = shown

    /** The shown page's start among all candidates ([Snapshot.first]): paged indexes count from it. */
    val first get() = shown.first

    private val english get() = session.takesEveryCharacter

    val composing get() = shown.preedit.isNotEmpty() && !shown.predicting

    /** [payload]: what the host's key carries ([INK], [GLIDE]). */
    fun onEvent(event: Int, arg: Int, learning: Boolean = true, payload: Action? = null): Snapshot {
        session.learning = learning
        shown = when (event) {
            EngineEvent.CHAR -> char(arg, payload)
            EngineEvent.KEYPAD -> keypad(arg)
            EngineEvent.BACKSPACE -> session.apply(Action.Backspace)
            EngineEvent.ENTER, EngineEvent.PAGE_UP, EngineEvent.PAGE_DOWN, EngineEvent.CURSOR, EngineEvent.OTHER -> whileComposing(event, arg)
            // the nine keys' 重输 is Escape: with nothing typed it is not the app's
            EngineEvent.ESCAPE -> if (composing || shown.predicting) session.apply(Action.Reset) else if (nineKeys) IDLE.copy(handled = true) else passOn()
            EngineEvent.PICK -> session.apply(Action.Pick(arg))
            EngineEvent.FORGET, EngineEvent.PIN, EngineEvent.UNPIN, EngineEvent.BLOCK -> session.apply(pressed(event, arg))
            EngineEvent.RESET -> reset(arg).copy(handled = false)
            EngineEvent.REFINE -> refine()
            EngineEvent.TYPED -> typed(arg)
            EngineEvent.DROP_PREDICTIONS -> if (shown.predicting) session.apply(Action.DropPredictions) else again(handled = false)
            else -> passOn()
        }
        return shown
    }

    /** For a keyboard made after the field's context: [Session.fieldFlags]. */
    fun fieldFlags(flags: Int) = session.fieldFlags(flags)

    /**
     * [before] the cursor, moved where the engine did not: what is typed next follows it. Nothing shown is dropped
     * (the service resets fcitx first). Comes before any key in a newly focused field. Returns whether the session
     * offers candidates now (English's word in place of its correction), which a [RECALL] shows.
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

    private fun pressed(event: Int, index: Int) = when (event) {
        EngineEvent.FORGET -> Action.Forget(index)
        EngineEvent.PIN -> Action.Pin(index)
        EngineEvent.BLOCK -> Action.Block(index)
        else -> Action.Unpin(index)
    }

    // the host's own characters, null for others; a bare one (a replay's) shows what is held
    private fun hostChar(codePoint: Int, payload: Action?): Snapshot? = when (codePoint) {
        INK -> session.apply(payload as? Action.Ink ?: Action.Ink(NO_PIECE))
        GLIDE -> session.apply(payload as? Action.Glide ?: Action.Glide(null))
        RECALL -> again(handled = true)
        AGAIN -> session.reread() ?: again(handled = true)
        // always handled: let through, the app would type its private-use character
        in STROKES -> session.apply(Action.Stroke(Strokes.KEYS[codePoint - STROKES.first])).copy(handled = true)
        LOOKUP -> session.apply(Action.LookUp)
        else -> null
    }

    // grouped keyboards' codes are their sessions' keys; an unread digit (0, a letterless key) ends the input and
    // goes in after it: the host swallows a code handed back
    private fun keyed(codePoint: Int, k: Char, payload: Action?): Snapshot {
        val out = char(k.code, payload)
        return if (!out.handled && codePoint in PINYIN_NINE) out.copy(commit = out.commit + k, handled = true) else out
    }

    private fun char(codePoint: Int, payload: Action? = null): Snapshot {
        hostChar(codePoint, payload)?.let { return it }
        // read as its code whatever the session, never U+FFFD ending the input; the tap and neighbours from the key's payload
        val touch = (payload as? Action.Key)?.touch
        if (isTap(codePoint)) {
            val (code, at) = untapped(codePoint)
            return session.apply(Action.Key(code, at = at, touch = touch))
        }
        keyedChar(codePoint)?.let { return keyed(codePoint, it, payload) }
        if (codePoint in NINE_DIGITS) {
            val n = codePoint - NINE_DIGITS.first
            return session.apply(Action.NineKey('0' + n % DIGITS, upper = n >= DIGITS, touch = touch))
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
            // a bar pick: resent, its list id is gone, so never a key to type again
            return session.apply(Action.Syllable(n % Action.Syllable.MAX, n / Action.Syllable.MAX)).copy(picked = true)
        }
        if (session.reads(c) || english) return session.apply(Action.Key(c, touch = touch))
        // on the nine keys a digit is a key: an unread one (0) ends the input like punctuation
        val digit = c.digitToIntOrNull()?.takeUnless { nineKeys }
        return when {
            composing && c == ' ' -> select(0)
            // 1 is the first; 0 the tenth
            composing && digit != null -> select((digit + DIGITS - 1) % DIGITS)
            else -> ended(c)
        }
    }

    // ending input keeps what is written on the keys (InkOver, outermost); a drop does not
    private fun reset(arg: Int) = (session as? InkOver)?.takeIf { arg != EngineEvent.RESET_DROP }?.endInput() ?: session.apply(Action.Reset)

    // told as a pick ([Snapshot.picked]), never a key to type again (KeyRouter's log)
    private fun select(index: Int) = session.apply(Action.Select(index)).copy(picked = true)

    // the app types the key after the session's commit; but if something is still composing (strokes picking part
    // of the pinyin) the session took it, as typing it would land before that
    private fun ended(c: Char) = session.apply(Action.Key(c)).let { if (it.preedit.isEmpty()) it.copy(handled = false) else it }

    // a keypad digit is never a session key: picks the numbered candidate while composing, else the app's; English
    // reads it as any digit unless its keys are the nine keys' (Session.picksByDigit); other keypad chars as the keyboard's
    private fun keypad(codePoint: Int): Snapshot {
        val digit = codePoint - '0'.code
        val picks = !english || composing && session.picksByDigit
        if (!picks || digit !in 0 until DIGITS) return char(codePoint)
        return if (composing) select((digit + DIGITS - 1) % DIGITS) else passOn()
    }

    // keys the session takes while composing, the app's otherwise
    private fun whileComposing(event: Int, arg: Int): Snapshot = when {
        // an arrow or Tab: English commits its word and the key moves on; other sessions move their cursor by
        // arrows, Home, End, and keep any other key
        composing && event == EngineEvent.OTHER -> when {
            english -> session.apply(Action.CommitRaw)
            else -> step(arg)?.let { session.apply(it).takeIf(Snapshot::handled) } ?: again(handled = true)
        }
        composing -> session.apply(
            when (event) {
                EngineEvent.ENTER -> Action.CommitRaw
                EngineEvent.PAGE_UP -> Action.PreviousPage
                EngineEvent.PAGE_DOWN -> Action.NextPage
                // the session counts UTF-16 units: a picked piece may be past the BMP
                EngineEvent.CURSOR -> Action.Cursor(utf16Offset(shown.preedit, arg))
                else -> error("event $event is not one taken while composing")
            },
        )
        // a tap in the app's text: the panel as it was
        event == EngineEvent.CURSOR -> again(handled = false).also { session.passedOn() }
        else -> passOn()
    }

    // what the app typed itself where English suggests nothing, so a later glide knows whether to add a space.
    // Past the BMP (an emoji) it is passed on as unknown; a word being typed is committed as by Enter, not dropped
    private fun typed(codePoint: Int): Snapshot {
        val committed = if (composing) whileComposing(EngineEvent.ENTER, 0) else null
        if (!english || codePoint !in 0..Char.MAX_VALUE.code) {
            // the committed word goes in all the same
            val passed = passOn()
            return committed?.let { passed.copy(commit = it.commit, deleteBefore = it.deleteBefore) } ?: passed
        }
        session.passedOn()
        session.typed(if (codePoint == 0) "" else codePoint.toChar().toString())
        return committed ?: IDLE
    }

    private fun step(keySym: Int) = when (keySym) {
        // the keypad's too, Num Lock off
        KEY_LEFT, KEY_KP_LEFT -> Action.Step(-1)
        KEY_RIGHT, KEY_KP_RIGHT -> Action.Step(1)
        KEY_HOME, KEY_KP_HOME -> Action.Step.START
        KEY_END, KEY_KP_END -> Action.Step.END
        else -> null
    }

    // [codePoints] into [text] in UTF-16 units, capped at its end: the host's tap counts code points (PreeditUi.preeditAt)
    private fun utf16Offset(text: String, codePoints: Int) =
        text.offsetByCodePoints(0, codePoints.coerceIn(0, text.codePointCount(0, text.length)))

    private fun refine(): Snapshot {
        val last = again(handled = true)
        if (!last.refines) return last.copy(handled = false)
        val next = session.apply(Action.Refine)
        return if (next.copy(refines = false) == last.copy(refines = false)) next.copy(handled = false) else next
    }

    // the last snapshot again with its commit and deletion done: androidengine deletes before the cursor for any
    // snapshot it shows, handled or not
    private fun again(handled: Boolean) = shown.copy(commit = "", deleteBefore = 0, handled = handled)

    /** The key is the app's; a prediction offer goes away, and so does English's knowledge of the text before (Enter or an arrow: its added space is not there). */
    private fun passOn(): Snapshot {
        session.passedOn()
        return if (shown.predicting || english) session.apply(Action.Reset).copy(handled = false) else IDLE
    }

    companion object {
        private const val DIGITS = 10
        private const val ALPHABET = 26
        private const val REPLACEMENT = '\uFFFD'

        // X keysyms, as androidengine passes non-character keys (EngineEvent.OTHER)
        private const val KEY_HOME = 0xff50
        private const val KEY_LEFT = 0xff51
        private const val KEY_RIGHT = 0xff53
        private const val KEY_END = 0xff57
        private const val KEY_KP_HOME = 0xff95
        private const val KEY_KP_LEFT = 0xff96
        private const val KEY_KP_RIGHT = 0xff98
        private const val KEY_KP_END = 0xff9c

        /** Private-use characters the nine keys send for an offered syllable ([Snapshot.syllables]; [syllableKey]); fcitx passes any character on, so no event is needed. U+F8C0 on are the grouped keyboards' codes. */
        val SYLLABLES = '\uE000' until '\uE000' + Action.Syllable.IDS * Action.Syllable.MAX

        /** For index [index] of list [id] ([Snapshot.syllablesId]). */
        fun syllableKey(index: Int, id: Int): Char {
            require(index in 0 until Action.Syllable.MAX && id in 0 until Action.Syllable.IDS) { "syllable $index of $id" }
            return SYLLABLES.first + id * Action.Syllable.MAX + index
        }

        /** Supplementary private-use characters the nine keys send for a letter typed as itself ([Action.Letter]; [letterKey]). */
        val LETTERS = 0xF0000 until 0xF0000 + 2 * ALPHABET

        /** [again]: the key pressed again soon ([Action.Letter]). */
        fun letterKey(letter: Char, again: Boolean): Int {
            require(letter in 'a'..'z') { "letter $letter" }
            return LETTERS.first + (if (again) ALPHABET else 0) + (letter - 'a')
        }

        /** Sent by the handwriting pad carrying what it read ([Action.Ink]); no keyboard types it otherwise. */
        const val INK = 0xF0100

        /** Sent for a glide carrying it, to commit its word ([Action.Glide]); as [INK]. */
        const val GLIDE = 0xF0101

        /** Sent after a [context] the session offers candidates for (English's correction tapped), to show them; as [INK]. */
        const val RECALL = 0xF0102

        /** Sent after learning changed under the input (a long press undone, a clear: LearningOp), to reread and show candidates ([Session.reread]); as [INK]. */
        const val AGAIN = 0xF0103

        /** A table's 拼 key ([Action.LookUp]); as [INK]. */
        const val LOOKUP = 0xF0104

        /** One per [Strokes.KEYS] for a stroke drawn while composing (笔画筛选, [Action.Stroke]; [strokeKey]); as [INK], never text. */
        val STROKES = 0xF0110 until 0xF0110 + Strokes.KEYS.length

        fun strokeKey(stroke: Char): Int {
            val i = Strokes.KEYS.indexOf(stroke)
            require(i >= 0) { "stroke $stroke" }
            return STROKES.first + i
        }

        /** The nine keys' characters for English ([Action.NineKey]; [nineKey]); a digit typed otherwise (hardware, number pad) stays a digit. */
        val NINE_DIGITS = 0xF0040 until 0xF0040 + 2 * DIGITS

        /** [upper] with Shift on. */
        fun nineKey(digit: Char, upper: Boolean): Int {
            require(digit in '2'..'9') { "nine key $digit" }
            return NINE_DIGITS.first + (if (upper) DIGITS else 0) + (digit - '0')
        }

        /** A letter a nine key's long press pins into English's word ([Action.NineKey]; [nineLetter]). */
        val NINE_LETTERS = 0xF0060 until 0xF0060 + 2 * ALPHABET

        /** [letter]: a to z, either case. */
        fun nineLetter(letter: Char): Int = when (letter) {
            in 'a'..'z' -> NINE_LETTERS.first + (letter - 'a')
            in 'A'..'Z' -> NINE_LETTERS.first + ALPHABET + (letter - 'A')
            else -> throw IllegalArgumentException("nine key letter $letter")
        }

        /** Pinyin's nine keys' digit codes ([pinyinNineKey]), read as digits by their session ([keyedChar]): codes, so a real digit stays a hardware or number-pad candidate pick. */
        val PINYIN_NINE = 0xF0400 until 0xF0400 + DIGITS

        /** [digit] 0 to 9 (0 and letterless keys sent only while typing). */
        fun pinyinNineKey(digit: Char): Int {
            require(digit in '0'..'9') { "nine key $digit" }
            return PINYIN_NINE.first + (digit - '0')
        }

        /** Pinyin's 18 keys ([NineKeyLetters.G18]; [pinyin18Key], taps [pinyin18Tap]), separate from 双拼's codes for the same keys; read as G18 codes ([keyedChar]). */
        val PINYIN_18 = 0xF0410 until 0xF0410 + ALPHABET

        fun pinyin18Key(code: Char): Int = PINYIN_18.first + groupIndex(NineKeyLetters.G18_BLOCK, code)

        /** 双拼's 14 keys ([NineKeyLetters.G14]; [shuangpin14Key]), separate from pinyin's; read as G14 codes ([keyedChar]). */
        val SHUANGPIN_14 = 0xF0430 until 0xF0430 + ALPHABET

        fun shuangpin14Key(code: Char): Int = SHUANGPIN_14.first + groupIndex(NineKeyLetters.G14_BLOCK, code)

        /** 注音九键's keys: groups ([zhuyinNineKey]), the tone key ([zhuyinNineTone], 0 bare), symbols from long press or 连按 ([zhuyinNineSymbol]); codes, so a ㄅ from the symbols page stays text. */
        val ZHUYIN_NINE = 0xF0450 until 0xF0450 + ZhuyinKeys.GROUPS + 1 + ZhuyinKeys.TONES + ZhuyinKeys.SYMBOLS.length

        /** [group] 0 to 10 in key order ([ZhuyinKeys.NINE]). */
        fun zhuyinNineKey(group: Int): Int {
            require(group in 0 until ZhuyinKeys.GROUPS) { "zhuyin group $group" }
            return ZHUYIN_NINE.first + group
        }

        /** [tone] 0 bare, 1 to 5 (5 轻声). */
        fun zhuyinNineTone(tone: Int): Int {
            require(tone in 0..ZhuyinKeys.TONES) { "tone $tone" }
            return ZHUYIN_NINE.first + ZhuyinKeys.GROUPS + tone
        }

        fun zhuyinNineSymbol(symbol: Char): Int = ZHUYIN_NINE.first + ZhuyinKeys.GROUPS + 1 + ZhuyinKeys.TONES + ZhuyinKeys.indexOf(symbol)

        /** 大千's keys: a symbol each ([zhuyinKey]) and the tones ([zhuyinTone]). */
        val ZHUYIN_FULL = 0xF0490 until 0xF0490 + ZhuyinKeys.SYMBOLS.length + ZhuyinKeys.TONES

        fun zhuyinKey(symbol: Char): Int = ZHUYIN_FULL.first + ZhuyinKeys.indexOf(symbol)

        /** [tone] 1 to 5 (5 轻声). */
        fun zhuyinTone(tone: Int): Int {
            require(tone in 1..ZhuyinKeys.TONES) { "tone $tone" }
            return ZHUYIN_FULL.first + ZhuyinKeys.SYMBOLS.length + tone - 1
        }

        /** All keyed keyboards' codes past the BMP. */
        val KEYED_CODES = PINYIN_NINE.first..ZHUYIN_FULL.last

        private fun groupIndex(block: IntRange, code: Char): Int {
            require(code.code in block) { "code ${code.code} of no block $block" }
            return code.code - block.first
        }

        /** The session-input key a [KEYED_CODES] code stands for; null for any other. */
        fun keyedChar(codePoint: Int): Char? = when (codePoint) {
            in PINYIN_NINE -> '0' + (codePoint - PINYIN_NINE.first)
            in PINYIN_18 -> (NineKeyLetters.G18_BLOCK.first + (codePoint - PINYIN_18.first)).toChar()
            in SHUANGPIN_14 -> (NineKeyLetters.G14_BLOCK.first + (codePoint - SHUANGPIN_14.first)).toChar()
            in ZHUYIN_NINE -> ZhuyinKeys.nineChar(codePoint - ZHUYIN_NINE.first)
            in ZHUYIN_FULL -> ZhuyinKeys.fullChar(codePoint - ZHUYIN_FULL.first)
            else -> null
        }

        private val FIRST_CODE = NineKeyLetters.G14_BLOCK.first.toChar()
        private val LAST_CODE = NineKeyLetters.G14_BLOCK.last.toChar()

        /** Places across a key [tapKey] distinguishes; the middle one, 8, is the centre. */
        const val TAP_STEPS = 17

        /** A grouped keyboard's tap on a two-letter key, code plus place across ([tapKey]): the 14 keys' (26 × [TAP_STEPS]); pinyin's 18 keys use [TAPS_18]. Never learned or stored, so the numbering may change. */
        val TAPS = 0xF0200 until 0xF0200 + ALPHABET * TAP_STEPS

        /** As [TAPS] for pinyin's 18 keys ([pinyin18Tap]): a separate block so 双拼's 18 keys, same codes, never send one. */
        val TAPS_18 = 0xF0500 until 0xF0500 + ALPHABET * TAP_STEPS

        fun pinyin18Tap(code: Char, at: Float): Int {
            require(!at.isNaN()) { "tap $code at $at" }
            return TAPS_18.first + groupIndex(NineKeyLetters.G18_BLOCK, code) * TAP_STEPS + (at.coerceIn(0f, 1f) * (TAP_STEPS - 1)).roundToInt()
        }

        fun isTap(codePoint: Int): Boolean = codePoint in TAPS || codePoint in TAPS_18 || codePoint in PLACES

        /** Places down a key [tapKey] with a y distinguishes; the middle one, 4, is the centre. */
        const val TAP_ROWS = NineKeyLetters.PLACE_ROWS

        private val FIRST_CUSTOM = NineKeyLetters.CUSTOM_BLOCK.first.toChar()
        private val LAST_CUSTOM = NineKeyLetters.CUSTOM_BLOCK.last.toChar()

        /** A layout's own keyboard's taps, code plus place across and down ([tapKey] with a y); as [TAPS], never learned or stored. */
        val PLACES = 0xF1000 until 0xF1000 + NineKeyLetters.CUSTOM_MAX * TAP_STEPS * TAP_ROWS

        /** Whether taps on [code] carry a place: the 14 keys' and a layout's own codes. */
        fun placesTaps(code: Char): Boolean = code in FIRST_CODE..LAST_CODE || code in FIRST_CUSTOM..LAST_CUSTOM

        /** A tap at ([x], [y]) on [code]'s key (0–1, left to right, top to bottom) to the nearest place: [PLACES] for a layout's own, across alone for the 14 keys. Clamped first; NaN means no place, send the code. */
        fun tapKey(code: Char, x: Float, y: Float): Int {
            require((code in FIRST_CUSTOM..LAST_CUSTOM && !x.isNaN() && !y.isNaN()) || code in FIRST_CODE..LAST_CODE) { "tap $code at $x, $y" }
            if (code in FIRST_CODE..LAST_CODE) return tapKey(code, x)
            val across = (x.coerceIn(0f, 1f) * (TAP_STEPS - 1)).roundToInt()
            val down = (y.coerceIn(0f, 1f) * (TAP_ROWS - 1)).roundToInt()
            return PLACES.first + ((code - FIRST_CUSTOM) * TAP_STEPS + across) * TAP_ROWS + down
        }

        /** Null for any other. */
        fun tappedCode(codePoint: Int): Char? = if (isTap(codePoint)) untapped(codePoint).first else null

        /** A tap at [at] across [code]'s key, to the nearest of [TAP_STEPS]; clamped first, since just past 1 would round into the next code's places. NaN: send the code. A layout's own code: its middle row. */
        fun tapKey(code: Char, at: Float): Int {
            if (code in FIRST_CUSTOM..LAST_CUSTOM) return tapKey(code, at, 0.5f)
            require(code in FIRST_CODE..LAST_CODE && !at.isNaN()) { "tap $code at $at" }
            return TAPS.first + (code - FIRST_CODE) * TAP_STEPS + (at.coerceIn(0f, 1f) * (TAP_STEPS - 1)).roundToInt()
        }

        // a [tapKey]'s code and place: across alone (TAPS, TAPS_18), or across and down as one (NineKeyLetters.place)
        internal fun untapped(codePoint: Int): Pair<Char, Float> {
            if (codePoint in TAPS_18) {
                val n = codePoint - TAPS_18.first
                return (NineKeyLetters.G18_BLOCK.first + n / TAP_STEPS).toChar() to (n % TAP_STEPS).toFloat() / (TAP_STEPS - 1)
            }
            if (codePoint in PLACES) {
                val n = codePoint - PLACES.first
                val across = (n / TAP_ROWS) % TAP_STEPS
                return (FIRST_CUSTOM + n / (TAP_ROWS * TAP_STEPS)) to
                    NineKeyLetters.place(across.toFloat() / (TAP_STEPS - 1), (n % TAP_ROWS).toFloat() / (TAP_ROWS - 1))
            }
            val n = codePoint - TAPS.first
            return (FIRST_CODE + n / TAP_STEPS) to (n % TAP_STEPS).toFloat() / (TAP_STEPS - 1)
        }

        /** The letter [codePoint] types for a temporary mode (quick phrase, Unicode) that reads only letters: a tap its half's letter, a grouped code its key's first letter, pinyin's nine-key code its digit; null for others. */
        fun typedLetter(codePoint: Int): Char? {
            if (codePoint in PINYIN_NINE) return keyedChar(codePoint)
            val internal = keyedChar(codePoint)?.code ?: codePoint
            // a code alone has no side: its key's first letter
            if (internal in PLACES) {
                val (code, place) = untapped(internal)
                val letters = NineKeyLetters.ofCode(code.code) ?: return null
                val group = letters.letters(code)
                // the letter the tap went least against, the first of ties
                return group[group.indices.minBy { letters.against(code, it, place) }]
            }
            if (internal !in TAPS && internal !in TAPS_18) return NineKeyLetters.ofCode(internal)?.letters(internal.toChar())?.first()
            val (code, at) = untapped(codePoint)
            val letters = if (internal in TAPS_18) NineKeyLetters.G18 else NineKeyLetters.G14
            val group = letters.letters(code).ifEmpty { return null }
            // the place runs along the group's letters as sent (a stacking key sends where down it fell), so these halves
            // are its letters' own; past the middle goes against the first letter
            return if (letters.weighs(code) && letters.against(0, at) > 0f) group[1] else group[0]
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
