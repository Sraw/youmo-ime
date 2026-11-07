package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.Expand
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenTest {

    // a label that itself reads "%2$s" stays as written: both places are filled in one pass
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
