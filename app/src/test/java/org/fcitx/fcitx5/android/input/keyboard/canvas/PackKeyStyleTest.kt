/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.fcitx.fcitx5.android.scene.KeyNode
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
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
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
    fun allTwelve() = assertEquals(12, packs.size)

    @Test
    fun followingThePackIsItsOwnDrawing() {
        assertNull(KeyStyleChoice.FOLLOW.overPack)
        // 有边框 is a pack's own faces
        assertNull(KeyStyleChoice(true, null, null, null).overPack)
    }

    @Test
    fun everyPacksKeysTakeTheSpacingsInEveryForm() {
        val choice = KeyStyleChoice(null, KeyStyleChoice.H_STANDARD, KeyStyleChoice.V_LOOSE, null)
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

    // a pack of no key.space or key.enter style (天青, 奶油, 护眼·纸色, 霓虹赛博): 无边框 the space a bar, Return a circle all the same
    @Test
    fun withoutBordersEveryPacksSpaceIsABarAndReturnACircle() {
        val flat = KeyStyleChoice(false, null, null, null)
        packs.forEach { (name, theme) ->
            forms.forEach { (form, env) ->
                val s = scene(theme, flat, env)
                val space = s.key("space")
                val bar = (space.shape as Shape.Rect).box
                assertTrue("$name $form space a bar: $bar in ${space.bounds}", near((bar.left - space.bounds.left) / density, 10.0))
                val enter = s.key("enter").shape as Shape.Rect
                assertEquals("$name $form Return", enter.box.width, enter.box.height, 0.01)
                assertTrue("$name $form Return a circle: $enter", enter.radii.all { abs(it - enter.box.width / 2) < 0.01 })
                // letters: no face, border, shadow or cap
                s.key("a").faces.forEach { (states, ops) ->
                    assertTrue("$name $form a $states $ops", ops.none { it is Op.Shadow || it is Op.InnerShadow || it is Op.Stroke })
                }
            }
        }
    }

    // 机械键帽: its cap (depth, inner shadow) and shadow gone flat
    @Test
    fun withoutBordersTheKeycapsHaveNoCap() {
        val s = scene(packs.getValue("keycap"), KeyStyleChoice(false, null, null, null), portrait)
        listOf("a", "shift", "backspace").forEach { k ->
            s.key(k).faces.values.flatten().let { ops ->
                assertTrue("$k $ops", ops.none { it is Op.Shadow || it is Op.InnerShadow || it is Op.Stroke })
                assertTrue("$k one face at most (no cap under it): $ops", ops.filterIsInstance<Op.Fill>().count { it.color.alpha != 0 } <= 2)
            }
        }
    }

    // none blank: a key that must show what it is shows it, flat too
    @Test
    fun withoutBordersNoPackLeavesAKeyBlank() {
        val flat = KeyStyleChoice(false, null, null, KeyStyleChoice.RADIUS_ROUND).overPack
        packs.forEach { (name, theme) ->
            assertEquals(name, emptyList<BlankKeys.Blank>(), BlankKeys.find(theme, listOf(BlankKeys.Board(qwerty)), limit = 10, keyStyle = flat))
        }
    }

    @Test
    fun theRadiusIsEveryKeysInEveryFormPressedToo() {
        val choice = KeyStyleChoice(null, null, null, KeyStyleChoice.RADIUS_SQUARE)
        packs.forEach { (name, theme) ->
            forms.forEach { (form, env) ->
                val s = scene(theme, choice, env)
                listOf("a", "shift", "space", "enter").forEach { k ->
                    val n = s.key(k)
                    if (theme.styles.any { st -> "shape" in st.values }) return@forEach
                    assertEquals("$name $form $k", List(4) { 0.0 }, (n.shape as Shape.Rect).radii)
                }
            }
        }
    }
}
