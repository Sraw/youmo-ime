/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationStartKeys
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.floating.FloatingHandle
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.CodeRowHeight
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightCap
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyles
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.AndroidTextMeasurer
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneFonts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.input.keyboard.keyboardCompact
import org.fcitx.fcitx5.android.input.keyboard.KeyboardNames
import org.fcitx.fcitx5.android.input.keyboard.keyboardMakers
import org.fcitx.fcitx5.android.input.keyboard.lettersOf
import org.fcitx.fcitx5.android.input.keyboard.nameOf
import org.fcitx.fcitx5.android.input.keyboard.swipeSpots
import org.fcitx.fcitx5.android.input.keyboard.swipeWays
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.MarkFate
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.scene.theme.Cascades
import timber.log.Timber
import org.fcitx.fcitx5.android.input.keys.KeyState as KeysState
import org.fcitx.fcitx5.android.scene.theme.Theme as SceneTheme

/** Scenes are built on the page's one thread (its measurer is no other's); [note] says what the page shows. */
object SwipeFates {

    class Job internal constructor(
        val label: String,
        val landscape: Boolean,
        internal val theme: SceneTheme,
        internal val env: Env,
        internal val layout: SceneLayout,
        internal val measurer: TextMeasurer,
        // a table's every root on the letters: its letters' ways drawn nowhere
        internal val rootsShown: Boolean,
        // the page's own: only its thread works them out, not SceneThemes' shared ones
        internal val cascades: Cascades,
        internal val ways: Set<Boolean>,
    )

    /** Fates by shortcut name; null, no scene made. [roots]: keys a shape code's roots fill, their ways drawn nowhere. */
    class Seen(
        val label: String,
        val landscape: Boolean,
        val fates: Map<String, Map<Boolean, MarkFate>>?,
        val ways: Set<Boolean> = setOf(true, false),
        val roots: Set<String> = emptySet(),
    )

    private class Shown(val label: String, val keyboard: BaseKeyboard, val ime: InputMethodEntry?, val codeRow: ImKeyboards.Layout, val rootsShown: Boolean)

    // [custom]: the user layout it draws, if any
    private class Made(val ime: InputMethodEntry?, val keyboard: BaseKeyboard, val layout: ImKeyboards.Layout, val roots: Boolean, val custom: String? = null)

    class Page(private val ctx: Context, imes: List<InputMethodEntry>, tableLabels: Map<String, String?>) {
        private val theme: Theme = ThemeManager.activeTheme
        private val look = ThemeManager.shownLook(theme)

        // the page's thread's alone: a measurer keeps a Paint and what it measured
        private val measurer = measurer(theme, look)

        // they listen to the settings while held, so let go with the page ([release])
        private var shown: List<Shown> = shown(imes, tableLabels)

        // made on the main thread, used on the page's thread alone
        private var cascades: Cascades? = null

        fun release() {
            shown = emptyList()
        }

        private fun shown(imes: List<InputMethodEntry>, tableLabels: Map<String, String?>): List<Shown> {
            val makers = keyboardMakers({ ctx }, { theme })
            val prefs = AppPrefs.getInstance().keyboard
            val scheme = EngineState.shuangpinScheme
            val on = imes.ifEmpty { listOf(null) }
            val keyboards = HashMap<String, BaseKeyboard>()
            val chosen = prefs.imKeyboards()
            val made = on.mapNotNull { ime ->
                val layout = ImKeyboards.layoutOf(ime?.uniqueName ?: InputMethodCatalog.ENGLISH, chosen, scheme)
                val name = ime?.let { lettersOf(it.uniqueName, chosen, scheme) } ?: nameOf(layout, scheme)
                val keyboard = keyboards[name] ?: makers[KeyboardNames.base(name)]?.invoke(KeyboardNames.custom(name))?.also { keyboards[name] = it } ?: return@mapNotNull null
                // roots fill only the full keyboard's letters
                val roots = layout == ImKeyboards.Layout.TEXT && ime != null && ime.uniqueName in InputMethodCatalog.TABLES &&
                    KeyLabels.of(tableLabels[ime.uniqueName].orEmpty()) == KeyLabels.ROOTS && !keyboardCompact()
                Made(ime, keyboard, layout, roots, ime?.let { ImKeyboards.shown(it.uniqueName, chosen, scheme).custom?.name })
            }
            val names = made.map { it.custom ?: boardName(ctx, it.keyboard.themeBoard) }
            return made.mapIndexed { i, m ->
                val board = names[i]
                val label = if (names.count { it == board } > 1 && m.ime != null) "$board（${InputMethodNames.of(ctx, m.ime)}）" else board
                Shown(label, m.keyboard, m.ime, m.layout, m.roots)
            }
        }

