/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.user.ImportFiles.Scel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * [Scel.textDictionary] against what 0.3.2's native scel2org5 wrote for the same files (uat/fixtures/scel).
 * contract: a file format and its fixtures, which mutation cannot see.
 */
class ScelGoldenTest {

    // found from wherever the test runs inside the repo, not only from lib/ime-core
    private val dir = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "uat/fixtures/scel") }.first { it.isDirectory }

    @Test
    fun everyFileGivesWhatScel2org5Wrote() {
        for (name in listOf("extra", "sogou-4", "sogou-75228", "sogou-15117")) {
            val want = File(dir, "$name.txt").readText()
            assertEquals(name, want, Scel.textDictionary(File(dir, "$name.scel").readBytes()))
        }
    }

    @Test
    fun theBrokenFilesFailAsIOException() {
        for (name in listOf("truncated", "not-scel")) {
            assertThrows(name, IOException::class.java) { Scel.textDictionary(File(dir, "$name.scel").readBytes()) }
        }
    }
}
