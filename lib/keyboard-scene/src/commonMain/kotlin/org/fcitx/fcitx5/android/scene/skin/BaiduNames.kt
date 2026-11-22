/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.engine.Content
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.theme.Values
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Baidu's names for keys, shared by its classic skins ([BaiduSkin]: a key's `CENTER=` action) and `.bda`
 * ([BaiduBdaSkin]: `KEY_<action>[_S<state>][_<variant>]`): a letter, a punctuation mark, or a function code `F<n>`.
 */
object BaiduNames {
    /** The kinds of key styled on their own, in the order read (the function keys' before backspace's and shift's, which take theirs where it is the same), each with its theme selector. */
    enum class Kind(val selector: String) {
        LETTER("key"), FUNCTION("key.function"), SPACE("key.space"), ENTER("key.enter"),
        BACKSPACE("key[role=backspace]"), SHIFT("key[role=shift]"),
    }

    /** A `.bda` key's name split: its action as a classic skin's `CENTER=` has it (a letter in lower case), its state, its variant. */
    class Key(val action: String, val state: Int?, val variant: String?)

    private val KEY = Regex("KEY_(.+?)(?:_S(\\d{1,6}))?(?:_([A-Za-z0-9]{1,16}))?")
    private val FUNCTION = Regex("F\\d{1,4}")

    private val KINDS = mapOf(
        "F38" to Kind.SPACE, "F39" to Kind.ENTER, "F75" to Kind.ENTER, "F36" to Kind.BACKSPACE, "F10" to Kind.SHIFT, "F11" to Kind.SHIFT,
    )

    // the layouts' roles and key ids a code stands for
    private val ROLES = mapOf(
        "F36" to "backspace", "F10" to "shift", "F11" to "shift", "F39" to "enter", "F75" to "enter", "F38" to "space",
        "F6" to "numbers", "F1" to "symbols", "F90" to "symbols", "F15" to "lang", "F16" to "lang", "F7" to "emoji", "F40" to "reset",
    )
    private val MARKS = mapOf("，" to "comma", "," to "comma", "。" to "period", "." to "period", "'" to "apostrophe")

    // the nine keys' letters, digit by digit (Baidu names a nine key by one of its letters; 1 is the apostrophe's)
    private val NINE = listOf("'", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")

    fun key(name: String): Key? {
        val m = KEY.matchEntire(name) ?: return null
        val a = m.groupValues[1]
        val action = if (a.length == 1 && a[0] in 'A'..'Z') a.lowercase() else a
        return Key(action, m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull(), m.groupValues[3].takeIf { it.isNotEmpty() })
    }

    /** A key's kind by its action; null: a key not styled by kind (a punctuation mark, none). */
    fun kind(action: String): Kind? = when {
        letter(action) != null -> Kind.LETTER
        else -> KINDS[action] ?: if (action.matches(FUNCTION)) Kind.FUNCTION else null
    }

    fun letter(action: String): Char? = action.singleOrNull()?.takeIf { it in 'a'..'z' }

    /** The app's icon a function key's picture stands for. */
    fun icon(action: String): String? = SkinIcons.byBaiduCode[action]

    /**
     * [action] as a layout key ([SkinLayoutWriter]): a letter (on [nine] keys its digit, and a digit itself), a role, or none for a key
     * without one; a code of no role is a role no layout has, so its board does not match rather than lose a key.
     */
    fun action(action: String, nine: Boolean): SkinAction {
        if (action.isEmpty()) return SkinAction.None
        if (nine) {
            NINE.indexOfFirst { action.length == 1 && action[0] in it }.takeIf { it >= 0 }?.let { return SkinAction.Letter('1' + it) }
            action.singleOrNull()?.takeIf { it in '0'..'9' }?.let { return SkinAction.Letter(it) }
        }
        letter(action)?.let { return SkinAction.Letter(it) }
        return SkinAction.Role(ROLES[action] ?: MARKS[action] ?: "baidu-$action")
    }

    /** The app's layout a board of these letters is, or null: 26 the full keys, 18 and 14 the grouped ones, the nine keys' few on a `_9` board. */
    fun boardId(letters: Set<Char>, nine: Boolean): String? = when {
        letters.size == FULL -> "qwerty"
        letters.size == K18 -> "k18"
        letters.size == K14 -> "k14"
        nine && letters.isNotEmpty() && letters.size < NINE.size -> "nine"
        else -> null
    }

    /** A key's text against the app's: its `scale` (%, null near 100), and whether it was past the theme's bounds or the letters' differ. */
    class Scale(val percent: Int?, val noted: Boolean)

    /** The app's key at its standard keyboard height (a 360 dp phone's), dp: a skin's text is measured against it. */
    val ROW_DP = LayoutCheck.keysDp(LayoutCheck.SCREENS.first(), compact = false).toDouble() / ROWS

    // the app's text over its key's height
    private val APP_RATIO = Content.DEFAULT_TEXT_DP / ROW_DP

    /** [ratios] (each a key's text size over its key's height) as one `scale`: their median against the app's. */
    fun scale(ratios: List<Double>): Scale? {
        val sorted = ratios.filter { it.isFinite() && it > 0 }.sorted().takeIf { it.isNotEmpty() } ?: return null
        val median = sorted[sorted.size / 2]
        val percent = (median / APP_RATIO * PERCENT).roundToInt()
        val differ = sorted.last() - sorted.first() > median * SAME_SIZE
        val past = percent < Values.MIN_SCALE || percent > Values.MAX_SCALE
        return Scale(percent.takeIf { abs(it - PERCENT) >= NEAR }, differ || past)
    }

    /** A skin's font weight as the theme's two: bold, light, or none. */
    fun weight(w: Int?): Int? = when {
        w == null -> null
        w >= BOLD_FROM -> BOLD
        w <= LIGHT_TO -> LIGHT
        else -> null
    }

    private const val ROWS = 4
    private const val PERCENT = 100
    private const val NEAR = 5
    private const val SAME_SIZE = 0.02
    private const val BOLD_FROM = 600
    private const val BOLD = 700
    private const val LIGHT_TO = 350
    private const val LIGHT = 300
    private const val FULL = 26
    private const val K18 = 18
    private const val K14 = 14
}
