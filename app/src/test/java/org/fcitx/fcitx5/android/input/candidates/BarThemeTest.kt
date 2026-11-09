/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateLayout
import org.fcitx.fcitx5.android.input.candidates.floating.LabeledCandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fcitx.fcitx5.android.input.keyboard.canvas.AndroidTextMeasurer
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneFonts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.BarLook
import org.fcitx.fcitx5.android.scene.engine.BarSample
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * 060-render-p3 T6a in the app: the candidate bar, the expanded candidates and the physical keyboard's
 * window drawn as the theme has its candidates ([BarLook]); a theme that sets none of it, every built-in
 * theme of 3.0's look, has the bar of before (its pixels: BarUatTest's and CandidateHintsUatTest's goldens);
 * and the bar's views hold each candidate where the engine's sample bar has it, in its colours.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class BarThemeTest {

    @get:Rule
    val prefs = PrefsRule()

    private val realSource = SceneThemes.source

    @After
    fun tearDown() {
        SceneThemes.source = realSource
    }

    // a theme 3.0's file and [styles] in it
    private fun styled(styles: String) {
        SceneThemes.source = { look, name, dark -> LookTheme.xml(look, name, dark).replace("</theme>", "$styles\n</theme>") }
    }

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun bar(h: KeyboardHarness): RecyclerView = h.inputView.findViewById(R.id.candidate_view)

    private fun items(rv: RecyclerView): List<ViewGroup> = (0 until rv.childCount).map { rv.getChildAt(it) as ViewGroup }

    private fun text(item: ViewGroup): TextView = item.getChildAt(0) as TextView

    private fun draw(v: View): Bitmap = Bitmap.createBitmap(maxOf(1, v.width), maxOf(1, v.height), Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

    // the colour each run of a candidate's text is drawn in, by its text
    private fun runs(t: TextView): List<Pair<String, Int>> {
        val s = t.text as Spanned
        return s.getSpans(0, s.length, ForegroundColorSpan::class.java).map { s.subSequence(s.getSpanStart(it), s.getSpanEnd(it)).toString() to it.foregroundColor }
    }

    private fun KeyboardHarness.candidates(words: List<String>) {
        api.candidates = Array(words.size) { CandidateWord("", words[it], "") }
        emit(HostEvent.Candidates(words.size, api.candidates.toList()))
        after(300)
        relayout()
    }

    // the picture for the reviewer's eye, in place of an emulator's (060-render-p3 T6a's)
    private fun keep(v: View, name: String) {
        val dir = File("build/t6a-bar").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use { draw(v).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun aThemeOfNoneOfItIsTheBarOfBefore() {
        // every built-in theme, its look made a 4.0 theme as the app makes it: the bar of its 3.0 colours
        for (theme in ThemeManager.BuiltinThemes) {
            assertEquals(theme.name, SceneBar.plain(theme), SceneBar.of(ctx, theme))
        }
        // a look that made no theme: the same, worked out of its colours
        SceneThemes.source = { _, _, _ -> "not a theme" }
        val theme = ThemeManager.activeTheme
        assertEquals(SceneBar.plain(theme), SceneBar.of(ctx, theme))
        // the item drawn as before: the pressed colour over the cell, no fill
        val ui = CandidateItemUi(ctx, theme)
        assertTrue(ui.root.background is android.graphics.drawable.StateListDrawable)
        ui.updateCandidate(CandidateWord("", "你", ""), 0)
        assertEquals(listOf("你" to theme.candidateTextColor), runs(text(ui.root as ViewGroup)))
    }

    @Test
    fun theBarIsTheThemes() {
        styled(CAPSULES)
        prefs.set(AppPrefs.getInstance().keyboard.horizontalCandidateStyle, HorizontalCandidateMode.NeverFillWidth)
        KeyboardHarness().use { h ->
            h.start()
            h.candidates(WORDS)
            val rv = bar(h)
            val items = items(rv)
            assertEquals(WORDS.size, items.size)
            // numbered, the number in its own colour; the first in white on the accent
            assertEquals(listOf("1 " to BLUE, "你" to WHITE), runs(text(items[0])))
            assertEquals(listOf("2 " to BLUE, "呢" to INK), runs(text(items[1])))
            // a pill inset 6dp top and bottom: its fill at the middle near its top, the bar's base beside it
            val d = h.service.resources.displayMetrics.density
            val top = (6 * d).toInt() + 2
            assertEquals(ACCENT, draw(items[0]).getPixel(items[0].width / 2, top))
            assertEquals(WHITE, draw(items[1]).getPixel(items[1].width / 2, top))
            assertEquals("the inset's room: no fill", 0, draw(items[1]).getPixel(items[1].width / 2, 1))
            // 4dp more between two, no line drawn there
            val room = items[1].left - items[0].right
            assertEquals((4 * d).toInt() + maxOf(1, (1 * d).toInt()), room)
            val barBitmap = draw(h.inputView)
            val at = IntArray(2).also(rv::getLocationInWindow)
            val y = at[1] + rv.height / 2
            assertEquals("the bar's base between, no line", BASE, barBitmap.getPixel(at[0] + items[0].right + room / 2, y))
            keep(h.inputView, "capsules")
        }
    }

    @Test
    fun theExpandedCandidatesAreTheThemes() {
        styled(CAPSULES)
        KeyboardHarness().use { h ->
            h.start()
            h.candidates(List(60) { "字$it" })
            h.tap(h.described(h.service.getString(R.string.expand_candidates_list)))
            h.after(300)
            h.relayout()
            val layout = h.inputView.findViewById<ViewGroup>(R.id.expanded_candidate_view)
            assertEquals(PANEL, (layout.background as ColorDrawable).color)
            // the cells: their numbers, of the whole list (the bar's first past what it showed), their fill
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
            assertEquals(WHITE, draw(cell).getPixel(cell.width / 2, (6 * d).toInt() + 2))
            // no line beside the keys' column: transparent
            assertNull((layout as ExpandedCandidateLayout).embeddedKeyboard.background)
            keep(h.inputView, "expanded")
        }
    }

    @Test
    fun thePhysicalKeyboardsWindowIsTheThemes() {
        styled(CAPSULES)
        val theme = ThemeManager.activeTheme
        val ui = LabeledCandidateItemUi(ctx, theme, {})
        ui.root.layoutParams = ViewGroup.LayoutParams(200, 100)
        ui.root.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        ui.update(CandidateWord("1.", "你", ""), active = true, first = true)
        // selected: the theme's :selected; its label the theme's number style (fcitx's own label)
        assertEquals(listOf("1." to SELECTED, "你" to SELECTED), runs(ui.root))
        ui.root.layout(0, 0, 200, 100)
        assertEquals(SELECTED_FILL, draw(ui.root).getPixel(100, 50))
        ui.update(CandidateWord("2.", "呢", ""), active = false, first = false)
        assertEquals(listOf("2." to BLUE, "呢" to INK), runs(ui.root))
        assertEquals(WHITE, draw(ui.root).getPixel(100, 50))
        // dot: a dot for fcitx's label
        styled("<style select=\"candidate\" label-style=\"dot\"/>")
        val dotted = LabeledCandidateItemUi(ctx, theme, {})
        dotted.update(CandidateWord("1.", "你", ""), active = false)
        assertEquals("• 你", dotted.root.text.toString())
    }

    @Test
    fun aHolderBoundElsewhereTakesThatPlacesLook() {
        styled(CAPSULES)
        val holder = CandidateViewHolder(CandidateItemUi(ctx, ThemeManager.activeTheme))
        val word = CandidateWord("", "你", "")
        holder.update(0, word)
        assertEquals(listOf("1 " to BLUE, "你" to WHITE), runs(text(holder.ui.root as ViewGroup)))
        // the same candidate, now second: its number and the plain look, though the candidate is the same
        holder.update(1, word)
        assertEquals(listOf("2 " to BLUE, "你" to INK), runs(text(holder.ui.root as ViewGroup)))
        // with no labels, third to fourth is no change: not drawn again
        styled("<style select=\"candidate\" color=\"#263238\"/>")
        val plain = CandidateViewHolder(CandidateItemUi(ctx, ThemeManager.activeTheme))
        plain.update(2, word)
        val shown = text(plain.ui.root as ViewGroup).text
        plain.update(3, word)
        assertTrue(shown === text(plain.ui.root as ViewGroup).text)
    }

    // 060-render-p3 T6a's check: the bar's views place each candidate where the engine's sample bar does, as
    // wide, in its colours (structure, not pixels: each box within a pixel, each colour the same)
    @Test
    fun theBarsViewsAreTheEnginesSampleBar() {
        styled(CAPSULES)
        prefs.set(AppPrefs.getInstance().keyboard.horizontalCandidateStyle, HorizontalCandidateMode.NeverFillWidth)
        val samples = BarSample.SAMPLES.filter { it.comment.isEmpty() }
        KeyboardHarness().use { h ->
            h.start()
            h.candidates(samples.map { it.words })
            val rv = bar(h)
            val items = items(rv)
            val theme = ThemeManager.activeTheme
            val look = SceneBar.of(h.service, theme)
            val d = h.service.resources.displayMetrics.density.toDouble()
            val m = BarSample.Metrics(
                width = rv.width.toDouble(), height = rv.height.toDouble(), density = d, textSize = text(items[0]).textSize.toDouble(),
                paddingDp = KawaiiBarComponent.candidatePaddingDp().toDouble(), minWidthDp = 32.0,
            )
            val scene: Scene = BarSample.scene(look, m, AndroidTextMeasurer(SceneFonts(ThemeManager.shownLook(theme))), samples)
            assertEquals(samples.size, items.size)
            val left0 = items[0].left
            for ((i, key) in scene.keys.withIndex()) {
                val v = items[i]
                assertTrue("$i: ${key.bounds} vs ${v.left - left0}+${v.width}", abs(key.bounds.left - (v.left - left0)) <= 1 && abs(key.bounds.width - v.width) <= 1)
                val ops = key.faces.getValue(emptySet())
                val fill = ops.filterIsInstance<Op.Fill>().single()
                val top = (fill.shape.bounds.top + 2).toInt()
                assertEquals("$i's fill", fill.color.value, draw(v).getPixel(v.width / 2, top))
                assertEquals("$i's text", ops.filterIsInstance<Op.Text>().map { it.text to it.color.value }, runs(text(v)))
            }
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

        // a theme's pills (060-render-p3 T6a's capsule theme): numbered, the first accented, 4dp apart, no lines
        private val CAPSULES = """
            <style select="bar" fill="#eceff1"/>
            <style select="candidate" fill="#ffffff" color="#263238" radius="50%" inset="6dp 2dp" gap="4dp" label-style="number"/>
            <style select="candidate:first" fill="#00897b" color="#ffffff"/>
            <style select="candidate label" color="#1565c0"/>
            <style select="candidate:selected" fill="#3e2723" color="#ffeb3b"/>
            <style select="candidates-divider" fill="transparent"/>
            <style select="candidates-panel" fill="#cfd8dc"/>
        """.trimIndent()
    }
}
