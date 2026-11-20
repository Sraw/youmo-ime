/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.Manifest
import android.text.InputType
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.voice.VoiceSession
import org.fcitx.fcitx5.android.input.voice.VoiceStyle
import org.fcitx.fcitx5.android.uat.FakeRecognizer
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** A small phone on its side (640 × 360 dp) under gestures' 48 dp, at its lowest: goldens looked at for text cut off and rows crowded. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w640dp-h360dp-land-xhdpi")
class LowLandscapeScreenshotTest {

    @get:Rule
    val prefs = PrefsRule()

    private val voice = FakeRecognizer()
    private val haptics = VoiceStyle.haptics

    @After
    fun close() {
        VoiceSession.listening = null
        VoiceStyle.haptics = haptics
    }

    private fun dp(v: Int) = (v * RuntimeEnvironment.getApplication().resources.displayMetrics.density).roundToInt()

    private val panels: List<Pair<String, KeyboardHarness.() -> Unit>> = listOf(
        "symbols" to { tap(key("符")) },
        "emoji" to { tap(described(service.getString(R.string.a11y_key_emoji))) },
        "clipboard" to { clipboard() },
        "voice" to {
            tap(described(service.getString(R.string.voice_input)))
            advance(100)
        },
        "expanded" to {
            api.candidates = Array(60) { CandidateWord("", "字$it", "") }
            emit(HostEvent.Candidates(60, api.candidates.toList()))
            tap(described(service.getString(R.string.expand_candidates_list)))
        },
    )

    private fun shown(level: Int, name: String, inputType: Int = InputType.TYPE_CLASS_TEXT, keyboards: ImKeyboards.Chosen? = null, open: KeyboardHarness.() -> Unit = {}): Int {
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, level)
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        VoiceSession.listening = { events -> voice.also { v -> v.events = events } }
        VoiceStyle.haptics = { _, _ -> }
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        return KeyboardHarness(keyboards = keyboards).use { h ->
            h.start(inputType)
            // a clip another test stored after its harness emptied the database once showed here
            runBlocking {
                ClipboardManager.coroutineContext.job.children.forEach { it.join() }
                ClipboardManager.purgeMarked(*ClipboardManager.deleteAll(skipPinned = false))
            }
            val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, dp(48))).build()
            ViewCompat.dispatchApplyWindowInsets(h.inputView, insets)
            h.relayout()
            h.relayout()
            val keys = h.box(h.board).height()
            h.open()
            h.capture("LowLandscapeScreenshotTest/$name")
            keys
        }
    }

    @Test
    @Uat("LS-50")
    fun thePanelsAtTheFloor() {
        for ((panel, open) in panels) assertEquals(panel, dp(4 * 36).toFloat(), shown(60, "${panel}_36dp", open = open).toFloat(), 2f)
        assertEquals(dp(4 * 36).toFloat(), shown(60, "number_36dp", InputType.TYPE_CLASS_NUMBER).toFloat(), 2f)
    }

    @Test
    fun thePanelsAtTheLowestRow() {
        // 20: below 紧凑's 34, half a dp a level lower, down to 28 dp a row
        for ((panel, open) in panels) assertEquals(panel, dp(4 * 28).toFloat(), shown(20, "${panel}_28dp", open = open).toFloat(), 2f)
        assertEquals(dp(4 * 28).toFloat(), shown(20, "number_28dp", InputType.TYPE_CLASS_NUMBER).toFloat(), 2f)
    }

    @Test
    @Uat("LS-50")
    fun zhuyinsFiveRowsOf36() {
        val keys = shown(60, "zhuyin_36dp", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_FULL))
        assertEquals(dp(5 * 36).toFloat(), keys.toFloat(), 2f)
    }
}
