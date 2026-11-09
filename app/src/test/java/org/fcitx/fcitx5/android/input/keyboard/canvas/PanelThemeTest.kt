/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.input.clipboard.ClipboardEntryUi
import org.fcitx.fcitx5.android.input.editing.TextEditingButton
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPadView
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognitionTest.Queue
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognitionTest.Reader
import org.fcitx.fcitx5.android.input.keyboard.HandwritingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.laidOut
import org.fcitx.fcitx5.android.input.picker.PickerData
import org.fcitx.fcitx5.android.input.picker.PickerSideUi
import org.fcitx.fcitx5.android.input.picker.PickerTabsUi
import org.fcitx.fcitx5.android.input.voice.VoiceGlass
import org.fcitx.fcitx5.android.input.voice.VoiceStyle
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.engine.PanelLooks
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.PressEffect
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 060-render-p3 T7b in the app: the clipboard's entries, the picker's categories, the editing panel's
 * buttons, the voice mask and the handwriting pad take what the theme sets (a theme 3.0's file and
 * every T7b property set in it, through SceneThemes' source), and a theme that sets none of it, a
 * theme 3.0's, draws them as the app did before: each default spelt out here as the code of before had it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class PanelThemeTest {

    private val realSource = SceneThemes.source

    @After
    fun tearDown() {
        SceneThemes.source = realSource
    }

    private fun everything() {
        SceneThemes.source = { look, name, dark -> LookTheme.xml(look, name, dark).replace("</theme>", "$STYLES\n</theme>") }
    }

    private val theme: Theme = TestThemes.PixelLight

    private val ctx by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private val density get() = ctx.resources.displayMetrics.density

    private val keys get() = ThemeManager.shownLook(theme).keys

    private fun draw(v: View, w: Int, h: Int): Bitmap {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }

    private fun fill(v: View) = (v.background as GradientDrawable).color!!.defaultColor

    @Test
    fun theClipboardsEntries() {
        fun entry() = ScenePanels.clipboard(ctx, theme).let { ClipboardEntryUi(ctx, theme, it, ScenePanels.clipboardRadius(ctx, it)) }
        // before: the entry colour, ThemeColors.entryRadius's half the keys' corners in dp, the keys' text
        val plain = entry()
        assertEquals(theme.clipboardEntryColor, fill(plain.root))
        assertEquals(keys.radiusDp / 2f * density, (plain.root.background as GradientDrawable).cornerRadius, 0f)
        assertEquals(theme.keyTextColor, plain.textView.currentTextColor)
        everything()
        val themed = entry()
        assertEquals(0xff102030.toInt(), fill(themed.root))
        assertEquals(10 * density, (themed.root.background as GradientDrawable).cornerRadius, 0f)
        assertEquals(0xff405060.toInt(), themed.textView.currentTextColor)
    }

    @Test
    fun aFlatLooksEntriesAreAsRoundAsItsKeysSay() {
        // 按键圆角 10 dp, the keys flat (square): the entries half of it still, as ThemeColors.entryRadius had them
        val light = TestThemes.PixelLight.look
        val flat = Theme.Custom("flat", false, light.copy(keys = light.keys.copy(radiusDp = 10, blocks = false)))
        val look = ScenePanels.clipboard(ctx, flat)
        assertEquals(5 * density, ScenePanels.clipboardRadius(ctx, look), 0f)
    }

    @Test
    fun theCategoryShownIsTheKeysTextOpaque() {
        // a key text half seen: the one shown whole, as alpha(1f) had it, whether the engine made the theme or not
        val light = TestThemes.PixelLight.look
        val faint = Theme.Custom("faint", false, light.copy(palette = light.palette.copy(keyTextColor = 0x80212121.toInt())))
        assertEquals(Argb(0xff212121.toInt()), ScenePanels.picker(ctx, faint, side = true).selected.color)
        SceneThemes.source = { _, _, _ -> "<theme/>" }
        assertEquals(Argb(0xff212121.toInt()), ScenePanels.picker(ctx, faint, side = true).selected.color)
        assertEquals(Argb(0xff212121.toInt()), ScenePanels.picker(ctx, faint, side = false).selected.color)
    }

    @Test
    fun aTabShownOnItsOwnFillHasItsRippleBackOnceAnotherIs() {
        everything()
        val light = TestThemes.PixelLight.look
        val rippled = Theme.Custom("rippled", false, light.copy(keys = light.keys.copy(press = PressEffect.Ripple)))
        val tabs = PickerTabsUi(ctx, rippled).apply { setTabs(listOf(PickerData.Category("一"), PickerData.Category("二"))) }
        fun tab(i: Int) = tabs.root.getChildAt(i)
        tabs.activateTab(1)
        assertEquals(0xff444444.toInt(), (tab(1).background as ColorDrawable).color)
        assertTrue(tab(0).background is RippleDrawable)
        tabs.activateTab(0)
        assertTrue(tab(1).background is RippleDrawable)
        assertEquals(0xff444444.toInt(), (tab(0).background as ColorDrawable).color)
    }

    @Test
    fun thePickersCategories() {
        fun side() = PickerSideUi(ctx, theme).apply {
            setCategories(listOf("一", "二"))
            activate(1)
        }
        fun items(s: PickerSideUi) = (s.getChildAt(0) as LinearLayout).let { c -> (0 until c.childCount).map { c.getChildAt(it) as TextView } }
        fun tabs() = PickerTabsUi(ctx, theme).apply {
            setTabs(listOf(PickerData.Category("一"), PickerData.Category("二")))
            activateTab(1)
        }
        fun labels(t: PickerTabsUi) = (0 until t.root.childCount).map { ((t.root.getChildAt(it) as ViewGroup).getChildAt(0) as TextView) }
        // before: the column the bar's colour, the one shown the keyboard's; the text the keys' at 60% and whole
        val s = side()
        assertEquals(theme.barColor, (s.background as ColorDrawable).color)
        val whole = ColorUtils.setAlphaComponent(theme.keyTextColor, 0xFF)
        assertEquals(listOf(ColorUtils.setAlphaComponent(theme.keyTextColor, 153), whole), items(s).map { it.currentTextColor })
        assertEquals(theme.keyboardColor, (items(s)[1].background as ColorDrawable).color)
        // the tabs on the bar's own, at 50% and whole
        val t = tabs()
        assertNull(t.root.background)
        assertEquals(listOf(ColorUtils.setAlphaComponent(theme.keyTextColor, 128), whole), labels(t).map { it.currentTextColor })
        assertNull(ScenePanels.picker(ctx, theme, side = true).fill)
        everything()
        val themed = side()
        assertEquals(0xff222222.toInt(), (themed.background as ColorDrawable).color)
        assertEquals(listOf(0xff333333.toInt(), 0xff555555.toInt()), items(themed).map { it.currentTextColor })
        assertEquals(0xff444444.toInt(), (items(themed)[1].background as ColorDrawable).color)
        val themedTabs = tabs()
        assertEquals(0xff222222.toInt(), (themedTabs.root.background as ColorDrawable).color)
        assertEquals(listOf(0xff333333.toInt(), 0xff555555.toInt()), labels(themedTabs).map { it.currentTextColor })
        assertEquals(0xff444444.toInt(), (themedTabs.root.getChildAt(1).background as ColorDrawable).color)
        assertEquals(Argb(0xff111111.toInt()), ScenePanels.picker(ctx, theme, side = false).fill)
    }

    @Test
    fun theEditingPanelsButtons() {
        fun button(bordered: Boolean, alt: Boolean): Pair<Float, Int> {
            val look = ScenePanels.editing(ctx, theme)
            val radius = ScenePanels.editingRadius(ctx, look)
            val b = TextEditingButton(ctx, theme, false, bordered, radius, alt, look.fill?.value).apply { setText(R.string.select) }
            return radius to draw(b, 300, 200).getPixel(30, 30)
        }
        // before: its corners the keys' dp as as many px (ThemeColors.buttonRadius), the keys' colours as blocks, flat none
        assertEquals(keys.radiusDp * 2f, button(true, false).first, 0f)
        assertEquals(theme.keyBackgroundColor, button(true, false).second)
        assertEquals(theme.altKeyBackgroundColor, button(true, true).second)
        assertEquals(0, Color.alpha(button(false, false).second))
        everything()
        assertEquals(6 * density, button(false, false).first, 0f)
        // the theme's fill, a block whatever the keys, the selection's buttons too
        for (bordered in listOf(true, false)) for (alt in listOf(true, false)) assertEquals(0xff666666.toInt(), button(bordered, alt).second)
    }

    @Test
    fun theVoiceMask() {
        // before: the keyboard's colour at 0xE0 of its alpha (VoiceStyle.mask), the pill the accent at 0x29, its bars the accent; the glass of before
        val plain = VoiceStyle.panel(ctx, theme)
        val kb = theme.keyboardColor
        assertEquals((kb and 0xFFFFFF) or ((kb ushr 24) * 0xE0 / 0xFF shl 24), plain.fill.value)
        assertEquals(ColorUtils.setAlphaComponent(theme.accentKeyBackgroundColor, 0x29), plain.micFill.value)
        assertEquals(theme.accentKeyBackgroundColor, plain.micColor.value)
        assertEquals(VoiceGlass.Blur(VoiceGlass.SCALE, 2), VoiceStyle.glass(ctx, plain).blur)
        everything()
        val themed = VoiceStyle.panel(ctx, theme)
        assertEquals(PanelLooks.Voice(Argb(0x80123456.toInt()), 0.0, Argb(0xff777777.toInt()), Argb(0xff888888.toInt())), themed)
        // blur 0: none taken, the keys plain under the mask
        assertNull(VoiceStyle.glass(ctx, themed).blur)
        assertEquals(VoiceGlass.Blur.of(12.0 * density), VoiceStyle.glass(ctx, themed.copy(blurDp = 12.0)).blur)
    }

    @Test
    fun theBlurOfAThemeIsThreeBoxBlursOfItsRadius() {
        // as many small pixels as leave 2 of the radius, no more than 8 times smaller: within half a small pixel;
        // no less than twice smaller, a small blur a little more (never the keys taken whole)
        assertEquals(VoiceGlass.Blur(8, 2), VoiceGlass.Blur.of(16.0))
        assertEquals(VoiceGlass.Blur(8, 8), VoiceGlass.Blur.of(25 * 2.625))
        assertEquals(VoiceGlass.Blur(3, 2), VoiceGlass.Blur.of(6.0))
        assertEquals(VoiceGlass.Blur(2, 2), VoiceGlass.Blur.of(3.0))
        assertEquals(VoiceGlass.Blur(2, 1), VoiceGlass.Blur.of(0.5))
        assertNull(VoiceGlass.Blur.of(0.4))
        assertNull(VoiceGlass.Blur.of(0.0))
    }

    @Test
    fun theHandwritingPad() {
        fun pad(): Pair<HandwritingPadView, KeyboardCanvasView> {
            val k = HandwritingKeyboard(PlaceholderStrings(ctx), theme, deliver = { _, _ -> }, recognition = HandwritingRecognition(Queue(), Queue(), { -1 }, Reader())).also { ctx.setContentView(it) }.laidOut()
            val views = (0 until k.childCount).map { k.getChildAt(it) }
            return views.filterIsInstance<HandwritingPadView>().single() to views.filterIsInstance<KeyboardCanvasView>().single()
        }
        // before: the keys' text colour, 3.5 dp; no grid (the slot's face alone, its shadow and fill)
        val (plain, plainCanvas) = pad()
        assertEquals(theme.keyTextColor, plain.inkPaint.color)
        assertEquals(3.5f * density, plain.inkPaint.strokeWidth, 1e-4f)
        assertEquals(2, plainCanvas.scene!!.slots.single { it.id == "pad" }.ops.size)
        everything()
        val (themed, canvas) = pad()
        assertEquals(0xff990000.toInt(), themed.inkPaint.color)
        assertEquals(2 * density, themed.inkPaint.strokeWidth, 1e-4f)
        val lines = canvas.scene!!.slots.single { it.id == "pad" }.ops.drop(2)
        assertTrue(lines.isNotEmpty() && lines.all { (it as Op.Fill).color == Argb(0xff00aa00.toInt()) })
    }

    companion object {
        private const val STYLES = """
<style select="clipboard" fill="#ff102030" radius="10dp" color="#ff405060"/>
<style select="picker" fill="#ff111111"/>
<style select="picker categories" fill="#ff222222" color="#ff333333"/>
<style select="picker:selected categories" fill="#ff444444" color="#ff555555"/>
<style select="editing" fill="#ff666666" radius="6dp"/>
<style select="voice" fill="#80123456" blur="0"/>
<style select="voice mic" fill="#ff777777" icon-color="#ff888888"/>
<style select="handwriting/pad" ink-color="#ff990000" ink-width="2dp" grid="true" grid-color="#ff00aa00"/>"""
    }
}
