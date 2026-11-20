/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.graphics.drawable.ColorDrawable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.CandidateSpacing
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateLayout
import org.fcitx.fcitx5.android.input.candidates.floating.LabeledCandidateItemUi
import org.fcitx.fcitx5.android.input.keyboard.canvas.AndroidTextMeasurer
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneFonts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemesRule
import org.fcitx.fcitx5.android.input.keyboard.canvas.drawn
import org.fcitx.fcitx5.android.input.keyboard.canvas.keepPicture
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.BarLook
import org.fcitx.fcitx5.android.scene.engine.BarSample
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The candidate bar, the expanded candidates and the hardware keyboard's window as the theme has them; a theme
 * setting none of it (every built-in) the bar of before; the bar's views where the engine's sample bar has them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class BarThemeTest {

    @get:Rule
    val prefs = PrefsRule()

    @get:Rule
    val themes = SceneThemesRule()

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun bar(h: KeyboardHarness): RecyclerView = h.inputView.findViewById(R.id.candidate_view)

    private fun items(rv: RecyclerView): List<ViewGroup> = (0 until rv.childCount).map { rv.getChildAt(it) as ViewGroup }

    private fun text(item: ViewGroup): TextView = item.getChildAt(0) as TextView

    private fun runs(t: TextView): List<Pair<String, Int>> {
        val s = t.text as Spanned
        return s.getSpans(0, s.length, ForegroundColorSpan::class.java).map { s.subSequence(s.getSpanStart(it), s.getSpanEnd(it)).toString() to it.foregroundColor }
    }

    private fun KeyboardHarness.candidates(words: List<String>) = shown(words.map { CandidateWord("", it, "") })

    // [total] past the words: the bar stretches what it shows (CandidateSpacing.stretches)
    private fun KeyboardHarness.shown(words: List<CandidateWord>, total: Int = words.size) {
        api.candidates = words.toTypedArray()
        emit(HostEvent.Candidates(total, api.candidates.toList()))
        after(300)
        relayout()
    }

    // only with -Dyoumo.keepBar=true: not written on every run
    private fun keep(v: View, name: String) = keepPicture(java.lang.Boolean.getBoolean("youmo.keepBar"), v, "build/t6a-bar", name)

    @Test
    fun aThemeOfNoneOfItIsTheBarOfBefore() {
        for (theme in ThemeManager.BuiltinThemes) {
            assertEquals(theme.name, SceneBar.plain(theme), SceneBar.of(ctx, theme))
        }
        SceneThemes.source = { _, _, _ -> "not a theme" }
        val theme = ThemeManager.activeTheme
        assertEquals(SceneBar.plain(theme), SceneBar.of(ctx, theme))
        val ui = CandidateItemUi(ctx, theme)
        assertTrue(ui.root.background is android.graphics.drawable.StateListDrawable)
        ui.updateCandidate(CandidateWord("", "你", ""), 0)
        assertEquals(listOf("你" to theme.candidateTextColor), runs(text(ui.root as ViewGroup)))
    }

    @Test
    fun theBarIsTheThemes() {
        themes.styled(CAPSULES)
        KeyboardHarness().use { h ->
            h.start()
            h.candidates(WORDS)
            val rv = bar(h)
            val items = items(rv)
            assertEquals(WORDS.size, items.size)
            assertEquals(listOf("1 " to BLUE, "你" to WHITE), runs(text(items[0])))
            assertEquals(listOf("2 " to BLUE, "呢" to INK), runs(text(items[1])))
            assertEquals("呢", text(items[1]).contentDescription.toString())
            val d = h.service.resources.displayMetrics.density
            val top = (6 * d).toInt() + 2
            assertEquals(ACCENT, drawn(items[0]).getPixel(items[0].width / 2, top))
            assertEquals(WHITE, drawn(items[1]).getPixel(items[1].width / 2, top))
            assertEquals("the inset's room: no fill", 0, drawn(items[1]).getPixel(items[1].width / 2, 1))
            val room = items[1].left - items[0].right
            assertEquals((4 * d).toInt() + maxOf(1, (1 * d).toInt()), room)
            val barBitmap = drawn(h.inputView)
            val at = IntArray(2).also(rv::getLocationInWindow)
            val y = at[1] + rv.height / 2
            assertEquals("the bar's base between, no line", BASE, barBitmap.getPixel(at[0] + items[0].right + room / 2, y))
            keep(h.inputView, "capsules")
        }
    }

    @Test
    fun theExpandedCandidatesAreTheThemes() {
        themes.styled(CAPSULES)
        KeyboardHarness().use { h ->
            h.start()
            h.candidates(List(60) { "字$it" })
            h.tap(h.described(h.service.getString(R.string.expand_candidates_list)))
            h.after(300)
            h.relayout()
            val layout = h.inputView.findViewById<ViewGroup>(R.id.expanded_candidate_view)
            assertEquals(PANEL, (layout.background as ColorDrawable).color)
            val cells = KeyboardHarness.all(layout).filter { it is TextView && h.shown(it) && (it.text as? Spanned)?.toString()?.contains("字") == true }.toList()
            assertTrue(cells.size > 10)
            for (c in cells.map { it as TextView }) {
                val (label, words) = runs(c)
                assertEquals(BLUE, label.second)
                assertEquals("${words.first.removePrefix("字").toInt() + 1} ", label.first)
                assertEquals(INK, words.second)
            }
            val cell = cells.first().parent as View
            val d = h.service.resources.displayMetrics.density
            assertEquals(WHITE, drawn(cell).getPixel(cell.width / 2, (6 * d).toInt() + 2))
            assertNull((layout as ExpandedCandidateLayout).embeddedKeyboard.background)
            keep(h.inputView, "expanded")
        }
    }

    @Test
    fun thePhysicalKeyboardsWindowIsTheThemes() {
        themes.styled(CAPSULES)
        val theme = ThemeManager.activeTheme
        val ui = LabeledCandidateItemUi(ctx, theme, {})
        ui.root.layoutParams = ViewGroup.LayoutParams(200, 100)
        ui.root.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        ui.update(CandidateWord("1.", "你", ""), active = true, first = true)
        assertEquals(listOf("1." to SELECTED, "你" to SELECTED), runs(ui.root))
        ui.root.layout(0, 0, 200, 100)
        assertEquals(SELECTED_FILL, drawn(ui.root).getPixel(100, 50))
        ui.update(CandidateWord("2.", "呢", ""), active = false, first = false)
        assertEquals(listOf("2." to BLUE, "呢" to INK), runs(ui.root))
        assertEquals(WHITE, drawn(ui.root).getPixel(100, 50))
        themes.styled("<style select=\"candidate\" label-style=\"dot\"/>")
        val dotted = LabeledCandidateItemUi(ctx, theme, {})
        dotted.update(CandidateWord("1.", "你", ""), active = false)
        assertEquals("• 你", dotted.root.text.toString())
        assertEquals("the dot not read out", "你", dotted.root.contentDescription.toString())
        assertNull(ui.root.contentDescription)
    }

    @Test
    fun aCandidatesFontIsNamedAsTheKeys() {
        val shown = ThemeManager.shownLook(ThemeManager.activeTheme)
        val look = shown.copy(text = shown.text.copy(keyFont = "/t/k.ttf", candidateFont = "/t/c.ttf", candidateWeight = 700))
        assertEquals("/t/c.ttf", SceneFonts.path(look, LookTheme.CANDIDATE_FONT))
        // one font, not two alike: the bar's for `candidate` (SceneBar.typeface) is the code's (ThemeAssets.candidateTypeface)
        assertSame(
            ThemeAssets.candidateTypeface(look),
            ThemeAssets.typeface(look, SceneFonts.path(look, LookTheme.CANDIDATE_FONT), look.text.candidateWeight),
        )
        assertEquals("/t/k.ttf", SceneFonts.path(look, LookTheme.KEY_FONT))
        assertNull(SceneFonts.path(look, "system"))
        assertNull(SceneFonts.path(look, "kai"))
    }

    @Test
    fun theBarsLookIsWorkedOutOnceWhileTheSame() {
        val first = SceneBar.of(ctx, ThemeManager.activeTheme)
        assertSame(first, SceneBar.of(ctx, ThemeManager.activeTheme))
        themes.styled(CAPSULES)
        val capsules = SceneBar.of(ctx, ThemeManager.activeTheme)
        assertTrue(capsules !== first)
        assertEquals(0xFF00897B.toInt(), capsules.first.fill!!.value)
    }

    // 空格上屏联想词: the prediction space takes drawn as the first, past an emoji; none when space is a space
    @Test
    @Uat("PY-160")
    fun theBarDrawsAsFirstTheOneItIsToldLeads() {
        themes.styled(CAPSULES)
        KeyboardHarness().use { h ->
            h.start()
            h.api.candidates = WORDS.map { CandidateWord("", it, "") }.toTypedArray()
            fun leads(lead: Int): List<String> {
                h.emit(HostEvent.Candidates(WORDS.size, h.api.candidates.toList(), predicting = true, lead = lead))
                h.after(300)
                h.relayout()
                return items(bar(h)).filter { runs(text(it)).last().second == WHITE }.map { text(it).text.toString().substringAfter(' ') }
            }
            assertEquals(listOf(WORDS[1]), leads(1))
            assertEquals(emptyList<String>(), leads(-1))
            assertEquals(listOf(WORDS[0]), leads(0))
        }
    }

    @Test
    fun aHolderBoundElsewhereTakesThatPlacesLook() {
        themes.styled(CAPSULES)
        val holder = CandidateViewHolder(CandidateItemUi(ctx, ThemeManager.activeTheme))
        val word = CandidateWord("", "你", "")
        holder.update(0, word)
        assertEquals(listOf("1 " to BLUE, "你" to WHITE), runs(text(holder.ui.root as ViewGroup)))
        // the same candidate, now second: its number and the plain look
        holder.update(1, word)
        assertEquals(listOf("2 " to BLUE, "你" to INK), runs(text(holder.ui.root as ViewGroup)))
        holder.clear()
        assertEquals("", text(holder.ui.root as ViewGroup).text.toString())
        assertNull(text(holder.ui.root as ViewGroup).contentDescription)
        // with no labels, third to fourth is no change: not drawn again
        themes.styled("<style select=\"candidate\" color=\"#263238\"/>")
        val plain = CandidateViewHolder(CandidateItemUi(ctx, ThemeManager.activeTheme))
        plain.update(2, word)
        val shown = text(plain.ui.root as ViewGroup).text
        plain.update(3, word)
        assertTrue(shown === text(plain.ui.root as ViewGroup).text)
    }

    // structure, not pixels: each box within a pixel and each colour the same as the engine's sample bar; a bar
    // with more behind stretches each candidate, no narrower than the sample's
    @Test
    fun theBarsViewsAreTheEnginesSampleBar() {
        for ((name, styles) in listOf("none" to null, "capsules" to CAPSULES, "dotted" to DOTTED)) {
            for (stretched in listOf(false, true)) {
                if (styles == null) themes.real() else themes.styled(styles)
                sampleBar("$name stretched=$stretched", stretched)
            }
        }
    }

    private fun sampleBar(what: String, stretched: Boolean) = KeyboardHarness().use { h ->
        h.start()
        val samples = listOf(0, 3, 2).map { BarSample.SAMPLES[it] }
        assertTrue(samples[1].comment.isNotEmpty())
        h.shown(samples.map { CandidateWord("", it.words, it.comment) }, total = if (stretched) samples.size + 1 else samples.size)
        val rv = bar(h)
        val items = items(rv)
        val theme = ThemeManager.activeTheme
        val look = SceneBar.of(h.service, theme)
        val m = BarSample.Metrics(
            width = rv.width.toDouble(), height = rv.height.toDouble(), density = h.service.resources.displayMetrics.density.toDouble(),
            textSize = text(items[0]).textSize.toDouble(), paddingDp = KawaiiBarComponent.candidatePaddingDp().toDouble(),
            minWidthDp = CandidateSpacing.TOUCH_FLOOR_DP.toDouble(),
        )
        val scene: Scene = BarSample.scene(look, m, AndroidTextMeasurer(SceneFonts(ThemeManager.shownLook(theme))), samples)
        assertEquals(what, samples.size, items.size)
        val left0 = items[0].left
        for ((i, key) in scene.keys.withIndex()) {
            val v = items[i]
            val box = "$what $i: ${key.bounds} vs ${v.left - left0}+${v.width}"
            if (stretched) {
                assertTrue(box, v.width >= key.bounds.width - 1)
            } else {
                assertTrue(box, abs(key.bounds.left - (v.left - left0)) <= 1 && abs(key.bounds.width - v.width) <= 1)
            }
            val ops = key.faces.getValue(emptySet())
            ops.filterIsInstance<Op.Fill>().singleOrNull()?.let { fill ->
                assertEquals("$what $i's fill", fill.color.value, drawn(v).getPixel(v.width / 2, (fill.shape.bounds.top + 2).toInt()))
            }
            // the comment's space is the sample's run's, no span of the view's: the runs compared trimmed
            val texts = ops.filterIsInstance<Op.Text>().map { it.text.trim() to it.color.value }
            assertEquals("$what $i's text", texts, runs(text(v)).map { (t, c) -> t.trim() to c })
            assertEquals("$what $i's whole text", ops.filterIsInstance<Op.Text>().joinToString("") { it.text }, text(v).text.toString())
        }
    }

    companion object {
        private val WORDS = listOf("你", "呢", "那", "拿", "哪")
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val INK = 0xFF263238.toInt()
        private const val BLUE = 0xFF1565C0.toInt()
        private const val ACCENT = 0xFF00897B.toInt()
        private const val BASE = 0xFFECEFF1.toInt()
        private const val PANEL = 0xFFCFD8DC.toInt()
        private const val SELECTED = 0xFFFFEB3B.toInt()
        private const val SELECTED_FILL = 0xFF3E2723.toInt()

        private val CAPSULES = """
            <style select="bar" fill="#eceff1"/>
            <style select="candidate" fill="#ffffff" color="#263238" radius="50%" inset="6dp 2dp" gap="4dp" label-style="number"/>
            <style select="candidate:first" fill="#00897b" color="#ffffff"/>
            <style select="candidate label" color="#1565c0"/>
            <style select="candidate:selected" fill="#3e2723" color="#ffeb3b"/>
            <style select="candidates-divider" fill="transparent"/>
            <style select="candidates-panel" fill="#cfd8dc"/>
        """.trimIndent()

        private val DOTTED = """
            <style select="candidate" label-style="dot" padding="0 14dp" weight="700"/>
            <style select="candidate comment" color="#c62828"/>
        """.trimIndent()
    }
}
