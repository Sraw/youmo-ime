/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A shortcut page's cap is the keyboard's key (060-render-p2 S7a): on 大千 (five rows) and 手写 (five rows),
 * standard and 紧凑, each key's marks the cap keeps are the ones the keyboard's scene keeps on that key.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyCapsBoardTest {

    @get:Rule
    val prefs = PrefsRule()

    private val ctx get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_FcitxAppTheme)

    // what a key draws besides its face and label: its marks, by what they show and where on the key, px
    private fun marks(k: KeyNode): List<String> = k.ops(false).mapNotNull {
        val at = { x: Double, y: Double -> "at %.1f,%.1f".format(x - k.bounds.left, y - k.bounds.top) }
        when (it) {
            // the space's voice badge the input method's (a voice pack), not a mark the page sets
            is Op.Icon -> if (it.name == "voice") null else "icon ${it.name} " + at(it.box.left, it.box.top)
            is Op.Text -> if (it.text == k.label) null else "text ${it.text} " + at(it.x, it.baseline)
            else -> null
        }
    }.sorted()

    private fun cap(shortcut: String, layout: String): SceneKeyCell {
        val shortcuts = KeyShortcuts.parse(AppPrefs.getInstance().internal.keyShortcuts.getValue())
        val f = KeyCaps.key(ctx, "", "", marked = false, dimmed = false, shortcut = KeyCaps.Shortcut(shortcut, shortcuts, layout = layout)) as FrameLayout
        val cell = (0 until f.childCount).map(f::getChildAt).filterIsInstance<SceneKeyCell>().single()
        cell.measure(View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY))
        cell.layout(0, 0, SIZE, SIZE)
        return cell
    }

    private fun check(what: String, layout: String, im: InputMethodEntry, keyboards: ImKeyboards.Chosen?) {
        // the shortcuts' marks top right, the symbols bottom right: both kinds drawn
        prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, KeyMarkPosition.TopRight)
        prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, KeyMarkPosition.BottomRight)
        prefs.set(AppPrefs.getInstance().keyboard.swipeLayers, SwipeLayersChoice.SymbolUpShortcutDown)
        for (compact in listOf(false, true)) {
            if (compact) prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
            KeyboardHarness(keyboards = keyboards, im = im).use { h ->
                h.start()
                val keys = h.keys().filterIsInstance<SceneShownKey>().map { it.node }.filter { it.shortcut != null && it.copy == 0 }
                    // Return's icon the field's action (完成 here), the page's a plain field's
                    .filter { it.shortcut != "return" }
                assertTrue("$what: keys with shortcuts", keys.isNotEmpty())
                var marked = 0
                for (k in keys) {
                    val c = cap(k.shortcut!!, layout)
                    assertEquals("$what compact $compact ${k.shortcut}", marks(k), marks(c.keyNode!!))
                    if (marks(k).isNotEmpty()) marked++
                }
                assertTrue("$what compact $compact: keys with marks", marked > 0)
            }
        }
    }

    @Test
    fun daqiansCapsKeepTheKeyboardsMarks() {
        check("大千", KeyNames.ZHUYIN, KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_FULL))
    }

    @Test
    fun handwritingsCapsKeepTheKeyboardsMarks() {
        val hw = InputMethodEntry(InputMethodCatalog.HANDWRITING, "手写", "", "", "手", "zh_CN", "androidengine", false)
        check("手写", KeyNames.HANDWRITING, hw, null)
    }

    private companion object {
        const val SIZE = 154
    }
}
