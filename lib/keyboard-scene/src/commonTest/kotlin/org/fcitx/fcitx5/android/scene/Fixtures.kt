/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.TextMetrics
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.fail

/**
 * A font of fixed sizes, the same on every platform (060-render-v2 §6 step 5's fake): a Latin
 * character 0.6 of the size across, a wide (CJK) one 1.0, bold 5% wider; ink from 0.05 of the size
 * in from each side, cap height 0.7, descenders 0.2; the font's extent 1.05 above, 0.3 below.
 */
object FakeMeasurer : TextMeasurer {
    override fun measure(text: String, font: String, weight: Int, size: Double): TextMetrics {
        val bold = if (weight >= BOLD) BOLD_WIDER else 1.0
        val advance = text.sumOf { (if (it.code >= WIDE_FROM) WIDE else NARROW) * size * bold }
        val descends = text.any { it in "gjpqy,;" }
        val ink = Box(size * SIDE, -size * CAP, maxOf(size * SIDE, advance - size * SIDE), if (descends) size * DESCENT else 0.0)
        return TextMetrics(advance, ink, -size * TOP, size * BOTTOM)
    }

    private const val BOLD = 700
    private const val BOLD_WIDER = 1.05
    private const val WIDE_FROM = 0x2E80
    private const val WIDE = 1.0
    private const val NARROW = 0.6
    private const val SIDE = 0.05
    private const val CAP = 0.7
    private const val DESCENT = 0.2
    private const val TOP = 1.05
    private const val BOTTOM = 0.3
}

/** The examples (dev/design/060-render-xsd/examples, copied into src/commonTest/data) and the four sizes the snapshots are of. */
object Fixtures {
    const val DENSITY = 2.625

    val qwertySource: String by lazy { testData("qwerty.xml") }
    val qwerty: Layout by lazy { LayoutReader.layout(qwertySource).ok() }
    val materialLight: Theme by lazy { ThemeReader.theme(testData("material-light.xml")).ok() }

    /** The letters' long press firsts as built in (PopupPreset's): what the app gives `{alt:x}` till the user changes one. */
    val qwertyAlts: Map<String, String> = "q1w2e3r4t5y6u7i8o9p0a@s*d+f-g=h/j#k(l)z'x:c\"v?b!n~m\\".chunked(2).associate { it.take(1) to it.drop(1) }

    private val texts = Texts(imeName = "拼音", returnLabel = "换行", returnIcon = "enter", alts = qwertyAlts)

    /** A phone upright, 411 dp wide, the keyboard 274 dp tall. */
    val portrait = Env(1080.0, 720.0, DENSITY, input = Input(langKey = true), texts = texts)

    /** The same, the keyboard compact (its height setting's lowest, text 0.9). */
    val compact = portrait.copy(height = 576.0, compact = true)

    /** A phone on its side, 914 dp: split (SplitKeyboard.Mode.AUTO splits from 600 dp). */
    val landscape = portrait.copy(width = 2400.0, height = 528.0, landscape = true, split = true)

    /** Floating, 300 dp wide, its text 0.85. */
    val floating = portrait.copy(width = 787.5, height = 612.0, floating = 0.85)

    val sizes = mapOf("portrait" to portrait, "compact" to compact, "landscape" to landscape, "floating" to floating)

    fun scene(env: Env, layout: Layout = qwerty, theme: Theme = materialLight): Scene =
        SceneBuilder(theme, env, FakeMeasurer).build(layout).ok()

    fun <T> Parsed<T>.ok(): T = getOr { fail("problems: $it") }
}
