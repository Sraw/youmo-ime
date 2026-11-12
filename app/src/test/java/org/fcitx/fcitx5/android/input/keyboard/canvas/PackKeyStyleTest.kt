/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs

/**
 * The key style settings over every built-in 4.0 pack, as the keyboard draws it (KeyStyleChoice.overPack on the
 * scene's Env): the owner, 0.6.0-beta2, 国风·天青's keys spaced as the pack wrote whatever was set. Each kind of key,
 * upright, on its side, 紧凑 and both.
 */
class PackKeyStyleTest {

    private val packs: Map<String, Theme> by lazy {
        File("src/main/res/raw").listFiles { f -> f.name.startsWith("builtin_theme_") && f.name.endsWith(".theme") }!!
            .associate { f ->
                val text = ZipFile(f).use { z -> z.getInputStream(z.getEntry("theme.xml")).bufferedReader().readText() }
                f.name.removePrefix("builtin_theme_").removeSuffix(".theme") to (ThemeReader.theme(text) as Parsed.Ok).value
            }
            // the tests below go over every pack: none found (the path moved) would pass them all
            .also { assertTrue("no built-in packs under src/main/res/raw", it.isNotEmpty()) }
    }

    private val qwerty: Layout by lazy {
        (LayoutReader.layout(File("../lib/keyboard-scene/src/commonMain/layouts/qwerty.xml").readText()) as Parsed.Ok).value
    }

    private val density = 2.625
    private val portrait = Env(1080.0, 720.0, density, input = Input())
    private val forms = mapOf(
        "upright" to portrait,
        "landscape" to portrait.copy(width = 2340.0, landscape = true),
        "compact" to portrait.copy(height = 576.0, compact = true),
        "landscape compact" to portrait.copy(width = 2340.0, height = 576.0, landscape = true, compact = true),
    )

    private fun scene(theme: Theme, choice: KeyStyleChoice, env: Env): Scene {
        val over = choice.overPack
        return (SceneBuilder(theme, env.copy(flat = over?.flat == true, keyStyle = over), FixedMeasurer).build(qwerty) as Parsed.Ok).value
    }

    private fun Scene.key(role: String) = keys.first { it.role == role || it.id == role }

    // the face's inset, dp: top, right, bottom, left
    private fun inset(n: KeyNode): List<Double> = (n.shape as Shape.Rect).box.let { b ->
        listOf(b.top - n.bounds.top, n.bounds.right - b.right, n.bounds.bottom - b.bottom, b.left - n.bounds.left).map { it / density }
    }

    private fun near(a: Double, b: Double) = abs(a - b) < 0.6

    @Test
    fun everyPacksKeysTakeTheSpacingsInEveryForm() {
        val choice = KeyStyleChoice(null, KeyStyleChoice.H_STANDARD, KeyStyleChoice.V_LOOSE, null)
        // "own" below is the pack as it drew itself: following it lays nothing over it
        assertNull(KeyStyleChoice.FOLLOW.overPack)
        packs.forEach { (name, theme) ->
            forms.forEach { (form, env) ->
                val own = scene(theme, KeyStyleChoice.FOLLOW, env)
                val set = scene(theme, choice, env)
                val level = (if (env.landscape) 6 else 9).let { if (env.compact) Gap.compact(it) else it }.toDouble()
                listOf("a", "shift", "space", "enter").forEach { k ->
                    val what = "$name $form $k"
                    val i = inset(set.key(k))
                    val o = inset(own.key(k))
                    assertTrue("$what left/right $i", near(i[1], 3.0) && near(i[3], 3.0))
                    // the pack's own top and bottom for this key (the face measured has its cap, depth, under it)
                    val n = own.key(k)
                    val raw = KeyLook(Cascades(theme).of(env.scenes).of(Selector.Target("key", qwerty.id, n.id, n.role, setOf(n.kind))), theme).inset
                    val half = (raw.bottom - raw.top) / 2
                    val top = (level - half).coerceAtLeast(0.0)
                    assertTrue("$what top $i (own $o, $raw)", near(i[0], top))
                    // the bottom moved as the top did: the pack's difference (and its cap) kept
                    assertTrue("$what bottom-minus-top kept $i (own $o, $raw)", near(i[2] - o[2], i[0] - o[0]) || top == 0.0)
                }
            }
        }
    }

    // 无边框 on every pack, every form: every key alike (the space and Return too), no fill, border, shadow,
    // inner shadow or cap (机械键帽's depth) unpressed
    @Test
    fun withoutBordersEveryPacksKeysAreFlat() {
        val flat = KeyStyleChoice(false, null, null, null)
        packs.forEach { (name, theme) ->
            forms.forEach { (form, env) ->
                scene(theme, flat, env).keys.forEach { n ->
                    n.faces.forEach { (states, ops) ->
                        assertTrue("$name $form ${n.id} $states $ops", ops.none { it is Op.Shadow || it is Op.InnerShadow || it is Op.Stroke || it is Op.NineSlice })
                        assertTrue(
                            "$name $form ${n.id} $states $ops",
                            ops.filterIsInstance<Op.Fill>().none { it.color.alpha != 0 && KeyState.Pressed !in states },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun theRadiusIsEveryKeysInEveryFormPressedToo() {
        val choice = KeyStyleChoice(null, null, null, KeyStyleChoice.RADIUS_SQUARE)
        packs.forEach { (name, theme) ->
            // a pack drawing its own shape= (none of the built-in ones now) is not a rounded rect to square
            if (theme.styles.any { st -> "shape" in st.values }) return@forEach
            forms.forEach { (form, env) ->
                val s = scene(theme, choice, env)
                listOf("a", "shift", "space", "enter").forEach { k ->
                    val n = s.key(k)
                    assertEquals("$name $form $k", List(4) { 0.0 }, (n.shape as Shape.Rect).radii)
                    // the pressed face's fills square too
                    n.faces.filterKeys { KeyState.Pressed in it }.values.flatten().filterIsInstance<Op.Fill>().forEach {
                        assertEquals("$name $form $k pressed", List(4) { 0.0 }, (it.shape as? Shape.Rect)?.radii ?: List(4) { 0.0 })
                    }
                }
            }
        }
    }
}
