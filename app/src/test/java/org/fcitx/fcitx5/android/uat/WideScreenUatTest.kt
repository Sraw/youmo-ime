/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.candidates.expanded.window.GridExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneWindow
import org.fcitx.fcitx5.android.input.popup.CharCardLayout
import org.fcitx.fcitx5.android.ui.main.settings.theme.KeyboardPreviewUi
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes as ThemeSizes

/**
 * A window 600 dp wide or wider is 横屏 held either way (061-wide-as-landscape): a foldable's inner screen and a
 * tablet upright are laid out as turned, a foldable's outer screen and a phone upright as before. The qualifiers'
 * screens have no system bars, so the input method's screen is the display's whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w673dp-h841dp-port-420dpi")
class WideScreenUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val was = KeyboardArea.themeSizes

    // the settings' sizes, 标准 (30% upright, 49% turned), whatever the theme in use sets
    private fun noThemeSizes() {
        KeyboardArea.themeSizes = { ThemeSizes() }
    }

    @After
    fun theThemesOwn() {
        KeyboardArea.themeSizes = was
    }

    private fun scenes() = SceneWindow.scenes(ctx, ThemeManager.activeTheme)

    @Test
    @Uat("LS-33", "LS-49")
    fun anUnfoldedFoldableUprightIsLaidOutTurned() {
        noThemeSizes()
        val s = KeyboardArea.screen(ctx)
        assertTrue(s.landscape)
        // turned's rows, a large screen's (its short side 673 dp): 4 × 56 dp, as high as turned (the next test), not 30% of 841
        assertTrue(s.large)
        assertEquals(s.dp(4 * 56.0), KeyboardArea.requestedHeight(ctx))
        // the theme's `@landscape` and the gaps' turned ones, the split on its default (自动)
        assertTrue("landscape" in scenes())
        assertTrue(KeyboardArea.splits(s, floating = false))
        // the display's pair as the settings pages draw it: 横屏 both ways up, the one held now as the keyboard's
        val (upright, turned) = KeyboardArea.screens(ctx)
        assertTrue(upright.landscape && turned.landscape)
        assertEquals(s.widthDp, KeyboardArea.displayScreen(ctx).widthDp)
    }

    @Test
    @Uat("LS-33", "LS-49")
    @Config(qualifiers = "w841dp-h673dp-land-420dpi")
    fun theUnfoldedFoldableTurnedIsAsHigh() {
        noThemeSizes()
        val s = KeyboardArea.screen(ctx)
        assertTrue(s.landscape)
        // the same rows as upright: 4 × 56 dp
        assertEquals(s.dp(4 * 56.0), KeyboardArea.requestedHeight(ctx))
    }

    @Test
    @Uat("LS-34")
    @Config(qualifiers = "w412dp-h891dp-port-420dpi")
    fun theOuterScreenIsAPhoneUprightAsBefore() {
        noThemeSizes()
        val s = KeyboardArea.screen(ctx)
        assertFalse(s.landscape)
        assertEquals(ctx.resources.displayMetrics.heightPixels * 30 / 100, KeyboardArea.requestedHeight(ctx))
        assertFalse("landscape" in scenes())
        assertFalse(KeyboardArea.splits(s, floating = false))
        assertFalse(KeyboardArea.screens(ctx).first.landscape)
    }

    @Test
    @Uat("LS-36", "LS-49")
    @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun aTabletUprightIsTurnedAndSplits() {
        noThemeSizes()
        val s = KeyboardArea.screen(ctx)
        assertTrue(s.landscape)
        assertTrue(KeyboardArea.splits(s, floating = false))
        // as high as on its side, 4 × 56 dp at 1x, not 30% of 1280
        assertEquals(4 * 56, KeyboardArea.requestedHeight(ctx))
    }

    @Test
    @Uat("LS-36")
    @Config(qualifiers = "w599dp-h900dp-port-mdpi")
    fun justNarrowerIsUpright() {
        val s = KeyboardArea.screen(ctx)
        assertFalse(s.landscape)
        assertFalse(KeyboardArea.splits(s, floating = false))
        // the settings pages' screen held now says the same as the keyboard's
        assertEquals(s.widthDp to false, KeyboardArea.displayScreen(ctx).let { it.widthDp to it.landscape })
    }

    @Test
    @Uat("LS-36")
    @Config(qualifiers = "w600dp-h900dp-port-mdpi")
    fun sixHundredWideIsTurned() {
        val s = KeyboardArea.screen(ctx)
        assertTrue(s.landscape)
        assertTrue(KeyboardArea.splits(s, floating = false))
        assertEquals(s.widthDp to true, KeyboardArea.displayScreen(ctx).let { it.widthDp to it.landscape })
    }

    @Test
    @Uat("LS-38")
    fun theLowKeysMarksAndTheCardAsTurned() {
        val theme = ThemeManager.activeTheme
        val env = SceneEnvs.of(View(ctx), ThemeManager.shownLook(theme), theme.isDark, 1000, 700, Input())
        assertTrue(env.landscape)
        // a symbol at 底部 drawn in a corner, as on a phone on its side
        assertEquals(KeyMarks.At.TopRight, KeyMarks.onKeys(KeyMarks.At.Bottom, KeyMarks.At.TopLeft, env.landscape, compact = false).symbol)
        // the character card in two columns, three readings' rows before they scroll
        val twoColumns = CharCardLayout.twoColumns(KeyboardArea.landscape(ctx), floating = false)
        assertTrue(twoColumns)
        assertEquals(3, CharCardLayout.readingRows(twoColumns))
    }

    // the expanded candidates' columns: 横屏's setting on the wide screen upright, the upright one's on a phone
    private fun columns(): Int {
        AppPrefs.getInstance().keyboard.run {
            prefs.set(expandedCandidateGridSpanCount, 5)
            prefs.set(expandedCandidateGridSpanCountLandscape, 8)
        }
        return GridExpandedCandidateWindow.spanCount(ctx)
    }

    @Test
    @Uat("LS-33")
    fun theExpandedCandidatesTakeTheTurnedColumnsUpright() {
        assertEquals(8, columns())
    }

    @Test
    @Uat("LS-34")
    @Config(qualifiers = "w412dp-h891dp-port-420dpi")
    fun theOuterScreensExpandedCandidatesTakeTheUprightColumns() {
        assertEquals(5, columns())
    }

    @Test
    @Uat("LS-37")
    fun theSettingsPreviewInHalfASplitScreenIsTheDisplays() {
        // the settings page in half of the unfolded screen, upright and 336 dp wide: its window's own configuration
        val half = Configuration(ctx.resources.configuration).apply { screenWidthDp = 336 }
        val page = ContextThemeWrapper(ctx, ctx.theme).apply { applyOverrideConfiguration(half) }
        assertFalse(KeyboardArea.landscape(page))
        val ui = KeyboardPreviewUi(page, ThemeManager.activeTheme)
        fun keys(v: View): TextKeyboard? = v as? TextKeyboard ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { keys(g.getChildAt(it)) } }
        val kb = keys(ui.root)!!
        // the keys drawn as the keyboard is on the display, 横屏 and split, not as the page's narrow window
        assertTrue(KeyboardArea.landscape(kb.context))
        assertTrue(TextKeyboard.splits(kb.context))
        // the bar as the keyboard's there: its height the theme's 横屏 one
        assertEquals(SceneBar.heightPercent(true), SceneBar.heightPercent(kb.context), 0.0)
        ui.recalculateSize()
        assertEquals(KeyboardArea.displayScreen(ctx).widthPx, ui.intrinsicWidth)
    }
}
