/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseBookTest {

    @Test
    fun eachChangeIsSavedWhole() {
        val saved = ArrayList<String>()
        val book = PhraseBook(CustomPhrases.parse("yx,1=邮箱")) { saved += it.all.joinToString(" ") }
        book.pin("yx", "信箱")
        book.pin("dh", "电话")
        book.remove("yx", "邮箱")
        assertEquals(listOf("yx,1=信箱 yx,1=邮箱", "dh,1=电话 yx,1=信箱 yx,1=邮箱", "dh,1=电话 yx,1=信箱"), saved)
        assertEquals(book.phrases.all.joinToString(" "), saved.last())
    }
}
