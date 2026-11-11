/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.Expand
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenTest {

    // the bug: the spoken template's two places were filled one after the other, so a label that
    // itself reads "%2$s" had the second place's text put into it. Both are filled in one pass:
    // the label stays as written
    @Test
    fun aLabelHoldingAPlaceholderIsReadAsWritten() {
        val said = Expand.spoken(KeyAttrs(), false, "%2\$s", "1", Texts())
        assertEquals("%2\$s, 1", said)
    }

    @Test
    fun theLabelAndAltAreReadTogether() {
        assertEquals("Fn, 1", Expand.spoken(KeyAttrs(), false, "Fn", "1", Texts()))
    }
}
