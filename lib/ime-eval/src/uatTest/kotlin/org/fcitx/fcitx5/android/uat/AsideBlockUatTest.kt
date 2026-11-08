/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.junit.Assert.assertTrue
import org.junit.Test

/** uat/06-pinyin.md PY-115: what pinyin lists beside its readings, blocked from the keyboard. */
class AsideBlockUatTest {

    // through the host's long-press actions, as the keyboard acts: an act the list is not actionable for is dropped
    private fun EngineReplay.blocked(keys: String, text: String): Boolean {
        type(keys)
        val at = page.indexOf(text)
        assertTrue("$keys $page", at >= 0)
        return learn(LearningOp.Block(at, text)) is LearningResult.Done
    }

    @Test
    @Uat("PY-115")
    fun anEmojiAndACharacterByStrokesAreBlockedFromTheKeyboard() {
        with(EngineReplay()) {
            assertTrue(blocked("dianzan", "👍"))
            backspace(7)
            type("dianzan")
            assertTrue("dianzan $page", "👍" !in page)
        }
        with(EngineReplay()) {
            type("uhs")
            val first = page.first()
            backspace(3)
            assertTrue(blocked("uhs", first))
            backspace(3)
            type("uhs")
            assertTrue("uhs $page", first !in page)
        }
    }
}
