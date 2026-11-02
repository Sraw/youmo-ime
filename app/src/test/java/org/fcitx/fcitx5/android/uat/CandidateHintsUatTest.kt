/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.CandidateBarHeight
import org.fcitx.fcitx5.android.input.candidates.floating.LabeledCandidateItemUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import splitties.dimensions.dp

/**
 * 候选词注音 off: a misreading's hint (角色 jué sè) above the candidate, as the setting's pinyin is, in the
 * bar's own height, the candidate's characters where its plain neighbours' are (the user, 2026-11-02).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class CandidateHintsUatTest {

    private var harness: KeyboardHarness? = null

    @After
    fun close() {
        harness?.close()
        ThemeManager.prefs.candidateTextSize.let { it.setValue(it.defaultValue) }
    }

    private val words = listOf("角色" to listOf("jué", "sè"), "脚色" to emptyList(), "教" to emptyList(), "叫" to emptyList(), "好" to listOf("hǎo"))

    private fun textOf(v: View, text: String): TextView? = when (v) {
        is TextView -> v.takeIf { it.text.toString() == text }
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { textOf(v.getChildAt(it), text) }
        else -> null
    }

    private fun shown(sp: Int, dark: Boolean, name: String?) {
        ThemeManager.prefs.candidateTextSize.setValue(sp)
        val h = KeyboardHarness(theme = if (dark) ThemePreset.PixelDark else ThemePreset.PixelLight).also { harness = it }.start()
        val bar = h.described(h.service.getString(R.string.expand_toolbar)).parent as View
        h.api.candidates = words.map { (w, r) -> CandidateWord("", w, "", readings = r) }.toTypedArray()
        h.emit(HostEvent.Candidates(words.size, h.api.candidates.toList()))
        h.relayout()
        // the bar keeps its height, and the hinted words stand where the plain ones do
        assertEquals("$sp sp", h.service.dp(CandidateBarHeight.dp(sp)), bar.height)
        val hinted = textOf(h.inputView, "角色")!!
        val plain = textOf(h.inputView, "脚色")!!
        assertEquals("$sp sp", plain.baseline, hinted.baseline)
        assertEquals(plain.height, hinted.height)
        if (name != null) h.capture("CandidateHintsUatTest/$name")
        h.close()
        harness = null
    }

    @Test
    fun portrait() {
        for (dark in listOf(false, true)) shown(20, dark, "hint_20sp_port_" + if (dark) "dark" else "light")
        shown(28, false, "hint_28sp_port_light")
        // the smallest size: the reading at its floor
        shown(14, false, "hint_14sp_port_light")
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun landscape() {
        for (dark in listOf(false, true)) shown(20, dark, "hint_20sp_land_" + if (dark) "dark" else "light")
    }

    @Test
    fun theFloatingWindowsCandidatesAreOneHeightHintedOrNot() {
        val ctx = RuntimeEnvironment.getApplication()
        val theme = ThemePreset.PixelLight
        val setup: TextView.() -> Unit = { textSize = 20f; setPadding(4, 2, 4, 2) }
        val heights = words.map { (w, r) ->
            LabeledCandidateItemUi(ctx, theme, setup).also { it.update(CandidateWord("1", w, "", readings = r), false) }.root.run {
                measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                measuredHeight
            }
        }
        assertEquals(heights.toString(), 1, heights.distinct().size)
    }
}
