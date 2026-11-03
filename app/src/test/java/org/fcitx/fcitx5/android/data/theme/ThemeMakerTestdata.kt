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

    /** The theme maker page, whose generated blocks [checkBlock] holds. */
    val page = File(dir, "../../index.html")

    /**
     * The page's block `<script type="application/json" id="[id]">` holds [json] (one line, or
     * pretty: as given), compared as text; written into the page with `-PupdateThemeMaker`, the
     * rest of the page left as it is. The page's generated data: its format, the built-in themes
     * (ThemeMakerFormatTest), the measured layouts and icons (ThemeMakerLayoutTest).
     */
    fun checkBlock(id: String, json: String) {
        val html = page.readText()
        val open = "<script type=\"application/json\" id=\"$id\">\n"
        val start = html.indexOf(open)
        assertTrue("${page.path} has no block $id", start >= 0)
        val from = start + open.length
        val end = html.indexOf("\n</script>", from)
        assertTrue("${page.path}: block $id is not closed", end >= 0)
        if (update) {
            page.writeText(html.substring(0, from) + json + html.substring(end))
            return
        }
        assertEquals("${page.path}: block $id is not what the app makes now: run the test with -PupdateThemeMaker", json, html.substring(from, end))
    }
}
