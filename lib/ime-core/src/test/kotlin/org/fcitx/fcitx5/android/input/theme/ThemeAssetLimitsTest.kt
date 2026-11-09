/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.input.theme.LookFixtures.PALETTE
import org.fcitx.fcitx5.android.scene.look.KeyLabel
import org.fcitx.fcitx5.android.scene.look.KeyOverride
import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeAssetLimitsTest {

    @Test
    fun limitsTellWhatIsPast() {
        val l = ThemeAssetLimits
        assertNull(l.entry(300, l.MAX_FILE_BYTES, 0))
        assertEquals(ThemeAssetLimits.Breach.Entries, l.entry(301, 1, 0))
        assertEquals(ThemeAssetLimits.Breach.FileSize, l.entry(1, l.MAX_FILE_BYTES + 1, 0))
        assertEquals(ThemeAssetLimits.Breach.TotalSize, l.entry(3, 1, l.MAX_TOTAL_BYTES))
        assertNull(l.picture(4096, 4096))
        assertEquals(ThemeAssetLimits.Breach.PictureSide, l.picture(4097, 10))
        assertNull(l.keyImage(1024, 1024, l.MAX_KEY_IMAGE_BYTES))
        assertEquals(ThemeAssetLimits.Breach.KeyImageSide, l.keyImage(10, 1025, 1))
        assertEquals(ThemeAssetLimits.Breach.KeyImageSize, l.keyImage(10, 10, l.MAX_KEY_IMAGE_BYTES + 1))
        val look = LookDefaults.look(PALETTE)
        assertNull(l.look(look))
        val many = (1..401).associate { "qwerty/k$it" to KeyOverride(null, null, KeyLabel.Show) }
        assertEquals(ThemeAssetLimits.Breach.Overrides, l.look(look.copy(keys = look.keys.copy(overrides = many))))
        // the engine's limit, which its 3.0 reader holds a file to too
        assertEquals(Keys.MAX_OVERRIDES, l.MAX_OVERRIDES)
    }
}
