/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.theme.SvgSubset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// a theme's own SVG icon as the maker's preview (and a later app) draws it: its sides, its origin, its opacity
class SvgIconReadTest {

    @Test
    fun aSideOfNothingOrLessIsRefusedAsAViewBoxsIs() {
        // with no viewBox width= and height= are the sides: 0 would scale by Infinity, -24 mirror the icon
        for (side in listOf("0", "-24", "1e-300", "0px")) {
            assertTrue(SvgSubset.check("""<svg width="$side"><path d="M0 0"/></svg>""").single().message.startsWith("width="), side)
            assertTrue(SvgSubset.check("""<svg height="$side"><path d="M0 0"/></svg>""").single().message.startsWith("height="), side)
        }
        assertEquals(emptyList<Problem>(), SvgSubset.check("""<svg width="0.001" height="1e6"><path d="M0 0"/></svg>"""))
    }

    @Test
    fun theViewBoxsOriginIsKeptForTheDrawer() {
        // Material Symbols draw above y=0: without the origin the preview put the icon a whole key higher
        val icon = SvgSubset.read("""<svg viewBox="0 -960 960 960"><path d="M0 -480 h960"/></svg>""").ok()
        assertEquals(listOf(0.0, -960.0, 960.0, 960.0), listOf(icon.x, icon.y, icon.width, icon.height))
        assertEquals(listOf(0.0, 0.0), SvgSubset.read("""<svg width="10"><path d="M0 0"/></svg>""").ok().let { listOf(it.x, it.y) })
    }

    @Test
    fun aGroupsFillOpacityIsInheritedLikeItsFill() {
        // fill-opacity is inherited in SVG: the nearest one's counts, opacity multiplies on top
        val icon = SvgSubset.read(
            """<svg><g fill-opacity="0.5" opacity="0.5"><path d="M0 0"/><path d="M1 1" fill-opacity="1"/></g></svg>""",
        ).ok()
        assertEquals(listOf(0.25, 0.5), icon.paths.map { it.alpha })
    }
}
