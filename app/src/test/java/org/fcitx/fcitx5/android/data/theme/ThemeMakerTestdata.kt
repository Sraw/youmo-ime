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
}
