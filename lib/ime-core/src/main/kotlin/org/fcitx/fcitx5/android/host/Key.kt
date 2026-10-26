/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters

/**
 * fcitx's modifier bits (`fcitx-utils/keysym.h`), as plain ints: the app's `KeyState` enum has the
 * same values but lives with Android's `KeyEvent`.
 */
@Suppress("MagicNumber")
object KeyStates {
    const val SHIFT = 1
    const val CAPS_LOCK = 1 shl 1
    const val CTRL = 1 shl 2
    const val ALT = 1 shl 3
    const val NUM_LOCK = 1 shl 4
    const val MOD3 = 1 shl 5

    /** fcitx's `KeyState::Hyper`, the same bit as Mod3 (`keysym.h:35-36`). */
    const val HYPER = MOD3
    const val SUPER = 1 shl 6
    const val SUPER2 = 1 shl 26
    const val HYPER2 = 1 shl 27
    const val META = 1 shl 28
    const val VIRTUAL = 1 shl 29
    const val CTRL_ALT_SHIFT = CTRL or ALT or SHIFT

    /** `KeyState::SimpleMask`: what [Key.hasModifier] looks at. */
    const val SIMPLE_MASK = CTRL_ALT_SHIFT or SUPER or SUPER2 or MOD3 or META
}

/** The keysyms the host's own logic names (`fcitx-utils/keysymgen.h`). */
@Suppress("MagicNumber")
object KeySyms {
    const val SPACE = 0x0020
    const val ASCII_TILDE = 0x007e
    const val DIGIT_0 = 0x0030
    const val DIGIT_9 = 0x0039
    const val UPPER_A = 0x0041
    const val UPPER_Z = 0x005a
    const val LOWER_A = 0x0061
    const val LOWER_Z = 0x007a
    const val ISO_LEFT_TAB = 0xfe20
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val CLEAR = 0xff0b
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val KP_SPACE = 0xff80
    const val KP_TAB = 0xff89
    const val KP_ENTER = 0xff8d
    const val KP_MULTIPLY = 0xffaa
    const val KP_0 = 0xffb0
    const val KP_9 = 0xffb9
    const val KP_EQUAL = 0xffbd
    const val DELETE = 0xffff
    const val MINUS = 0x002d
    const val EQUAL = 0x003d
    const val LEFT = 0xff51
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val KP_F1 = 0xff91
    const val KP_DELETE = 0xff9f

    /** Shift_L … Hyper_R but the locks (`keysymgen.h`): `Key::isModifier`'s. */
    val MODIFIERS = setOf(0xffe1, 0xffe2, 0xffe3, 0xffe4, 0xffe7, 0xffe8, 0xffe9, 0xffea, 0xffeb, 0xffec, 0xffed, 0xffee)

    /** fcitx's keysym for a code point not in the table: this plus the code point. */
    const val UNICODE_OFFSET = 0x01000000
    private const val UNICODE_LAST = 0x0110ffff

    /**
     * Whether [codePoint] is of a private use area: a keyboard's or the host's own character
     * (a 14-key tap, a syllable picked, Keyboard's INK), no text a temporary mode should type.
     */
    fun isPrivateUse(codePoint: Int): Boolean = Character.getType(codePoint) == Character.PRIVATE_USE.toInt()

    /**
     * `Key::keySymToUnicode` (`fcitx-utils/key.cpp:738-800`): the character a keysym types, 0 for
     * none. The order of the checks is fcitx's: Latin-1 is itself, KP_Space is a space, the editing
     * keys are their control characters, `0x01xxxxxx` is the code point if it is a valid one, the
     * rest is the table.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    fun toUnicode(sym: Int): Int {
        if (sym in 0x0020..0x007e || sym in 0x00a0..0x00ff) return sym
        if (sym == KP_SPACE) return SPACE
        if (sym in BACKSPACE..CLEAR || sym in KP_MULTIPLY..KP_9 || sym == RETURN || sym == ESCAPE ||
            sym == DELETE || sym == KP_TAB || sym == KP_ENTER || sym == KP_EQUAL
        ) {
            return sym and 0x7f
        }
        if (sym in 0x0100d800..0x0100dfff) return 0
        if (sym in UNICODE_OFFSET..UNICODE_LAST) return sym - UNICODE_OFFSET
        val pairs = KeySymUnicodeTable.PAIRS
        var min = 0
        var max = pairs.size / 2 - 1
        while (max >= min) {
            val mid = (min + max) / 2
            val at = pairs[mid * 2]
            when {
                at < sym -> min = mid + 1
                at > sym -> max = mid - 1
                else -> return pairs[mid * 2 + 1]
            }
        }
        return 0
    }

    /**
     * The table stable-sorted by code point (`keydata.cpp:12-21`) as `ucs, keysym` pairs, searched as
     * fcitx does (`key.cpp:719-731`, mid = (min + max) / 2): a code point with several keysyms gets the
     * one the search lands on, not the first (U+2500: 0x9f1, not 0x8a3).
     */
    private val byUnicode: IntArray by lazy {
        val pairs = KeySymUnicodeTable.PAIRS
        val order = (0 until pairs.size / 2).sortedBy { pairs[it * 2 + 1] } // stable
        IntArray(pairs.size).also { a ->
            order.forEachIndexed { i, p ->
                a[i * 2] = pairs[p * 2 + 1]
                a[i * 2 + 1] = pairs[p * 2]
            }
        }
    }

