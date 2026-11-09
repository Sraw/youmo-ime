/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Content
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Scripts
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 060-render-p3 T4a: the text on a key ([Content]'s, as ContentTest's), each of a theme's looks on material-light
class ContentLooksTest {
    private val env = Fixtures.portrait
    private val d = Fixtures.DENSITY

    private fun near(e: Double, a: Double, what: String = "") = assertTrue(abs(e - a) < 0.01, "$what: expected $e, was $a")

    private fun ops(s: Scene, id: String) = s.keys.first { it.id == id }.ops(false)

    private fun texts(s: Scene, id: String) = ops(s, id).filterIsInstance<Op.Text>()

    private fun icons(s: Scene, id: String) = ops(s, id).filterIsInstance<Op.Icon>()

    private fun face(s: Scene, id: String) = (s.keys.first { it.id == id }.shape as Shape.Rect).box

    private fun styled(vararg styles: String, fonts: String = "") = ThemeReader.theme(
        testData("material-light.xml").replace("</colors>", "</colors>$fonts").replace("</theme>", styles.joinToString("\n", postfix = "\n</theme>")),
    ).ok()

    @Test
    fun alignPutsTheGlyphAtTheFacesTop() {
        val left = Fixtures.scene(env, theme = styled("<style select=\"key\" align=\"top-left\"/>"))
        val q = texts(left, "q").first { it.text == "q" }
        // its box (a character's ink across, the font's extent up) in the corner, 4 dp in as the symbol's
        near(face(left, "q").left + Content.SYMBOL_SIDE_DP * d, q.x + 0.05 * q.size, "the ink's left")
        near(face(left, "q").top, q.baseline - 1.05 * q.size, "the extent's top")
        val shift = icons(left, "shift").single()
        near(face(left, "shift").left + Content.SYMBOL_SIDE_DP * d, shift.box.left)
        near(face(left, "shift").top, shift.box.top)
        // centred across at the top: the glyph stays put, the class smaller about its top, its marks clear of it
        val centre = Fixtures.scene(env, theme = styled("<style select=\"key\" align=\"top-center\"/>"))
        for (id in listOf("q", "y", "a")) {
            val t = texts(centre, id).first { it.text == id }
            near(face(centre, id).centerX, t.x + t.size * 0.3, "$id centred")
            near(face(centre, id).top, t.baseline - 1.05 * t.size, "$id at the top")
        }
        val sizes = centre.keys.filter { it.kind == "letter" && it.label == it.id }.map { k -> texts(centre, k.id).first { it.text == k.id }.size }
        assertEquals(1, sizes.toSet().size, "every letter one size")
        // centre: as ever
        assertEquals(SceneText.of(Fixtures.scene(env)), SceneText.of(Fixtures.scene(env, theme = styled("<style select=\"key\" align=\"center\"/>"))))
    }

    @Test
    fun letterSpacingSetsCharactersApart() {
        // the measurer's default, as Paint.letterSpacing: half the space before each character, half after
        val plain = FakeMeasurer.measure("ab", "system", 400, 10.0)
        val spaced = FakeMeasurer.measure("ab", "system", 400, 10.0, 0.1)
        near(plain.advance + 2.0, spaced.advance)
        near(plain.ink.left + 0.5, spaced.ink.left)
        near(plain.ink.right + 1.5, spaced.ink.right)
        assertEquals(plain, FakeMeasurer.measure("ab", "system", 400, 10.0, 0.0))
        val s = Fixtures.scene(env, theme = styled("<style select=\"key\" letter-spacing=\"0.2em\"/>"))
        val name = texts(s, "space").single()
        val was = texts(Fixtures.scene(env), "space").single()
        near(0.2, name.letterSpacing)
        // two characters 0.2 em more each, centred by the advance (whole px): about 0.2 em further left
        assertTrue(abs(was.x - 0.2 * was.size - name.x) <= 1.0, "${was.x} ${name.x}")
        assertEquals(0.2, texts(s, "q").first { it.text == "1" }.letterSpacing, "the symbol's too")
    }

    @Test
    fun strokeAndShadowAreDrawnNotMeasured() {
        val t = styled(
            "<style select=\"key\" stroke=\"1dp #000000\" text-shadow=\"0 1dp 2dp #80000000\"/>",
            "<style select=\"key mark\" stroke=\"0 #000000\" text-shadow=\"none\"/>",
        )
        val s = Fixtures.scene(env, theme = t)
        val q = texts(s, "q").first { it.text == "q" }
        assertEquals(TextStroke(d, Argb(0xFF000000.toInt())), q.stroke)
        assertEquals(TextShadow(0.0, d, 2 * d, Argb(0x80000000.toInt())), q.shadow)
        // not measured: where it was, as large
        val was = texts(Fixtures.scene(env), "q").first { it.text == "q" }
        assertEquals(was, q.copy(stroke = null, shadow = null))
        val one = texts(s, "q").first { it.text == "1" }
        assertEquals(null, one.stroke, "the mark's own: none")
        assertEquals(null, one.shadow)
        // a text of no colour with an outline: the outline drawn
        val outline = Fixtures.scene(env, theme = styled("<style select=\"key label\" color=\"#00000000\" stroke=\"1dp #000000\"/>"))
        assertTrue(texts(outline, "q").any { it.text == "q" && it.color.transparent && it.stroke != null })
        assertTrue(texts(Fixtures.scene(env, theme = styled("<style select=\"key label\" color=\"#00000000\"/>")), "q").none { it.text == "q" })
    }

