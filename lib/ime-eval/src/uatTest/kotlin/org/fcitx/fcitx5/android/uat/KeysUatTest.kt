/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * uat/02-keyboard.md, what the engine makes of the keys: English letters and Shift, space and
 * return over pinyin, letters before @ in an email field. The keys' views are KeyboardUatTest's.
 */
class KeysUatTest {

    @Test
    @Uat("KB-02")
    fun englishLettersTypeThemselvesASpaceAfter() = with(EngineReplay(im = Engines.ENGLISH)) {
        type("ok")
        space()
        expectText("ok ")
    }

    @Test
    @Uat("KB-07")
    fun shiftCapitalisesTheNextLetter() = with(EngineReplay(im = Engines.ENGLISH)) {
        // ⇧ then h: the keyboard sends the capital (KB-08's test checks the key sent)
        type("Hi")
        expectFirst("Hi")
        space()
        expectText("Hi ")
    }

    @Test
    @Uat("KB-18")
    fun spaceCommitsTheFirstCandidate() = with(EngineReplay()) {
        type("shijian")
        val first = candidates.first()
        space()
        expectText(first)
        expectPreedit("")
    }

    @Test
    @Uat("KB-19")
    fun returnCommitsTheLettersAsTyped() = with(EngineReplay()) {
        type("abc")
        enter()
        expectText("abc")
    }

    @Test
    @Uat("KB-24")
    fun anEmailFieldsLettersBeforeAtAreCommittedAsTyped() = with(EngineReplay(field = EngineReplay.Field.EMAIL)) {
        // the address keys (@, .com) as the service's commitOverEngine has them: what is typed first, as its policy says
        // (applied by hand: the service's wiring is CommonKeyActionListenerTest's, the policy's cases PendingInputPolicyTest's)
        fun addressKey(key: String) {
            if (PendingInputPolicy.resolve("zh_CN", hasPreedit = preedit.isNotEmpty(), address = true) == PendingInputPolicy.Resolution.CommitAsTyped) enter()
            commit(key)
        }
        type("xiaoming")
        addressKey("@")
        type("qq")
        addressKey(".com")
        assertEquals("xiaoming@qq.com", text + preedit)
    }
}
