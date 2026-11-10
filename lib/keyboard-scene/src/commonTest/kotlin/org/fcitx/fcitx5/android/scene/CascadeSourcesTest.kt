/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertEquals

// Cascade.sources: the style each value of the cascade comes from, what the theme maker's 按键 page shows
class CascadeSourcesTest {
    @Test
    fun sourcesNameTheStyleEachValueOfComesFrom() {
        val t = Fixtures.materialLight
        val q = Selector.Target("key", "qwerty", "q", null, setOf("letter"), emptySet())
        val enter = Selector.Target("key", "qwerty", "enter", "enter", setOf("enter"), emptySet())
        for (scene in listOf(emptySet(), setOf("flat"), setOf("landscape", "dark"))) {
            val c = Cascade(t, scene)
            for (base in listOf(q, enter)) {
                for (target in listOf(base, base.copy(states = setOf("pressed")), base.copy(part = "label"), base.copy(part = "hint"))) {
                    val of = c.of(target)
                    val sources = c.sources(target)
                    assertEquals(of.keys, sources.keys, "$target in $scene")
                    for ((p, v) in of) assertEquals(v, sources.getValue(p).values[p], "$p of $target in $scene")
                }
            }
        }
        val source = testData("material-light.xml").replace("</theme>", "<style select=\"*/enter\" fill=\"#ff0000\"/>\n</theme>")
        val own = ThemeReader.theme(source).ok()
        assertEquals(own.styles.last(), Cascade(own, emptySet()).sources(enter).getValue("fill"))
    }
}