    private fun searchUnicode(code: Int): Int? {
        val tab = byUnicode
        var min = 0
        var max = tab.size / 2 - 1
        while (max >= min) {
            val mid = (min + max) / 2
            val at = tab[mid * 2]
            when {
                at < code -> min = mid + 1
                at > code -> max = mid - 1
                else -> return tab[mid * 2 + 1]
            }
        }
        return null
    }

    /**
     * `Key::keySymFromUnicode` (`key.cpp:689-736`): Latin-1 is itself; backspace, tab, linefeed,
     * clear, return and escape are their `0xff..` keys and DEL is Delete; surrogates, noncharacters
     * and anything past U+10FFFF have no keysym (0); else the table, else `0x01000000` + the code point.
     */
    @Suppress("ComplexCondition", "ReturnCount")
    fun fromUnicode(code: Int): Int {
        if (code in 0x0020..0x007e || code in 0x00a0..0x00ff) return code
        if (code in (BACKSPACE and 0x7f)..(CLEAR and 0x7f) || code == (RETURN and 0x7f) || code == (ESCAPE and 0x7f)) {
            return code or 0xff00
        }
        if (code == (DELETE and 0x7f)) return DELETE
        if (code in 0xd800..0xdfff || code in 0xfdd0..0xfdef || code !in 0..0x10ffff || (code and 0xfffe) == 0xfffe) {
            return 0
        }
        return searchUnicode(code) ?: (code or UNICODE_OFFSET)
    }

    /**
     * `Key::keySymFromString` (`key.cpp:622-658`): a name of `keynametable.h`, else a fcitx 4 name of
     * `keynametable-compat.h`, else a single character (ASCII as its byte, others by
     * [fromUnicode]); 0 (`FcitxKey_None`) otherwise.
     */
    @Suppress("ReturnCount")
    fun fromString(name: String): Int {
        val t = KeySymNameTable
        val i = t.names.binarySearch(name)
        if (i >= 0) return t.nameSyms[i]
        val j = t.compatNames.binarySearch(name)
        if (j >= 0) return t.compatSyms[j]
        if (name.isNotEmpty() && name.codePointCount(0, name.length) == 1) {
            val c = name.codePointAt(0)
            if (c in 0xd800..0xdfff) return 0   // a lone surrogate is not valid UTF-8 there
            return if (c < 0x80) c else fromUnicode(c)
        }
        return 0
    }

    /** `Key::keySymToString` (`key.cpp:660-687`), the portable form: the keysym's name, "" for none. */
    fun toString(sym: Int): String {
        val t = KeySymNameTable
        val i = t.valueSyms.binarySearch(sym)
        return if (i >= 0) t.valueNames[i] else ""
    }
}

/**
 * fcitx's `Key`: a keysym and modifier states. Only what the key pipeline's own logic needs is here;
 * names are [KeySyms.fromString] / [KeySyms.toString].
 */
data class Key(val sym: Int, val states: Int = 0) {

    /** `Key::isUAZ` (`key.cpp:446-448`). */
    val isUAZ get() = states == 0 && sym in KeySyms.UPPER_A..KeySyms.UPPER_Z

    /** `Key::isLAZ` (`key.cpp:450-452`). */
    val isLAZ get() = states == 0 && sym in KeySyms.LOWER_A..KeySyms.LOWER_Z

    /** `Key::isDigit` (`key.cpp:413-416`): `0`–`9` or a keypad digit, with no modifier. */
    val isDigit get() = states == 0 && (sym in KeySyms.DIGIT_0..KeySyms.DIGIT_9 || sym in KeySyms.KP_0..KeySyms.KP_9)

