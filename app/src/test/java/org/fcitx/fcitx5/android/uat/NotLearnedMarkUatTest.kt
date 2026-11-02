/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.ui.NotLearnedMark
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The bar's 「不学」 mark (uat/15 PR-48, D5): leftmost on the idle bar and the candidates, before a
 * password's digits; held, a bubble says why (docs/words.md「这里不学」). Each placement drawn light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class NotLearnedMarkUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var harness: KeyboardHarness? = null

    @After
    fun close() {
        harness?.close()
    }

    private fun keyboard(dark: Boolean = false) =
        KeyboardHarness(theme = if (dark) ThemePreset.PixelDark else ThemePreset.PixelLight).also { harness = it }

    private fun KeyboardHarness.mark(): NotLearnedMark? =
        KeyboardHarness.all(inputView).filterIsInstance<NotLearnedMark>().firstOrNull { shown(it) }

    private fun KeyboardHarness.s(id: Int) = service.getString(id)

    // the bubble a long press on the mark shows: its text
    private fun KeyboardHarness.held(): String {
        mark()!!.performLongClick()
        idle()
        val bubble = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow!!
        assertTrue(bubble.isShowing)
        return (bubble.contentView as TextView).text.toString()
    }

    private fun KeyboardHarness.candidates(vararg words: String) {
        api.candidates = Array(words.size) { CandidateWord("", words[it], "") }
        emit(HostEvent.Candidates(words.size, api.candidates.toList()))
        idle()
        relayout()
    }

    private fun search(dark: Boolean) = keyboard(dark).start(imeOptions = EditorInfo.IME_ACTION_SEARCH).also { it.relayout() }

    @Test
    @Uat("PR-48")
    fun anOrdinaryFieldHasNoMark() {
        val h = keyboard().start()
        assertNull(h.mark())
        h.candidates("你好", "拟好")
        assertNull(h.mark())
    }

    private fun theIdleBarsMarkIsLeftmost(dark: Boolean) {
        val h = search(dark)
        val mark = h.mark()!!
        assertTrue(h.box(mark).left <= h.box(h.bar).left + 1)
        assertTrue(h.box(mark).right <= h.box(h.described(h.s(R.string.expand_toolbar))).left)
        assertEquals(h.s(R.string.not_learned_no_predict), h.held())
        h.capture("NotLearnedMarkUatTest/idle_" + if (dark) "dark" else "light")
    }

    @Test
    @Uat("PR-48")
    fun theIdleBarsMarkIsLeftmostAndSaysASearchLearnsNo联想() = theIdleBarsMarkIsLeftmost(dark = false)

    @Test
    @Uat("PR-48")
    fun theIdleBarsMarkDark() = theIdleBarsMarkIsLeftmost(dark = true)

    private fun theCandidatesMarkComesFirst(dark: Boolean) {
        val h = search(dark)
        h.candidates("你好", "拟好", "妮好")
        val mark = h.mark()!!
        val first = h.entry("你好")!!
        assertTrue(h.box(mark).right <= h.box(first).left)
        h.capture("NotLearnedMarkUatTest/candidates_" + if (dark) "dark" else "light")
    }

    @Test
    @Uat("PR-48")
    fun theCandidatesMarkComesBeforeTheFirstCandidate() = theCandidatesMarkComesFirst(dark = false)

    @Test
    @Uat("PR-48")
    fun theCandidatesMarkDark() = theCandidatesMarkComesFirst(dark = true)

    private fun aPasswordsMarkIsBeforeItsDigits(dark: Boolean) {
        val h = keyboard(dark).start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
        h.relayout()
        val mark = h.mark()!!
        val one = h.key("1")
        val zero = h.key("0")
        assertTrue(h.box(mark).right <= h.box(one).left)
        // the row made narrower for it, not pushed past the bar's end
        assertTrue(h.box(zero).right <= h.box(h.bar).right)
        assertEquals(h.s(R.string.not_learned_password), h.held())
        h.capture("NotLearnedMarkUatTest/password_" + if (dark) "dark" else "light")
    }

    @Test
    @Uat("PR-48")
    fun aPasswordsMarkIsBeforeItsDigitsWhichMakeRoom() = aPasswordsMarkIsBeforeItsDigits(dark = false)

    @Test
    @Uat("PR-48")
    fun aPasswordsMarkDark() = aPasswordsMarkIsBeforeItsDigits(dark = true)

    @Test
    @Uat("PR-48")
    fun eachReasonInItsOwnWords() {
        val h = keyboard()
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        assertEquals(h.s(R.string.not_learned_no_pairs), h.held())
        h.start(InputType.TYPE_CLASS_NUMBER)
        assertEquals(h.s(R.string.not_learned_no_pairs), h.held())
        h.start(packageName = h.service.packageName)
        assertEquals(h.s(R.string.not_learned_own_app), h.held())
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, imeOptions = EditorInfo.IME_ACTION_SEARCH)
        assertEquals(h.s(R.string.not_learned_no_pairs) + "\n" + h.s(R.string.not_learned_no_predict), h.held())
    }

    @Test
    @Uat("PR-48", "PR-17")
    fun habitsOffMarksEveryFieldTillOn() {
        val habits = AppPrefs.getInstance().internal.useHabits
        prefs.set(habits, true)
        val h = keyboard().start()
        assertNull(h.mark())
        habits.setValue(false)
        h.idle()
        assertEquals(h.s(R.string.not_learned_habits_off), h.held())
        habits.setValue(true)
        h.idle()
        assertNull(h.mark())
    }
}
