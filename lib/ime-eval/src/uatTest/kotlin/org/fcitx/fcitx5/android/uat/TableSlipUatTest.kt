/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.Near
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.session.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** uat/10-tables.md: a table's key tapped off-centre (061-neighbour-table), with the app's 仓颉. */
class TableSlipUatTest {

    // e tapped at its right edge, toward r (QWERTY: w left, r right)
    private val eTowardR = Touch(0.45f, 0f, 36f, 50f, listOf(Near('w', -1f, 0f), Near('r', 1f, 0f)))

    private fun EngineReplay.tapE() = key(EngineReplay.symOf('e'.code), payload = Action.Key('e', touch = eTowardR))

    @Test
    @Uat("TBL-54")
    fun cangjiesEmptyCodeIsReadAsTheKeyTheTapLeansTo() = with(EngineReplay(im = InputMethodCatalog.CANGJIE)) {
        // hd (禾) then e: hde is no code, hdr (和) is, with others after it: a space commits 和
        type("hd")
        tapE()
        expectFirst("和")
        space()
        expectText("和")
    }

    @Test
    @Uat("TBL-54")
    fun cangjiesLoneCodeReadFromASlipWaitsForTheNextKey() = with(EngineReplay(im = InputMethodCatalog.CANGJIE)) {
        // yym then e: yyme is no code, yymr is 这 alone, which would have committed itself: it waits to be seen
        type("yym")
        tapE()
        assertEquals(listOf("这"), candidates)
        expectText("")
        // x cannot go on yymr: 这 committed by it, x (難) the next code's
        type("x")
        expectText("这")
        assertEquals("難", preedit)
    }

    // j tapped toward h on its left, [dx] across it
    private fun jTowardH(dx: Float) = Touch(dx, 0f, 36f, 50f, listOf(Near('h', -1f, 0f), Near('k', 1f, 0f)))

    private fun EngineReplay.tapJ(dx: Float) = key(EngineReplay.symOf('j'.code), payload = Action.Key('j', touch = jTowardH(dx)))

    @Test
    @Uat("TBL-55")
    fun wubisToppingKeyIsReadOnlyAtTheEdge() {
        // etn is 乃's whole code, etnj no code: j off-centre is the next code's first, 乃 committed (顶字)
        with(EngineReplay(im = InputMethodCatalog.WUBI)) {
            type("etn")
            tapJ(-0.3f)
            expectText("乃")
            assertEquals("j", preedit)
        }
        // at the very edge toward h: etnh, whose first 用 is commoner than 乃; nothing committed, and the
        // full code waits to be seen: a space commits it
        with(EngineReplay(im = InputMethodCatalog.WUBI)) {
            type("etn")
            tapJ(-0.49f)
            expectText("")
            expectFirst("用")
            space()
            expectText("用")
        }
        // or the next code's first key, once
        with(EngineReplay(im = InputMethodCatalog.WUBI)) {
            type("etn")
            tapJ(-0.49f)
            type("w")
            expectText("用")
            assertEquals("w", preedit)
        }
    }

    @Test
    @Uat("TBL-56")
    fun slipsOffLeaveTheEmptyCodeToBeDropped() = with(EngineReplay(im = InputMethodCatalog.CANGJIE)) {
        engines.settings = engines.settings.copy(typos = false)
        type("hd")
        tapE()
        assertEquals(emptyList<String>(), candidates)
        space()
        expectText("")
    }
}
