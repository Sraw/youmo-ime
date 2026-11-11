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
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.CodeRowHeight
import org.fcitx.fcitx5.android.input.keyboard.HandwritingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.AndroidTextMeasurer
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneFonts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.input.keyboard.keyboardCompact
import org.fcitx.fcitx5.android.input.keyboard.keyboardMakers
import org.fcitx.fcitx5.android.input.keyboard.lettersOf
import org.fcitx.fcitx5.android.input.keyboard.nameOf
import org.fcitx.fcitx5.android.input.keyboard.swipeSpots
import org.fcitx.fcitx5.android.input.keyboard.swipeWays
import org.fcitx.fcitx5.android.input.popup.PopupOverridesCache
import org.fcitx.fcitx5.android.input.scene.SceneMarks
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.MarkFate
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import timber.log.Timber
import org.fcitx.fcitx5.android.scene.theme.Theme as SceneTheme

/**
 * How a way the user set is drawn on its keyboards (the engine's [org.fcitx.fcitx5.android.scene.KeyNode.fates]):
 * each keyboard of the input methods the user has on, made as the input view makes it (its own keyboard class:
 * its input method's name on the space, the punctuation it types, the hold-to-talk badge, the language key), at the
 * size the input view gives it on this display upright and turned (KeyboardArea.screens: the height settings, the
 * bar, the code row, 紧凑, one-handed, floating, split). One scene a keyboard an orientation gives every key's ways.
 *
 * [Page.jobs] reads what the scenes are made of on the main thread; [made] makes them, on one other thread (the page's:
 * its measurer is no other's); [note] says what the page shows of them.
 */
object SwipeFates {

    /** One keyboard's scene to make: [label] the keyboard the page names, [landscape] the turned one. */
    class Job internal constructor(
        val label: String,
        val landscape: Boolean,
        internal val theme: SceneTheme,
        internal val env: Env,
        internal val layout: SceneLayout,
        internal val measurer: TextMeasurer,
        // a table's every root on the letters: its letters' ways drawn nowhere (TextKeyboard.charShortcutMarks)
        internal val rootsShown: Boolean,
    )

    /** What a [Job]'s scene shows: each key's (by its shortcut name) ways' fates; null, no scene made. */
    class Seen(val label: String, val landscape: Boolean, val fates: Map<String, Map<Boolean, MarkFate>>?)

    /** A keyboard the user types on: its own class, made once, as told its input method. */
    private class Shown(val label: String, val keyboard: BaseKeyboard, val codeRow: ImKeyboards.Layout, val rootsShown: Boolean)

    /**
     * A settings page's keyboards: one a keyboard of [imes] (the input methods on), [tableLabels] each table's
     * 按键显示 (its KeyLabels key); none on, the full keyboard. Made on the main thread, as the page opens.
     */
    class Page(private val ctx: Context, imes: List<InputMethodEntry>, tableLabels: Map<String, String?>) {
        private val theme: Theme = ThemeManager.activeTheme
        private val look = ThemeManager.shownLook(theme)

        // the page's thread's alone: a measurer keeps a Paint and what it measured
        private val measurer = measurer(theme, look)

        private val shown: List<Shown> = shown(imes, tableLabels)