    /** `Key::isSimple` (`key.cpp:456-458`): printable ASCII with no modifier. */
    val isSimple get() = states == 0 && sym in KeySyms.SPACE..KeySyms.ASCII_TILDE

    /** `Key::isModifier` (`key.cpp:460-467`): a Shift, Ctrl, Meta, Alt, Super or Hyper key itself. */
    val isModifier get() = sym in KeySyms.MODIFIERS

    /** `Key::isKeyPad` (`key.cpp:482-487`). */
    val isKeyPad
        get() = sym in KeySyms.KP_MULTIPLY..KeySyms.KP_9 || sym in KeySyms.KP_F1..KeySyms.KP_DELETE ||
            sym == KeySyms.KP_SPACE || sym == KeySyms.KP_TAB || sym == KeySyms.KP_ENTER || sym == KeySyms.KP_EQUAL

    /** `Key::hasModifier` (`key.cpp:489`). */
    val hasModifier get() = states and KeyStates.SIMPLE_MASK != 0

    /**
     * `Key::normalize` (`fcitx-utils/key.cpp:493-533`), run on every key before any handler sees it
     * (`fcitx/event.cpp:36`):
     * - `ISO_Left_Tab` is `Tab` (`:496-498`);
     * - only Ctrl, Alt, Shift, Super, Mod3 and Super2 are kept, CapsLock and NumLock dropped (`:501-503`);
     * - Super2 becomes Super (`:504-507`);
     * - with any modifier but Shift alone, a lowercase letter becomes uppercase: Ctrl+a is Ctrl+A (`:509-511`);
     * - a letter with Shift alone loses the Shift and keeps its sym: Shift+a is `a` (`:517-520`);
     * - Shift with a printable key, or a keypad digit, loses the Shift, but not with space, Return or
     *   Tab (`:521-529`): Shift+1 as `1`'s sym is `1`, Shift+space stays.
     */
    @Suppress("ComplexCondition")
    fun normalize(): Key {
        var sym = if (sym == KeySyms.ISO_LEFT_TAB) KeySyms.TAB else sym
        var states = states and (KeyStates.CTRL_ALT_SHIFT or KeyStates.SUPER or KeyStates.MOD3 or KeyStates.SUPER2)
        if (states and KeyStates.SUPER2 != 0) {
            states = (states and KeyStates.SUPER2.inv()) or KeyStates.SUPER
        }
        if (states != 0) {
            if (states != KeyStates.SHIFT && Key(sym).isLAZ) {
                sym += KeySyms.UPPER_A - KeySyms.LOWER_A
            }
            if (Key(sym).isLAZ || Key(sym).isUAZ) {
                if (states == KeyStates.SHIFT) states = 0
            } else if (states and KeyStates.SHIFT != 0 &&
                (
                    (Key(sym).isSimple || KeySyms.toUnicode(sym) != 0) &&
                        sym != KeySyms.SPACE && sym != KeySyms.RETURN && sym != KeySyms.TAB ||
                        sym in KeySyms.KP_0..KeySyms.KP_9
                    )
            ) {
                states = states xor KeyStates.SHIFT
            }
        }
        return Key(sym, states)
    }
}

/** The characters keyboards and the host send to each other, never text for an editor. */
object KeyCodes {
    /** U+F8DA–F8F3: a 14-key grouped key as one character (NineKeyLetters.G14). */
    val GROUPED = NineKeyLetters.G14_BLOCK

    /** U+F8C0–F8D9: 双拼's 18 keys' (NineKeyLetters.G18), private use past Keyboard.SYLLABLES. */
    val G18 = NineKeyLetters.G18_BLOCK

    /** U+F8F4–F8FB: 九键双拼's digit keys (NineKeyLetters.NINE_SP). */
    val NINE_SHUANGPIN = NineKeyLetters.NINE_SP_BLOCK

    /**
     * Whether [codePoint] is one of them: any private use character. The grouped keyboards' codes
     * ([GROUPED], [G18], [NINE_SHUANGPIN]) and `Keyboard`'s TAPS, LETTERS, NINE_*, SYLLABLES,
     * INK…AGAIN all lie in private use. The app drops such
     * a key rather than commit it when it comes back unhandled, e.g. with no focused context.
     */
    fun isInternal(codePoint: Int): Boolean =
        KeySyms.isPrivateUse(codePoint)
}
