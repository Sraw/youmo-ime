/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.scene.engine.KeyStyleOver
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Value
import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's 按键样式 over a 4.0 theme's keys, laid on each real key after the cascade (the owner, 0.6.0-beta2:
 * 国风·天青's `inset` drew whatever was set): the key's own selectors, whatever their shape or order; 无边框 never
 * blanking a key whose face carries its letter (a converted skin's), and always the space a bar, Return a circle.
 */
class KeyStyleOverTest {

    private val d = Fixtures.DENSITY

    // material-light less its own @flat styles and its space's and Return's: what the setting must make alone
    private val base: String by lazy {
        testData("material-light.xml").lines().filterNot { "@flat key" in it || "\"key.space\"" in it || "\"key.enter\"" in it }.joinToString("\n")
    }

    private fun theme(extra: String = "", text: String = base): Theme = ThemeReader.theme(text.replace("</theme>", "$extra\n</theme>")).ok()

    private fun scene(theme: Theme, over: KeyStyleOver?, env: Env = Fixtures.portrait): Scene =
        SceneBuilder(theme, env.copy(flat = over?.flat == true, keyStyle = over), FixedMeasurer).build(Fixtures.qwerty).ok()

    private fun Scene.key(id: String) = keys.first { it.id == id }

    private fun face(n: KeyNode) = (n.shape as Shape.Rect)

    // the face's inset in the cell, dp: top, right, bottom, left
    private fun inset(n: KeyNode): List<Double> = face(n).box.let { b ->
        listOf(b.top - n.bounds.top, n.bounds.right - b.right, n.bounds.bottom - b.bottom, b.left - n.bounds.left).map { round(it / d * 10) / 10.0 }
    }

    private fun near(a: Double, b: Double) = abs(a - b) < 0.6

    private val loose = KeyStyleOver.Level(5, 5)
    private val tight = KeyStyleOver.Level(5, 3)

    @Test
    fun noneSetIsTheThemesOwnScene() {
        val t = theme()
        val own = scene(t, null)
        val none = scene(t, KeyStyleOver())
        assertEquals(own.keys.map { it.shape }, none.keys.map { it.shape })
        assertEquals(own.keys.map { it.faces }, none.keys.map { it.faces })
    }

    @Test
    fun theSpacingsAreEveryRealKeysWhateverSelectsIt() {
        // Return's by its role, q's by its name, the landscape's written after Return's
        val t = theme(
            """<style select="*/enter" inset="9dp 8dp"/><style select="qwerty/q" inset="1dp 1dp"/><style select="@landscape key" inset="2dp 6dp"/>""",
        )
        val across = scene(t, KeyStyleOver(across = loose))
        listOf("enter" to 9.0, "q" to 1.0, "a" to 7.0).forEach { (id, v) ->
            val i = inset(across.key(id))
            assertTrue(near(i[0], v) && near(i[2], v) && near(i[1], 5.0) && near(i[3], 5.0), "$id $i")
        }
        val upDown = scene(t, KeyStyleOver(upDown = loose))
        listOf("enter" to 8.0, "q" to 1.0, "a" to 3.0).forEach { (id, h) ->
            val i = inset(upDown.key(id))
            assertTrue(near(i[1], h) && near(i[3], h) && near(i[0], 5.0) && near(i[2], 5.0), "$id $i")
        }
        // on its side the setting's other level; the other direction the theme's there: `@landscape key` wins a
        // letter (later than `key`), not Return or q (`*/enter`, `qwerty/q` weigh more)
        val land = scene(t, KeyStyleOver(upDown = tight), Fixtures.portrait.copy(landscape = true))
        listOf("enter" to 8.0, "q" to 1.0, "a" to 6.0).forEach { (id, h) -> inset(land.key(id)).let { assertTrue(near(it[0], 3.0) && near(it[1], h), "$id $it") } }
        // 紧凑: the level shrunk as a 3.0 theme's
        val compact = scene(t, KeyStyleOver(upDown = loose), Fixtures.compact)
        inset(compact.key("a")).let { assertTrue(near(it[0], Gap.compact(5).toDouble()), "$it") }
    }

    @Test
    fun theThemesUnevenTopAndBottomAreKept() {
        val t = theme("""<style select="key" inset="4dp 3dp 6dp 3dp"/>""")
        val i = inset(scene(t, KeyStyleOver(upDown = KeyStyleOver.Level(9, 6))).key("a"))
        assertTrue(near(i[0], 8.0) && near(i[2], 10.0), "$i")
    }

    @Test
    fun theRadiusIsEveryKeysPressedToo() {
        val t = theme("""<style select="*/enter" radius="12dp"/><style select="key:pressed" radius="20dp"/>""")
        val s = scene(t, KeyStyleOver(radiusDp = 0.0))
        listOf("a", "enter", "space", "shift").forEach { id ->
            val n = s.key(id)
            assertEquals(List(4) { 0.0 }, face(n).radii, id)
            n.faces.values.flatten().filterIsInstance<Op.Fill>().forEach { assertEquals(List(4) { 0.0 }, (it.shape as Shape.Rect).radii, "$id pressed") }
        }
    }

    // a theme of no `key.space`, `key.enter` or `@flat` style (天青, 奶油…), with a cap and its shadows: 无边框 flat
    // all the same, every key alike, the space and Return too
    @Test
    fun withoutBordersEveryKeyIsFlat() {
        val t = theme("""<style select="key" depth="4dp" inner-shadow="0 1dp 0 #ffffff" shadow="0 2dp 2dp #40000000" border="1dp #000000"/>""")
        val flat = KeyStyleOver(flat = true)
        listOf(Fixtures.portrait, Fixtures.portrait.copy(landscape = true), Fixtures.compact, Fixtures.compact.copy(landscape = true)).forEach { env ->
            scene(t, flat, env).keys.forEach { n ->
                n.faces.forEach { (states, ops) ->
                    assertTrue(ops.none { it is Op.Shadow || it is Op.InnerShadow || it is Op.Stroke }, "${n.id} $states $ops")
                    assertTrue(ops.filterIsInstance<Op.Fill>().none { it.color.alpha != 0 && KeyState.Pressed !in states }, "${n.id} $states $ops")
                }
            }
        }
    }

    @Test
    fun withoutBordersNothingSinksOrHasDepth() {
        val over = KeyStyleOver(flat = true).on(
            mapOf("press-sink" to Value.Number(2.0), "depth" to Value.Number(4.0)), landscape = false, compact = false,
        )
        assertEquals(Value.Number(0.0), over["press-sink"])
        assertEquals(Value.Number(0.0), over["depth"])
    }

    // no exceptions: a converted skin's pictured keys lose their pictures too (what is set is what is drawn)
    @Test
    fun withoutBordersAConvertedSkinsPicturesGoToo() {
        listOf(SkinFixtures.baidu(), SkinFixtures.sogou()).forEach { files ->
            val t = SkinFixtures.read(SkinFixtures.convert(files))
            assertTrue(scene(t, null).keys.any { k -> k.faces.values.flatten().any { it is Op.NineSlice } }, t.name)
            scene(t, KeyStyleOver(flat = true)).keys.forEach { k ->
                assertTrue(k.faces.values.flatten().none { it is Op.NineSlice }, "${t.name} ${k.id}: ${k.faces}")
            }
        }
    }
}
