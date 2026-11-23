/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/** Made from the app's code so the web maker and app cannot drift; compared with the repo, or written with `-PupdateThemeMaker`. */
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

    fun file(name: String) = File(dir, name)

    /** web/theme-maker/[path]: the maker's own source. */
    fun maker(path: String) = File(dir.parentFile.parentFile, path)

    // web/theme-maker/generated/<id>.json, which tools/assemble.mjs puts into the pages
    private val generated = File(dir.parentFile.parentFile, "generated")

    /** Compared as text, with a trailing newline; assembled as `<script type="application/json" id="[id]">`. */
    fun block(id: String, text: String) {
        val file = File(generated, "$id.json")
        val now = text + "\n"
        if (update) {
            generated.mkdirs()
            file.writeText(now)
            return
        }
        assertTrue("$file is missing: run the test with -PupdateThemeMaker", file.exists())
        assertEquals("$file is not what the app makes now: run the test with -PupdateThemeMaker", now, file.readText())
    }
}
