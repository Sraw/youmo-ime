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
        // A3: an effect's image, 256 px a side and 128 KB at most, PNG or WebP
        assertNull(l.effectImage("star.png", 256, 256, 128L * 1024))
        assertNull(l.effectImage("ring.WEBP", 10, 10, 1))
        assertEquals(ThemeAssetLimits.Breach.EffectImageSide, l.effectImage("star.png", 257, 10, 1))
        assertEquals(ThemeAssetLimits.Breach.EffectImageSize, l.effectImage("star.png", 10, 10, 128L * 1024 + 1))
        assertEquals(ThemeAssetLimits.Breach.EffectImageType, l.effectImage("star.jpg", 10, 10, 1))
        val look = LookDefaults.look(PALETTE)
        assertNull(l.look(look))
        val many = (1..401).associate { "qwerty/k$it" to KeyOverride(null, null, KeyLabel.Show) }
        assertEquals(ThemeAssetLimits.Breach.Overrides, l.look(look.copy(keys = look.keys.copy(overrides = many))))
        // the engine's limit, which its 3.0 reader holds a file to too
        assertEquals(Keys.MAX_OVERRIDES, l.MAX_OVERRIDES)
    }

    @Test
    fun aPacksOwnFilesHaveTheirLimits() {
        val l = ThemeAssetLimits
        assertNull(l.themeXml(l.MAX_THEME_XML_BYTES))
        assertEquals(ThemeAssetLimits.Breach.ThemeXml, l.themeXml(l.MAX_THEME_XML_BYTES + 1))
        assertNull(l.layouts(List(16) { l.MAX_LAYOUT_BYTES }))
        assertEquals(ThemeAssetLimits.Breach.Layouts, l.layouts(List(17) { 1L }))
        assertEquals(ThemeAssetLimits.Breach.LayoutSize, l.layouts(listOf(1, l.MAX_LAYOUT_BYTES + 1)))
        assertNull(l.preview(2048, 2048, l.MAX_PREVIEW_BYTES))
        assertEquals(ThemeAssetLimits.Breach.PreviewSize, l.preview(10, 10, l.MAX_PREVIEW_BYTES + 1))
        assertEquals(ThemeAssetLimits.Breach.PreviewSide, l.preview(2049, 10, 1))
        assertNull(l.icon(l.MAX_ICON_BYTES))
        assertEquals(ThemeAssetLimits.Breach.IconSize, l.icon(l.MAX_ICON_BYTES + 1))
    }
}
