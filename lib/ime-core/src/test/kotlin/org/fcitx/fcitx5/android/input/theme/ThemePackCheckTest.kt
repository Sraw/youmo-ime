/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.input.theme.LookFixtures.PALETTE
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// what the blank-key check may take on an import at its worst (a phone is some times slower than this JVM)
private const val CHECK_BUDGET_MS = 3000

// the app's boards: keyboard-scene's files, read from here (the module's directory)
private const val LAYOUTS = "../keyboard-scene/src/commonMain/layouts"

class ThemePackCheckTest {

    // the default look as 4.0: [before] ahead of its first style (a picture, fonts), [styles] after its
    // styles, [after] at its end (icons), [meta] before its colours, [colors] over its colours
    private fun theme(
        styles: String = "",
        meta: String = "",
        before: String = "",
        after: String = "",
        colors: Map<String, String> = emptyMap(),
    ): Theme {
        var xml = LookTheme.xml(LookDefaults.look(PALETTE), "t", false)
        colors.forEach { (name, value) ->
            xml = xml.replace(Regex("""<color name="$name" value="[^"]*"/>"""), """<color name="$name" value="$value"/>""")
        }
        val first = xml.indexOf("<style ")
        xml = xml.substring(0, first) + before + xml.substring(first)
        xml = xml.replace("<colors>", "$meta<colors>").replace("</theme>", "$styles$after</theme>")
        return when (val t = ThemeReader.theme(xml)) {
            is Parsed.Ok -> t.value
            is Parsed.Failed -> error(t.problems.toString())
        }
    }

    @Test
    fun theFilesATheme4NamesAreEachByWhatTheyAre() {
        val t = theme(
            styles = """<style select="key.enter" fill="image(e.png, 1 2 3 4)"/><style select="key:pressed" fill="image(p.png, 1 1 1 1, 2x)" effect="particles(s.png, 6, 12dp, 300ms)"/><style select="key.space:pressed" effect="ripple-image(r.webp, 80%, 300ms)"/>""",
            meta = """<meta author="a" preview="pre.png"/>""",
            before = """<picture src="src.jpg"><cut orientation="portrait" file="p.webp"/><cut orientation="landscape" file="l.webp"/></picture><font name="f" src="f.ttf"/>""",
            after = """<icon name="i" src="i.svg"/><sounds><sound key="letter" src="t.wav"/><sound key="future" src="f.ogg"/></sounds>""",
        )
        val f = ThemePackCheck.files(t)
        // a class this version does not know: its file all the same
        assertEquals(setOf("t.wav", "f.ogg"), f.sounds)
        assertEquals(setOf("p.webp", "l.webp"), f.pictures)
        assertEquals("src.jpg", f.source)
        assertEquals(setOf("e.png", "p.png"), f.images)
        assertEquals(setOf("f.ttf"), f.fonts)
        assertEquals(setOf("i.svg"), f.icons)
        assertEquals("pre.png", f.preview)
        assertEquals(setOf("s.png", "r.webp"), f.effects)
        assertEquals(setOf("p.webp", "l.webp", "src.jpg", "e.png", "p.png", "f.ttf", "i.svg", "s.png", "r.webp", "t.wav", "f.ogg", "pre.png"), f.all)
        assertEquals(ThemePackCheck.Files(emptySet(), null, emptySet(), emptySet(), emptySet(), null), ThemePackCheck.files(theme()))
    }

    // the app's boards, as keyboard-scene has them (the app copies the same files)
    private val boards: List<BlankKeys.Board> by lazy {
        val dir = File(LAYOUTS)
        val fragments = mapOf("grouped-bottom" to (LayoutReader.fragment(File(dir, "grouped-bottom.xml").readText()) as Parsed.Ok).value)
        dir.listFiles().orEmpty().filter { it.name != "grouped-bottom.xml" }.sortedBy { it.name }
            .map { BlankKeys.Board((LayoutReader.layout(it.readText()) as Parsed.Ok).value, fragments) }
            .also { check(it.isNotEmpty()) { "no boards under ${dir.absolutePath}" } }
    }

