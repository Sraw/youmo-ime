/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.web

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.listNames
import org.fcitx.fcitx5.android.scene.readText
import org.fcitx.fcitx5.android.scene.testDataDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The library's own layouts and themes (BuiltIns over the files the build put in, BuiltInFiles) and the browser's
 * measurer, on fixtures: boards offered and fragments not, a board's includes found in the page's own fragments
 * first, a missing one a problem; what the build put in is src/commonMain's files, the themes none while T3's are not.
 */
class BuiltInsTest {
    private val ns = LayoutReader.NAMESPACE
    private val board = "<keyboard xmlns=\"$ns\" id=\"b\" name=\"b\" family=\"symbols\" columns=\"10\">\n" +
        "<row><key id=\"a\" label=\"a\" send=\"commit:a\"/></row>\n<include src=\"bottom\"/>\n</keyboard>"
    private val bottom = "<fragment xmlns=\"$ns\" id=\"bottom\"><row><key id=\"z\" label=\"z\" send=\"commit:z\"/></row></fragment>"
    private val fixture = BuiltIns(
        mapOf("b" to board, "bottom" to bottom, "broken" to "<keyboard", "other" to board.replace("id=\"b\"", "id=\"other\"")),
        mapOf("night" to "<theme/>", "day" to "<theme/>"),
    )

    @Test
    fun boardsAreOfferedFragmentsAreWhatTheyInclude() {
        assertEquals(listOf("b", "other"), fixture.layouts)
        assertEquals(setOf("bottom"), fixture.fragments.keys)
        assertEquals(board, fixture.layoutText("b"))
        assertEquals(bottom, fixture.layoutText("bottom"))
        assertNull(fixture.layoutText("nope"))
        assertNull(fixture.layout("bottom"), "a fragment is no board")
        assertNull(fixture.layout("broken"))
        val b = assertIs<Parsed.Ok<*>>(fixture.layout("b"))
        assertTrue(fixture.layout("b") === fixture.layout("b"), "read once")
        assertEquals("b", (b.value as Layout).id)
        assertEquals(listOf("day", "night"), fixture.themes)
        assertEquals("<theme/>", fixture.themeText("day"))
        assertNull(fixture.themeText("dusk"))
    }

    @Test
    fun aBoardsIncludesAreThePagesOwnFirstThenTheLibrarysOrAProblem() {
        val layout = LayoutReader.layout(board).ok()
        assertEquals(fixture.fragments.getValue("bottom"), fixture.includes(layout).ok().getValue("bottom"))
        val own = LayoutReader.fragment(bottom.replace("\"z\"", "\"y\"")).ok()
        assertTrue(fixture.includes(layout, mapOf("bottom" to own)).ok().getValue("bottom") === own)
        val missing = BuiltIns(mapOf("b" to board), emptyMap()).includes(layout)
        val problem = assertIs<Parsed.Failed>(missing).problems.single()
        assertEquals(3, problem.line)
        assertTrue("bottom" in problem.message, problem.message)
    }

    // the build's BuiltInFiles are src/commonMain's layouts and themes, by name, as written (a layout packed)
    @Test
    fun theBuildPutInTheAppsFiles() {
        val main = "${testDataDir()}/../../commonMain"
        val layouts = listNames("$main/layouts").filter { it.endsWith(".xml") }.map { it.removeSuffix(".xml") }.sorted()
        assertEquals(layouts, BuiltInFiles.layouts.keys.sorted())
        // a layout without its comments and indents (the build's packed): its lines the file's
        val packed = { xml: String ->
            xml.replace(Regex("<!--[\\s\\S]*?-->")) { c -> "\n".repeat(c.value.count { it == '\n' }) }.replace(Regex("^[ \\t]+|[ \\t]+$", RegexOption.MULTILINE), "")
        }
        for (name in layouts) assertEquals(readText("$main/layouts/$name.xml")?.let(packed), BuiltInFiles.layouts[name], name)
        // none yet (T3 writes them): no folder at all is none
        val themes = if (!exists("$main/themes")) emptyList() else listNames("$main/themes").filter { it.endsWith(".xml") }.map { it.removeSuffix(".xml") }.sorted()
        assertEquals(themes, BuiltInFiles.themes.keys.sorted())
        for (name in themes) assertEquals(readText("$main/themes/$name.xml"), BuiltInFiles.themes[name], name)
    }