        private fun shown(imes: List<InputMethodEntry>, tableLabels: Map<String, String?>): List<Shown> {
            val makers = keyboardMakers({ ctx }, { theme }) { HandwritingKeyboard(ctx, theme, deliver = { _, _ -> }) }
            val prefs = AppPrefs.getInstance().keyboard
            val scheme = EngineState.shuangpinScheme
            val on = imes.ifEmpty { listOf(null) }
            val made = on.mapNotNull { ime ->
                val layout = ImKeyboards.layoutOf(ime?.uniqueName ?: InputMethodCatalog.ENGLISH, prefs.imKeyboards(), scheme)
                val name = ime?.let { lettersOf(it.uniqueName, prefs.imKeyboards(), scheme) } ?: nameOf(layout, scheme)
                val keyboard = makers[name]?.invoke() ?: return@mapNotNull null
                if (ime != null) keyboard.onInputMethodUpdate(ime)
                // what the punctuation keys type as the host starts: English types its own
                val english = ime?.uniqueName == InputMethodCatalog.ENGLISH
                keyboard.onPunctuationUpdate(if (english) emptyMap() else PunctuationStartKeys.known.orEmpty())
                val roots = ime != null && ime.uniqueName in InputMethodCatalog.TABLES &&
                    KeyLabels.of(tableLabels[ime.uniqueName].orEmpty()) == KeyLabels.ROOTS && !keyboardCompact()
                Triple(ime, keyboard, layout) to roots
            }
            // a board named by its page's name; two input methods on one board, each by its own too
            val boards = made.groupBy { it.first.second.themeBoard }
            return made.map { (m, roots) ->
                val (ime, keyboard, layout) = m
                val board = boardName(ctx, keyboard.themeBoard)
                val label = if (boards[keyboard.themeBoard].orEmpty().size > 1 && ime != null) "$board（${InputMethodNames.of(ctx, ime)}）" else board
                Shown(label, keyboard, layout, roots)
            }
        }

        /** The scenes of every keyboard upright and turned, the ways as [slots] has them: read here, on the main thread. */
        fun jobs(slots: SwipeSlots): List<Job> {
            val sceneTheme = SceneThemes.of(theme, look) ?: return emptyList<Job>().also { Timber.w("swipe fates: no scene theme of %s", theme.name) }
            val (upright, turned) = KeyboardArea.screens(ctx)
            val keyStyle = (theme as? Theme.Pack)?.let { ThemeManager.keyStyleShown.overPack }
            return shown.flatMap { s ->
                listOf(upright, turned).mapNotNull { screen ->
                    val size = size(screen, s.keyboard.themeBoard, s.codeRow)
                    val (layout, env) = s.keyboard.shownAs(look, size.width, size.height) ?: return@mapNotNull null
                    val names = SceneBoard.names(layout)
                    val spots = swipeSpots()
                    val marks = SceneMarks.settings(
                        slots, swipeWays(), spots.symbol, spots.shortcut, !s.rootsShown, names,
                        SceneBoard.firsts(PopupOverridesCache.byName.current, names),
                    )
                    val on = env.copy(
                        density = screen.density.toDouble(), landscape = screen.landscape, floating = size.floating,
                        split = size.split, marks = marks, typed = null, board = layout.id, keyStyle = keyStyle,
                    )
                    Job(s.label, screen.landscape, sceneTheme, on, layout, measurer, s.rootsShown)
                }
            }
        }
    }

    /** [job]'s scene made: on the page's thread. */
    fun made(job: Job): Seen {
        val built = SceneBuilder(job.theme, job.env, job.measurer, cascades = SceneThemes.cascades(job.theme)).build(job.layout.layout, job.layout.fragments)
        val scene = when (built) {
            is Parsed.Ok -> built.value
            is Parsed.Failed -> return Seen(job.label, job.landscape, null).also { Timber.e("swipe fates: no scene of %s: %s", job.layout.id, built.problems) }
        }
        val fates = scene.keys.filter { it.copy == 0 && it.shortcut != null }.associate { node ->
            // a table's letters show its roots: their ways drawn nowhere
            val letter = job.rootsShown && node.id.length == 1 && node.id[0] in 'a'..'z'
            node.shortcut!! to if (letter) mapOf(true to MarkFate.Dropped, false to MarkFate.Dropped) else node.fates
        }
        return Seen(job.label, job.landscape, fates)
    }

    /** The size of the keys of a board ([board]: KeyNames') on [s], as InputView gives it; [floating] its text scale, [split] the letters halved. */
    @VisibleForTesting
    internal data class Size(val width: Int, val height: Int, val floating: Double?, val split: Boolean)

