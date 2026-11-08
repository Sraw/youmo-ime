/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.RectF
import android.text.InputType
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.AndroidTextMeasurer
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneFonts
import org.fcitx.fcitx5.android.scene.engine.TextMetrics
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.engine.toInk
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.scene.rules.KeyTextScale
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The marks on every key of every keyboard (uat/02 KB-67), for every pair of 标点位置 and 快捷键图标位置
 * the settings page lets be chosen and as the keys are drawn (standard, 紧凑, floating, one-handed,
 * landscape): what each key draws, its label or icon, its symbol and its shortcuts' marks, as drawn
 * (as the scene placed them, docs/keyboard.md), never two over each other. Their ink: a text's glyphs, an
 * icon's box. 滑动 is not gone through: it moves no mark. No symbol is left out (those left out are
 * printed, the report's numbers).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class KeyMarksEverywhereUatTest {

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        EngineState.tableKeyboardState.value = null
    }

    private fun entry(im: String, name: String) = InputMethodEntry(im, name, "", "", name.take(1), "zh_CN", "androidengine", false)

    private val shuangpin = entry(InputMethodCatalog.SHUANGPIN, "双拼")

    private class Board(
        val name: String,
        val im: InputMethodEntry = KeyboardHarness.PINYIN,
        val keyboards: ImKeyboards.Chosen? = null,
        val inputType: Int = InputType.TYPE_CLASS_TEXT,
        val table: TableKeyboard? = null,
        val symbols: Boolean = false,
    )

    private val boards = listOf(
        Board("全键盘", keyboards = ImKeyboards.Chosen()),
        Board("九键", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS)),
        Board("14 键", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14)),
        Board("18 键", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS18)),
        Board("双拼九键", shuangpin, ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS)),
        Board("双拼 14 键", shuangpin, ImKeyboards.Chosen(shuangpin = ImKeyboard.KEYS14)),
        Board("双拼 18 键", shuangpin, ImKeyboards.Chosen(shuangpin = ImKeyboard.KEYS18)),
        Board("英文九键", KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS)),
        Board("大千", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_FULL)),
        Board("注音九键", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_NINE)),
        Board("手写", entry(InputMethodCatalog.HANDWRITING, "手写")),
        Board(
            "五笔两行字根", entry(InputMethodCatalog.WUBI, "五笔"),
            table = TableKeyboard(InputMethodCatalog.WUBI, emptySet(), false, KeyLabels.ROOTS, TableRoots.WUBI86),
        ),
        Board(
            "五笔键名", entry(InputMethodCatalog.WUBI, "五笔"),
            table = TableKeyboard(InputMethodCatalog.WUBI, emptySet(), false, KeyLabels.NAMES, TableRoots.WUBI86),
        ),
        Board("数字键盘", inputType = InputType.TYPE_CLASS_NUMBER),
        Board("符号页", keyboards = ImKeyboards.Chosen(), symbols = true),
    )

    // what a mode sets, put back before the next
    private class Local {
        private val undo = mutableListOf<() -> Unit>()

        fun <T : Any> set(pref: ManagedPreference<T>, value: T) {
            val was = pref.getValue()
            undo += { pref.setValue(was) }
            pref.setValue(value)
        }

        fun undo() {
            undo.asReversed().forEach { it() }
            undo.clear()
        }
    }

    private class Mode(val name: String, val set: (Local) -> Unit)

    private val upright = listOf(
        Mode("标准") {},
        Mode("紧凑") { compact(it) },
        Mode("悬浮") { it.set(ThemeManager.prefs.floatingKeyboard, true) },
        Mode("单手") { it.set(ThemeManager.prefs.oneHanded, OneHanded.Right) },
        Mode("紧凑单手") { p -> compact(p); p.set(ThemeManager.prefs.oneHanded, OneHanded.Right) },
    )

    private val turned = listOf(
        Mode("横屏") {},
        Mode("横屏紧凑") { compact(it) },
    )

    private companion object {
        // Content's and SceneBuilder's (rules B §5, §8)
        const val SYMBOL_DP = 10.666667
        const val SYMBOL_SIDE_DP = 4.0
        const val BELOW_DP = 2.0
        const val MARK_EDGE_DP = 0.25
        const val GAP_DP = 1.0
        const val HALF_PX = 0.5

        fun compact(p: Local) {
            p.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
            p.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        }
    }

    // what [k] draws, as the scene drew it, each with what it is: a text's glyphs (measured), an icon's box
    private fun drawn(k: SceneShownKey): List<Pair<String, RectF>> {
        // the label drawn first, a symbol the same text (a table's z) after it
        var labelSeen = false
        return k.node.ops(false).mapNotNull { op ->
        when (op) {
            is Op.Icon -> "icon ${op.name}" to RectF(op.box.left.toFloat(), op.box.top.toFloat(), op.box.right.toFloat(), op.box.bottom.toFloat())
            is Op.Text -> {
                // measured as the engine measured it (AndroidTextMeasurer, the theme's fonts)
                val ink = measurer.measure(op.text, op.font, op.weight, op.size).ink
                val r = RectF((ink.left * op.scaleX).toFloat(), ink.top.toFloat(), (ink.right * op.scaleX).toFloat(), ink.bottom.toFloat())
                val what = when {
                    op.text == k.label && !labelSeen -> "label".also { labelSeen = true }
                    op.text == k.alt -> "symbol"
                    else -> "text ${op.text}"
                }
                // a blank's no ink
                if (r.isEmpty) null else what to r.apply { offset(op.x.toFloat(), op.baseline.toFloat()) }
            }
            else -> null
        }
        }
    }

    private val measurer by lazy { AndroidTextMeasurer(SceneFonts(ThemeManager.shownLook(ThemeManager.activeTheme))) }

    // each symbol left out, and how many px it is short of fitting at the marks' least scale
    private val dropped = sortedMapOf<String, Float>()

    // KeyMarkYield's rule 4 (060-render-p2 S4e): a symbol is left out only where it still meets what the key
    // draws at its size ([least]: at [KeyMarkYield.MIN_MARK_SCALE], else its class's, as drawn on the board).
    // Asked of the engine's own yields (KeyMarkYield.board; the marks, the label no smaller than given), the
    // symbol's ink measured at that size (060-render-rules §8b) where Content places it in its [slot], the key's
    // label as the glyph, its other marks as drawn, the face pressed as rounded ([slack] px less each side). How many px it is short: its
    // side times how much smaller still it must be to stay; 0 where it stays (it fits)
    private fun short(k: SceneShownKey, symbol: String, d: List<Pair<String, RectF>>, drawn: Op.Text?, slot: KeyMarks.Slot?, shortcutAt: KeyMarks.At, compact: Boolean, least: Boolean, slack: Double = 0.0): Float {
        val label = d.firstOrNull { it.first == "label" }?.second
        // a symbol left out, and none drawn on the board to size it by: nothing to show it could not fit
        // (an infinite shortfall would pass whatever the engine did)
        assertTrue("${k.label.ifEmpty { k.description }}: symbol $symbol left out, no symbol drawn on the board to measure it by", drawn != null)
        if (drawn == null || slot == null || label == null) return Float.POSITIVE_INFINITY
        val dp = FcitxApplication.getInstance().resources.displayMetrics.density.toDouble()
        // its size before its class is scaled (Content.symbolMark: 10.67 dp, the theme's mark scale 1); floating, the
        // board's text scale not known here: as docked, larger, so a symbol is no sooner found to fit
        val nominal = SYMBOL_DP * dp * KeyTextScale.of(compact, null)
        // the face pressed, its border; half a px in each side, as Content works the yields out (not snapped)
        val stroke = k.node.ops(true).filterIsInstance<Op.Stroke>().firstOrNull()
        val shape = stroke?.shape ?: k.node.shape
        val line = stroke?.width ?: 0.0
        val inset = HALF_PX + slack
        val face = shape.bounds.let { Box(it.left + inset, it.top + inset, it.right - inset, it.bottom - inset) }
        val half = minOf(face.width, face.height) / 2
        val radii = ((shape as? Shape.Rect)?.radii ?: listOf(0.0, 0.0, 0.0, 0.0)).map { minOf(it, half) }
        val edge = KeyMarkYield.Edge(face.toInk(), radii, MARK_EDGE_DP * dp + line, line)
        val room = KeyMarkYield.Ink(face.left + 2 * dp, face.top + dp, face.right - 2 * dp, face.bottom - dp)
        val others = d.filter { it.first != "label" && it.first != "symbol" }.map { (_, r) ->
            KeyMarkYield.Mark(KeyMarkYield.Ink(r.left.toDouble(), r.top.toDouble(), r.right.toDouble(), r.bottom.toDouble()), shortcutAt.corner ?: KeyMarks.Slot.Bottom)
        }
        val size = if (least) nominal * KeyMarkYield.MIN_MARK_SCALE else drawn.size
        fun stays(f: Double): Boolean {
            val ink = symbolInk(measurer.measure(symbol, drawn.font, drawn.weight, size * f), face, slot, dp)
            val glyph = KeyMarkYield.Ink(label.left.toDouble(), label.top.toDouble(), label.right.toDouble(), label.bottom.toDouble())
            val member = KeyMarkYield.Member(glyph, (glyph.left + glyph.right) / 2, glyph.bottom, listOf(KeyMarkYield.Mark(ink, slot, KeyMarkYield.DROP_SYMBOL)) + others, room, edge, moves = false)
            // the label its class's size, as drawn: no smaller for this key alone
            val out = KeyMarkYield.board(listOf(member), GAP_DP * dp, minMark = 1.0)
            return out.scales[0] == 1.0 && out.keys[0].marks[0] != null
        }
        if (stays(1.0)) return 0f
        var lo = 0.0
        var hi = 1.0
        while (hi - lo > 1e-3) ((lo + hi) / 2).let { if (stays(it)) lo = it else hi = it }
        val m = measurer.measure(symbol, drawn.font, drawn.weight, size).ink
        return ((1 - lo) * maxOf(m.right - m.left, m.bottom - m.top)).toFloat()
    }

    // a symbol measured [m] where Content places it in its [slot] on [face]
    private fun symbolInk(m: TextMetrics, face: Box, slot: KeyMarks.Slot, dp: Double): KeyMarkYield.Ink {
        val w = m.ink.right - m.ink.left
        val h = m.bottom - m.top
        val x = when {
            slot.left -> face.left + SYMBOL_SIDE_DP * dp
            slot.right -> face.right - SYMBOL_SIDE_DP * dp - w
            else -> (face.left + face.right) / 2 - w / 2
        }
        val y = if (slot.bottom) face.bottom - (if (slot == KeyMarks.Slot.Below) BELOW_DP * dp else 0.0) - h else face.top
        return KeyMarkYield.Ink(x, y + m.ink.top - m.top, x + w, y + m.ink.bottom - m.top)
    }

    // what [k] draws, none over another; its symbol never left out where 标点位置 draws one
    private fun checkKey(case: String, k: SceneShownKey, symbols: Boolean, drawnSymbol: Op.Text?, slot: KeyMarks.Slot?, shortcutAt: KeyMarks.At, compact: Boolean) {
        // a table's roots in lines stay as theme 3.0 set them, nothing yielding to them (Content.KeyContent.yields)
        if ('\n' in k.label) return
        val d = drawn(k)
        val name = k.label.ifEmpty { k.description }
        for (i in d.indices) for (j in i + 1 until d.size) {
            assertTrue("$case key $name: ${d[i]} meets ${d[j]}", !RectF.intersects(d[i].second, d[j].second))
        }
        val symbol = k.alt
        if (symbols && symbol != null && d.none { it.first == "symbol" }) {
            // left out at its class's size (the board's symbols one size, 0.875 or larger: the keys past saving ask no
            // smaller of it, HOPELESS_BAND), so it must not fit there; how far short it is at 0.875 is printed
            // a px less face than the engine's (what this rebuilds of its geometry, not its own: snapped boxes, its
            // marks' unsnapped placing), so only a symbol that fits with room to spare counts as left out wrongly
            val there = short(k, symbol, d, drawnSymbol, slot, shortcutAt, compact, least = false, slack = 1.0)
            assertTrue("$case key $name: symbol $symbol left out, but it fits at its class's size", there > 0f)
            val short = short(k, symbol, d, drawnSymbol, slot, shortcutAt, compact, least = true)
            dropped["$case: $name symbol $symbol"] = short
        }
    }

    // every pair the settings page lets be chosen: apart, or 无
    private val legal = KeyMarkPosition.entries.flatMap { symbol ->
        KeyMarkPosition.entries.filter { it != symbol || it == KeyMarkPosition.None }.map { symbol to it }
    }

    // The two settings make the keyboard anew (FcitxInputMethodService.recreateInputViewPrefs). Under
    // Robolectric each old keyboard, about 3 MB, stays: its scroll views' scrollbar fade
    // (View.ScrollabilityCache, posted for 300 ms on) waits in the paused main looper, whose clock
    // idle() does not move; 62 for each of 90 keyboards ran a full run out of its 3 GB. Not made anew
    // here: the canvas's scene made again with the settings as they are now (check), as a new keyboard
    // makes it. Not registered again: [check] runs once on a harness closed right after it, whose
    // service unregisters it on destroy anyway. The field read by name: renamed, this fails loudly
    // (NoSuchFieldException), never silently
    private fun noRebuilds(h: KeyboardHarness) {
        val field = h.service.javaClass.getDeclaredField("recreateInputViewListener").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val rebuild = field.get(h.service) as ManagedPreference.OnChangeListener<Any>
        val keyboard = AppPrefs.getInstance().keyboard
        @Suppress("UNCHECKED_CAST")
        listOf(keyboard.punctuationPosition, keyboard.shortcutMarkPosition)
            .forEach { (it as ManagedPreference<Any>).unregisterOnChangeListener(rebuild) }
    }

    private fun check(board: Board, mode: Mode, h: KeyboardHarness) {
        noRebuilds(h)
        var keys = 0
        for ((symbolAt, shortcutAt) in legal) {
            prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, symbolAt)
            prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, shortcutAt)
            h.idle()
            // every scene made anew, as the keyboard made anew with these settings has it
            KeyboardHarness.all(h.inputView).filterIsInstance<KeyboardCanvasView>().forEach { it.invalidateScene() }
            h.relayout()
            val case = "${board.name} ${mode.name} 标点${symbolAt.name} 快捷键${shortcutAt.name}"
            val shown = h.keys().filterIsInstance<SceneShownKey>()
            // the symbols' font and size as drawn on this board: what a left-out one would be drawn in
            val symbol = shown.flatMap { k -> k.node.ops(false).filterIsInstance<Op.Text>().filter { it.text == k.alt } }.maxByOrNull { it.size }
            val slot = KeyMarks.symbolSlot(KeyMarks.At.valueOf(symbolAt.name), KeyMarks.At.valueOf(shortcutAt.name), landscape = false, compact = "紧凑" in mode.name)
            val before = dropped.size
            for (k in shown) checkKey(case, k, symbolAt != KeyMarkPosition.None, symbol, slot, KeyMarks.At.valueOf(shortcutAt.name), "紧凑" in mode.name)
            if (dropped.size > before) println("KeyMarksEverywhere $case: ${dropped.size - before} left out")
            keys += shown.size
        }
        assertTrue("${board.name} ${mode.name}: keys", keys > 0)
    }

    private fun each(modes: List<Mode>) {
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        for (mode in modes) for (board in boards) {
            val local = Local()
            mode.set(local)
            KeyboardHarness(keyboards = board.keyboards, im = board.im).use { h ->
                h.start(board.inputType)
                board.table?.let {
                    EngineState.tableKeyboardState.value = it
                    h.idle()
                    h.relayout()
                }
                if (board.symbols) {
                    h.tap(h.key("符"))
                    h.idle()
                }
                check(board, mode, h)
                EngineState.tableKeyboardState.value = null
            }
            local.undo()
        }
        println("KeyMarksEverywhere left out (${dropped.size}):")
        dropped.forEach { (it, short) -> println("  $it short ${"%.2f".format(short)} px") }
    }

    @Test
    @Uat("KB-67")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun noKeyDrawsTwoThingsOverEachOtherUpright() {
        each(upright)
    }

    @Test
    @Uat("KB-67")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun noKeyDrawsTwoThingsOverEachOtherTurned() {
        each(turned)
    }

    // Not walked at a larger system font size: the keys' texts and marks are sized in dp (KeyView), and
    // upright at fontScale 1.3 the walk printed what it does at 1 line for line. As a third test here it
    // took the full run's JVM past its 3 GB
}
