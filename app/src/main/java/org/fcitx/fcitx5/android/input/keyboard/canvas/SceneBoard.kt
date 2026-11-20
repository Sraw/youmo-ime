/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.PackDrawGuard
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.input.keyboard.KeyClock
import org.fcitx.fcitx5.android.input.keyboard.KeyboardVariants
import org.fcitx.fcitx5.android.input.keyboard.SwipeSlotsCache
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.KeyListsCache
import org.fcitx.fcitx5.android.input.keys.KeyResolver
import org.fcitx.fcitx5.android.input.keys.KeyState
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
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.engine.section
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import timber.log.Timber
import kotlin.math.roundToInt
import org.fcitx.fcitx5.android.scene.theme.Theme as SceneTheme

/** A theme pack's layout the engine refuses in some environment draws the app's own of the same id there ([ownLayout]). */
class SceneBoard(
    private val context: Context,
    private val theme: Theme,
    val layout: SceneLayout?,
    private val env: (look: ThemeLook, layout: SceneLayout, width: Int, height: Int) -> Env,
) {

    private val inActivity: Boolean = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.any { it is Activity }

    var scenesMade = 0
        private set

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

    // a scene the theme makes none of is the built-in theme's of the same lightness, logged, whole: its
    // look, fonts and measurer. None of that either: nothing drawn
    private fun drawn(look: ThemeLook, width: Int, height: Int): KeyboardCanvasView.Drawn? {
        if (layout == null) return null
        if (!inActivity) shownBoard = layout.id
        drawn(theme, look, width, height)?.let { return it.also { if (!inActivity) keysFellBack(theme.name, look, layout.id, false) } }
        val builtIn = ThemeManager.builtin(if (theme.isDark) "pixel#dark" else "pixel#light")
        if (builtIn.name == theme.name) return null
        // the popups and panel cells fall back with the shown keyboard's keys ([drawnTheme]); not a preview's
        if (!inActivity) keysFellBack(theme.name, look, layout.id, true)
        if (firstFallBack(theme.name, look)) Timber.w("keyboard canvas: no scene of theme %s; the built-in %s's drawn", theme.name, builtIn.name)
        return drawn(builtIn, ThemeManager.shownLook(builtIn), width, height)
    }

    private fun drawn(of: Theme, look: ThemeLook, width: Int, height: Int): KeyboardCanvasView.Drawn? {
        val layout = layout ?: return null
        val sceneTheme = SceneTrace.section("theme") { SceneThemes.of(of, look) } ?: return null
        val measurer = measurer(of, look)
        val keyStyle = (of as? Theme.Pack)?.let { ThemeManager.keyStyleShown.overPack }
        val using = measurerFor?.invoke() ?: measurer
        val env = envOf(look, layout, width, height, keyStyle)
        val refused by lazy(LazyThreadSafetyMode.NONE) { RefusedIn(layout, env) }
        // refused here before: not made just to be refused again. The app's own asks its env after the pack's,
        // so the keyboard reads the layout drawn (BaseKeyboard.keyMarks)
        val scene = (if (layoutsRefused.isNotEmpty() && refusedBefore(refused)) null else scene(layout, sceneTheme, using, env))
            ?: ownLayout(layout)?.let { own ->
                scene(own, sceneTheme, using, envOf(look, own, width, height, keyStyle))?.also { refusedHere(refused) }
            }
        return scene?.let { KeyboardCanvasView.Drawn(it, look, measurer.fonts, (of as? Theme.Pack)?.let(PackDrawGuard::key), PackIcons.of(of)) }
    }

    // refused here (a row too wide in this form, a `when=` leaving a row empty: what reading cannot see).
    // Null where [layout] is the app's already
    private fun ownLayout(layout: SceneLayout): SceneLayout? =
        SceneLayouts.builtIn(context, layout.fallback)?.takeIf { it !== layout }

    // the board's id is its theme's @keyboard=; a user's layout uses the board it is based on.
    // [keyStyle]: the key style settings over a pack's own keys
    private fun envOf(look: ThemeLook, layout: SceneLayout, width: Int, height: Int, keyStyle: KeyStyleOver?): Env =
        SceneTrace.section("env") { env(look, layout, width, height).copy(board = layout.id, base = layout.stylesOf, keyStyle = keyStyle) }

    private fun scene(layout: SceneLayout, sceneTheme: SceneTheme, measurer: TextMeasurer, env: Env): Scene? {
        // the same values make the same scene: asked again for nothing that shows, it is the last one
        scenesKept.lastOrNull { it.theme === sceneTheme && it.layout === layout && it.measurer === measurer && it.env == env }?.let { return it.scene }
        scenesMade++
        // looks worked out for the last scene are kept for the next: Shift's makes only what it changed
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

    // a layout (same instance) in the facts its rows come from (Env.facts: size, form, field, input method,
    // typing, Shift, all a `when=` asks), not what keys show: a refusal is remembered through typing, and
    // not carried to a field or state it did not happen in
    @VisibleForTesting
    internal class RefusedIn(val layout: SceneLayout, env: Env) {
        val placing: Env = env.copy(texts = NO_TEXTS, marks = NO_MARKS, states = emptyMap())

        override fun equals(other: Any?): Boolean = other is RefusedIn && layout === other.layout && placing == other.placing

        override fun hashCode(): Int = System.identityHashCode(layout) * 31 + placing.hashCode()
    }

    private class ResolverFor(val layout: SceneLayout, val variant: KeyboardVariant, val listsVersion: Int, val lists: KeyLists, val slots: SwipeSlots) {
        override fun equals(other: Any?): Boolean = other is ResolverFor && layout === other.layout && variant == other.variant &&
            listsVersion == other.listsVersion && lists === other.lists && slots === other.slots

        override fun hashCode(): Int =
            ((System.identityHashCode(layout) * 31 + variant.hashCode()) * 31 + listsVersion) * 31 + System.identityHashCode(slots)
    }

    companion object {
        // a theme switched back to makes no scene and measures no text again: what a switch allocates decides whether a
        // GC stops its first frame
        private const val KEPT_SCENES = 4
        // by look and a pack's fonts (two packs of one look may name others): as many as SceneThemes keeps
        // of one kind (4), not the scenes
        private const val KEPT_LOOKS = 4
        private val measurers = lruMap<Pair<ThemeLook, Map<String, String>>, AndroidTextMeasurer>(KEPT_LOOKS)
        private val scenesKept = ArrayDeque<Made>()

        private const val KEPT_FELL_BACK = 16
        private val fellBack = LinkedHashSet<Pair<String, ThemeLook>>()

        // a scene is made a layout at a time, so one keyboard of a theme may be refused and another not; the
        // popups and cells follow the one shown ([drawnTheme])
        private val keysRefused = LinkedHashSet<Triple<String, ThemeLook, String>>()

        private val NO_TEXTS = Texts()
        private val NO_MARKS = MarkSettings()

        // one per environment, so more than a theme's looks: the last KEPT_REFUSED used, so states in use are never dropped and redone
        @VisibleForTesting
        internal const val KEPT_REFUSED = 64
        private val layoutsRefused = LinkedHashSet<RefusedIn>()

        private fun refusedBefore(at: RefusedIn): Boolean = layoutsRefused.remove(at).also { if (it) layoutsRefused.add(at) }

        @VisibleForTesting
        internal fun refusedHere(at: RefusedIn) {
            if (!layoutsRefused.add(at)) return
            if (layoutsRefused.size > KEPT_REFUSED) layoutsRefused.remove(layoutsRefused.first())
            Timber.w("keyboard canvas: the pack's layout %s refused at %d×%d; the app's own drawn", at.layout.id, at.placing.width.roundToInt(), at.placing.height.roundToInt())
        }

        @VisibleForTesting
        internal val refusals: Collection<RefusedIn> get() = layoutsRefused.toList()

        private var shownBoard: String? = null

        @VisibleForTesting
        internal fun keysFellBack(name: String, look: ThemeLook, board: String, fell: Boolean) {
            val of = Triple(name, look, board)
            if (!fell) keysRefused.remove(of)
            else if (keysRefused.add(of) && keysRefused.size > KEPT_FELL_BACK) keysRefused.remove(keysRefused.first())
        }

        @VisibleForTesting
        internal fun shown(board: String?) {
            shownBoard = board
        }

        @VisibleForTesting
        internal fun firstFallBack(name: String, look: ThemeLook): Boolean =
            fellBack.add(name to look).also { if (fellBack.size > KEPT_FELL_BACK) fellBack.remove(fellBack.first()) }

        private var namesMade: Pair<SceneLayout, Set<String>>? = null
        // one per layout the IME and settings ask (keyboards, pads, panels, emoji search, the side column, key
        // caps); an evicted one is remade (every key resolved anew), so room for all of them
        private const val KEPT_RESOLVERS = 16
        private val resolvers = lruMap<ResolverFor, KeyResolver>(KEPT_RESOLVERS)

        @VisibleForTesting
        var measurerFor: (() -> TextMeasurer)? = null

        private fun measurer(of: Theme, look: ThemeLook): AndroidTextMeasurer =
            measurers.getOrPut(look to (of as? Theme.Pack)?.drawn?.fonts.orEmpty()) { AndroidTextMeasurer(SceneFonts.of(of, look)) }

        /**
         * [theme], or the built-in of the same lightness where the engine makes no theme of it or the shown
         * [board]'s keys fell back. Asked as they draw, so keys, popups and cells draw in one theme.
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

        fun names(layout: SceneLayout): Set<String> = namesMade?.takeIf { it.first === layout }?.second
            ?: SceneMarks.names(layout.layout, layout.fragments.values).also { namesMade = layout to it }

        /**
         * One resolver kept for each of the last few asked, so switching keyboards makes none again. A
         * keyboard keeps its own while nothing it is made of changed (KeyResolving): asked as that changes,
         * not on each touch. Main thread only: the resolvers' caches are not guarded.
         */
        @MainThread
        fun resolver(layout: SceneLayout, im: String, nineLetters: Map<Char, String>? = null, slots: SwipeSlots = SwipeSlotsCache.current): KeyResolver =
            resolver(layout, KeyboardVariants.of(layout.layout, im, EngineState.shuangpinScheme, nineLetters, layout.fallback), slots)

        @MainThread
        fun resolver(layout: SceneLayout, variant: KeyboardVariant, slots: SwipeSlots = SwipeSlotsCache.current): KeyResolver {
            val lists = KeyListsCache.user.current
            val of = ResolverFor(layout, variant, KeyListsCache.user.version, lists, slots)
            return resolvers.getOrPut(of) {
                KeyResolver(of.variant, of.lists, of.slots, layout.nodes, KeyClock)
            }
        }

        fun marks(layout: SceneLayout, charShortcuts: Boolean, im: String, state: KeyState, nineLetters: Map<Char, String>? = null): MarkSettings =
            marks(resolver(layout, im, nineLetters), charShortcuts, state)

        fun marks(resolver: KeyResolver, charShortcuts: Boolean, state: KeyState): MarkSettings {
            val marks = resolver.marks(state)
            if (marks.charShortcuts == charShortcuts) return marks
            marksCopied?.takeIf { it.first === marks }?.let { return it.second }
            return marks.copy(charShortcuts = charShortcuts).also { marksCopied = marks to it }
        }

        private var marksCopied: Pair<MarkSettings, MarkSettings>? = null

        /**
         * For KeyboardPerfBaselineTest to measure a scene's cost. The remembered refusals go too, so one
         * test's keyboard does not draw by another's refusal.
         */
        fun forgetScenes() {
            scenesKept.clear()
            layoutsRefused.clear()
        }

        fun forgetLooks() {
            forgetScenes()
            measurers.clear()
            // a theme written again may make its keys' scene now
            keysRefused.clear()
        }
    }
}