    @Test
    fun eachScriptInItsFont() {
        assertEquals(listOf("拼音" to "kai", " " to "system", "Pinyin" to "mono"), Scripts.runs("拼音 Pinyin", "system", "kai", "mono"))
        assertEquals(listOf("拼音 Pinyin" to "system"), Scripts.runs("拼音 Pinyin", "system", null, null))
        assertEquals(listOf("，。Ａ" to "kai", "é1" to "system", "😀" to "emoji"), Scripts.runs("，。Ａé1😀", "emoji", "kai", "system"))
        assertEquals(listOf("𠀀" to "kai"), Scripts.runs("𠀀", "system", "kai", null), "an ideograph past the BMP")
        val fonts = "\n<font name=\"kai\" src=\"kai.ttf\"/>\n<font name=\"mono\" src=\"mono.ttf\"/>"
        val t = styled("<style select=\"key\" font-han=\"kai\" font-latin=\"mono\"/>", fonts = fonts)
        val s = Fixtures.scene(env.copy(texts = env.texts.copy(imeName = "拼音 Pinyin")), theme = t)
        val runs = texts(s, "space")
        assertEquals(listOf("kai", "system", "mono"), runs.map { it.font })
        // each run from where the one before it ends, the whole centred as one text
        for (i in 1 until runs.size) {
            val before = runs[i - 1]
            near(before.x + FakeMeasurer.measure(before.text, before.font, before.weight, before.size).advance, runs[i].x)
        }
        val whole = FakeMeasurer.measure("拼音 Pinyin", "system", 400, runs[0].size).advance
        near(face(s, "space").centerX, runs[0].x + ceil(whole) / 2)
        assertEquals("mono", texts(s, "q").first { it.text == "q" }.font)
        assertEquals("mono", texts(s, "q").first { it.text == "1" }.font, "the symbol's too")
    }

    @Test
    fun theSpaceShowsWhatTheThemeGivesIt() {
        val voice = env.copy(input = Input(holdVoice = true))
        fun space(content: String) = Fixtures.scene(voice, theme = styled("<style select=\"*/space\" space-content=\"$content\"/>"))
        val text = texts(space("text(空格)"), "space").single()
        assertEquals("空格", text.text)
        near(texts(Fixtures.scene(voice), "space").single().size, text.size, "the space's size")
        assertEquals(SceneText.of(Fixtures.scene(voice)), SceneText.of(space("ime-name")))
        val none = space("none")
        assertTrue(texts(none, "space").isEmpty())
        assertEquals(listOf("voice", "cursor-move"), icons(none, "space").map { it.name }, "the badge and the swipe kept")
        val icon = space("icon(keyboard-space)")
        val (badge, glyph) = icons(icon, "space").take(2)
        assertEquals(listOf("voice", "keyboard-space"), listOf(badge.name, glyph.name))
        near(badge.box.right + Content.BADGE_GAP_DP * d, glyph.box.left, "beside the badge")
        near(face(icon, "space").centerX, (badge.box.left + glyph.box.right) / 2, "the two centred")
        // the label's part as the key's: set on the label, the same
        val onLabel = Fixtures.scene(voice, theme = styled("<style select=\"*/space label\" space-content=\"none\"/>"))
        assertEquals(SceneText.of(none), SceneText.of(onLabel))
    }

    @Test
    fun returnShowsTheThemesForItsAction() {
        val t = styled("<style select=\"*/enter\" return-icon-send=\"text(发送)\" return-icon-newline=\"icon(keyboard-return)\"/>")
        fun enter(icon: String) = Fixtures.scene(env.copy(texts = env.texts.copy(returnIcon = icon)), theme = t)
        val send = enter("send")
        assertTrue(icons(send, "enter").isEmpty())
        val text = texts(send, "enter").single()
        assertEquals("发送", text.text)
        near(Content.RETURN_TEXT_DP * d, text.size)
        assertEquals(listOf("keyboard-return"), icons(enter("enter"), "enter").map { it.name }, "enter's newline")
        assertEquals(listOf("go"), icons(enter("go"), "enter").map { it.name }, "none given for go: its own")
        assertEquals(listOf("previous"), icons(enter("previous"), "enter").map { it.name }, "no action of a theme's")
    }

    @Test
    fun aHiddenHintOrMarkIsLeftOut() {
        val plain = Fixtures.scene(env)
        assertEquals(listOf("cursor-move"), icons(plain, "space").map { it.name })
        val hint = Fixtures.scene(env, theme = styled("<style select=\"key hint\" hidden=\"true\"/>"))
        assertTrue(icons(hint, "space").isEmpty())
        assertEquals(listOf("backspace"), icons(hint, "backspace").map { it.name }, "its sweep and restore left out")
        assertEquals(icons(plain, "y"), icons(hint, "y"), "a letter's shortcut is no hint")
        // the key's hidden hides its label, never its hint (rules B §5)
        val key = Fixtures.scene(env, theme = styled("<style select=\"*/space\" hidden=\"true\"/>"))
        assertEquals(listOf("cursor-move"), icons(key, "space").map { it.name })
        val mark = Fixtures.scene(env, theme = styled("<style select=\"key mark\" hidden=\"true\"/>"))
        assertEquals(listOf("y"), texts(mark, "y").map { it.text })
        assertEquals(icons(plain, "y").map { it.name }, icons(mark, "y").map { it.name }, "the shortcut kept")
    }
}
