/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Content
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.Part
import org.fcitx.fcitx5.android.scene.engine.Scripts
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.TextLook
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
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
        // its box (a character's ink across, the font's extent up) in the corner, 4 dp in as the symbol's,
        // down clear of the corner's 4 dp arc (where it crosses its 45° line)
        val arc = 4 * d * (1 - sqrt(0.5))
        near(face(left, "q").left + Content.SYMBOL_SIDE_DP * d, q.x + 0.05 * q.size, "the ink's left")
        near(face(left, "q").top + arc, q.baseline - 1.05 * q.size, "the extent's top")
        val shift = icons(left, "shift").single()
        near(face(left, "shift").left + Content.SYMBOL_SIDE_DP * d, shift.box.left)
        near(face(left, "shift").top + arc, shift.box.top)
        // a round face (radius 50%): the glyph's corner on its arc, not out in the box's corner
        val round = Fixtures.scene(env, theme = styled("<style select=\"key\" align=\"top-left\" radius=\"50%\"/>"))
        val f = face(round, "q")
        val r = (round.keys.first { it.id == "q" }.shape as Shape.Rect).radii[0]
        val rq = texts(round, "q").first { it.text == "q" }
        near(f.left + r * (1 - sqrt(0.5)), rq.x + 0.05 * rq.size, "round: the ink's left")
        near(f.top + r * (1 - sqrt(0.5)), rq.baseline - 1.05 * rq.size, "round: the extent's top")
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
        // a space with the run before it, a leading one with the run after it; a no-break one alike
        assertEquals(listOf("拼音 " to "kai", "Pinyin" to "mono"), Scripts.runs("拼音 Pinyin", "system", "kai", "mono"))
        assertEquals(listOf(" 拼音 " to "kai", "Pinyin " to "mono"), Scripts.runs(" 拼音 Pinyin ", "system", "kai", "mono"))
        assertEquals(listOf("  " to "system"), Scripts.runs("  ", "system", "kai", "mono"))
        assertEquals(listOf("拼音 Pinyin" to "system"), Scripts.runs("拼音 Pinyin", "system", null, null))
        assertEquals(listOf("，。Ａ" to "kai", "é1" to "system", "😀" to "emoji"), Scripts.runs("，。Ａé1😀", "emoji", "kai", "system"))
        assertEquals(listOf("𠀀" to "kai"), Scripts.runs("𠀀", "system", "kai", null), "an ideograph past the BMP")
        val fonts = "\n<font name=\"kai\" src=\"kai.ttf\"/>\n<font name=\"mono\" src=\"mono.ttf\"/>"
        val t = styled("<style select=\"key\" font-han=\"kai\" font-latin=\"mono\"/>", fonts = fonts)
        val s = Fixtures.scene(env.copy(texts = env.texts.copy(imeName = "拼音 Pinyin")), theme = t)
        val runs = texts(s, "space")
        assertEquals(listOf("kai", "mono"), runs.map { it.font })
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
        val icon = space("icon(space)")
        val (badge, glyph) = icons(icon, "space").take(2)
        assertEquals(listOf("voice", "space"), listOf(badge.name, glyph.name))
        near(badge.box.right + Content.BADGE_GAP_DP * d, glyph.box.left, "beside the badge")
        near(face(icon, "space").centerX, (badge.box.left + glyph.box.right) / 2, "the two centred")
        // the label's part as the key's: set on the label, the same
        val onLabel = Fixtures.scene(voice, theme = styled("<style select=\"*/space label\" space-content=\"none\"/>"))
        assertEquals(SceneText.of(none), SceneText.of(onLabel))
    }

    @Test
    fun returnShowsTheThemesForItsAction() {
        val t = styled("<style select=\"*/enter\" return-icon-send=\"text(发送)\" return-icon-newline=\"icon(done)\"/>")
        fun enter(icon: String) = Fixtures.scene(env.copy(texts = env.texts.copy(returnIcon = icon)), theme = t)
        val send = enter("send")
        assertTrue(icons(send, "enter").isEmpty())
        val text = texts(send, "enter").single()
        assertEquals("发送", text.text)
        near(Content.RETURN_TEXT_DP * d, text.size)
        assertEquals(listOf("done"), icons(enter("enter"), "enter").map { it.name }, "enter's newline")
        assertEquals(listOf("go"), icons(enter("go"), "enter").map { it.name }, "none given for go: its own")
        assertEquals(listOf("previous"), icons(enter("previous"), "enter").map { it.name }, "no action of a theme's")
    }

    @Test
    fun alignTakesTheBadgeAndAnIconButNotALabelOverAnIcon() {
        // the space's badge and name: the two at the corner, the badge first
        val voice = env.copy(input = Input(holdVoice = true))
        val left = Fixtures.scene(voice, theme = styled("<style select=\"key\" align=\"top-left\"/>"))
        val badge = icons(left, "space").first { it.name == "voice" }
        near(face(left, "space").left + Content.SYMBOL_SIDE_DP * d, badge.box.left, "the badge at the corner")
        val name = texts(left, "space").single()
        assertTrue(name.x > badge.box.right, "its name after it")
        assertTrue(badge.box.top >= face(left, "space").top - 0.01 && name.baseline - 1.05 * name.size >= face(left, "space").top - 0.01)
        // an icon alone centred at the top
        val top = Fixtures.scene(env, theme = styled("<style select=\"key\" align=\"top-center\"/>"))
        val shift = icons(top, "shift").single()
        near(face(top, "shift").centerX, shift.box.centerX)
        near(face(top, "shift").top, shift.box.top)
        // a label over its icon: where it was
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"a\" label=\"emoji\" icon=\"emoji\" width=\"10\"/></row></keyboard>",
        ).ok()
        assertEquals(SceneText.of(Fixtures.scene(env, l)), SceneText.of(Fixtures.scene(env, l, styled("<style select=\"key\" align=\"top-left\"/>"))))
    }

    @Test
    fun anAlignedTextTooWideStaysAtItsCorner() {
        val long = "text(${"空".repeat(30)})"
        val s = Fixtures.scene(env, theme = styled("<style select=\"*/space\" align=\"top-left\" space-content=\"$long\"/>"))
        val t = texts(s, "space").single()
        val inkLeft = t.x + 0.05 * t.size * t.scaleX
        val room = face(s, "space").left + Content.ROOM_H_DP * d
        assertTrue(inkLeft >= room - 0.5 && inkLeft <= face(s, "space").left + Content.SYMBOL_SIDE_DP * d + 0.01, "$inkLeft from $room")
    }

    @Test
    fun aLongTextOfTheThemesIsSmallerAlone() {
        val send = env.copy(texts = env.texts.copy(returnIcon = "send"))
        val plain = Fixtures.scene(send)
        val long = Fixtures.scene(send, theme = styled("<style select=\"*/enter\" return-icon-send=\"text(发送这条消息)\"/>"))
        val text = texts(long, "enter").single()
        assertTrue(text.size < Content.RETURN_TEXT_DP * d, "smaller: ${text.size}")
        for (id in listOf("symbols", "comma", "period")) assertEquals(texts(plain, id), texts(long, id), "$id as ever")
        val space = Fixtures.scene(env, theme = styled("<style select=\"*/space\" space-content=\"text(${"空".repeat(20)})\"/>"))
        for (id in listOf("symbols", "comma", "period")) assertEquals(texts(Fixtures.scene(env), id), texts(space, id), "$id as ever")
    }

    @Test
    fun runsOutlinedAreDrawnOutlinesFirst() {
        val fonts = "\n<font name=\"kai\" src=\"kai.ttf\"/>\n<font name=\"mono\" src=\"mono.ttf\"/>"
        val t = styled("<style select=\"key\" font-han=\"kai\" font-latin=\"mono\" stroke=\"2dp #ff0000\" text-shadow=\"0 1dp 0 #0000ff\"/>", fonts = fonts)
        val runs = texts(Fixtures.scene(env.copy(texts = env.texts.copy(imeName = "拼音 Pinyin")), theme = t), "space")
        assertEquals(listOf("拼音 ", "Pinyin", "拼音 ", "Pinyin"), runs.map { it.text })
        assertTrue(runs.take(2).all { it.color.transparent && it.stroke != null && it.shadow != null }, "outlines and shadows first")
        assertTrue(runs.drop(2).all { !it.color.transparent && it.stroke == null && it.shadow == null }, "then the fills")
        assertEquals(listOf("label"), runs.map { it.part }.distinct())
        // one run: one op, as ever
        assertEquals(1, texts(Fixtures.scene(env, theme = t), "q").count { it.text == "q" })
        // a transparent text with a shadow: the shadow drawn
        val shadowed = Fixtures.scene(env, theme = styled("<style select=\"key label\" color=\"#00000000\" text-shadow=\"0 1dp 0 #0000ff\"/>"))
        assertTrue(texts(shadowed, "q").any { it.text == "q" && it.color.transparent && it.shadow != null && it.stroke == null })
    }

    @Test
    fun runsAreSpacedAndSqueezedAsOne() {
        val fonts = "\n<font name=\"kai\" src=\"kai.ttf\"/>\n<font name=\"mono\" src=\"mono.ttf\"/>"
        val theme = styled("<style select=\"key\" font-han=\"kai\" font-latin=\"mono\" letter-spacing=\"0.2em\"/>", fonts = fonts)
        val look = TextLook(Cascade(theme, emptySet()).of(Selector.Target("key", "qwerty", "space", "space", part = "label")), theme)
        val m = Content.measure(FakeMeasurer, "拼音 Pinyin", look, 400, 20.0)
        val first = FakeMeasurer.measure("拼音 ", "kai", 400, 20.0, 0.2)
        near(first.advance + FakeMeasurer.measure("Pinyin", "mono", 400, 20.0, 0.2).advance, m.m.advance, "the advance, spaced")
        val drawn = m.squeezed(m.box.width / 2).from(10.0, 50.0, Part.Label)
        assertEquals(listOf(10.0), drawn.texts.take(1).map { it.x })
        near(10.0 + first.advance * 0.5, drawn.texts[1].x, "the second run where the first ends, squeezed")
        assertTrue(drawn.texts.all { it.scaleX == 0.5 && it.spacing == 0.2 })
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

    @Test
    fun aHiddenShortcutIsLeftOutOfCharacterKeysOnly() {
        val plain = Fixtures.scene(env)
        val shortcut = Fixtures.scene(env, theme = styled("<style select=\"key shortcut\" hidden=\"true\"/>"))
        assertTrue(icons(plain, "y").isNotEmpty(), "the fixture's y has a shortcut")
        assertTrue(icons(plain, "space").isNotEmpty(), "the fixture's space has its swipes")
        // key, icons drawn with `key shortcut { hidden }`, why
        val rows = listOf(
            Triple("y", emptyList<String>(), "a character key's shortcut is the shortcut part"),
            Triple("space", icons(plain, "space").map { it.name }, "a function key's swipes are the hint part"),
            Triple("backspace", icons(plain, "backspace").map { it.name }, "its sweep too"),
        )
        for ((id, want, why) in rows) assertEquals(want, icons(shortcut, id).map { it.name }, "$id: $why")
        assertEquals(texts(plain, "y"), texts(shortcut, "y"), "the symbol is the mark part, kept")
    }

    @Test
    fun aHiddenShortcutIsLeftOutOnTheNineKeyToo() {
        val nine = Forms.env("nine", env).let { it.copy(marks = it.marks.copy(shortcuts = it.marks.shortcuts + ("t9/2:down" to MarkContent.Icon("content-copy")))) }
        val plain = Forms.scene("nine", nine, styled())
        assertEquals(listOf("content-copy"), icons(plain, "2").map { it.name }, "the fixture's 2 has a shortcut")
        val hidden = Forms.scene("nine", nine, styled("<style select=\"key shortcut\" hidden=\"true\"/>"))
        assertEquals(emptyList(), icons(hidden, "2").map { it.name })
        assertEquals(texts(plain, "2"), texts(hidden, "2"), "its letters and digit kept")
    }
}
