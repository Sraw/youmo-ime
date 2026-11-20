/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.res.Configuration
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
import org.fcitx.fcitx5.android.scene.Icons
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
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
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
 * The marks on every key of every keyboard, for every pair of 标点位置 and 快捷键图标位置 and every keyboard style:
 * never two over each other. A symbol is left out only where it does not fit at its class's size.
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

    private fun entry(im: String, name: String) = InputMethodEntry(im, name, "", name.take(1), "zh_CN")

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

    private class Local {
        private val undo = mutableListOf<() -> Unit>()

        fun <T : Any> set(pref: ManagedPreference<T>, value: T) {
            val was = pref.getValue()
            undo += { pref.setValue(was) }
            pref.setValue(value)
        }

        fun setStyle(style: KeyboardStyle, side: OneHanded? = null) {
            side?.let { set(ThemeManager.prefs.oneHandedSide, it) }
            TestStyles.set(style)
            undo += { TestStyles.clear() }
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
        Mode("悬浮") { it.setStyle(KeyboardStyle.FLOATING) },
        Mode("单手") { it.setStyle(KeyboardStyle.ONE_HANDED, side = OneHanded.Right) },
        Mode("紧凑单手") { p -> compact(p); p.setStyle(KeyboardStyle.ONE_HANDED, side = OneHanded.Right) },
    )

    private val turned = listOf(
        Mode("横屏") {},
        Mode("横屏紧凑") { compact(it) },
    )

    private companion object {
        // Content's and SceneBuilder's, written out on purpose: changed there, this checks the old geometry until copied again
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

    // a text's glyphs (measured), an icon's box
    private fun drawn(k: SceneShownKey): List<Pair<String, RectF>> {
        var labelSeen = false
        return k.node.ops(false).mapNotNull { op ->
        when (op) {
            // the built-in sign's ink is its two lines; a theme's own, its whole square
            is Op.Icon -> "icon ${op.name}" to (if (op.name == Icons.SHORT_TEXT && !themedSign) Icons.SHORT_TEXT_INK.of(op.box) else op.box)
                .let { RectF(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
            is Op.Text -> {
                val ink = measurer.measure(op.text, op.font, op.weight, op.size).ink
                val r = RectF((ink.left * op.scaleX).toFloat(), ink.top.toFloat(), (ink.right * op.scaleX).toFloat(), ink.bottom.toFloat())
                val what = when {
                    op.text == k.label && !labelSeen -> "label".also { labelSeen = true }
                    op.text == k.alt -> "symbol"
                    else -> "text ${op.text}"
                }
                if (r.isEmpty) null else what to r.apply { offset(op.x.toFloat(), op.baseline.toFloat()) }
            }
            else -> null
        }
        }
    }

    private val themedSign by lazy {
        val t = ThemeManager.activeTheme
        org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes.of(t, ThemeManager.shownLook(t))?.let { Icons.SHORT_TEXT in it.icons } == true
    }

    private val measurer by lazy { AndroidTextMeasurer(SceneFonts(ThemeManager.shownLook(ThemeManager.activeTheme))) }

    // how many px each symbol left out is short of fitting at the marks' least scale
    private val dropped = sortedMapOf<String, Float>()

    // KeyMarkYield's rule 4: a symbol is left out only where it still meets what the key draws at [least]'s size, asked
    // of the engine's own yields; how many px it is short, 0 where it fits
    private fun short(k: SceneShownKey, symbol: String, d: List<Pair<String, RectF>>, drawn: Op.Text?, slot: KeyMarks.Slot?, shortcutAt: KeyMarks.At, compact: Boolean, least: Boolean, slack: Double = 0.0): Float {
        val label = d.firstOrNull { it.first == "label" }?.second
        // an infinite shortfall would pass whatever the engine did
        assertTrue("${k.label.ifEmpty { k.description }}: symbol $symbol left out, no symbol drawn on the board to measure it by", drawn != null)
        if (drawn == null || slot == null || label == null) return Float.POSITIVE_INFINITY
        val dp = FcitxApplication.getInstance().resources.displayMetrics.density.toDouble()
        // floating, the board's text scale not known here: as docked, larger, so a symbol is no sooner found to fit
        val nominal = SYMBOL_DP * dp * KeyTextScale.of(compact, null)
        // half a px in each side, as Content works the yields out (not snapped)
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
            KeyMarkYield.Mark(KeyMarkYield.Ink(r.left.toDouble(), r.top.toDouble(), r.right.toDouble(), r.bottom.toDouble()), KeyMarks.shortcutSlot(shortcutAt) ?: KeyMarks.Slot.Bottom)
        }
        val size = if (least) nominal * KeyMarkYield.MIN_MARK_SCALE else drawn.size
        fun stays(f: Double): Boolean {
            val ink = symbolInk(measurer.measure(symbol, drawn.font, drawn.weight, size * f), face, slot, dp)
            val glyph = KeyMarkYield.Ink(label.left.toDouble(), label.top.toDouble(), label.right.toDouble(), label.bottom.toDouble())
            val member = KeyMarkYield.Member(glyph, (glyph.left + glyph.right) / 2, glyph.bottom, listOf(KeyMarkYield.Mark(ink, slot, KeyMarkYield.DROP_SYMBOL)) + others, room, edge, moves = false)
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

    private fun checkKey(case: String, k: SceneShownKey, symbols: Boolean, drawnSymbol: Op.Text?, slot: KeyMarks.Slot?, shortcutAt: KeyMarks.At, compact: Boolean) {
        // a table's roots in lines stay as theme 3.0 set them, nothing yielding to them
        if ('\n' in k.label) return
        val d = drawn(k)
        val name = k.label.ifEmpty { k.description }
        for (i in d.indices) for (j in i + 1 until d.size) {
            assertTrue("$case key $name: ${d[i]} meets ${d[j]}", !RectF.intersects(d[i].second, d[j].second))
        }
        val symbol = k.alt
        if (symbols && symbol != null && d.none { it.first == "symbol" }) {
            // a px less face than the engine's (snapped boxes, unsnapped marks), so only a symbol that fits with room to spare
            // counts as left out wrongly
            val there = short(k, symbol, d, drawnSymbol, slot, shortcutAt, compact, least = false, slack = 1.0)
            assertTrue("$case key $name: symbol $symbol left out, but it fits at its class's size", there > 0f)
            val short = short(k, symbol, d, drawnSymbol, slot, shortcutAt, compact, least = true)
            dropped["$case: $name symbol $symbol"] = short
        }
    }

    private val legal = KeyMarkPosition.entries.flatMap { symbol ->
        KeyMarkPosition.entries.filter { it != symbol || it == KeyMarkPosition.None }.map { symbol to it }
    }

    // The two settings make the keyboard anew; under Robolectric each old one (3 MB) stays, its scrollbar fade waiting in
    // the paused main looper, and the full run ran out of memory. Here the scene is made again instead. The field read by
    // name: renamed, this fails loudly
    private fun noRebuilds(h: KeyboardHarness) {
        val field = h.service.javaClass.getDeclaredField("recreateInputViewListener").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val rebuild = field.get(h.service) as ManagedPreference.OnChangeListener<Any>
        val keyboard = AppPrefs.getInstance().keyboard
        @Suppress("UNCHECKED_CAST")
        listOf(keyboard.swipeUpPosition, keyboard.swipeDownPosition)
            .forEach { (it as ManagedPreference<Any>).unregisterOnChangeListener(rebuild) }
    }

    private fun check(board: Board, mode: Mode, h: KeyboardHarness) {
        noRebuilds(h)
        var keys = 0
        for ((symbolAt, shortcutAt) in legal) {
            prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, symbolAt)
            prefs.set(AppPrefs.getInstance().keyboard.swipeDownPosition, shortcutAt)
            h.idle()
            KeyboardHarness.all(h.inputView).filterIsInstance<KeyboardCanvasView>().forEach { it.invalidateScene() }
            h.relayout()
            val case = "${board.name} ${mode.name} 标点${symbolAt.name} 快捷键${shortcutAt.name}"
            val shown = h.keys().filterIsInstance<SceneShownKey>()
            val symbol = shown.flatMap { k -> k.node.ops(false).filterIsInstance<Op.Text>().filter { it.text == k.alt } }.maxByOrNull { it.size }
            // landscape (from the configuration, as SceneEnvs) moves the marks to corners
            val landscape = h.service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val compact = "紧凑" in mode.name
            val at = KeyMarks.At.valueOf(symbolAt.name)
            val shortcutOn = KeyMarks.onKeys(at, KeyMarks.At.valueOf(shortcutAt.name), landscape, compact).shortcut
            val slot = KeyMarks.symbolSlot(at, KeyMarks.At.valueOf(shortcutAt.name), landscape, compact)
            val before = dropped.size
            for (k in shown) checkKey(case, k, symbolAt != KeyMarkPosition.None, symbol, slot, shortcutOn, compact)
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

    // Not walked at a larger font size: the keys are sized in dp; as a third test here it took the full run's JVM past its 3 GB
}
