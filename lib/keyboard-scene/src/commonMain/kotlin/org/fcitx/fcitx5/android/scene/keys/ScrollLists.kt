/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

import org.fcitx.fcitx5.android.scene.layout.LegacyNumberKeys

/** Which list a `scroll` or `nine-side` slot shows: one rule for the keyboard, the settings' cards and the web pages. */
object ScrollLists {
    const val NINE_SIDE = "nine-side"

    sealed interface Shown {
        /** The typing's syllables, which only the keyboard has. */
        data object Syllables : Shown

        data class Category(val id: String) : Shown
    }

    private val FIELD_LISTS = setOf(ScrollDefaults.URL, ScrollDefaults.EMAIL)
    private val PAD_LISTS = setOf(ScrollDefaults.PHONE, ScrollDefaults.DATETIME)
    private val SYLLABLE_IMES = setOf("pinyin", "shuangpin")

    /** Arguments as the scene's Input has them; the field decides over what the layout names, on every keyboard. */
    @Suppress("LongParameterList")
    fun shown(kind: String, list: String?, field: String?, fieldPad: String?, pad: String?, ime: String?, composing: Boolean): Shown {
        if (kind == NINE_SIDE && composing && ime in SYLLABLE_IMES) return Shown.Syllables
        val id = field?.takeIf { it in FIELD_LISTS }
            ?: fieldPad?.takeIf { it in PAD_LISTS }
            ?: pad?.takeIf { it in PAD_LISTS }
            ?: when {
                kind == NINE_SIDE -> if (ime == "english") ScrollDefaults.NINE_ENGLISH else ScrollDefaults.NINE
                else -> list ?: ScrollDefaults.NINE
            }
        return Shown.Category(id)
    }

    /** What a tap on an item does; the app makes each a key action. */
    sealed interface Typed {
        /** Handed to English, which puts a word's space behind it. */
        data class English(val text: String) : Typed

        /** [punctuation]: an ASCII mark held back, as the punctuation keys are, while the 中文标点 mapping is not known. */
        data class Commit(val text: String, val punctuation: Boolean = false) : Typed

        /** A keypad key by its keysym's name (`KP_Add`), as the number pad's keys send them. */
        data class Keypad(val sym: String) : Typed

        /** By KeyCommand's key, as a swipe set to it runs it. */
        data class Command(val key: String) : Typed
    }

    private val MARKED = setOf(ScrollDefaults.NINE, ScrollDefaults.NINE_ENGLISH)

    /** Whether [category]'s marks go to the input method's 中文标点, so an item may be kept as it is. */
    fun convertsMarks(category: String): Boolean = category in MARKED
    private val PADS = setOf(ScrollDefaults.NUMBER, ScrollDefaults.PHONE, ScrollDefaults.DATETIME)
    private const val ENGLISH = "english"

    // the 0.6.2 number pad's keys typed these as keypad keys, which no input method turns full-width
    private val KEYPAD = LegacyNumberKeys.SENDS.mapNotNull { (text, send) -> send.removePrefix(SYM).takeIf { it != send }?.let { text to it } }.toMap()
    private const val SYM = "sym:"

    /** [item] of [category] tapped under [ime]; [marks]: ASCII marks to the input method's 中文标点. */
    fun typed(category: String, item: ScrollItem, ime: String?, marks: Map<String, String> = emptyMap()): Typed = when (item) {
        is ScrollItem.Command -> Typed.Command(item.key)
        is ScrollItem.Text -> when (category) {
            in MARKED -> when {
                ime == ENGLISH -> if (item.text.length == 1) Typed.English(item.text) else Typed.Commit(item.text)
                item.asIs -> Typed.Commit(item.text)
                else -> Typed.Commit(marks[item.text] ?: item.text, punctuation = item.text.singleOrNull()?.code?.let(::isAsciiMark) == true)
            }
            in PADS -> KEYPAD[item.text]?.let(Typed::Keypad) ?: Typed.Commit(item.text)
            else -> Typed.Commit(item.text)
        }
    }

    /** What [item] shows; null for a command, drawn as its icon ([SlotDefaults.icon]). */
    fun label(category: String, item: ScrollItem, ime: String?, marks: Map<String, String> = emptyMap()): String? = when (item) {
        is ScrollItem.Command -> null
        is ScrollItem.Text -> if (category in MARKED && ime != ENGLISH && !item.asIs) marks[item.text] ?: item.text else item.text
    }

    /** A mark the 中文标点 mapping may turn full-width, a key's code or a keysym alike: ASCII's printable non-alphanumerics. */
    fun isAsciiMark(code: Int): Boolean = code in '!'.code..'~'.code && !code.toChar().isLetterOrDigit()
}
