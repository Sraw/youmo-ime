/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// 0.3.1's fix (7d9a5c14) was the text itself: 全部清空 lists pins and blocks as cleared, not kept, in each language.
// Read from the resource files: app unit tests run without the app's resources (isIncludeAndroidResources off)
class UserWordsClearTextTest {

    private fun message(values: String): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$values/strings.xml"))
        val strings = doc.getElementsByTagName("string")
        return (0 until strings.length).map { strings.item(it) as Element }.single { it.getAttribute("name") == "learning_clear_all_message" }.textContent
    }

    private fun check(values: String, kept: String, pins: String, blocks: String) {
        val (cleared, left) = message(values).split(kept, limit = 2)
        assertTrue(values, pins in cleared && blocks in cleared)
        assertFalse(values, pins in left || blocks in left)
    }

    @Test
    fun clearingAllListsPinsAndBlocksAsCleared() {
        // the text the tick shows (review F3425: the mapping checked here, not in a test of its own)
        assertEquals(R.string.learning_clear_all_message, clearMessage(all = true))
        assertEquals(R.string.learning_clear_learned_message, clearMessage(all = false))
        check("values", "Kept:", "pins", "blocks")
        check("values-zh-rCN", "留下：", "固定", "屏蔽")
        check("values-zh-rTW", "留下：", "固定", "封鎖")
    }
}