    // InputView.updateKeyboardSize and placement: floating as narrow and short as there, docked one-handed or at a
    // side as SplitKeyboard puts it, a docked handle counted in the cap
    @VisibleForTesting
    internal fun size(s: KeyboardArea.Screen, board: String?, codeRow: ImKeyboards.Layout): Size {
        val prefs = ThemeManager.prefs
        if (prefs.floatingKeyboard.getValue()) {
            val widthDp = FloatingKeyboard.widthDp(s.widthDp)
            val height = FloatingKeyboard.height(KeyboardArea.requestedHeight(s), widthDp, s.widthDp)
            return Size(s.dp(widthDp.toDouble()), height, FloatingKeyboard.textScale(widthDp, s.widthDp).toDouble(), false)
        }
        val handle = if (prefs.alwaysShowHandle.getValue()) s.dp(FloatingHandle.HEIGHT_DP.toDouble()) else 0
        val height = KeyboardArea.keysHeight(s, CodeRowHeight.keyboardDp(codeRow, KawaiiBarComponent.codeRowFloats()), handle)
        val letters = board == KeyNames.QWERTY
        val splitting = KeyboardArea.splits(s, floating = false)
        val widthDp = KeyboardArea.widthDp(s, floating = false)
        val placed = SplitKeyboard.placement(prefs.oneHanded.getValue().dock, prefs.splitDock.getValue().dock, splitting, letters, widthDp)
        return when {
            placed != null -> Size(s.dp(placed.widthDp.toDouble()), height, null, false)
            else -> Size(KeyboardArea.keysWidth(s), height, null, splitting && letters)
        }
    }

    /**
     * What the page says of way [up] of the key of [shortcut] (`full/j`, a function key's `space`), set, as [seen]:
     * nothing where every keyboard that has the key draws it as set, or a scene was not made (no claim then); the
     * keyboards differing, the one that draws it worst, by its name.
     */
    fun note(ctx: Context, seen: List<Seen>, shortcut: String, up: Boolean): String? {
        if (seen.any { it.fates == null }) return null
        val per = seen.groupBy { it.label }.mapNotNull { (label, both) ->
            fun fate(landscape: Boolean): MarkFate? = both.firstOrNull { it.landscape == landscape }?.fates?.get(shortcut)?.let {
                // set and on, and drawn nowhere: no room even to say so
                it[up] ?: MarkFate.Dropped
            }
            val upright = fate(false)
            val turned = fate(true)
            if (upright == null && turned == null) null else label to (upright to turned)
        }
        if (per.isEmpty()) return null
        val (label, worst) = per.maxBy { (_, f) -> rank(f.first) * RANKS + rank(f.second) }
        val text = note(ctx, worst.first, worst.second) ?: return null
        return if (per.map { it.second }.distinct().size > 1) ctx.getString(R.string.swipe_fate_on, label, text) else text
    }

    // how far from as set: none, shortened, a sign, not drawn
    private fun rank(f: MarkFate?): Int = when (f) {
        null, MarkFate.AsSet -> 0
        is MarkFate.Shortened -> 1
        MarkFate.Sign -> 2
        MarkFate.Dropped -> 3
    }

    private const val RANKS = 4

    /** What the page says of [upright] and [turned]: nothing where both are drawn as set. */
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

    // drawn as set, or not drawn at all for nothing set: no note
    private fun MarkFate?.shown(): MarkFate? = takeUnless { it == null || it == MarkFate.AsSet }

    /** A board's name as the shortcut pages call it. */
    fun boardName(ctx: Context, board: String?): String = ctx.getString(
        when (board) {
            KeyNames.NINE -> R.string.shortcut_page_nine
            KeyNames.K14 -> R.string.shortcut_page_g14
            KeyNames.K18 -> R.string.shortcut_page_g18
            KeyNames.ZHUYIN_NINE -> R.string.shortcut_page_zy9
            KeyNames.ZHUYIN -> R.string.shortcut_page_zyfull
            KeyNames.NUMBER -> R.string.shortcut_page_number
            KeyNames.HANDWRITING -> R.string.im_handwriting
            else -> R.string.shortcut_page_full
        },
    )

    /** The measurer a page's thread measures [theme]'s text with: its own, made here (the test's seam: [measurerFor]). */
    private fun measurer(theme: Theme, look: ThemeLook): TextMeasurer =
        measurerFor?.invoke() ?: AndroidTextMeasurer(SceneFonts.of(theme, look))

    /** A test's measurer in place of the fonts' (a fixed width a character): null, the fonts'. */
    @VisibleForTesting
    var measurerFor: (() -> TextMeasurer)? = null
}
