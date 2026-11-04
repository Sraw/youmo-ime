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
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
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
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
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
 * The marks on every key of every keyboard (uat/02 KB-67), for every pair of 标点位置 and 快捷键图标位置
 * the settings page lets be chosen and as the keys are drawn (standard, 紧凑, floating, one-handed,
 * landscape): what each key draws, its label or icon, its symbol and its shortcuts' marks, as drawn
 * (moved, smaller, left out: KeyMarkYield), never two over each other. Their ink: a text's glyphs, an
 * icon's box. 滑动 is not gone through: it moves no mark.
 *
 * Nothing is left out: a shortcut with no room in its place is drawn in another free corner of its
 * key (KeyMarks.elsewhere); those moved are counted and printed (the report's numbers).
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

    // shortcuts drawn in another corner than their setting's (KeyMarks.elsewhere), by case and key
    private val moved = sortedMapOf<String, Int>()

    // [r], drawn on a face [w] by [h], in [slot]'s half each way it has one
    private fun inItsPlace(slot: KeyMarks.Slot, r: RectF, w: Int, h: Int): Boolean {
        val right = r.centerX() > w / 2f
        val bottom = r.centerY() > h / 2f
        return (slot.centred || right == slot.right) && bottom == slot.bottom
    }

    // the letters made smaller to keep clear (KeyMarkYield.letters: all of a keyboard's alike), by case
    private val smaller = sortedMapOf<String, Float>()

    // what [k] draws, none over another; its symbol never left out
    private fun checkKey(case: String, k: KeyView) {
        val d = drawn(k)
        val name = (k as? TextKeyView)?.mainText?.text ?: k.contentDescription
        for (i in d.indices) for (j in i + 1 until d.size) {
            assertTrue("$case key $name: ${d[i]} meets ${d[j]}", !RectF.intersects(d[i].second, d[j].second))
        }
        val elsewhere = k.shortcutMarks.entries.filter { (slot, v) ->
            val r = d.firstOrNull { it.first == "shortcut $slot" }?.second
            val face = v.parent as View
            r != null && !inItsPlace(slot, r, face.width, face.height)
        }
        if (elsewhere.isNotEmpty()) moved.merge("$case: $name ${elsewhere.joinToString { "shortcut ${it.key}" }}", 1, Int::plus)
        val out = leftOut(k)
        assertTrue("$case key $name: its symbol left out", "symbol" !in out)
        if (out.isNotEmpty()) dropped.merge("$case: $name ${out.joinToString()}", 1, Int::plus)
    }

    // every pair the settings page lets be chosen: apart, or 无
    private val legal = KeyMarkPosition.entries.flatMap { symbol ->
        KeyMarkPosition.entries.filter { it != symbol || it == KeyMarkPosition.None }.map { symbol to it }
    }

    // The two settings make the keyboard anew (FcitxInputMethodService.recreateInputViewPrefs). Under
    // Robolectric each old keyboard, about 3 MB, stays: its scroll views' scrollbar fade
    // (View.ScrollabilityCache, posted for 300 ms on) waits in the paused main looper, whose clock
    // idle() does not move; 62 for each of 90 keyboards ran a full run out of its 3 GB. On a device it
    // runs and lets go (a heap dump's only path; the clock moved, none was left). Moving the clock
    // after each would do too, with the symbol page and a table's keys set again after each rebuild: not
    // made anew here, its keys laid out again as a new one lays them out (check), the swipes' marks by
    // their own listener (KeySwipes). Not registered again: [check] runs once on a harness closed right
    // after it, whose service unregisters it on destroy anyway. The field read by name: renamed, this
    // fails loudly (NoSuchFieldException), never silently
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
            val all = KeyboardHarness.all(h.inputView).filterIsInstance<KeyView>().toList()
            // every key's symbol laid out anew, as the keyboard made anew with these settings has it
            all.filterIsInstance<AltTextKeyView>().forEach { it.dispatchConfigurationChanged(it.resources.configuration) }
            h.relayout()
            val case = "${board.name} ${mode.name} 标点${symbolAt.name} 快捷键${shortcutAt.name}"
            val shown = all.filter { h.shown(it) && it.width > 0 }
            for (k in shown) checkKey(case, k)
            shown.filterIsInstance<AltTextKeyView>().minOfOrNull { it.mainText.scaleX }?.takeIf { it < 1f }?.let { smaller[case] = it }
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
        dropped.keys.forEach { println("  $it") }
        println("KeyMarksEverywhere moved elsewhere (${moved.size}):")
        moved.keys.forEach { println("  $it") }
        println("KeyMarksEverywhere letters smaller (${smaller.size}):")
        smaller.forEach { (case, scale) -> println("  $case: ${"%.3f".format(scale)}") }
    }

    @Test
    @Uat("KB-67")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi")
    fun noKeyDrawsTwoThingsOverEachOtherUpright() {
        each(upright)
        assertEquals(emptyList<String>(), dropped.keys.toList())
    }

    @Test
    @Uat("KB-67")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun noKeyDrawsTwoThingsOverEachOtherTurned() {
        each(turned)
        assertEquals(emptyList<String>(), dropped.keys.toList())
    }

    // Not walked at a larger system font size: the keys' texts and marks are sized in dp (KeyView), and
    // upright at fontScale 1.3 the walk printed what it does at 1 line for line. As a third test here it
    // took the full run's JVM past its 3 GB
}
