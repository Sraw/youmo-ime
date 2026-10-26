/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.host.Engines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/17-hardware-keyboard.md, what the engine makes of a hardware keyboard's keys (not the
 * screen's: keyEvent's isVirtual false): space and digits, the page keys, the page size, English
 * typing as the keys. Where the keys go in the service is HardwareKeyUatTest's; the
 * window beside the cursor is the device's (HK-01).
 */
class HardwareKeysUatTest {

    @Test
    @Uat("HK-02")
    fun spaceCommitsTheFirstADigitPicksByNumber() = with(EngineReplay()) {
        press("nihao")
        val first = page.first()
        press(" ")
        expectText(first)
        press("shi")
        val third = page[2]
        press("3")
        expectText(first + third)
        expectPreedit("")
    }

    @Test
    @Uat("HK-06")
    fun minusAndEqualTurnThePagesWhileTyping() = with(EngineReplay()) {
        press("shi")
        val one = page
        press("=")
        assertNotEquals(one, page)
        press("-")
        assertEquals(one, page)
        expectPreedit("shi")
        pressKey(EngineReplay.ESCAPE)
        expectPreedit("")
        press("-=")
        expectText("-=")
    }

    @Test
    @Uat("HK-07")
    fun minusAfterACommitIsTheMark() = with(EngineReplay()) {
        press("jintian")
        val first = page.first()
        press(" ")
        // 联想 up, nothing typed: the key is the mark, not a page turned
        press("-")
        expectText("$first-")
    }

    @Test
    @Uat("HK-08")
    fun pageDownAndPageUpTurnThePages() = with(EngineReplay()) {
        press("shi")
        val one = page
        pressKey(EngineReplay.PAGE_DOWN)
        assertNotEquals(one, page)
        pressKey(EngineReplay.PAGE_UP)
        assertEquals(one, page)
    }

    @Test
    @Uat("HK-34")
    fun thePageSizeSetIsTheWindowsPage() = with(EngineReplay()) {
        engines.settings = engines.settings.copy(pageSize = 3)
        press("shi")
        val one = page
        assertEquals(one.toString(), 3, one.size)
        press("=")
        assertEquals(page.toString(), 3, page.size)
        assertNotEquals(one, page)
    }

    @Test
    @Uat("HK-21")
    fun returnPutsTheWrittenInThenGoesToTheApp() = with(EngineReplay(im = Engines.HANDWRITING)) {
        write("人", "入", "八")
        expectPreedit("人")
        pressKey(EngineReplay.RETURN)
        // first the character in, not sent
        expectText("人")
        expectPreedit("")
        // then the app's: the chat sends it
        pressKey(EngineReplay.RETURN)
        expectText("人\n")
    }

    @Test
    @Uat("HK-23")
    fun englishTypesTheKeysAsPressed() = with(EngineReplay(im = Engines.ENGLISH)) {
        press("teh ")
        expectText("teh ")
        assertTrue(page.toString(), page.isEmpty())
    }
}
