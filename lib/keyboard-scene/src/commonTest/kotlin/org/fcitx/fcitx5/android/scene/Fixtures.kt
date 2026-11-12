/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.SlotDefaults
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.fail

/** The engine's font of fixed sizes ([FixedMeasurer]), which the snapshots are of. */
val FakeMeasurer: TextMeasurer = FixedMeasurer

/** The examples (dev/design/060-render-xsd/examples, copied into src/commonTest/data) and the four sizes the snapshots are of. */
object Fixtures {
    const val DENSITY = 2.625

    val qwertySource: String by lazy { testData("qwerty.xml") }
    val qwerty: Layout by lazy { LayoutReader.layout(qwertySource).ok() }
    val materialLight: Theme by lazy { ThemeReader.theme(testData("material-light.xml")).ok() }

    /** The letters' long press firsts as built in (KeyDefaults'), by letter: what each letter's corner shows till the user changes one. */
    val qwertyAlts: Map<String, String> = "q1w2e3r4t5y6u7i8o9p0a@s*d+f-g=h/j#k(l)z'x:c\"v?b!n~m\\".chunked(2).associate { it.take(1) to it.drop(1) }

    /** [qwertyAlts] by key name, as Texts.alts has them (what a screen reader says of each corner). */
    val qwertyReadings: Map<String, String> = qwertyAlts.mapKeys { (k, _) -> "full/$k" }

    /**
     * The function keys' commands as built in ([SlotDefaults]), the long press's first up, with the full keyboard's letters':
     * a select all, c copy, x cut, v paste, z undo, y redo down; the space's edit menu, ⌫'s clear and restore. No firsts.
     */
    val presetMarks: Map<String, MarkContent> = (('a'..'z').map { KeyId("full/$it") } + listOf("space", "backspace").map(KeyId::function))
        .flatMap { id ->
            listOf(true, false).mapNotNull { up ->
                (SlotDefaults.of(id, up, firstUp = true) as? SlotDefaults.Slot.Command)
                    ?.let { "${id.name}:${if (up) "up" else "down"}" to MarkContent.Icon(SlotDefaults.icon(it.key)) }
            }
        }.toMap()

    /** qwerty's marks as built in (DefaultMarks: the app's on a new install), its letters' firsts up as [qwertyAlts]. */
    val qwertyMarks: Map<String, MarkContent> by lazy { DefaultMarks.shortcuts(qwerty, emptyMap()) }

    private val texts = Texts(imeName = "拼音", returnLabel = "换行", returnIcon = "enter", alts = qwertyReadings)

    /** A phone upright, 411 dp wide, the keyboard 274 dp tall, qwerty's marks as built in. */
    val portrait: Env by lazy { Env(1080.0, 720.0, DENSITY, input = Input(langKey = true), texts = texts, marks = MarkSettings(shortcuts = qwertyMarks)) }

    /** The same, the keyboard compact (text 0.9), 576 px: not the height setting's lowest (that is [Forms.compact]'s). */
    val compact: Env by lazy { portrait.copy(height = 576.0, compact = true) }

    /** A phone on its side, 914 dp: split (SplitKeyboard.Mode.AUTO splits from 600 dp). */
    val landscape: Env by lazy { portrait.copy(width = 2400.0, height = 528.0, landscape = true, split = true) }

    /** Floating, 300 dp wide, its text 0.85. */
    val floating: Env by lazy { portrait.copy(width = 787.5, height = 612.0, floating = 0.85) }

    val sizes: Map<String, Env> by lazy { mapOf("portrait" to portrait, "compact" to compact, "landscape" to landscape, "floating" to floating) }

    fun scene(env: Env, layout: Layout = qwerty, theme: Theme = materialLight): Scene =
        SceneBuilder(theme, env, FakeMeasurer).build(layout).ok()

    fun <T> Parsed<T>.ok(): T = getOr { fail("problems: $it") }
}
