/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 060-render-v2 §9: the qwerty example in the material-light example at four sizes, each key in its
 * normal and pressed state, as [SceneText] writes them. The JVM writes them (-Psnapshots.record=true)
 * and both targets compare with what is committed, so the JVM and JS engines agree to the hundredth
 * of a pixel or one of them fails. A change to the engine shows here as a diff to review.
 */
class SnapshotTest {

    @Test
    fun qwertyMaterialLightPortrait() = check("portrait")

    @Test
    fun qwertyMaterialLightCompact() = check("compact")

    @Test
    fun qwertyMaterialLightLandscapeSplit() = check("landscape")

    @Test
    fun qwertyMaterialLightFloating() = check("floating")

    private fun check(size: String) {
        val text = SceneText.of(Fixtures.scene(Fixtures.sizes.getValue(size)))
        val path = "$TEST_DATA/snapshots/qwerty-material-light-$size.txt"
        if (recording()) {
            writeText(path, text)
            return
        }
        val committed = assertNotNull(readText(path), "no $path: run the JVM tests with -Psnapshots.record=true")
        assertEquals(committed, text, "$path differs: review, then record anew")
    }
}
