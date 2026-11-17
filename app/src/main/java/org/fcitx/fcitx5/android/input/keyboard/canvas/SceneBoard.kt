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

/**
 * What every keyboard the scene engine draws shares (060-render-p2 S4): its canvas ([view]), the
 * scene of its [layout] in its [theme] ([drawn]), the built-in theme's where the theme makes none
 * (U8), and the scenes and measurers kept a process. A keyboard gives its layout and what its keys
 * show now as the engine's environment ([env]: SceneEnvs.of with its own Input, texts and marks),
 * and asks for a new scene ([KeyboardCanvasView.invalidateScene]) whenever any of that changes.
 * BaseKeyboard.drawKeysWithScene puts one over a keyboard. A [layout] that did not read (null) draws
 * nothing: no scene, no key (SceneLayouts logged it). A theme pack's layout the engine refuses in some
 * environment draws the app's own of the same id there ([ownLayout]).
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
        val keyStyle = (of as? Theme.Pack)?.let { ThemeManager.keyStyleShown.overPack }
        val using = measurerFor?.invoke() ?: measurer
        val env = envOf(look, layout, width, height, keyStyle)
        // made as it is asked: with nothing refused (every theme but a pack whose layout was), none is
        val refused by lazy(LazyThreadSafetyMode.NONE) { RefusedIn(layout, env) }
        // refused here before: not made again to be refused again each time what the keys show changes (R25r2 F7833).
        // The app's own asks its env after the pack's, so the keyboard reads the layout drawn (BaseKeyboard.keyMarks)
        val scene = (if (layoutsRefused.isNotEmpty() && refusedBefore(refused)) null else scene(layout, sceneTheme, using, env))
            ?: ownLayout(layout)?.let { own ->
                scene(own, sceneTheme, using, envOf(look, own, width, height, keyStyle))?.also { refusedHere(refused) }
            }
        return scene?.let { KeyboardCanvasView.Drawn(it, look, measurer.fonts, (of as? Theme.Pack)?.let(PackDrawGuard::key), PackIcons.of(of)) }
    }

    // a theme pack's layout the engine refused here (a row too wide in this form, a `when=` leaving a row empty: what
    // reading it cannot see) draws the app's own of its id, as one that does not read does (SceneLayouts.of), logged;
    // the scene's env asks the keyboard of that layout, so the keys touched are the ones drawn (BaseKeyboard.keyMarks).
    // Null where [layout] is the app's already: nothing else to draw
    private fun ownLayout(layout: SceneLayout): SceneLayout? =
        SceneLayouts.builtIn(context, layout.base)?.takeIf { it !== layout }

    // what the keys of [layout] show now as the engine's environment, the board's id its theme's @keyboard= (060-render-p3 T5),
    // a layout of the user's the board it is based on too (the theme's styles of that board its, 061-custom-layout §10)
    // [keyStyle]: the key style settings over a pack's own keys (a look's has them in it)
    private fun envOf(look: ThemeLook, layout: SceneLayout, width: Int, height: Int, keyStyle: KeyStyleOver?): Env =
        SceneTrace.section("env") { env(look, layout, width, height).copy(board = layout.id, base = layout.base.takeIf { it != layout.id }, keyStyle = keyStyle) }

    // the scene of [sceneTheme] in [env] ([layout]'s): the last kept for the same values, or made; null, the engine refused it
    private fun scene(layout: SceneLayout, sceneTheme: SceneTheme, measurer: TextMeasurer, env: Env): Scene? {
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

    // a layout as read (the same instance, SceneLayouts': one a pack and id) in what of an env its rows and their
    // places are made from: the size, the form, the field, the input method, typing, Shift, all a `when=` asks
    // (Env.facts). Not what the keys show besides (the texts, the marks, the keys' states: no row comes or goes by
    // them), so a refusal is remembered through every key typed (R25r2 F7833), and not for a field or a state it
    // did not happen in (R25r3 F7922: by the size alone, a layout refused in one field drew the app's own in all)
    @VisibleForTesting
    internal class RefusedIn(val layout: SceneLayout, env: Env) {
        val placing: Env = env.copy(texts = NO_TEXTS, marks = NO_MARKS, states = emptyMap())

        override fun equals(other: Any?): Boolean = other is RefusedIn && layout === other.layout && placing == other.placing

        override fun hashCode(): Int = System.identityHashCode(layout) * 31 + placing.hashCode()
    }

    // what a resolver is made from: the layout, lists and slots compared as the same instances (the caches'), the
    // lists' version too, and hashed so (none of their contents hashed each time a resolver is asked for)
    private class ResolverFor(val layout: SceneLayout, val variant: KeyboardVariant, val listsVersion: Int, val lists: KeyLists, val slots: SwipeSlots) {
        override fun equals(other: Any?): Boolean = other is ResolverFor && layout === other.layout && variant == other.variant &&
            listsVersion == other.listsVersion && lists === other.lists && slots === other.slots

        override fun hashCode(): Int =
            ((System.identityHashCode(layout) * 31 + variant.hashCode()) * 31 + listsVersion) * 31 + System.identityHashCode(slots)
    }

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

        private val NO_TEXTS = Texts()
        private val NO_MARKS = MarkSettings()

        // the theme packs' layouts (as read: one a pack and id, read anew as the pack changes) the engine refused in
        // an environment ([RefusedIn]), the app's own making a scene there: its own drawn from then on, the pack's
        // not made again each time
        // what the keys show changes (most keys typed), and logged once while remembered. Whatever the
        // theme: one whose every scene is refused has the app's own refused too, and is not remembered
        // R25r4 F7976: one an environment (each field, Shift's state, typing or not …), so more than a theme's looks:
        // the last KEPT_REFUSED used kept, one asked again moved last, so the states in use are never let go of and
        // made, refused and logged again
        @VisibleForTesting
        internal const val KEPT_REFUSED = 64
        private val layoutsRefused = LinkedHashSet<RefusedIn>()

        // whether [at] is remembered refused, kept as the last used if so
        private fun refusedBefore(at: RefusedIn): Boolean = layoutsRefused.remove(at).also { if (it) layoutsRefused.add(at) }

        @VisibleForTesting
        internal fun refusedHere(at: RefusedIn) {
            if (!layoutsRefused.add(at)) return
            if (layoutsRefused.size > KEPT_REFUSED) layoutsRefused.remove(layoutsRefused.first())
            // the size in whole pixels, as a screen's is said (Env's are Double)
            Timber.w("keyboard canvas: the pack's layout %s refused at %d×%d; the app's own drawn", at.layout.id, at.placing.width.roundToInt(), at.placing.height.roundToInt())
        }

        /** The refusals remembered, the least recently used first ([KEPT_REFUSED] of them at most). */
        @VisibleForTesting
        internal val refusals: Collection<RefusedIn> get() = layoutsRefused.toList()

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
        // one a layout the IME and the settings page ask: each keyboard, the number pad, the symbol, emoji and
        // kaomoji panels, emoji search, the side column, and the settings page's key caps besides; one evicted is
        // made again (every key resolved anew, its marks lost), so room for all of them, not the last few scenes'
        private const val KEPT_RESOLVERS = 16
        private val resolvers = lruMap<ResolverFor, KeyResolver>(KEPT_RESOLVERS)

        /**
         * A test's measurer in place of the fonts' (a fixed width a character: Robolectric measures no text), the
         * keyboard's and the settings page's ([org.fcitx.fcitx5.android.ui.main.settings.SwipeFates]) alike: null, the fonts'.
         */
        @VisibleForTesting
        var measurerFor: (() -> TextMeasurer)? = null

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
         * [layout]'s keys' lists, swipes and marks for the input method [im] (KeyboardVariants.of) as the settings
         * are now (the user's lists, the swipes' slots): one resolver kept for each of the last few asked, so
         * keyboards switched between make none again (the least recently asked dropped). A keyboard keeps its own
         * while nothing it is made of changed (KeyResolving), so this is asked as that changes, not on each touch.
         * [slots] the settings' editor's, being edited. Main thread only, as the resolvers' caches are not guarded
         * (KeyResolver): a scene made ahead on another thread asks for none.
         */
        @MainThread
        fun resolver(layout: SceneLayout, im: String, nineLetters: Map<Char, String>? = null, slots: SwipeSlots = SwipeSlotsCache.current): KeyResolver =
            resolver(layout, KeyboardVariants.of(layout.layout, im, EngineState.shuangpinScheme, nineLetters, layout.base), slots)

        /** [layout]'s resolver for [variant] as given: the long press editor's (060-row-unlock §2: 18 键's L with its `:`), as [resolver]. */
        @MainThread
        fun resolver(layout: SceneLayout, variant: KeyboardVariant, slots: SwipeSlots = SwipeSlotsCache.current): KeyResolver {
            val lists = KeyListsCache.user.current
            val of = ResolverFor(layout, variant, KeyListsCache.user.version, lists, slots)
            return resolvers.getOrPut(of) {
                KeyResolver(of.variant, of.lists, of.slots, layout.nodes, KeyClock)
            }
        }

        /**
         * The marks' settings of [layout]'s keys for [im] in [state] (060-keys-unify §1.3: each key's first and
         * slots as the resolver has them), a character key's shortcut drawn where [charShortcuts].
         */
        fun marks(layout: SceneLayout, charShortcuts: Boolean, im: String, state: KeyState, nineLetters: Map<Char, String>? = null): MarkSettings =
            marks(resolver(layout, im, nineLetters), charShortcuts, state)

        /** [resolver]'s marks in [state], a character key's shortcut drawn where [charShortcuts]: a keyboard's own resolver's (KeyResolving). */
        fun marks(resolver: KeyResolver, charShortcuts: Boolean, state: KeyState): MarkSettings {
            val marks = resolver.marks(state)
            if (marks.charShortcuts == charShortcuts) return marks
            // the copy made of the same marks given again: the scene's env then equal to the last at once
            marksCopied?.takeIf { it.first === marks }?.let { return it.second }
            return marks.copy(charShortcuts = charShortcuts).also { marksCopied = marks to it }
        }

        private var marksCopied: Pair<MarkSettings, MarkSettings>? = null

        /**
         * The scenes kept let go, the next asked made anew as things are: a measure of what making one costs
         * (KeyboardPerfBaselineTest). The packs' layouts remembered refused too ([layoutsRefused]): which layout is
         * drawn is asked of the engine again, a test's keyboard not drawing by what another test's was refused.
         */
        fun forgetScenes() {
            scenesKept.clear()
            layoutsRefused.clear()
        }

        /** The scenes and the looks' measurers let go: a theme's file written over under the same path ([SceneThemes.forgetAll]). */
        fun forgetLooks() {
            forgetScenes()
            measurers.clear()
            // a theme written again may make its keys' scene now: its popups and cells its own until the keys say
            keysRefused.clear()
        }
    }
}
