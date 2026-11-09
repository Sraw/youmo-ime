/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.content.Context
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutsCache
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers
import org.fcitx.fcitx5.android.input.keyboard.markSpots
import org.fcitx.fcitx5.android.input.scene.SceneMarks
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.section
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import timber.log.Timber
import org.fcitx.fcitx5.android.scene.theme.Theme as SceneTheme

/**
 * What every keyboard the scene engine draws shares (060-render-p2 S4): its canvas ([view]), the
 * scene of its [layout] in its [theme] ([drawn]), the built-in theme's where the theme makes none
 * (U8), and the scenes and measurers kept a process. A keyboard gives its layout and what its keys
 * show now as the engine's environment ([env]: SceneEnvs.of with its own Input, texts and marks),
 * and asks for a new scene ([KeyboardCanvasView.invalidateScene]) whenever any of that changes.
 * BaseKeyboard.drawKeysWithScene puts one over a keyboard. A [layout] that did not read (null) draws
 * nothing: no scene, no key (SceneLayouts logged it).
 */
class SceneBoard(
    private val context: Context,
    private val theme: Theme,
    val layout: SceneLayout?,
    /** What the keys of [layout] show now, in [look] at [width] × [height]: the keyboard's state as the engine's environment. */
    private val env: (look: ThemeLook, layout: SceneLayout, width: Int, height: Int) -> Env,
) {

    /** The scenes the engine made for this keyboard: a test's count of what was not kept. */
    var scenesMade = 0
        private set

    /** The canvas drawing the keys of this board, [key] the key wired for a node (what a touch on it and a screen reader's action do). */
    fun view(
        images: BoardImages,
        string: (String) -> String? = { null },
        key: (KeyNode) -> SceneKey?,
        built: (Scene?) -> Unit = {},
    ): KeyboardCanvasView {
        val look = ThemeManager.shownLook(theme)
        return KeyboardCanvasView(context, images, object : KeyboardCanvasView.Host {
            override fun scene(width: Int, height: Int) = drawn(look, width, height)
            override fun key(node: KeyNode) = key(node)
            override val board get() = layout?.id
            override fun built(scene: Scene?) = built(scene)
            override fun string(name: String) = string(name)
        })
    }

    // 060-render-p2 U8: a scene the theme makes none of (the engine refusing it, or what the theme made)
    // is the built-in theme's of the same lightness, logged; none of that either, nothing (the canvas
    // draws nothing and logs it). Never the views again: they go in S8
    // The fallback's the built-in theme's whole: its look, its fonts, its measurer, as it draws itself
    private fun drawn(look: ThemeLook, width: Int, height: Int): KeyboardCanvasView.Drawn? {
        if (layout == null) return null
        drawn(theme, look, width, height)?.let { return it }
        val builtIn = if (theme.isDark) ThemePreset.PixelDark else ThemePreset.PixelLight
        if (builtIn.name == theme.name) return null
        // logged once a look: asked again each time what shows changes
        if (firstFallBack(theme.name, look)) Timber.w("keyboard canvas: no scene of theme %s; the built-in %s's drawn", theme.name, builtIn.name)
        return drawn(builtIn, ThemeManager.shownLook(builtIn), width, height)
    }

    private fun drawn(of: Theme, look: ThemeLook, width: Int, height: Int): KeyboardCanvasView.Drawn? {
        val layout = layout ?: return null
        val sceneTheme = SceneTrace.section("theme") { SceneThemes.of(look, of.name, of.isDark) } ?: return null
        val measurer = measurer(look)
        return scene(layout, sceneTheme, look, measurer, width, height)?.let { KeyboardCanvasView.Drawn(it, look, measurer.fonts) }
    }

    // the scene of [sceneTheme] ([look]'s): the last kept for the same values, or made; null, the engine refused it
    private fun scene(layout: SceneLayout, sceneTheme: SceneTheme, look: ThemeLook, measurer: TextMeasurer, width: Int, height: Int): Scene? {
        val env = SceneTrace.section("env") { env(look, layout, width, height) }
        // the same values make the same scene: one asked again for nothing that shows (the first key typed,
        // the word committed: Return's look unchanged) is the last one, not made again in the frame
        scenesKept.lastOrNull { it.theme === sceneTheme && it.layout === layout && it.measurer === measurer && it.env == env }?.let { return it.scene }
        scenesMade++
        // the theme's looks worked out for the last scene kept for the next: Shift's makes only what it changed
        val builder = SceneBuilder(sceneTheme, env, measurer, phases = SceneTrace, cascades = SceneThemes.cascades(sceneTheme))
        return when (val built = builder.build(layout.layout, layout.fragments)) {
            is Parsed.Ok -> built.value.also {
                scenesKept.addLast(Made(sceneTheme, layout, measurer, env, it))
                if (scenesKept.size > KEPT_SCENES) scenesKept.removeFirst()
            }
            is Parsed.Failed -> null.also { Timber.e("keyboard canvas: the engine refused the scene of %s: %s", layout.id, built.problems) }
        }
    }

    private class Made(val theme: SceneTheme, val layout: SceneLayout, val measurer: TextMeasurer, val env: Env, val scene: Scene)

    // what the marks' settings are made from (KeyShortcuts compared as the same instance: the cache's)
    private data class MarksFor(
        val shortcuts: KeyShortcuts,
        val layers: SwipeLayers,
        val spots: KeyMarks.Spots,
        val charShortcuts: Boolean,
        val names: Set<String>,
    )

    companion object {
        // kept a process (main thread), as SceneThemes keeps the themes, for a keyboard made again (each
        // theme switch builds the input view): a look's measurer and the last KEPT_SCENES scenes made, so
        // a theme switched back to makes no scene again and measures no text again; what a switch allocates
        // more than the views' decides whether a GC stops its first frame (specs/app.md)
        private const val KEPT_SCENES = 4
        private val measurers = LinkedHashMap<ThemeLook, AndroidTextMeasurer>(KEPT_SCENES, 0.75f, true)
        private val scenesKept = ArrayDeque<Made>()

        // the themes (by name and look) that made no scene, the built-in drawn instead: logged once each,
        // the last KEPT_FELL_BACK of them (a theme switched through many looks logs a look again, no more)
        private const val KEPT_FELL_BACK = 16
        private val fellBack = LinkedHashSet<Pair<String, ThemeLook>>()

        /** Whether [name] in [look] is a fall back not logged yet (the last [KEPT_FELL_BACK] kept). */
        @VisibleForTesting
        internal fun firstFallBack(name: String, look: ThemeLook): Boolean =
            fellBack.add(name to look).also { if (fellBack.size > KEPT_FELL_BACK) fellBack.remove(fellBack.first()) }

        // a layout's shortcut names, and the marks' settings as last made from their inputs: kept for the
        // scene asked again, the same instances making the env equal to the last at once
        private var namesMade: Pair<SceneLayout, Set<String>>? = null
        private var marksMade: Pair<MarksFor, MarkSettings>? = null

        private fun measurer(look: ThemeLook): AndroidTextMeasurer = measurers.getOrPut(look) { AndroidTextMeasurer(SceneFonts(look)) }
            .also { if (measurers.size > KEPT_SCENES) measurers.remove(measurers.keys.first()) }

        /** [layout]'s keys' shortcut names, its fragments' too. */
        fun names(layout: SceneLayout): Set<String> = namesMade?.takeIf { it.first === layout }?.second
            ?: SceneMarks.names(layout.layout, layout.fragments.values).also { namesMade = layout to it }

        /**
         * The marks' settings of [layout]'s keys as the settings are now (the user's shortcuts, the
         * swipe layers, where marks go), a character key's shortcut drawn where [charShortcuts].
         */
        fun marks(layout: SceneLayout, charShortcuts: Boolean): MarkSettings {
            val of = MarksFor(KeyShortcutsCache.current, AppPrefs.getInstance().keyboard.swipeLayers.getValue().layers, markSpots(), charShortcuts, names(layout))
            return marksMade?.takeIf { it.first == of }?.second
                ?: SceneMarks.settings(of.shortcuts, of.layers, of.spots, of.charShortcuts, of.names).also { marksMade = of to it }
        }

        /** The scenes kept let go, the next asked made anew as things are: a measure of what making one costs (KeyboardPerfBaselineTest). */
        fun forgetScenes() = scenesKept.clear()
    }
}
