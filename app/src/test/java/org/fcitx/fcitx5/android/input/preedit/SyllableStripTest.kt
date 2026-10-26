/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The code row's two parts: the code, and the 14 keys' syllables at its right end, never over 60%
 * of the row, gone with nothing to take. A tap takes the syllable with its list's id, as 九键's
 * column; one released on a list since replaced is dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class SyllableStripTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun typing(vararg syllables: String, id: Int = 1, im: String = Engines.PINYIN) =
        EngineBridge.SyllableState(im, composing = true, syllables = syllables.toList(), id = id)

    private fun SyllableStrip.item(i: Int) = (getChildAt(0) as ViewGroup).getChildAt(i) as TextView

    private fun tap(view: View) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(0L, 0L, action, 1f, 1f, 0).also {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
    }

    @Test
    fun theRowIsTheCodeAndTheSyllablesEachWithItsId() {
        val row = CodeRowUi(context, ThemePreset.PixelDark) {}
        assertTrue(row.root.findViewById<View>(R.id.code_row_text) != null)
        assertTrue(row.root.findViewById<View>(R.id.code_row_syllables) === row.syllables)
        // the code first, the syllables at the right end
        assertEquals(R.id.code_row_text, (row.root as ViewGroup).getChildAt(0).id)
        assertEquals(R.id.code_row_syllables, (row.root as ViewGroup).getChildAt(1).id)
    }

    @Test
    fun goneWithNothingToTakeOrForAnotherInputMethod() {
        val strip = SyllableStrip(context, ThemePreset.PixelDark)
        assertEquals(View.GONE, strip.visibility)
        strip.show(typing("ni", "mi"))
        assertEquals(View.VISIBLE, strip.visibility)
        assertEquals("ni", strip.item(0).text.toString())
        strip.show(EngineBridge.SyllableState(Engines.PINYIN))
        assertEquals(View.GONE, strip.visibility)
        // typing, all keys taken
        strip.show(typing())
        assertEquals(View.GONE, strip.visibility)
        // 九键's are its column's
        strip.show(typing("ni", im = Engines.T9))
        assertEquals(View.GONE, strip.visibility)
    }

    @Test
    fun theSyllablesTakeAtMostSixTenthsOfTheRow() {
        val row = CodeRowUi(context, ThemePreset.PixelDark) {}
        row.syllables.show(typing(*Array(30) { "zhuang" }))
        val width = 1000
        row.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        assertEquals((width * SyllableStrip.MAX_SHARE).toInt(), row.syllables.measuredWidth)
        assertEquals(width - row.syllables.measuredWidth, row.root.findViewById<View>(R.id.code_row_text).measuredWidth)
        // none: the code takes the whole row
        row.syllables.show(EngineBridge.SyllableState())
        row.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        assertEquals(width, row.root.findViewById<View>(R.id.code_row_text).measuredWidth)
    }

    @Test
    fun aTapGivesFeedbackThenSendsTheSyllablesKey() {
        val strip = SyllableStrip(context, ThemePreset.PixelDark)
        val sent = ArrayList<KeyAction>()
        strip.show(typing("ni", "mi", "o", id = 3))
        val order = ArrayList<String>()
        strip.feedback = { order += "feedback" }
        strip.onPick = { sent += it; order += "pick" }
        strip.item(1).performClick()
        assertEquals(listOf("feedback", "pick"), order)
        // fcitx's key for a Unicode character: UNICODE (0x01000000) plus its code point
        assertEquals(listOf(KeyAction.SymAction(KeySym(UNICODE + Keyboard.syllableKey(1, 3).code), KeyStates.Virtual)), sent)
        assertEquals(SyllableStrip.pick(typing(id = 3), 1), sent.single())
    }

    @Test
    fun aClickReleasedOnAListSinceReplacedIsDropped() {
        val strip = SyllableStrip(context, ThemePreset.PixelDark)
        val sent = ArrayList<KeyAction>()
        strip.onPick = { sent += it }
        // in a window, where View posts the click of a released item to the looper, as on a phone
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(FrameLayout(context).apply { addView(strip) })
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(1, TimeUnit.SECONDS)
        strip.show(typing("ni", "mi", id = 1))
        tap(strip.item(0))
        val next = typing("ni", "mi", id = 2)
        strip.show(next)
        looper.idle()
        assertEquals(emptyList<KeyAction>(), sent)
        tap(strip.item(0))
        looper.idle()
        assertEquals(listOf(SyllableStrip.pick(next, 0)), sent)
    }

    @Test
    fun talkBackReadsASyllableAsAPinyinButton() {
        val strip = SyllableStrip(context, ThemePreset.PixelDark)
        strip.show(typing("ni"))
        assertEquals(context.getString(R.string.a11y_syllable, "ni"), strip.item(0).contentDescription)
        val info = AccessibilityNodeInfo.obtain()
        strip.item(0).onInitializeAccessibilityNodeInfo(info)
        assertEquals(Button::class.java.name, info.className)
    }

    @Test
    fun aSyllableIsATouchWide() {
        val strip = SyllableStrip(context, ThemePreset.PixelDark)
        strip.show(typing("e"))
        assertEquals((40 * context.resources.displayMetrics.density).toInt(), strip.item(0).minWidth)
    }

    @Test
    fun aPressHeldWhileTheListIsReplacedTakesNothingOnRelease() {
        val strip = SyllableStrip(context, ThemePreset.PixelDark)
        val sent = ArrayList<KeyAction>()
        strip.onPick = { sent += it }
        Robolectric.buildActivity(Activity::class.java).setup().get()
            .setContentView(FrameLayout(context).apply { addView(strip, FrameLayout.LayoutParams(400, 100)) })
        val looper = shadowOf(Looper.getMainLooper())
        strip.show(typing("ni", "mi", id = 1))
        looper.idleFor(1, TimeUnit.SECONDS)
        // through the strip, as a finger: the item is the touch target the cancel reaches
        fun send(action: Int) = MotionEvent.obtain(0L, 0L, action, 5f, 5f, 0).also {
            strip.dispatchTouchEvent(it)
            it.recycle()
        }
        send(MotionEvent.ACTION_DOWN)
        strip.show(typing("ni", "mi", id = 2))
        send(MotionEvent.ACTION_UP)
        looper.idle()
        assertEquals(emptyList<KeyAction>(), sent)
    }

    @Test
    fun aTapOnTheCodeStillMovesTheCursorBesideTheSyllables() {
        val row = CodeRowUi(context, ThemePreset.PixelDark) {}
        val taps = ArrayList<Int>()
        row.onTap = { taps += it }
        Robolectric.buildActivity(Activity::class.java).setup().get()
            .setContentView(FrameLayout(context).apply { addView(row.root, FrameLayout.LayoutParams(1000, 100)) })
        val looper = shadowOf(Looper.getMainLooper())
        row.update(FcitxEvent.InputPanelEvent.Data(FormattedText(arrayOf("ni hao"), intArrayOf(0), 6), FormattedText.Empty, FormattedText.Empty, emptyArray()))
        row.syllables.show(typing("ni", "mi"))
        looper.idleFor(1, TimeUnit.SECONDS)
        assertEquals(View.VISIBLE, row.syllables.visibility)
        // through the new root, as a finger: the code's part on the left takes it
        fun tapAt(x: Float) {
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(0L, 0L, action, x, 5f, 0).also {
                    row.root.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
        }
        tapAt(1f)
        assertEquals(listOf(0), taps)
        // the syllables' part beside it is not the code's
        tapAt(row.syllables.left + 5f)
        assertEquals(listOf(0), taps)
    }

    @Test
    fun shuangpinsSyllablesShowUnlessTheKeyboardOffersThemItself() {
        val strip = SyllableStrip(context, ThemePreset.PixelDark)
        // the 18 keys: 双拼's grouped session answers as 双拼
        strip.show(typing("ni", "mi", im = Engines.SHUANGPIN))
        assertEquals(View.VISIBLE, strip.visibility)
        assertEquals("mi", strip.item(1).text.toString())
        // 九键双拼's column has them: shown again as the keyboard changes
        strip.owned = true
        assertEquals(View.GONE, strip.visibility)
        strip.show(typing("ni", im = Engines.SHUANGPIN))
        assertEquals(View.GONE, strip.visibility)
        strip.owned = false
        assertEquals(View.VISIBLE, strip.visibility)
        // other input methods still none
        strip.show(typing("ni", im = Engines.WUBI))
        assertEquals(View.GONE, strip.visibility)
    }
}