        fun jobs(slots: SwipeSlots): List<Job> {
            val sceneTheme = SceneThemes.of(theme, look) ?: return emptyList<Job>().also { Timber.w("swipe fates: no scene theme of %s", theme.name) }
            val (upright, turned) = KeyboardArea.screens(ctx)
            val keyStyle = (theme as? Theme.Pack)?.let { ThemeManager.keyStyleShown.overPack }
            val cascades = cascades?.takeIf { it.theme === sceneTheme } ?: Cascades(sceneTheme).also { cascades = it }
            val spots = swipeSpots()
            val on = swipeWays()
            val ways = setOfNotNull(true.takeIf { on.up && spots.symbol != KeyMarks.At.None }, false.takeIf { on.down && spots.shortcut != KeyMarks.At.None })
            return shown.flatMap { s ->
                s.ime?.let(s.keyboard::onInputMethodUpdate)
                // what the punctuation keys type as the host starts: English types its own
                val english = s.ime?.uniqueName == InputMethodCatalog.ENGLISH
                val punctuation = if (english) emptyMap() else PunctuationStartKeys.known.orEmpty()
                s.keyboard.onPunctuationUpdate(punctuation)
                val rows = KeyboardWindow.rowUnits(s.keyboard, look)
                listOf(upright, turned).mapNotNull { screen ->
                    val size = size(screen, s.keyboard.themeBoard, s.codeRow, rows)
                    val (layout, env) = s.keyboard.shownAs(look, size.width, size.height) ?: return@mapNotNull null
                    // as the keyboard's resolver makes them
                    val marks = SceneBoard.resolver(layout, s.ime?.uniqueName.orEmpty(), KeyCaps.nineLetters(), slots)
                        .marks(KeysState(punctuation = punctuation, ways = on, spots = spots))
                        .let { if (it.charShortcuts == !s.rootsShown) it else it.copy(charShortcuts = !s.rootsShown) }
                    val shownEnv = env.copy(
                        density = screen.density * size.resize, landscape = screen.landscape, floating = size.floating,
                        split = size.split, marks = marks, typed = null, board = layout.id, keyStyle = keyStyle,
                    )
                    // the turned one of the two, not 横屏: a foldable's inner screen is that both ways up
                    Job(s.label, screen === turned, sceneTheme, shownEnv, layout, measurer, s.rootsShown, cascades, ways)
                }
            }
        }
    }

    fun made(job: Job): Seen {
        val built = SceneBuilder(job.theme, job.env, job.measurer, cascades = job.cascades).build(job.layout.layout, job.layout.fragments)
        val scene = when (built) {
            is Parsed.Ok -> built.value
            is Parsed.Failed -> return Seen(job.label, job.landscape, null, job.ways).also { Timber.e("swipe fates: no scene of %s: %s", job.layout.id, built.problems) }
        }
        val keys = scene.keys.filter { it.copy == 0 && it.shortcut != null }
        // roots shown: character keys' ways drawn nowhere; the function keys keep theirs
        val roots = if (job.rootsShown) keys.mapNotNullTo(HashSet()) { it.shortcut?.takeUnless { n -> KeyId(n).isFunction } } else emptySet()
        val fates = keys.associate { node ->
            node.shortcut!! to if (node.shortcut in roots) mapOf(true to MarkFate.Dropped, false to MarkFate.Dropped) else node.fates
        }
        return Seen(job.label, job.landscape, fates, job.ways, roots)
    }

    /** As InputView gives it on [s] (all drawn as much bigger: SceneEnvs.of's density). */
    @VisibleForTesting
    internal data class Size(val width: Int, val height: Int, val floating: Double?, val split: Boolean, val resize: Double = 1.0)

