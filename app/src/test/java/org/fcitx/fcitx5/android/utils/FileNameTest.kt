/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Names from other apps and imported files; one that is a path would lead out of the directory it is joined to. */
class FileNameTest {

    @Test
    fun plainFileNamesAreAccepted() {
        for (name in listOf("notes.txt", "码表 (2).conf", "my theme", "0b6f-src", "0b6f-cropped.png", "主题")) {
            assertTrue(name, isPlainFileName(name))
        }
    }

    @Test
    fun namesThatArePathsAreRejected() {
        val names = listOf("", ".", "..", "a/b", "../x", "/data/x", "../../files/engine/tables/x", "/data/data/x")
        for (name in names) {
            assertFalse(name, isPlainFileName(name))
        }
    }
}
