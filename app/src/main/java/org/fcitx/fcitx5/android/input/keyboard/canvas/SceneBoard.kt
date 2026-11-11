/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.PackDrawGuard
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.KeySwipe
import org.fcitx.fcitx5.android.input.keyboard.SwipeSlotsCache
import org.fcitx.fcitx5.android.input.keyboard.swipeSpots
import org.fcitx.fcitx5.android.input.keyboard.swipeWays
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.popup.PopupOverridesCache
import org.fcitx.fcitx5.android.input.scene.SceneMarks
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.KeyStyleOver
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.section
import org.fcitx.fcitx5.android.scene.keys.KeyId
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

    private val inActivity: Boolean = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.any { it is Activity }

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
    // draws nothing and logs it). The old views are gone
    // The fallback's the built-in theme's whole: its look, its fonts, its measurer, as it draws itself
    private fun drawn(look: ThemeLook, width: Int, height: Int): KeyboardCanvasView.Drawn? {
        if (layout == null) return null
        // the keyboard drawn last is the one shown: the popups and cells follow its keys ([drawnTheme]);
        // not one drawn in an activity (the theme's preview in the settings), never the input method's
        if (!inActivity) shownBoard = layout.id
        drawn(theme, look, width, height)?.let { return it.also { if (!inActivity) keysFellBack(theme.name, look, layout.id, false) } }
        val builtIn = ThemeManager.builtin(if (theme.isDark) "pixel#dark" else "pixel#light")
        if (builtIn.name == theme.name) return null
        // the popups and panel cells fall back with this keyboard's keys while it is shown ([drawnTheme]);
        // a preview's keys are not the input method's
        if (!inActivity) keysFellBack(theme.name, look, layout.id, true)
        // logged once a look: asked again each time what shows changes
        if (firstFallBack(theme.name, look)) Timber.w("keyboard canvas: no scene of theme %s; the built-in %s's drawn", theme.name, builtIn.name)
        return drawn(builtIn, ThemeManager.shownLook(builtIn), width, height)
    }

    private fun drawn(of: Theme, look: ThemeLook, width: Int, height: Int): KeyboardCanvasView.Drawn? {
        val layout = layout ?: return null
        val sceneTheme = SceneTrace.section("theme") { SceneThemes.of(of, look) } ?: return null
        val measurer = measurer(of, look)
        return scene(layout, sceneTheme, look, measurer, width, height, (of as? Theme.Pack)?.let { ThemeManager.keyStyleShown.overPack })?.let { KeyboardCanvasView.Drawn(it, look, measurer.fonts, (of as? Theme.Pack)?.let(PackDrawGuard::key), PackIcons.of(of)) }
    }

    // the scene of [sceneTheme] ([look]'s): the last kept for the same values, or made; null, the engine refused it
    // [keyStyle]: the key style settings over a pack's own keys (a look's has them in it)
    private fun scene(layout: SceneLayout, sceneTheme: SceneTheme, look: ThemeLook, measurer: TextMeasurer, width: Int, height: Int, keyStyle: KeyStyleOver?): Scene? {
        // the board's id its theme's @keyboard= (060-render-p3 T5)
        val env = SceneTrace.section("env") { env(look, layout, width, height).copy(board = layout.id, keyStyle = keyStyle) }
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

    // what the marks' settings are made from (the slots and long presses compared as the same instances: the caches')
    private data class MarksFor(
        val slots: SwipeSlots,
        val ways: KeySwipe.Ways,
        val spots: KeyMarks.Spots,
        val charShortcuts: Boolean,
        val names: Set<String>,
        val popups: PopupOverrides,
    )

    companion object {
        // kept a process (main thread), as SceneThemes keeps the themes, for a keyboard made again (each
        // theme switch builds the input view): a look's measurer and the last KEPT_SCENES scenes made, so
        // a theme switched back to makes no scene again and measures no text again; what a switch allocates
        // more than the views' decides whether a GC stops its first frame (specs/app.md)
        private const val KEPT_SCENES = 4
        // a look's measurer, by the look and a pack's fonts (two packs of one look may name others):
        // as many as one kind of theme SceneThemes keeps (its looks' or its packs', 4 each), not the
        // scenes; with packs and built-ins alternating a measurer may go first and is measured anew
        private const val KEPT_LOOKS = 4
        private val measurers = lruMap<Pair<ThemeLook, Map<String, String>>, AndroidTextMeasurer>(KEPT_LOOKS)
        private val scenesKept = ArrayDeque<Made>()

        // the themes (by name and look) that made no scene, the built-in drawn instead: logged once each,
        // the last KEPT_FELL_BACK of them (a theme switched through many looks logs a look again, no more)
        private const val KEPT_FELL_BACK = 16
        private val fellBack = LinkedHashSet<Pair<String, ThemeLook>>()

        // the keyboards (by theme name, look and board id) whose keys the built-in draws now, the engine
        // refusing their scene though the theme converted: one keyboard's refused, another's not (the engine
        // makes a scene a layout at a time). The popups and cells follow the one shown ([drawnTheme])
        private val keysRefused = LinkedHashSet<Triple<String, ThemeLook, String>>()

        // the board whose keys were drawn last: the keyboard on screen, whose keys' popups show
        private var shownBoard: String? = null

        /** That the keys of [board] in [name]'s [look] are drawn in the built-in ([fell]) or their own now (the last [KEPT_FELL_BACK] kept). */
        @VisibleForTesting
        internal fun keysFellBack(name: String, look: ThemeLook, board: String, fell: Boolean) {
            val of = Triple(name, look, board)
            if (!fell) keysRefused.remove(of)
            else if (keysRefused.add(of) && keysRefused.size > KEPT_FELL_BACK) keysRefused.remove(keysRefused.first())
        }

        /** The keyboard shown now, as its keys being drawn says: a test's, for a keyboard not drawn here. */
        @VisibleForTesting
        internal fun shown(board: String?) {
            shownBoard = board
        }

        /** Whether [name] in [look] is a fall back not logged yet (the last [KEPT_FELL_BACK] kept). */
        @VisibleForTesting
        internal fun firstFallBack(name: String, look: ThemeLook): Boolean =
            fellBack.add(name to look).also { if (fellBack.size > KEPT_FELL_BACK) fellBack.remove(fellBack.first()) }

        // a layout's shortcut names, and the marks' settings as last made from their inputs: kept for the
        // scene asked again, the same instances making the env equal to the last at once
        private var namesMade: Pair<SceneLayout, Set<String>>? = null
        private var marksMade: Pair<MarksFor, MarkSettings>? = null

        private fun measurer(of: Theme, look: ThemeLook): AndroidTextMeasurer =
            measurers.getOrPut(look to (of as? Theme.Pack)?.drawn?.fonts.orEmpty()) { AndroidTextMeasurer(SceneFonts.of(of, look)) }

        /**
         * The theme a popup or a panel's cell draws in: [theme], or where the engine makes no theme of
         * it or the keys of [board] (the keyboard shown now) fell back (the engine refusing their scene), the
         * built-in of the same lightness, as the keys are (060-render-p2 U8; logged once a look, as theirs).
         * Asked as they draw: keys, popups and cells so draw in one theme, the keyboard switched or not.
         */
        fun drawnTheme(theme: Theme, board: String? = shownBoard): Theme {
            val look = ThemeManager.shownLook(theme)
            val refused = board != null && Triple(theme.name, look, board) in keysRefused
            if (!refused && SceneThemes.of(theme, look) != null) return theme
            val builtIn = ThemeManager.builtin(if (theme.isDark) "pixel#dark" else "pixel#light")
            if (builtIn.name == theme.name) return theme
            if (firstFallBack(theme.name, look)) Timber.w("scene: no theme of %s; the built-in %s's drawn", theme.name, builtIn.name)
            return builtIn
        }

        /** [layout]'s keys' shortcut names, its fragments' too. */
        fun names(layout: SceneLayout): Set<String> = namesMade?.takeIf { it.first === layout }?.second
            ?: SceneMarks.names(layout.layout, layout.fragments.values).also { namesMade = layout to it }

        /**
         * The marks' settings of [layout]'s keys as the settings are now (each key's two slots, 上滑 and
         * 下滑 on, where each draws, the long presses set by a key's name), a character key's shortcut
         * drawn where [charShortcuts].
         */
        fun marks(layout: SceneLayout, charShortcuts: Boolean): MarkSettings {
            val of = MarksFor(SwipeSlotsCache.current, swipeWays(), swipeSpots(), charShortcuts, names(layout), PopupOverridesCache.byName.current)
            return marksMade?.takeIf { it.first == of }?.second
                ?: SceneMarks.settings(of.slots, of.ways, of.spots.symbol, of.spots.shortcut, of.charShortcuts, of.names, firsts(of.popups, of.names))
                    .also { marksMade = of to it }
        }

        /**
         * The firsts of the long presses [popups] set by name of [names]' keys (their one names; key_popups has a
         * function key's bare, `comma`): null where one is turned off.
         */
        fun firsts(popups: PopupOverrides, names: Collection<String>): Map<String, String?> {
            val labels = popups.labels.associateBy { KeyId.of(it).name }
            return names.mapNotNull { n -> labels[n]?.let { n to popups.resolve(it, null)?.firstOrNull() } }.toMap()
        }

        /** The scenes kept let go, the next asked made anew as things are: a measure of what making one costs (KeyboardPerfBaselineTest). */
        fun forgetScenes() = scenesKept.clear()

        /** The scenes and the looks' measurers let go: a theme's file written over under the same path ([SceneThemes.forgetAll]). */
        fun forgetLooks() {
            scenesKept.clear()
            measurers.clear()
            // a theme written again may make its keys' scene now: its popups and cells its own until the keys say
            keysRefused.clear()
        }
    }
}
