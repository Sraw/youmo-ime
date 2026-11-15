/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.Contrast
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.theme.Argb
import kotlin.math.roundToInt

/**
 * The toolbar's 手写 (061-handwriting-toolbar §3.4): a toggle the user flips ([open], remembered), and
 * whether the letters are written on now, which is that and what the keyboard is in at the moment.
 */
object HandwritingMode {

    /** What a graphic needs against what is around it (WCAG 1.4.11): a theme's ink fainter than this is not used. */
    const val INK_CONTRAST = 3.0

    /** The letters' faces while they are written on (§3.3, the user's Q8): seen, and plainly not for typing. */
    const val LETTERS_FADED = 0.35

    /**
     * Whether the toolbar offers 手写 in input method [im]: not in a [password] field (what is written is
     * shown as it is read), nor in English, whose keys are typed (the user's Q3).
     */
    fun offered(im: String, password: Boolean) = !password && InputMethodCatalog.writesOnKeys(im)

    /**
     * Whether a finger down on the letters writes. Not while 快速输入 or Unicode take the keys ([tempMode]), the
     * recognizer cannot load (nothing would be read), nor with a screen reader's touch exploration
     * ([touchExploring]: its single taps find keys, the user's Q7). A hardware keyboard typing hides the keys:
     * no touch to ask about.
     */
    fun writes(
        open: Boolean,
        im: String,
        password: Boolean,
        tempMode: Boolean,
        recognizerLoadable: Boolean,
        touchExploring: Boolean,
    ) = open && offered(im, password) && !tempMode && recognizerLoadable && !touchExploring

    /**
     * The ink's colour on the letter keys: the theme's (`handwriting/pad`'s `ink-color`, [themeInk]) where it
     * stands out from the letter keys' fill [letterFill] as much as a graphic needs, else the letters' own
     * colour [keyText], which the theme made to be read on those keys. A pad's white ink is not lost on a
     * light key so.
     */
    fun inkColor(themeInk: Int?, keyText: Int, letterFill: Int): Int =
        themeInk?.takeIf { Contrast.ratio(it, letterFill) >= INK_CONTRAST } ?: keyText

    /**
     * A letter key's drawing [ops] as it shows while written on (§3.3): its texts and icons (the letters, the marks
     * in its corners) at [LETTERS_FADED] of their alpha, outline and shadow with them; its face as it is.
     */
    fun faded(ops: List<Op>): List<Op> = ops.map {
        when (it) {
            is Op.Text -> it.copy(
                color = it.color.faded(),
                stroke = it.stroke?.let { s -> s.copy(color = s.color.faded()) },
                shadow = it.shadow?.let { s -> s.copy(color = s.color.faded()) },
            )
            is Op.Icon -> it.copy(tint = it.tint.faded())
            else -> it
        }
    }

    private fun Argb.faded() = Argb((value and RGB) or ((alpha * LETTERS_FADED).roundToInt() shl ALPHA_SHIFT))

    private const val RGB = 0xFFFFFF
    private const val ALPHA_SHIFT = 24
}