    // as InputView.updateKeyboardSize places it
    @VisibleForTesting
    internal fun size(s: KeyboardArea.Screen, board: String?, codeRow: ImKeyboards.Layout, rows: Double = KeyboardHeightCap.DEFAULT_ROWS): Size {
        val prefs = ThemeManager.prefs
        // each screen by its own shape's 键盘样式
        val style = KeyboardStyles.of(s)
        if (style.floating) {
            val floating = KeyboardArea.floatingSize(s)
            return Size(s.dp(floating.widthDp.toDouble()), floating.keysHeightPx, floating.textScale.toDouble(), false, floating.resize.toDouble())
        }
        val handle = if (prefs.alwaysShowHandle.getValue()) s.dp(FloatingHandle.HEIGHT_DP.toDouble()) else 0
        val height = KeyboardArea.keysHeight(s, CodeRowHeight.keyboardDp(codeRow, KawaiiBarComponent.codeRowFloats()), handle, rows = rows)
        val letters = board == KeyNames.QWERTY
        val splitting = style.splits
        val widthDp = KeyboardArea.widthDp(s, floating = false)
        val placed = SplitKeyboard.placement(KeyboardStyles.oneHandedDock(s), prefs.splitDock.getValue().dock, splitting, letters, widthDp)
        return when {
            placed != null -> Size(s.dp(placed.widthDp.toDouble()), height, null, false)
            else -> Size(KeyboardArea.keysWidth(s), height, null, splitting && letters)
        }
    }

    /** Null where every keyboard with the key draws it as set or a scene was not made; else the worst, by name. */
    fun note(ctx: Context, seen: List<Seen>, name: String, up: Boolean): String? {
        val shortcut = KeyId.of(name).name
        // a scene not made: no claim; a way off or placed 无: drawn nowhere as the user chose
        if (seen.any { it.fates == null || up !in it.ways }) return null
        val per = seen.groupBy { it.label }.mapNotNull { (label, both) ->
            val roots = both.any { shortcut in it.roots }
            // no fate: the engine gave the way none, so no claim; under the roots, which fill the key, it is theirs
            fun fate(landscape: Boolean): MarkFate? = both.firstOrNull { it.landscape == landscape }?.fates?.get(shortcut)?.let {
                it[up] ?: MarkFate.Dropped.takeIf { roots }
            }
            val upright = fate(false)
            val turned = fate(true)
            if (upright == null && turned == null) null else Triple(label, upright to turned, roots)
        }
        if (per.isEmpty()) return null
        val (label, worst, roots) = per.maxBy { (_, f) -> rank(f.first) * RANKS + rank(f.second) }
        // the roots fill the key: not drawn as the user chose, not for want of room
        val text = (if (roots) ctx.getString(R.string.swipe_fate_roots) else note(ctx, worst.first, worst.second)) ?: return null
        return if (per.map { it.second to it.third }.distinct().size > 1) ctx.getString(R.string.swipe_fate_on, label, text) else text
    }

    // distance from as set: none, shortened, a sign, not drawn
    private fun rank(f: MarkFate?): Int = when (f) {
        null, MarkFate.AsSet -> 0
        is MarkFate.Shortened -> 1
        MarkFate.Sign -> 2
        MarkFate.Dropped -> 3
    }

    private const val RANKS = 4

    fun note(ctx: Context, upright: MarkFate?, turned: MarkFate?): String? {
        val a = upright.shown()
        val b = turned.shown()
        if (a == null && b == null) return null
        if (a == b) return when (a) {
            is MarkFate.Shortened -> ctx.getString(R.string.swipe_fate_shortened, a.text)
            MarkFate.Sign -> ctx.getString(R.string.swipe_fate_sign)
            else -> ctx.getString(R.string.swipe_fate_dropped)
        }
        fun part(f: MarkFate?) = when (f) {
            null -> ctx.getString(R.string.swipe_fate_part_as_set)
            is MarkFate.Shortened -> ctx.getString(R.string.swipe_fate_part_shortened, f.text)
            MarkFate.Sign -> ctx.getString(R.string.swipe_fate_part_sign)
            else -> ctx.getString(R.string.swipe_fate_part_dropped)
        }
        return ctx.getString(R.string.swipe_fate_both, part(a), part(b))
    }

    private fun MarkFate?.shown(): MarkFate? = takeUnless { it == null || it == MarkFate.AsSet }

    fun boardName(ctx: Context, board: String?): String = ctx.getString(
        when (board) {
            KeyNames.NINE -> R.string.shortcut_page_nine
            KeyNames.K14 -> R.string.shortcut_page_g14
            KeyNames.K18 -> R.string.shortcut_page_g18
            KeyNames.ZHUYIN_NINE -> R.string.shortcut_page_zy9
            KeyNames.ZHUYIN -> R.string.shortcut_page_zyfull
            KeyNames.NUMBER -> R.string.shortcut_page_number
            else -> R.string.shortcut_page_full
        },
    )

    /** The test's seam: [SceneBoard.measurerFor]. */
    private fun measurer(theme: Theme, look: ThemeLook): TextMeasurer =
        SceneBoard.measurerFor?.invoke() ?: AndroidTextMeasurer(SceneFonts.of(theme, look))
}
