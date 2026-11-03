/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.RectF
import android.text.InputType
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.ImageKeyView
import org.fcitx.fcitx5.android.input.keyboard.ImageTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.ShortcutMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The marks on every key of every keyboard (uat/02 KB-65), for each 快捷键标记位置 and as the keys are
 * drawn (standard, 紧凑, floating, one-handed, landscape; 标点位置 底部; 滑动 either way): what each key
 * draws, its label or icon, its symbol and its shortcuts' marks, as drawn (moved, smaller, left out:
 * KeyMarkYield), never two over each other. Their ink: a text's glyphs, an icon's box.
 *
 * What was left out to keep them apart is counted and printed (the report's numbers), and no symbol
 * ever is.
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
        Mode("标点底部") { it.set(ThemeManager.prefs.punctuationPosition, PunctuationPosition.Bottom) },
        Mode("紧凑单手") { p -> compact(p); p.set(ThemeManager.prefs.oneHanded, OneHanded.Right) },
    )

    private val turned = listOf(
        Mode("横屏") {},
        Mode("横屏紧凑") { compact(it) },
    )

    private companion object {
        fun compact(p: Local) {
            p.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
            p.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        }
    }

    // what [k] draws, as drawn, in its face's coordinates, each with what it is; a mark left out not drawn
    private fun drawn(k: KeyView): List<Pair<String, RectF>> {
        val out = mutableListOf<Pair<String, RectF>>()
        fun add(what: String, v: View?) {
            if (v == null || !drawn(v)) return
            val r = RectF()
            if (v is AutoScaleTextView) {
                if (v.text.isNullOrEmpty() || !v.inkBounds(r)) return
            } else {
                r.set(0f, 0f, v.width.toFloat(), v.height.toFloat())
            }
            v.matrix.mapRect(r)
            r.offset(v.left.toFloat(), v.top.toFloat())
            out += what to r
        }
        when (k) {
            is ImageKeyView -> add("icon", k.img)
            is ImageTextKeyView -> {
                add("label", k.mainText)
                add("icon", k.img)
            }
            is TextKeyView -> add("label", k.mainText)
        }
        if (k is AltTextKeyView) add("symbol", k.altText)
        k.shortcutMarks.forEach { (slot, v) -> add("shortcut $slot", v) }
        return out
    }

    private fun drawn(v: View) = v.visibility == View.VISIBLE && v.alpha != 0f && v.width != 0

    // left out: [k]'s symbol and shortcut marks laid out but not drawn
    private fun leftOut(k: KeyView): List<String> =
        listOfNotNull((k as? AltTextKeyView)?.altText?.takeIf { it.visibility == View.VISIBLE && it.alpha == 0f }?.let { "symbol" }) +
            k.shortcutMarks.filter { it.value.alpha == 0f }.map { "shortcut ${it.key}" }

    private val dropped = sortedMapOf<String, Int>()

    // what [k] draws, none over another; its symbol never left out
    private fun checkKey(case: String, k: KeyView) {
        val d = drawn(k)
        val name = (k as? TextKeyView)?.mainText?.text ?: k.contentDescription
        for (i in d.indices) for (j in i + 1 until d.size) {
            assertTrue("$case key $name: ${d[i]} meets ${d[j]}", !RectF.intersects(d[i].second, d[j].second))
        }
        val out = leftOut(k)
        assertTrue("$case key $name: its symbol left out", "symbol" !in out)
        if (out.isNotEmpty()) dropped.merge("$case: $name ${out.joinToString()}", 1, Int::plus)
    }

    private fun check(board: Board, mode: Mode, h: KeyboardHarness) {
        var keys = 0
        for (choice in listOf(SwipeLayersChoice.ShortcutUpSymbolDown, SwipeLayersChoice.SymbolUpShortcutDown)) {
            prefs.set(AppPrefs.getInstance().keyboard.swipeLayers, choice)
            for (at in ShortcutMarkPosition.entries) {
                prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, at)
                h.idle()
                h.relayout()
                val case = "${board.name} ${mode.name} ${choice.name} ${at.name}"
                val shown = KeyboardHarness.all(h.inputView).filterIsInstance<KeyView>().filter { h.shown(it) && it.width > 0 }.toList()
                for (k in shown) checkKey(case, k)
                keys += shown.size
            }
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
        dropped.keys.forEach { println("  $it") }
    }

    @Test
    @Uat("KB-65")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun noKeyDrawsTwoThingsOverEachOtherUpright() {
        each(upright)
        assertEquals(emptyList<String>(), dropped.keys.filter { "symbol" in it })
    }

    @Test
    @Uat("KB-65")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun noKeyDrawsTwoThingsOverEachOtherTurned() {
        each(turned)
        assertEquals(emptyList<String>(), dropped.keys.filter { "symbol" in it })
    }
}