    @Test
    fun keysLeftBlankAreFoundAsTheEngineDrawsThem() {
        assertEquals(emptyList<BlankKeys.Blank>(), ThemePackCheck.hidesRequired(theme(), boards))
        fun found(style: String) = ThemePackCheck.hidesRequired(theme(styles = style), boards).size
        // every key, the function keys, the space, by role or by name: found
        assertEquals(1, found("""<style select="key" hidden="true"/>"""))
        assertEquals(1, found("""<style select="key.function" hidden="true"/>"""))
        assertEquals(1, found("""<style select="key[role=space] label" hidden="true"/>"""))
        assertEquals(1, found("""<style select="*/backspace" hidden="true"/>"""))
        assertEquals(1, found("""<style select="qwerty/shift icon" hidden="true"/>"""))
        assertEquals(1, found("""<style select="qwerty/*" hidden="true"/>"""))
        assertEquals(1, found("""<style select="key.letter, key.enter" hidden="true"/>"""))
        // the letters, a key of no such role, a mark, or not hidden: not
        assertEquals(0, found("""<style select="key.letter" hidden="true"/>"""))
        assertEquals(0, found("""<style select="qwerty/q" hidden="true"/>"""))
        assertEquals(0, found("""<style select="*/comma" hidden="true"/>"""))
        assertEquals(0, found("""<style select="key mark" hidden="true"/>"""))
        assertEquals(0, found("""<style select="key" hidden="false"/>"""))
        // Return's face an image of its own everywhere (as 3.0 drew one, converted): its label hidden is no blank key
        val img = """<style select="*/enter" fill="image(e.png, 0 0 0 0, 3x)"/>"""
        val hide = """<style select="*/enter label, */enter icon" hidden="true"/>"""
        assertEquals(0, found(img + hide))
        // an image only pressed, or on another key: blank
        assertEquals(1, found("""<style select="*/enter:pressed" fill="image(e.png, 0 0 0 0, 3x)"/>""" + hide))
        assertEquals(1, found("""<style select="*/space" fill="image(e.png, 0 0 0 0, 3x)"/>""" + hide))
        // the image not the face that wins: a later tie, a board's own key, a scene, two scenes, a board's every
        // key in a scene, flat, a class the key has
        for (
            over in listOf(
                """<style select="*/enter" fill="#ffffffff"/>""",
                """<style select="qwerty/enter" fill="#ffffffff"/>""",
                """<style select="@dark */enter" fill="#ffffffff"/>""",
                """<style select="@dark @landscape */enter" fill="#ffffffff"/>""",
                """<style select="@dark qwerty/*" fill="#ffffffff"/>""",
                """<style select="@flat */enter" fill="#00000000"/>""",
                """<style select="*/enter.enter" fill="#ffffffff"/>""",
            )
        ) assertEquals(over, 1, found(img + over + hide))
        // Shift locked
        val shift = """<style select="*/shift" fill="image(s.png, 0 0 0 0, 3x)"/><style select="*/shift label, */shift icon" hidden="true"/>"""
        assertEquals(0, found(shift))
        assertEquals(1, found(shift + """<style select="*/shift:locked" fill="#ffffffff"/>"""))
        // two states at once
        assertEquals(1, found(shift + """<style select="*/shift:locked:on" fill="#ffffffff"/>"""))
        // four scenes at once, however many scenes the rest of the theme names (the keyboard's: no key's)
        val padding = (1..6).joinToString("") { """<style select="@im=x$it keyboard" fill="#ff000000"/>""" }
        assertEquals(1, found(img + padding + """<style select="@dark @landscape @compact @flat */enter" fill="#ffffffff"/>""" + hide))
        // scenes that cannot be at once: no blank there
        val apart = """<style select="@floating */enter" fill="#ffffffff"/><style select="@split */enter label, @split */enter icon" hidden="true"/>"""
        assertEquals(0, found(img + apart))
        assertEquals(0, found(img + """<style select="@im=a */enter" fill="#ffffffff"/><style select="@im=b */enter label, @im=b */enter icon" hidden="true"/>"""))
        // too many to try: refused as such, not let through
        val scenes = (1..10).flatMap { listOf("im=s$it", "keyboard=k$it") } + listOf("dark", "flat", "compact", "landscape")
        val many = scenes.joinToString("") { """<style select="@$it */comma" fill="#ff000000"/>""" }
        val start = System.nanoTime()
        assertEquals(listOf(BlankKeys.TOO_MANY), ThemePackCheck.hidesRequired(theme(styles = many), boards))
        // and soon: the sets counted as they are made, not all made first; nor many values of one scene
        val many2 = (1..120).joinToString("") { """<style select="@im=x$it */comma" fill="#ff000000"/>""" } +
            (1..40).joinToString("") { """<style select="@keyboard=k$it */comma" fill="#ff000000"/>""" }
        assertEquals(listOf(BlankKeys.TOO_MANY), ThemePackCheck.hidesRequired(theme(styles = many2), boards))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$ms ms", ms < CHECK_BUDGET_MS)
    }

    // the worst a pack can ask short of being refused: the largest built-in theme, its key styles naming scenes
    // whose sets to try come near the most tried, within the time an import may take
    @Test
    fun theCheckTakesLittleTimeAtItsWorst() {
        val scenes = (1..4).flatMap { listOf("im=s$it", "keyboard=k$it") } + listOf("dark", "flat", "landscape", "compact", "split")
        val xml = File("../keyboard-scene/src/commonMain/themes/amoled-black.xml").readText()
            .replace("</theme>", scenes.joinToString("") { """<style select="@$it */comma" fill="#ff000000"/>""" } + "</theme>")
        val t = (ThemeReader.theme(xml) as Parsed.Ok).value
        ThemePackCheck.hidesRequired(t, boards)
        val start = System.nanoTime()
        assertEquals(emptyList<BlankKeys.Blank>(), ThemePackCheck.hidesRequired(t, boards))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$ms ms", ms < CHECK_BUDGET_MS)
    }

    // a pack's own board: its Return named go, styled by that name, found by its role
    @Test
    fun aPacksOwnBoardIsAskedByRole() {
        val text = File(LAYOUTS, "qwerty.xml").readText().replace("id=\"enter\"", "id=\"go\"")
        val own = BlankKeys.Board((LayoutReader.layout(text) as Parsed.Ok).value, boards.first().fragments)
        val styles = """<style select="*/enter" fill="image(e.png, 0 0 0 0, 3x)"/><style select="qwerty/go" fill="#ffffffff"/>""" +
            """<style select="*/enter label, */enter icon" hidden="true"/>"""
        assertEquals(listOf("qwerty/go"), ThemePackCheck.hidesRequired(theme(styles = styles), listOf(own)).map { "${it.board}/${it.key}" })
    }

    @Test
    fun wordsHardToReadOnTheirKeysAreFoundByVersion() {
        assertEquals(emptyList<Boolean>(), ThemePackCheck.lowContrast(theme(colors = mapOf("keyText" to "#000000", "key" to "#ffffff"))))
        assertEquals(listOf(false), ThemePackCheck.lowContrast(theme(colors = mapOf("keyText" to "#777777", "key" to "#888888"))))
    }
}