    @Test
    fun theBrowsersMeasurerIsItsInkAndExtentKeptByWhatWasAsked() {
        val ctx: dynamic = js(
            """({ asked: [], measureText: function (t) { this.asked.push(this.font + '|' + this.letterSpacing + '|' + t);
              return t === 'bare' ? { width: 40 } : { width: 10 * t.length, actualBoundingBoxLeft: 1, actualBoundingBoxRight: 9, actualBoundingBoxAscent: 7,
              actualBoundingBoxDescent: 2, fontBoundingBoxAscent: 11, fontBoundingBoxDescent: 3 }; } })""",
        )
        val m = CanvasMeasurer(ctx, { "\"F-$it\"" })
        val q = m.measure("qq", "system", 700, 20.0)
        assertEquals(20.0, q.advance)
        assertEquals(Box(-1.0, -7.0, 9.0, 2.0), q.ink)
        assertEquals(-11.0, q.top)
        assertEquals(3.0, q.bottom)
        m.measure("qq", "system", 700, 20.0)
        assertEquals(listOf("700 20px \"F-system\"|0px|qq"), (ctx.asked as Array<String>).toList(), "kept: asked once")
        m.measure("qq", "system", 400, 20.0)
        assertEquals(2, (ctx.asked as Array<String>).size, "another weight is another ask")
        // a browser with no boxes: the advance, a Latin font's usual proportions
        val bare = m.measure("bare", "system", 400, 10.0)
        assertEquals(Box(-0.0, -7.0, 40.0, 0.0), bare.ink)
        assertEquals(-10.5, bare.top)
        assertEquals(3.0, bare.bottom)
    }

    // the page's own fonts first: their sources' yMax and yMin, as Android's FontMetrics of the same fonts
    @Test
    fun thePagesFontsExtentIsTheirHeadTablesBox() {
        val m = CanvasMeasurer(measuring(), { "\"Youmo Latin\", \"Youmo Han\", sans-serif" })
        val latin = m.measure("qq", "system", 400, 20.0)
        assertEquals(-20 * 2163.0 / 2048, latin.top)
        assertEquals(20 * 555.0 / 2048, latin.bottom)
        val han = CanvasMeasurer(measuring(), { "\"Youmo Han\"" }).measure("中", "system", 700, 10.0)
        assertEquals(-18.06, han.top, 1e-9)
        assertEquals(10.46, han.bottom, 1e-9)
    }

    // a font not in yet: measured in the one shown meanwhile, and not kept, so the scene built once it is in is its
    @Test
    fun aMeasureBeforeItsFontIsInIsNotKept() {
        var loaded = false
        val ctx = measuring()
        val m = CanvasMeasurer(ctx, { "\"F-$it\"" }, loaded = { loaded })
        m.measure("qq", "system", 400, 20.0)
        m.measure("qq", "system", 400, 20.0)
        assertEquals(2, (ctx.asked as Array<String>).size, "asked again while the font is not in")
        loaded = true
        m.measure("qq", "system", 400, 20.0)
        m.measure("qq", "system", 400, 20.0)
        assertEquals(3, (ctx.asked as Array<String>).size, "kept once it is")
    }

    // the last [limit] asked for kept: one asked again is kept over one asked once before it
    @Test
    fun theMeasuresKeptAreTheLastAskedFor() {
        val ctx = measuring()
        val m = CanvasMeasurer(ctx, { "\"F-$it\"" }, limit = 2)
        for (t in listOf("a", "b", "a", "c", "a", "b")) m.measure(t, "system", 400, 20.0)
        // a, b; a kept; c (b out: a was asked since); a kept; b again
        assertEquals(listOf("a", "b", "c", "b"), (ctx.asked as Array<String>).map { it.substringAfterLast('|') })
    }

    private fun measuring(): dynamic = js(
        """({ asked: [], measureText: function (t) { this.asked.push(this.font + '|' + this.letterSpacing + '|' + t);
          return { width: 10 * t.length, fontBoundingBoxAscent: 11, fontBoundingBoxDescent: 3 }; } })""",
    )

    private fun exists(path: String): Boolean = js("require('fs')").existsSync(path) as Boolean
}
