/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where 记住 judges a corrected word: on the stretch voice judged, after the one kept before it. */
class VoiceEditsGlueTest {

    private fun judged(talks: List<List<String>>, text: String, word: String, last: Boolean = false): Triple<String, Int, String>? {
        val at = if (last) text.lastIndexOf(word) else text.indexOf(word)
        return VoiceEditsGlue.stretchAt(talks, text, at, word.length)?.let { Triple(it.text, it.at, it.before) }
    }

    @Test
    @Uat("VO-59")
    fun `a corrected word is judged on the stretch voice judged`() {
        assertRows(
            "a comma the polish put inside a stretch: the whole stretch, as voice read it" to {
                assertEquals(Triple("这个公式很猛", 2, ""), judged(listOf(listOf("这个公式很猛")), "这个公式，很猛。", "公式"))
            },
            "a later stretch: after the one kept before it, not the whole field" to {
                assertEquals(
                    Triple("推导这个公式", 4, "敌人的攻势很猛"),
                    judged(listOf(listOf("敌人的攻势很猛", "推导这个公式")), "敌人的攻势很猛，推导这个公式。", "公式"),
                )
            },
            "the same words in an earlier talk: each stretch found from the end, the latest talk last in the field" to {
                val talks = listOf(listOf("公式"), listOf("推导", "公式"))
                assertEquals(Triple("公式", 0, "推导"), judged(talks, "公式。推导公式", "公式", last = true))
                assertEquals(Triple("公式", 0, ""), judged(talks, "公式。推导公式", "公式"))
            },
            "the place across two stretches, or written otherwise by the polish: none, the clause judged instead" to {
                assertNull(judged(listOf(listOf("这个公", "式很猛")), "这个公式很猛", "公式"))
                assertNull(judged(listOf(listOf("十一点开会")), "11点开会", "开会"))
            },
            "the clause otherwise: to the marks around the place, after what is before it" to {
                val c = VoiceEditsGlue.clauseAt("敌人的攻势很猛，推导这个公式", 12, 2)
                assertEquals(Triple("推导这个公式", 4, "敌人的攻势很猛，"), Triple(c.text, c.at, c.before))
            },
        )
    }
}
