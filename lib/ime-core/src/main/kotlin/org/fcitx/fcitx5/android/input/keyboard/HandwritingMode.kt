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

/** The toolbar's 手写: the user's toggle ([open]) and whether the letters are written on now. */
object HandwritingMode {

    /** WCAG 1.4.11's for graphics: a theme's ink fainter than this is not used. */
    const val INK_CONTRAST = 3.0

    /** Seen, and plainly not for typing. */
    const val LETTERS_FADED = 0.35

    /** Not in a [password] field (shown as read), nor English, whose keys are typed. */
    fun offered(im: String, password: Boolean) = !password && InputMethodCatalog.writesOnKeys(im)

    /** Not under [tempMode], with no recognizer, nor with touch exploration (its taps find keys). */
    fun writes(
        open: Boolean,
        im: String,
        password: Boolean,
        tempMode: Boolean,
        recognizerLoadable: Boolean,
        touchExploring: Boolean,
    ) = open && offered(im, password) && !tempMode && recognizerLoadable && !touchExploring

    /** The theme's ink where it stands out from [letterFill], else [keyText] (a white pad ink is lost on a light key). */
    fun inkColor(themeInk: Int?, keyText: Int, letterFill: Int): Int =
        themeInk?.takeIf { Contrast.ratio(it, letterFill) >= INK_CONTRAST } ?: keyText

    /** Texts and icons at [LETTERS_FADED] alpha, outline and shadow with them; the face as is. */
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
