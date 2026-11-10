/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/**
 * The files the theme maker's own tests (web/theme-maker/test) read: made here from the app's code,
 * so the page and the app cannot drift apart unseen. Each is compared with the one in the repo, or
 * written there when Gradle is given `-PupdateThemeMaker` (app/build.gradle.kts passes both).
 */
object ThemeMakerTestdata {

    private val dir = File(System.getProperty("themeMaker.testdata") ?: "web/theme-maker/test/testdata")
    private val update = System.getProperty("themeMaker.update") == "true"

    fun check(name: String, bytes: ByteArray) {
        val file = File(dir, name)
        if (update) {
            dir.mkdirs()
            file.writeBytes(bytes)
            return
        }
        assertTrue("$file is missing: run the test with -PupdateThemeMaker", file.exists())
        assertArrayEquals("$file is not what the app makes now: run the test with -PupdateThemeMaker", file.readBytes(), bytes)
    }

    fun check(name: String, text: String) = check(name, text.encodeToByteArray())

    /** The file [name] as it is in the repo: for the app's own tests to read what the page reads. */
    fun file(name: String) = File(dir, name)

    // the pages themselves: the maker (web/theme-maker/index.html) and the one before 0.6.0 (classic/index.html)
    private val pages = File(dir.parentFile.parentFile, "index.html").let { listOf(it, File(it.parentFile, "classic/index.html")) }

    /**
     * Each page's block `<script type="application/json" id="[id]">` holds [text] between a newline at each
     * end, compared as text; written into the pages with `-PupdateThemeMaker`, the rest of each page left as
     * it is. A page carries only the blocks it reads (tools/assemble.mjs), so a block is in one page or both,
     * never in none. The generated data: the format and the built-in themes (ThemeMakerFormatTest), the
     * measured layouts and icons (ThemeMakerLayoutTest), the engine's icons (EnginePaintTest).
     */
    fun block(id: String, text: String) {
        val open = "<script type=\"application/json\" id=\"$id\">"
        val holding = pages.filter { it.readText().contains(open) }
        assertTrue("no page has block $id", holding.isNotEmpty())
        for (page in holding) {
            val html = page.readText()
            val from = html.indexOf(open) + open.length
            val end = html.indexOf("</script>", from)
            assertTrue("$page: block $id is not closed", end >= 0)
            val now = "\n" + text + "\n"
            if (update) {
                page.writeText(html.substring(0, from) + now + html.substring(end))
                continue
            }
            assertEquals("$page: block $id is not what the app makes now: run the test with -PupdateThemeMaker", now, html.substring(from, end))
        }
    }
}
