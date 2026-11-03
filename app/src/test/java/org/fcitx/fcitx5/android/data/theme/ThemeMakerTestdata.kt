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

    // the page itself (web/theme-maker/index.html)
    private val page = File(dir.parentFile.parentFile, "index.html")

    /**
     * The page's block `<script type="application/json" id="[id]">` holds [text] between a newline at
     * each end, compared as text; written into the page with `-PupdateThemeMaker`, the rest of the page
     * left as it is. The page's generated data: its format and the built-in themes
     * (ThemeMakerFormatTest), the measured layouts and icons (ThemeMakerLayoutTest).
     */
    fun block(id: String, text: String) {
        val html = page.readText()
        val open = "<script type=\"application/json\" id=\"$id\">"
        val start = html.indexOf(open)
        assertTrue("$page has no block $id", start >= 0)
        val from = start + open.length
        val end = html.indexOf("</script>", from)
        assertTrue("$page: block $id is not closed", end >= 0)
        val now = "\n" + text + "\n"
        if (update) {
            page.writeText(html.substring(0, from) + now + html.substring(end))
            return
        }
        assertEquals("$page: block $id is not what the app makes now: run the test with -PupdateThemeMaker", now, html.substring(from, end))
    }
}
