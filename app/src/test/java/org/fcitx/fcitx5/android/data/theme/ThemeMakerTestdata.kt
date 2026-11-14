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

    // the generated blocks, a file each (web/theme-maker/generated/<id>.json), which tools/assemble.mjs puts into the pages
    private val generated = File(dir.parentFile.parentFile, "generated")

    /**
     * The generated block [id] (web/theme-maker/generated/[id].json) holds [text] and a newline at its end, compared
     * as text; written with `-PupdateThemeMaker`. tools/assemble.mjs puts each into the pages that read it, as their
     * `<script type="application/json" id="[id]">`. The generated data: the format and the built-in themes
     * (ThemeMakerFormatTest), the measured layouts and icons (ThemeMakerLayoutTest), the engine's icons (EnginePaintTest).
     */
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
