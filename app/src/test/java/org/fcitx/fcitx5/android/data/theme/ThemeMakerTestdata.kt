/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertArrayEquals
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

    // the page itself (web/theme-maker/index.html): its generated blocks, the data it draws by
    private val page = File(dir.parentFile.parentFile, "index.html")

    /**
     * The page's block `<script type="application/json" id="[id]">` holds [text], or is written so with
     * -PupdateThemeMaker. Nothing while there is no page, or no such block in it yet: the page is
     * written by hand, its blocks only here.
     */
    fun block(id: String, text: String) {
        if (!page.exists()) return
        val html = page.readText()
        val open = "<script type=\"application/json\" id=\"$id\">"
        val start = html.indexOf(open).takeIf { it >= 0 }?.plus(open.length) ?: return
        val end = html.indexOf("</script>", start)
        check(end > 0) { "$page: block $id not closed" }
        val now = "\n" + text + "\n"
        if (update) {
            page.writeText(html.substring(0, start) + now + html.substring(end))
            return
        }
        assertTrue("$page's block $id is not what the app makes now: run the test with -PupdateThemeMaker", html.substring(start, end) == now)
    }

    /** The file [name] as it is in the repo: for the app's own tests to read what the page reads. */
    fun file(name: String) = File(dir, name)
}
